package app.linkfill.core.reliability

import app.linkfill.core.policy.Decision
import app.linkfill.core.policy.RecoveryAction
import app.linkfill.core.policy.RecoveryFeatures
import app.linkfill.core.policy.RecoveryPolicy
import app.linkfill.core.protocol.AckEntry
import app.linkfill.core.protocol.DataChunk
import app.linkfill.core.protocol.Decoded
import app.linkfill.core.protocol.Envelope
import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.protocol.MessageCodec
import app.linkfill.core.protocol.MessageType
import app.linkfill.core.protocol.PayloadException
import app.linkfill.core.protocol.Payloads
import app.linkfill.core.protocol.RedundantFix
import app.linkfill.core.protocol.RejectReason
import app.linkfill.core.security.Sealer
import kotlin.random.Random

class ChannelConfig(
    val locationTtlMs: Long = 5_000,
    val controlTtlMs: Long = 30_000,
    val maxAttempts: Int = 4,
    val initialRtoMs: Long = 500,
    val minRtoMs: Long = 250,
    val maxRtoMs: Long = 4_000,
    val heartbeatMs: Long = 1_000,
    val nackDelayMs: Long = 150,
    val nackRepeatMs: Long = 600,
    val maxNacks: Int = 3,
    val maxQueue: Int = 32,
    val redundancyDepth: Int = 2,
    val redundancyHoldMs: Long = 5_000,
    /** false = "basic transmission": no retransmissions and no recovery requests at all. */
    val retransmissionsEnabled: Boolean = true,
    /** true = always piggyback the last unacknowledged fixes (the "retry + redundancy" baseline). */
    val alwaysRedundant: Boolean = false,
)

enum class Delivery { DIRECT, RETRANSMISSION, REDUNDANCY }

interface ChannelListener {
    fun onLocation(seq: Int, fix: LocationFix, delivery: Delivery) {}
    fun onStopSharing() {}
    fun onDataChunk(chunk: DataChunk) {}
    fun onPeerError(code: Int) {}
}

/** One policy decision with the context it was made in (for the training-data collector and the log). */
class DecisionRecord(val now: Long, val seq: Int, val fixTimeMs: Long, val features: RecoveryFeatures, val decision: Decision)

/**
 * Application-level reliability for one session over an already-connected transport.
 *
 * Sender side: sequence numbers, acknowledgements, RTO from measured RTT, bounded retries with
 * exponential backoff and jitter, latest-location-wins, expiry, and a [RecoveryPolicy] that decides
 * what to do when a location update times out. Receiver side: validation, decryption, duplicate and
 * replay rejection, gap detection, recovery requests (NACKs), and recovery from piggybacked fixes.
 *
 * Time is passed in explicitly so the same code runs in the app (wall clock) and the simulator.
 * Not thread-safe: call everything from one thread.
 */
class ReliableChannel(
    private val sessionId: Long,
    private val localId: Int,
    private val peerId: Int,
    private val sealer: Sealer,
    private val transport: (ByteArray) -> Unit,
    var policy: RecoveryPolicy,
    private val random: Random,
    private val listener: ChannelListener,
    val stats: LinkStats = LinkStats(),
    val config: ChannelConfig = ChannelConfig(),
    /** Peer clock minus our clock, measured during the handshake. */
    var peerClockOffsetMs: Long = 0,
) {
    var decisionObserver: ((DecisionRecord) -> Unit)? = null

    private class Outgoing(val env: Envelope, val fix: LocationFix?) {
        val isLocation get() = fix != null
        var attempts = 0
        var firstSentAt = 0L
        var lastSentAt = 0L
        var nextActionAt = 0L
        var acked = false
        var retired = false          // no further timeouts: superseded, riding on redundancy, or stale
        var stale = false            // given up: not recoverable on request either
        var waitingRetransmit = false
        var retransmitted = false
    }

    private class Missing(val detectedAt: Long) { var nacks = 0; var lastNackAt = detectedAt }

    private val outgoing = ArrayDeque<Outgoing>()
    private val window = ReplayWindow()
    private val missing = LinkedHashMap<Int, Missing>()
    private var nextSeq = 0
    private var connected = true
    private var redundancyUntil = 0L
    private var lastHeartbeatAt = Long.MIN_VALUE
    private var lastAckAt = 0L
    private var peerLowWater = 1
    var latestLocationSeq = 0
        private set
    var lastPeerMessageAt = 0L
        private set

    val isConnected get() = connected
    val pendingCount get() = outgoing.count { !it.acked && !it.retired }

    // ---------------------------------------------------------------- sending

    fun sendLocation(fix: LocationFix, now: Long) {
        require(fix.isValid())
        val redundant = if (config.alwaysRedundant || now < redundancyUntil) {
            outgoing.filter { it.isLocation && !it.acked && !it.stale && now <= it.env.expiresAt }
                .takeLast(config.redundancyDepth)
                .map { RedundantFix(it.env.seq, it.env.messageId, it.fix!!) }
        } else emptyList()
        for (o in outgoing) if (o.isLocation && !o.acked && !o.retired) { o.retired = true; stats.superseded++ }
        stats.redundantFixesSent += redundant.size
        enqueue(newEnvelope(MessageType.LOCATION_UPDATE, Payloads.encodeLocation(fix, redundant), config.locationTtlMs, now), fix, now)
    }

    fun sendStop(now: Long) =
        enqueue(newEnvelope(MessageType.STOP_SHARING, ByteArray(0), config.controlTtlMs, now), null, now)

    /** Unreliable on purpose: FEC chunks are recovered by redundancy, not by retransmission. */
    fun sendChunk(chunk: DataChunk, now: Long) =
        sendEnvelope(newEnvelope(MessageType.DATA_CHUNK, Payloads.encodeChunk(chunk), config.controlTtlMs, now))

    fun sendError(code: Int, now: Long) =
        sendEnvelope(newEnvelope(MessageType.ERROR, Payloads.encodeError(code), config.controlTtlMs, now))

    /** True once every reliable message has been acknowledged or abandoned. */
    fun isSettled(): Boolean = outgoing.none { !it.acked && !it.stale && !it.retired }

    fun setConnected(value: Boolean, now: Long) {
        if (connected && !value) stats.recordDisconnect(now)
        connected = value
    }

    /** Drives timers: expiry, timeouts, policy decisions, heartbeats and recovery requests. */
    fun tick(now: Long) {
        expire(now)
        if (!connected) return
        for (o in outgoing.toList()) {
            if (o.acked || o.retired) continue
            when {
                o.attempts == 0 -> transmit(o, now, retransmission = false)
                now < o.nextActionAt -> Unit
                o.waitingRetransmit -> transmit(o, now, retransmission = true)
                else -> onTimeout(o, now)
            }
        }
        if (now - lastHeartbeatAt >= config.heartbeatMs) sendHeartbeat(now)
        if (config.retransmissionsEnabled) requestMissing(now)
    }

    private fun newEnvelope(type: MessageType, body: ByteArray, ttl: Long, now: Long) = Envelope(
        type = type,
        sessionId = sessionId,
        senderId = localId,
        seq = if (type.reliable) ++nextSeq else 0,
        messageId = random.nextLong(),
        createdAt = now,
        expiresAt = now + ttl,
        body = body,
    )

    private fun enqueue(env: Envelope, fix: LocationFix?, now: Long) {
        val o = Outgoing(env, fix)
        outgoing.addLast(o)
        stats.messagesSent++
        // Bounded queue: drop the oldest settled entries first, then the oldest locations.
        while (outgoing.size > config.maxQueue) {
            val victim = outgoing.firstOrNull { it.acked || it.stale } ?: outgoing.firstOrNull { it.isLocation } ?: break
            outgoing.remove(victim)
        }
        if (connected) transmit(o, now, retransmission = false)
    }

    private fun transmit(o: Outgoing, now: Long, retransmission: Boolean) {
        sendEnvelope(if (retransmission) o.env.with(flags = Envelope.FLAG_RETRANSMIT) else o.env)
        if (o.attempts == 0) o.firstSentAt = now
        o.attempts++
        o.lastSentAt = now
        o.nextActionAt = now + rto()
        o.waitingRetransmit = false
        stats.transmissions++
        if (retransmission) { stats.retransmissions++; o.retransmitted = true }
    }

    private fun sendEnvelope(plain: Envelope) {
        val body = if (plain.type.encrypted) sealer.seal(MessageCodec.aad(plain), plain.body) else plain.body
        val bytes = MessageCodec.encode(plain.with(body = body))
        if (!connected) { stats.droppedWhileDisconnected++; return }
        stats.bytesSent += bytes.size
        transport(bytes)
    }

    private fun onTimeout(o: Outgoing, now: Long) {
        stats.recordTimeout()
        if (o.retransmitted) stats.recordRetryResult(false)
        if (!o.isLocation) {
            if (o.attempts >= config.maxAttempts) { o.retired = true; o.stale = true; stats.controlFailed++ }
            else { o.waitingRetransmit = true; o.nextActionAt = now + backoff(o.attempts) }
            return
        }
        if (!config.retransmissionsEnabled || o.attempts >= config.maxAttempts) { markStale(o); return }
        val f = features(o, now)
        val d = policy.decide(f)
        stats.recordDecision(d)
        decisionObserver?.invoke(DecisionRecord(now, o.env.seq, o.fix!!.fixTimeMs, f, d))
        when (d.action) {
            RecoveryAction.RETRY_NOW -> transmit(o, now, retransmission = true)
            RecoveryAction.WAIT_RETRY -> { o.waitingRetransmit = true; o.nextActionAt = now + backoff(o.attempts) }
            RecoveryAction.ADD_REDUNDANCY -> { redundancyUntil = now + config.redundancyHoldMs; o.retired = true }
            RecoveryAction.MARK_STALE -> markStale(o)
        }
    }

    private fun markStale(o: Outgoing) { o.retired = true; o.stale = true; stats.markedStale++ }

    private fun expire(now: Long) {
        val it = outgoing.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (now > o.env.expiresAt) {
                if (!o.acked && !o.stale && o.isLocation && !o.retired) stats.expiredUnacked++
                it.remove()
            }
        }
    }

    private fun features(o: Outgoing, now: Long) = RecoveryFeatures(
        rttMeanMs = stats.srttMs ?: config.initialRtoMs.toDouble(),
        rttVarMs = stats.rttVarMs ?: (config.initialRtoMs / 2.0),
        timeoutRate = stats.timeoutRate(),
        retrySuccessRate = stats.retrySuccessRate(),
        consecutiveTimeouts = stats.consecutiveTimeouts,
        disconnectsLast60s = stats.disconnectsSince(now - 60_000),
        queueLength = pendingCount,
        messageAgeMs = now - o.firstSentAt,
        ttlMs = o.env.expiresAt - o.env.createdAt,
        attempt = o.attempts,
        maxAttempts = config.maxAttempts,
        msSinceLastAck = if (lastAckAt == 0L) 10_000 else now - lastAckAt,
    )

    fun rto(): Long {
        val s = stats.srttMs ?: return config.initialRtoMs
        return (s + 4 * (stats.rttVarMs ?: 0.0)).toLong().coerceIn(config.minRtoMs, config.maxRtoMs)
    }

    /** Exponential backoff with "equal jitter": half fixed, half random. */
    fun backoff(attempt: Int): Long {
        val exp = (rto() shl attempt.coerceIn(0, 6)).coerceAtMost(config.maxRtoMs)
        return exp / 2 + random.nextLong(exp / 2 + 1)
    }

    /** Lowest sequence number we could still deliver; everything below it is settled for the receiver. */
    private fun lowWater(now: Long): Int {
        val newestAckedLocation = outgoing.filter { it.isLocation && it.acked }.maxOfOrNull { it.env.seq } ?: 0
        return outgoing.filter { recoverable(it, now, newestAckedLocation) }.minOfOrNull { it.env.seq } ?: (nextSeq + 1)
    }

    private fun recoverable(o: Outgoing, now: Long, newestAckedLocation: Int) =
        !o.acked && !o.stale && now <= o.env.expiresAt && !(o.isLocation && o.env.seq < newestAckedLocation)

    private fun sendHeartbeat(now: Long) {
        lastHeartbeatAt = now
        sendEnvelope(newEnvelope(MessageType.HEARTBEAT, Payloads.encodeHeartbeat(lowWater(now)), config.controlTtlMs, now))
    }

    // ---------------------------------------------------------------- receiving

    fun receive(bytes: ByteArray, now: Long) {
        stats.bytesReceived += bytes.size
        val decoded = MessageCodec.decode(bytes, now + peerClockOffsetMs)
        if (decoded is Decoded.Rejected) return stats.reject(decoded.reason)
        val e = (decoded as Decoded.Ok).envelope
        if (e.sessionId != sessionId) return stats.reject(RejectReason.WRONG_SESSION)
        if (e.senderId != peerId) return stats.reject(RejectReason.WRONG_SENDER)
        if (!e.type.encrypted) return stats.reject(RejectReason.NOT_READY) // handshake messages are handled outside
        val plain = sealer.open(MessageCodec.aad(e), e.body) ?: return stats.reject(RejectReason.AUTH_FAILED)
        lastPeerMessageAt = now
        try {
            if (e.type.reliable) receiveReliable(e, plain, now) else receiveUnreliable(e, plain, now)
        } catch (_: PayloadException) {
            stats.reject(RejectReason.BAD_PAYLOAD)
        }
    }

    private fun receiveReliable(e: Envelope, plain: ByteArray, now: Long) {
        if (window.status(e.seq) != ReplayWindow.Status.NEW) {
            stats.duplicatesDiscarded++
            sendAck(listOf(AckEntry(e.seq, e.messageId)), now) // our ACK may have been the thing that was lost
            return
        }
        // Parse before accepting, so a malformed message does not consume its sequence number.
        val location = if (e.type == MessageType.LOCATION_UPDATE) Payloads.decodeLocation(plain) else null
        stats.messagesReceived++
        noteArrival(e.seq, if (e.isRetransmission) Delivery.RETRANSMISSION else Delivery.DIRECT, now)
        val acks = mutableListOf(AckEntry(e.seq, e.messageId))
        when (e.type) {
            MessageType.LOCATION_UPDATE -> {
                val p = location!!
                if (e.seq > latestLocationSeq) latestLocationSeq = e.seq
                listener.onLocation(e.seq, p.current, if (e.isRetransmission) Delivery.RETRANSMISSION else Delivery.DIRECT)
                for (r in p.redundant) {
                    if (r.seq >= e.seq || e.seq - r.seq >= 64 || window.status(r.seq) != ReplayWindow.Status.NEW) continue
                    noteArrival(r.seq, Delivery.REDUNDANCY, now)
                    acks += AckEntry(r.seq, r.messageId)
                    listener.onLocation(r.seq, r.fix, Delivery.REDUNDANCY)
                }
            }
            MessageType.STOP_SHARING -> listener.onStopSharing()
            else -> Unit
        }
        sendAck(acks, now)
    }

    private fun noteArrival(seq: Int, via: Delivery, now: Long) {
        val wasMissing = missing.remove(seq) != null
        if (seq > window.highest + 1) {
            val firstGap = maxOf(window.highest + 1, seq - 63, peerLowWater)
            stats.gapsDetected += seq - 1 - window.highest
            for (s in firstGap until seq) missing[s] = Missing(now)
        }
        window.mark(seq)
        if (!wasMissing) return
        when (via) {
            Delivery.RETRANSMISSION -> { stats.recoveredByRetransmission++; stats.lastRecoveryMethod = "retransmission (seq $seq)" }
            Delivery.REDUNDANCY -> { stats.recoveredByRedundancy++; stats.lastRecoveryMethod = "redundant copy in a later update (seq $seq)" }
            Delivery.DIRECT -> stats.lateArrivals++
        }
    }

    private fun receiveUnreliable(e: Envelope, plain: ByteArray, now: Long) {
        when (e.type) {
            MessageType.MESSAGE_ACK -> for (a in Payloads.decodeAck(plain)) onAck(a, now)
            MessageType.HEARTBEAT -> onPeerLowWater(Payloads.decodeHeartbeat(plain))
            MessageType.RECOVERY_REQUEST -> onRecoveryRequest(Payloads.decodeSeqs(plain), now)
            MessageType.DATA_CHUNK -> listener.onDataChunk(Payloads.decodeChunk(plain))
            MessageType.ERROR -> listener.onPeerError(Payloads.decodeError(plain))
            else -> stats.reject(RejectReason.NOT_READY)
        }
    }

    private fun onAck(a: AckEntry, now: Long) {
        val o = outgoing.firstOrNull { it.env.seq == a.seq && it.env.messageId == a.messageId }
        if (o == null || o.acked) { stats.duplicateAcks++; return }
        o.acked = true
        stats.acked++
        stats.recordAck()
        lastAckAt = now
        if (!o.retransmitted && o.attempts == 1) stats.recordRtt(now - o.lastSentAt)
        if (o.retransmitted) stats.recordRetryResult(true)
    }

    private fun onRecoveryRequest(seqs: List<Int>, now: Long) {
        if (!config.retransmissionsEnabled) return
        val newestAckedLocation = outgoing.filter { it.isLocation && it.acked }.maxOfOrNull { it.env.seq } ?: 0
        for (s in seqs) {
            val o = outgoing.firstOrNull { it.env.seq == s } ?: continue
            if (!recoverable(o, now, newestAckedLocation)) continue
            transmit(o, now, retransmission = true)
            stats.nackRetransmissions++
        }
        sendHeartbeat(now) // tells the receiver which of the rest are settled
    }

    private fun onPeerLowWater(lowWater: Int) {
        if (lowWater <= peerLowWater) return
        peerLowWater = lowWater
        val settled = missing.keys.filter { it < lowWater }
        settled.forEach(missing::remove)
        stats.skippedSettled += settled.size
    }

    private fun requestMissing(now: Long) {
        val ask = mutableListOf<Int>()
        val giveUp = mutableListOf<Int>()
        for ((seq, m) in missing) {
            val wait = if (m.nacks == 0) config.nackDelayMs else config.nackRepeatMs
            if (now - m.lastNackAt < wait) continue
            if (m.nacks >= config.maxNacks) giveUp += seq
            else if (ask.size < Payloads.MAX_RECOVERY_SEQS) { ask += seq; m.nacks++; m.lastNackAt = now }
        }
        giveUp.forEach(missing::remove)
        stats.unrecovered += giveUp.size
        if (ask.isNotEmpty()) {
            stats.recoveryRequestsSent++
            sendEnvelope(newEnvelope(MessageType.RECOVERY_REQUEST, Payloads.encodeSeqs(ask), config.controlTtlMs, now))
        }
    }

    val missingCount get() = missing.size

    private fun sendAck(entries: List<AckEntry>, now: Long) {
        for (batch in entries.chunked(Payloads.MAX_ACKS)) {
            stats.acksSent++
            sendEnvelope(newEnvelope(MessageType.MESSAGE_ACK, Payloads.encodeAck(batch), config.controlTtlMs, now))
        }
    }
}
