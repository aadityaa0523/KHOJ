package app.linkfill.session

import android.content.Context
import android.content.Intent
import app.linkfill.core.fec.FecCodec
import app.linkfill.core.policy.DeterministicRecoveryPolicy
import app.linkfill.core.policy.MlRecoveryPolicy
import app.linkfill.core.policy.RecoveryPolicy
import app.linkfill.core.protocol.Accept
import app.linkfill.core.protocol.DataChunk
import app.linkfill.core.protocol.Decoded
import app.linkfill.core.protocol.Envelope
import app.linkfill.core.protocol.Hello
import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.protocol.MessageCodec
import app.linkfill.core.protocol.MessageType
import app.linkfill.core.protocol.PayloadException
import app.linkfill.core.protocol.Payloads
import app.linkfill.core.reliability.ChannelListener
import app.linkfill.core.reliability.Delivery
import app.linkfill.core.reliability.LinkStats
import app.linkfill.core.reliability.ReliableChannel
import app.linkfill.core.security.AesGcmSealer
import app.linkfill.core.security.SessionCrypto
import app.linkfill.core.security.SessionKeys
import app.linkfill.location.Compass
import app.linkfill.location.LocationProvider
import app.linkfill.nearby.NearbyConnectionManager
import app.linkfill.service.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.KeyPair
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The one session object for the app (created by LinkFillApp). It owns the Nearby link, the handshake,
 * the reliable channel and location, and publishes [state] for the UI. Everything runs on the main thread.
 */
class LinkSession(private val context: Context, private val scope: CoroutineScope) : NearbyConnectionManager.Events {

    private val nearby = NearbyConnectionManager(context, this)
    private val location = LocationProvider(context)
    private val compass = Compass(context)
    private val secure = SecureRandom()
    private val random: Random = secure.asKotlinRandom()

    private val modelLoad = MlRecoveryPolicy.loadBundled()
    private val mlPolicy = (modelLoad as? MlRecoveryPolicy.Companion.Load.Ready)?.policy

    private val _state = MutableStateFlow(
        SessionState(
            localName = android.os.Build.MODEL,
            policyMode = if (mlPolicy != null) PolicyMode.MODEL else PolicyMode.RULES,
            modelAvailable = mlPolicy != null,
            modelStatus = when (modelLoad) {
                is MlRecoveryPolicy.Companion.Load.Ready -> "Loaded: ${modelLoad.policy.model.metadata.summary()}"
                is MlRecoveryPolicy.Companion.Load.Unavailable -> "Not available (${modelLoad.reason}); using deterministic rules"
            },
        ),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // Link and session
    private var endpointId: String? = null
    private val authDigits = mutableMapOf<String, String>()
    private val requestNames = mutableMapOf<String, String>()
    private val userConfirmed = mutableSetOf<String>()
    private val autoAccepted = mutableSetOf<String>()
    private var keyPair: KeyPair? = null
    private var sessionId = 0L
    private var localId = 0
    private var keys: SessionKeys? = null
    private var previousKeys: SessionKeys? = null
    private var previousSessionId = 0L
    private var channel: ReliableChannel? = null
    private var linkStats = LinkStats()
    private var latestShownSeq = 0
    private var sessionStartedAt = 0L
    private var handshakeDeadline = 0L
    private var reconnectDeadline = 0L
    private var serviceStarted = false
    private var advertisedName = ""
    private val decisionLog = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private class FecTransfer(val k: Int, val m: Int, val total: Int, val sha: ByteArray, val startedAt: Long) {
        val shards = mutableMapOf<Int, ByteArray>()
        var done = false
    }
    private val fecTransfers = mutableMapOf<Int, FecTransfer>()

    private var loop: Job? = null

    init {
        loop = scope.launch {
            var n = 0
            while (isActive) {
                val now = System.currentTimeMillis()
                channel?.tick(now)
                checkDeadlines(now)
                if (n++ % 5 == 0) publish()
                delay(100)
            }
        }
    }

    // ---------------------------------------------------------------- user actions

    fun setName(name: String) = _state.update { it.copy(localName = name.take(24)) }

    fun startSharing() {
        if (_state.value.phase != Phase.IDLE && _state.value.phase != Phase.ENDED) return
        resetSessionData()
        advertisedName = "${_state.value.localName.ifBlank { "Phone" }} #${tag()}"
        _state.update { it.copy(role = Role.SENDER, phase = Phase.ADVERTISING, notice = null, localName = it.localName) }
        nearby.startAdvertising(advertisedName)
    }

    fun startFinding() {
        if (_state.value.phase != Phase.IDLE && _state.value.phase != Phase.ENDED) return
        resetSessionData()
        advertisedName = "${_state.value.localName.ifBlank { "Phone" }} #${tag()}"
        _state.update { it.copy(role = Role.RECEIVER, phase = Phase.DISCOVERING, notice = null, peers = emptyList()) }
        nearby.startDiscovery()
    }

    fun connectTo(peer: PeerItem) {
        val s = _state.value
        if (s.role != Role.RECEIVER || s.phase != Phase.DISCOVERING) return
        if (nearby.requestConnection(advertisedName, peer.endpointId)) {
            requestNames[peer.endpointId] = peer.name
            _state.update { it.copy(phase = Phase.CONNECTING, notice = "Asking ${peer.name} to share...") }
        }
    }

    /** The user compared the digits on both screens and confirmed. */
    fun confirmRequest() {
        val r = _state.value.request ?: return
        userConfirmed += r.endpointId
        requestNames[r.endpointId] = r.name
        nearby.accept(r.endpointId)
        _state.update { it.copy(request = null, phase = Phase.CONNECTING, notice = "Waiting for the other phone to confirm...") }
    }

    fun declineRequest() {
        val r = _state.value.request ?: return
        nearby.reject(r.endpointId)
        _state.update { it.copy(request = null, phase = searchPhase(), notice = "Declined.") }
    }

    /** Stop sharing or stop receiving. Tells the other phone, waits briefly for its acknowledgement, then disconnects. */
    fun stop() {
        val c = channel
        if (c == null || _state.value.phase != Phase.ACTIVE) { finish("Session ended.", Phase.ENDED); return }
        c.sendStop(System.currentTimeMillis())
        _state.update { it.copy(notice = "Stopping...") }
        scope.launch {
            val until = System.currentTimeMillis() + 1500
            while (!c.isSettled() && System.currentTimeMillis() < until) delay(50)
            finish(if (_state.value.role == Role.SENDER) "You stopped sharing." else "You stopped receiving.", Phase.ENDED)
        }
    }

    /** Back to the home screen, dropping everything. */
    fun reset() {
        if (_state.value.phase == Phase.ACTIVE) { stop(); return } // ends the session properly first
        finish(null, Phase.IDLE)
        _state.update { it.copy(role = Role.NONE, notice = null) }
    }

    fun setLoss(profile: LossProfile) {
        nearby.simulatedLoss = profile.model()
        _state.update { it.copy(loss = profile) }
    }

    fun setPolicy(mode: PolicyMode) {
        val m = if (mode == PolicyMode.MODEL && mlPolicy == null) PolicyMode.RULES else mode
        channel?.policy = policy(m)
        _state.update { it.copy(policyMode = m) }
    }

    /** Sends a 4 KB random payload as 10 data + 4 Reed-Solomon parity chunks, without retransmission. */
    fun sendFecTest() {
        val c = channel ?: return
        val now = System.currentTimeMillis()
        val payload = ByteArray(4096).also(secure::nextBytes)
        val k = 10; val m = 4
        val shards = FecCodec.split(payload, k, m)
        val sha = FecCodec.sha256(payload)
        val id = random.nextInt()
        shards.forEachIndexed { i, s -> c.sendChunk(DataChunk(id, k, m, i, payload.size, sha, s), now) }
        _state.update { it.copy(fecResult = "Sent ${payload.size} bytes as ${k + m} chunks ($k data + $m parity). The other phone shows what it rebuilt.") }
    }

    // ---------------------------------------------------------------- Nearby events

    override fun onEndpointFound(endpointId: String, name: String) {
        _state.update { s -> s.copy(peers = (s.peers.filter { it.endpointId != endpointId } + PeerItem(endpointId, name)).sortedBy { it.name }) }
        val s = _state.value
        if (s.role == Role.RECEIVER && s.phase == Phase.RECONNECTING && name == s.peerName) {
            if (nearby.requestConnection(advertisedName, endpointId)) requestNames[endpointId] = name
        }
    }

    override fun onEndpointLost(endpointId: String) {
        _state.update { s -> s.copy(peers = s.peers.filter { it.endpointId != endpointId }) }
    }

    override fun onConnectionInitiated(endpointId: String, name: String, authDigits: String, incoming: Boolean) {
        this.authDigits[endpointId] = authDigits
        val s = _state.value
        when {
            s.phase == Phase.RECONNECTING && name == s.peerName -> {
                // Same peer coming back. Accept at the radio level; the handshake must still prove it holds the old keys.
                autoAccepted += endpointId
                requestNames[endpointId] = name
                nearby.accept(endpointId)
            }
            s.role == Role.SENDER && s.phase == Phase.ADVERTISING && incoming ||
                s.role == Role.RECEIVER && s.phase == Phase.CONNECTING && !incoming -> {
                _state.update { it.copy(phase = Phase.CONFIRMING, request = ConnectionRequest(endpointId, name, authDigits, incoming), notice = null) }
            }
            else -> nearby.reject(endpointId)
        }
    }

    override fun onConnected(endpointId: String) {
        this.endpointId = endpointId
        val name = requestNames[endpointId]
        nearby.stopAdvertising(); nearby.stopDiscovery()
        handshakeDeadline = System.currentTimeMillis() + 10_000
        _state.update { it.copy(phase = Phase.SECURING, peerName = name ?: it.peerName, notice = "Securing the connection...") }
        if (_state.value.role == Role.RECEIVER) beginHandshake()
    }

    override fun onConnectionFailed(endpointId: String, reason: String) {
        val s = _state.value
        if (s.phase == Phase.RECONNECTING) { _state.update { it.copy(notice = "Reconnect attempt failed: $reason") }; return }
        if (s.phase == Phase.ENDED || s.phase == Phase.IDLE) return
        _state.update { it.copy(phase = searchPhase(), request = null, notice = reason) }
        restartSearch()
    }

    override fun onDisconnected(endpointId: String) {
        if (endpointId != this.endpointId) return
        val now = System.currentTimeMillis()
        channel?.setConnected(false, now)
        this.endpointId = null
        val s = _state.value
        if (s.phase == Phase.ACTIVE || (s.phase == Phase.SECURING && previousKeys != null)) {
            keys?.let { previousKeys = it; previousSessionId = sessionId }
            channel = null
            keys = null
            reconnectDeadline = now + 120_000
            _state.update { it.copy(phase = Phase.RECONNECTING, notice = "Connection lost. Trying to reconnect for 2 minutes...") }
            restartSearch()
        } else if (s.phase != Phase.ENDED && s.phase != Phase.IDLE) {
            channel = null
            _state.update { it.copy(phase = searchPhase(), notice = "The connection closed before it was secured.") }
            restartSearch()
        }
    }

    override fun onBytes(endpointId: String, bytes: ByteArray) {
        if (endpointId != this.endpointId) return
        val now = System.currentTimeMillis()
        val c = channel
        if (c != null) { c.receive(bytes, now); return }
        handleHandshake(bytes, now)
    }

    override fun onError(message: String) {
        _state.update { it.copy(notice = message, phase = if (it.phase == Phase.ADVERTISING || it.phase == Phase.DISCOVERING) Phase.ENDED else it.phase) }
    }

    // ---------------------------------------------------------------- handshake

    private fun envelope(type: MessageType, body: ByteArray, now: Long) =
        Envelope(type, sessionId, localId, 0, random.nextLong(), now, now + 30_000, body)

    /** Receiver side: propose a session with a fresh ECDH key, plus proof of the old keys when resuming. */
    private fun beginHandshake() {
        val id = endpointId ?: return
        val now = System.currentTimeMillis()
        keyPair = SessionCrypto.newKeyPair()
        sessionId = random.nextLong().let { if (it == 0L) 1L else it }
        localId = random.nextInt().let { if (it == 0) 1 else it }
        val pub = keyPair!!.public.encoded
        val prev = previousKeys
        val hello = if (prev != null && autoAccepted.contains(id)) {
            Hello(pub, previousSessionId, SessionCrypto.resumeProof(prev, sessionId, pub))
        } else Hello(pub, null, null)
        nearby.send(id, MessageCodec.encode(envelope(MessageType.SESSION_HELLO, Payloads.encodeHello(hello), now)))
    }

    private fun handleHandshake(bytes: ByteArray, now: Long) {
        val id = endpointId ?: return
        val e = (MessageCodec.decode(bytes, null) as? Decoded.Ok)?.envelope ?: return
        try {
            when {
                e.type == MessageType.SESSION_HELLO && _state.value.role == Role.SENDER -> onHello(id, e, now)
                e.type == MessageType.SESSION_ACCEPT && _state.value.role == Role.RECEIVER -> onAccept(id, e, now)
            }
        } catch (_: PayloadException) {
            failSecurity(id, "The other phone sent an invalid handshake.")
        } catch (_: java.security.GeneralSecurityException) {
            failSecurity(id, "The other phone's key could not be used.")
        }
    }

    /** Sender side: accept a session only from a user-confirmed connection or with a valid resume proof. */
    private fun onHello(id: String, e: Envelope, now: Long) {
        val hello = Payloads.decodeHello(e.body)
        if (id !in userConfirmed) {
            val prev = previousKeys
            val proofOk = prev != null && id in autoAccepted && hello.resumeSessionId == previousSessionId &&
                hello.resumeProof != null &&
                SessionCrypto.constantTimeEquals(hello.resumeProof!!, SessionCrypto.resumeProof(prev, e.sessionId, hello.publicKey))
            if (!proofOk) return failSecurity(id, "A phone tried to reconnect without the session keys. Refused.")
        }
        sessionId = e.sessionId
        localId = random.nextInt().let { if (it == 0) 1 else it }
        val kp = SessionCrypto.newKeyPair().also { keyPair = it }
        val myPub = kp.public.encoded
        val k = SessionCrypto.deriveKeys(kp, hello.publicKey, sessionId, authDigits[id].orEmpty(), hello.publicKey, myPub)
        val tagBytes = SessionCrypto.confirmTag(k, hello.publicKey, myPub)
        nearby.send(id, MessageCodec.encode(envelope(MessageType.SESSION_ACCEPT, Payloads.encodeAccept(Accept(myPub, tagBytes)), now)))
        activate(k, peerId = e.senderId, clockOffset = e.createdAt - now, isSender = true)
    }

    private fun onAccept(id: String, e: Envelope, now: Long) {
        if (e.sessionId != sessionId) return
        val kp = keyPair ?: return
        val accept = Payloads.decodeAccept(e.body)
        val myPub = kp.public.encoded
        val k = SessionCrypto.deriveKeys(kp, accept.publicKey, sessionId, authDigits[id].orEmpty(), myPub, accept.publicKey)
        if (!SessionCrypto.constantTimeEquals(accept.confirmTag, SessionCrypto.confirmTag(k, myPub, accept.publicKey))) {
            return failSecurity(id, "Security check failed: the keys did not match. Disconnected.")
        }
        activate(k, peerId = e.senderId, clockOffset = e.createdAt - now, isSender = false)
    }

    private fun activate(k: SessionKeys, peerId: Int, clockOffset: Long, isSender: Boolean) {
        val id = endpointId ?: return
        keys = k
        val sealer = if (isSender) AesGcmSealer(k.responderToInitiator, k.initiatorToResponder, secure)
        else AesGcmSealer(k.initiatorToResponder, k.responderToInitiator, secure)
        val c = ReliableChannel(
            sessionId = sessionId, localId = localId, peerId = peerId, sealer = sealer,
            transport = { bytes -> nearby.send(id, bytes) },
            policy = policy(_state.value.policyMode), random = random, listener = listener,
            stats = linkStats, peerClockOffsetMs = clockOffset,
        )
        c.decisionObserver = { r ->
            val d = r.decision
            val detail = buildString {
                append("${timeFormat.format(Date())}  update #${r.seq}: ${d.action}  [${d.source}")
                d.margin?.let { append(", margin %.2f s".format(it)) }
                if (d.inferenceNanos > 0) append(", %.0f µs".format(d.inferenceNanos / 1000.0))
                append("]")
            }
            decisionLog.addFirst(detail)
            while (decisionLog.size > 12) decisionLog.removeLast()
        }
        channel = c
        latestShownSeq = 0
        handshakeDeadline = 0
        if (sessionStartedAt == 0L) sessionStartedAt = System.currentTimeMillis()
        val resumed = _state.value.phase == Phase.SECURING && previousKeys != null && id !in userConfirmed
        _state.update { it.copy(phase = Phase.ACTIVE, notice = if (resumed) "Reconnected." else null) }
        if (isSender) {
            location.start(onFix = ::onOwnFixAsSender, onError = { msg -> _state.update { it.copy(notice = msg) } })
        } else {
            location.start(onFix = { fix -> _state.update { it.copy(myFix = fix) } }, onError = { msg -> _state.update { it.copy(notice = msg) } })
            compass.start { h -> _state.update { it.copy(headingDeg = h) } }
        }
        startService(isSender)
        publish()
    }

    private fun failSecurity(id: String, message: String) {
        nearby.disconnect(id)
        endpointId = null
        _state.update { it.copy(notice = message, phase = if (it.phase == Phase.RECONNECTING) Phase.RECONNECTING else searchPhase()) }
        restartSearch()
    }

    // ---------------------------------------------------------------- data

    private fun onOwnFixAsSender(fix: LocationFix) {
        _state.update { it.copy(myFix = fix) }
        val c = channel ?: return
        if (_state.value.phase == Phase.ACTIVE) c.sendLocation(fix, System.currentTimeMillis())
    }

    private val listener = object : ChannelListener {
        override fun onLocation(seq: Int, fix: LocationFix, delivery: Delivery) {
            if (seq <= latestShownSeq) return // an older fix recovered late: counted, but never shown as current
            latestShownSeq = seq
            val now = System.currentTimeMillis()
            _state.update { it.copy(previousPeerFix = it.peerFix, peerFix = fix, peerFixReceivedAt = now, peerFixVia = delivery) }
        }

        override fun onStopSharing() {
            val who = _state.value.peerName ?: "The other phone"
            finish(if (_state.value.role == Role.RECEIVER) "$who stopped sharing." else "$who stopped receiving.", Phase.ENDED)
        }

        override fun onDataChunk(chunk: DataChunk) = onFecChunk(chunk)

        override fun onPeerError(code: Int) = finish("The other phone reported an error ($code).", Phase.ENDED)
    }

    private fun onFecChunk(c: DataChunk) {
        val t = fecTransfers.getOrPut(c.transferId) { FecTransfer(c.k, c.m, c.totalLength, c.sha256, System.currentTimeMillis()) }
        if (t.done || c.k != t.k || c.m != t.m) return
        t.shards[c.index] = c.data
        if (t.shards.size < t.k) return
        t.done = true
        val joined = FecCodec.join(t.shards, t.k, t.m, t.total)
        val lostIdx = (0 until t.k + t.m).filter { it !in t.shards }
        val result = when {
            joined == null -> "Could not rebuild the payload."
            !SessionCrypto.constantTimeEquals(FecCodec.sha256(joined), t.sha) -> "Rebuilt bytes do not match the sender's SHA-256. Rejected."
            else -> "Rebuilt all ${t.total} bytes from ${t.shards.size} of ${t.k + t.m} chunks" +
                (if (lostIdx.isEmpty()) " (none lost so far)" else "; chunks lost before rebuild: ${lostIdx.joinToString()}") +
                ". Method: Reed-Solomon ${t.k}+${t.m}. SHA-256 verified."
        }
        _state.update { it.copy(fecResult = result) }
    }

    // ---------------------------------------------------------------- housekeeping

    private fun checkDeadlines(now: Long) {
        val s = _state.value
        if (s.phase == Phase.SECURING && handshakeDeadline != 0L && now > handshakeDeadline) {
            endpointId?.let { failSecurity(it, "The other phone did not complete the secure handshake in time.") }
        }
        if (s.phase == Phase.RECONNECTING && now > reconnectDeadline) finish("Could not reconnect within 2 minutes.", Phase.ENDED)
        if (sessionStartedAt != 0L && now - sessionStartedAt > SESSION_MAX_MS) finish("The session reached its 2-hour limit and was closed.", Phase.ENDED)
        for ((_, t) in fecTransfers) if (!t.done && now - t.startedAt > 5_000) {
            t.done = true
            _state.update { it.copy(fecResult = "Only ${t.shards.size} of ${t.k + t.m} chunks arrived; ${t.k} are needed. Not recoverable without resending.") }
        }
    }

    private fun publish() {
        val c = channel
        val s = linkStats
        _state.update {
            it.copy(
                now = System.currentTimeMillis(),
                decisionLog = decisionLog.toList(),
                stats = StatsView(
                    messagesSent = s.messagesSent, transmissions = s.transmissions, retransmissions = s.retransmissions,
                    nackRetransmissions = s.nackRetransmissions, acked = s.acked, timeouts = s.timeouts, markedStale = s.markedStale,
                    superseded = s.superseded, expiredUnacked = s.expiredUnacked, redundantFixesSent = s.redundantFixesSent,
                    messagesReceived = s.messagesReceived, acksSent = s.acksSent, duplicatesDiscarded = s.duplicatesDiscarded,
                    gapsDetected = s.gapsDetected, recoveredByRetransmission = s.recoveredByRetransmission,
                    recoveredByRedundancy = s.recoveredByRedundancy, lateArrivals = s.lateArrivals, unrecovered = s.unrecovered,
                    skippedSettled = s.skippedSettled, recoveryRequestsSent = s.recoveryRequestsSent,
                    rejected = if (s.rejected.isEmpty()) "none" else s.rejected.entries.joinToString { (k, v) -> "$k $v" },
                    srttMs = s.srttMs?.toLong(), rtoMs = c?.rto(), pending = c?.pendingCount ?: 0, missingNow = c?.missingCount ?: 0,
                    handedToTransport = nearby.handedToTransport, transportFailures = nearby.transportFailures,
                    droppedBySimulation = nearby.droppedBySimulation, bytesSent = s.bytesSent, bytesReceived = s.bytesReceived,
                    decisions = if (s.decisions.isEmpty()) "none yet" else s.decisions.entries.joinToString { (k, v) -> "$k $v" },
                    mlFallbacks = s.mlRejectedToFallback,
                    inferenceMicros = if (s.inferenceCount > 0) s.inferenceNanosTotal / 1000.0 / s.inferenceCount else null,
                    lastRecovery = s.lastRecoveryMethod,
                    lastDecision = s.lastDecision?.let { d -> "${d.action} (${d.source})" },
                ),
            )
        }
        // Last successful transmission = the last time a new acknowledgement arrived (sampled every 500 ms).
        if (s.acked > lastAckCount) { lastAckCount = s.acked; _state.update { it.copy(lastAckAt = System.currentTimeMillis()) } }
    }

    private var lastAckCount = 0

    private fun finish(message: String?, phase: Phase) {
        endpointId?.let { nearby.disconnect(it) }
        nearby.stopAll()
        location.stop()
        compass.stop()
        stopService()
        channel = null
        keys = null; previousKeys = null; keyPair = null
        endpointId = null
        sessionStartedAt = 0
        fecTransfers.clear()
        // No location history is kept after the session ends.
        _state.update {
            it.copy(
                phase = phase, notice = message, request = null, peers = emptyList(),
                peerFix = null, previousPeerFix = null, peerFixReceivedAt = null, peerFixVia = null, myFix = null, headingDeg = null,
            )
        }
    }

    private fun resetSessionData() {
        linkStats = LinkStats()
        lastAckCount = 0
        decisionLog.clear()
        authDigits.clear(); requestNames.clear(); userConfirmed.clear(); autoAccepted.clear()
        previousKeys = null; previousSessionId = 0
        _state.update { it.copy(peerName = null, lastAckAt = null, fecResult = null, stats = StatsView(), decisionLog = emptyList()) }
    }

    private fun restartSearch() {
        when (_state.value.role) {
            Role.SENDER -> nearby.startAdvertising(advertisedName)
            Role.RECEIVER -> nearby.startDiscovery()
            Role.NONE -> Unit
        }
    }

    private fun searchPhase() = if (_state.value.role == Role.SENDER) Phase.ADVERTISING else Phase.DISCOVERING

    private fun policy(mode: PolicyMode): RecoveryPolicy = if (mode == PolicyMode.MODEL && mlPolicy != null) mlPolicy else DeterministicRecoveryPolicy()

    private fun tag() = (1000 + random.nextInt(9000)).toString()

    private fun startService(isSender: Boolean) {
        if (serviceStarted) return
        serviceStarted = true
        val i = Intent(context, SessionService::class.java).putExtra(SessionService.EXTRA_SENDER, isSender)
        context.startForegroundService(i)
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        context.stopService(Intent(context, SessionService::class.java))
    }

    companion object {
        const val SESSION_MAX_MS = 2 * 60 * 60_000L
    }
}
