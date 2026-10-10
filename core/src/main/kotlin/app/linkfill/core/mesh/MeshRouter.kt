package app.linkfill.core.mesh

import kotlin.random.Random

/** How hard the radio works. Chosen from predicted loss risk (see [MeshRouter.updateMode]). */
enum class LinkMode(val repeats: Int, val coded: Boolean, val label: String) {
    NORMAL(1, false, "Normal"),
    MEDIUM(2, false, "Extra copies (2x)"),
    HIGH(3, true, "Extra copies (3x), long range"),
}

/** How relay delays are scheduled (Phase 4). */
enum class RelayRule { RANDOM_SLOT, RSSI_FAR_FIRST }

/** Adaptive policy parameters for a given crowd density tier (Phase 4). */
data class DensityPolicy(
    val routineTtl: Int,
    val urgentTtl: Int,
    val relayProbability: Double,
    val suppressAfter: Int,
    val beaconPeriodMs: Long,
)

open class MeshConfig(
    val defaultTtl: Int = 6,
    val urgentRepeatMs: Long = 10_000,
    val urgentMaxSends: Int = 6,
    val relayBaseMs: Long = 40,
    val relayJitterMs: Long = 250,
    val relayRssiSpreadMs: Long = 400,
    /** Cancel a pending relay once this many *other* relays were heard carrying the same packet. */
    val suppressAfter: Int = 2,
    val seenMemoryMs: Long = 120_000,
    val maxAgeS: Long = 600,
    val maxQueue: Int = 64,
    val codedSupported: Boolean = false,
    val suppression: Boolean = true,
    /** Store-carry-forward: how many urgent packets each phone keeps to hand to phones that arrive later. */
    val carryMax: Int = 32,
    /** A phone not heard for this long counts as newly arrived, and is offered the carried packets. */
    val newNeighborGapMs: Long = 60_000,
    /** At most this many carried packets per offer, newest first. */
    val offerMax: Int = 8,
    val offerEveryMs: Long = 5_000,
    val dwellMs: Long = 1_000L,
    val lowPowerDwellMs: Long = 3_000L,
    val relayRule: RelayRule = RelayRule.RANDOM_SLOT,
) {
    /** Returns adaptive routing parameters for the local crowd density (Phase 4). */
    open fun densityPolicy(neighbors5s: Int): DensityPolicy = when {
        neighbors5s < 10 -> DensityPolicy(routineTtl = 4, urgentTtl = 6, relayProbability = 1.0, suppressAfter = 2, beaconPeriodMs = 3_000L)
        neighbors5s < 50 -> DensityPolicy(routineTtl = 3, urgentTtl = 5, relayProbability = 0.7, suppressAfter = 3, beaconPeriodMs = 6_000L)
        neighbors5s < 200 -> DensityPolicy(routineTtl = 2, urgentTtl = 4, relayProbability = (8.0 / neighbors5s).coerceAtMost(0.4), suppressAfter = 4, beaconPeriodMs = 12_000L)
        else -> DensityPolicy(routineTtl = 2, urgentTtl = 3, relayProbability = (8.0 / neighbors5s).coerceAtMost(0.1), suppressAfter = 5, beaconPeriodMs = 20_000L)
    }
}

interface MeshListener {
    fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) {}
    fun onAcked(packetId: Int, byOriginId: Int) {}
    /** Messages for everyone (volunteers, police, families of a wristband); see [PublicMessage]. */
    fun onPublic(packet: MeshPacket, message: PublicMessage, hops: Int, rssi: Int, ageS: Long) {}
}

class MeshStats {
    var framesIn = 0; var framesOut = 0; var malformed = 0; var repeatedFrames = 0
    var carriedOffered = 0; var friendPolls = 0
    var duplicates = 0; var delivered = 0; var authFailed = 0; var tooOld = 0
    var relayed = 0; var relayScheduled = 0; var suppressed = 0; var foreignRelayed = 0; var queueDropped = 0
    var originated = 0; var urgentResends = 0; var acksReceived = 0
    var publicDelivered = 0; var publicRateLimited = 0
}

/**
 * Managed flooding over any broadcast radio (BLE advertising on phones, or a LoRa radio later).
 * Every node relays every KHOJ packet once, after a short random delay; nodes that heard the
 * packet strongly wait longer, so the farthest nodes relay first, and a node cancels its relay if
 * enough other relays already carried it. Time is passed in, so the same code runs on phones and in
 * the simulator. Not thread-safe.
 */
class MeshRouter(
    val nodeId: Int,
    var crew: CrewKey?,
    val config: MeshConfig,
    private val random: Random,
    private val listener: MeshListener,
    var predictor: LossPredictor? = null,
) {
    val links = LinkTracker()
    val stats = MeshStats()

    /**
     * Internet bridge: called once for every packet this phone originates and for every new packet it first
     * hears over a local radio. A phone with mobile data uploads it, independent of local relay suppression
     * (in a dense crowd most local relays are suppressed, but the upload is what carries it beyond the crowd).
     */
    var bridge: ((frame: ByteArray, packet: MeshPacket) -> Unit)? = null

    /**
     * Called for every valid Bluetooth advertising frame with the phone that transmitted it ([relayId], which is the
     * originator itself when the frame is its own) and the signal strength it arrived with. Used by Find Member to track
     * the signal from one specific crew member. Only frames heard directly over Bluetooth are reported.
     */
    var rssiObserver: ((relayId: Int, rssi: Int, coded: Boolean, now: Long) -> Unit)? = null
    var mode = LinkMode.NORMAL
        private set

    /**
     * Low-power member (Bluetooth Mesh's "low power node", adapted): this phone does not relay or carry for others and
     * marks its frames, so any normal phone that hears it acts as its friend and re-offers what it may have missed
     * while its radio slept.
     */
    var lowPower = false
    var risk: Double? = null
        private set

    private class Out(val packet: MeshPacket, var repeatsLeft: Int, val dueAt: Long, val priority: Int, val isRelay: Boolean) {
        val otherRelays = mutableSetOf<Int>()
        var firstRelay = 0
    }

    /** Resends reuse the original encrypted packet unchanged: a new timestamp under the same nonce would break AES-GCM. */
    private class Urgent(val packet: MeshPacket, var sends: Int, var nextRetryAt: Long)

    private val queue = ArrayList<Out>()
    private val seen = LinkedHashMap<Long, Long>()
    private val pendingRelay = HashMap<Long, Out>()
    private val urgent = LinkedHashMap<Int, Urgent>()
    private val acked = mutableSetOf<Long>()
    private val publicTimes = HashMap<Int, ArrayDeque<Long>>()

    /** Urgent packets kept for later arrivals, ready to transmit, with the time each was stored. */
    private class Carried(val packet: MeshPacket, val at: Long)
    private val carried = LinkedHashMap<Long, Carried>()
    /** Carried packets stay "seen" for their whole life, so a re-offer is never delivered twice. */
    private val longSeen = HashMap<Long, Long>()
    private val lastHeardFrom = HashMap<Int, Long>()
    private val lastOfferTo = HashMap<Int, Long>()
    private var lastNewcomerOffer = 0L
    private var linkSeq = random.nextInt(0, 0xFFFF)
    private var packetSeq = random.nextInt()
    private var lowRiskSince = 0L
    private var lastModeUpdate = 0L

    /** Sends a crew message. SOS and RALLY are repeated until a crew member acknowledges them. Returns the packet id. */
    fun originate(message: MeshMessage, now: Long, ttl: Int = -1): Int {
        val key = crew ?: error("join a crew before sending")
        val (type, plain) = MeshMessage.encode(message)
        val id = ++packetSeq
        val policy = config.densityPolicy(links.density(now))
        val isUrgent = type == MeshType.SOS || type == MeshType.ACK
        val actualTtl = if (ttl > 0) ttl else if (isUrgent) policy.urgentTtl else policy.routineTtl
        val bare = MeshPacket(type, actualTtl, 0, nodeId, 0, key.crewId, nodeId, id, now / 1000, ByteArray(0))
        val packet = MeshPacket(type, actualTtl, 0, nodeId, 0, key.crewId, nodeId, id, now / 1000, key.seal(bare, plain))
        seen[packet.key] = now
        stats.originated++
        val priority = when (type) {
            MeshType.SOS -> 0
            MeshType.ACK -> 1
            MeshType.BEACON, MeshType.RALLY, MeshType.TEXT, MeshType.LOOKING -> 3
            else -> 4
        }
        if (type == MeshType.BEACON) queue.removeAll { !it.isRelay && it.packet.type == MeshType.BEACON } // only the newest position matters
        enqueue(Out(packet, mode.repeats, now, priority, isRelay = false))
        if (type == MeshType.SOS || type == MeshType.RALLY) {
            val jitter = (random.nextDouble(-0.2, 0.2) * 1_000L).toLong()
            urgent[id] = Urgent(packet, 1, now + 1_000L + jitter)
        }
        carry(packet, now)
        bridge?.invoke(MeshPacket.encode(packet), packet)
        return id
    }

    /**
     * Handles a frame from any transport. Only Bluetooth advertising frames feed the link tracker (its PDR
     * is measured from link sequence numbers); copies of a packet arriving over several transports are
     * dropped by the packet-level duplicate filter below.
     */
    /**
     * Sends a public message (not crew-sealed). Found reports and public SOS are repeated using jittered
     * exponential backoff, up to 6 times, until [cancelRepeats] is called.
     */
    fun originatePublic(message: PublicMessage, now: Long, crewId: Int = 0, ttl: Int = -1): Int {
        val (type, body) = PublicMessage.encode(message)
        val id = ++packetSeq
        val policy = config.densityPolicy(links.density(now))
        val isUrgent = type in setOf(MeshType.PUBLIC_SOS, MeshType.FOUND, MeshType.FOUND_ACK, MeshType.RESPOND, MeshType.OFFICIAL)
        val actualTtl = if (ttl > 0) ttl else if (isUrgent) policy.urgentTtl else policy.routineTtl
        val packet = MeshPacket(type, actualTtl, 0, nodeId, 0, crewId, nodeId, id, now / 1000, body)
        seen[packet.key] = now
        stats.originated++
        val priority = when (type) {
            MeshType.PUBLIC_SOS, MeshType.FOUND -> 0
            MeshType.FOUND_ACK, MeshType.RESPOND -> 1
            MeshType.OFFICIAL, MeshType.FOUND_NOTICE -> 2
            else -> 3
        }
        enqueue(Out(packet, mode.repeats, now, priority, isRelay = false))
        if (type == MeshType.FOUND || type == MeshType.PUBLIC_SOS) {
            val jitter = (random.nextDouble(-0.2, 0.2) * 1_000L).toLong()
            urgent[id] = Urgent(packet, 1, now + 1_000L + jitter)
        }
        carry(packet, now)
        bridge?.invoke(MeshPacket.encode(packet), packet)
        return id
    }

    fun cancelRepeats(packetId: Int) { urgent.remove(packetId) }

    /** At most 5 public packets per 10 s from one sender, so one phone cannot flood volunteers' screens. */
    private fun publicAllowed(originId: Int, now: Long): Boolean {
        val q = publicTimes.getOrPut(originId) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
        if (q.size >= 5) return false
        q.addLast(now)
        if (publicTimes.size > 512) publicTimes.keys.firstOrNull()?.let(publicTimes::remove)
        return true
    }

    fun onFrame(bytes: ByteArray, rssi: Int, coded: Boolean, now: Long, via: TransportKind = TransportKind.BLE) {
        val p = MeshPacket.decode(bytes) ?: run { stats.malformed++; return }
        stats.framesIn++
        // Every copy feeds the signal tracker: each frame goes out on 1M and LE Coded at once, and if the Coded copy is
        // de-duplicated first, Find (which only trusts 1M RSSI) would starve.
        if (via == TransportKind.BLE) rssiObserver?.invoke(p.relayId, rssi, coded, now)
        if (via == TransportKind.BLE && !links.onFrame(p.relayId, p.linkSeq, rssi, coded, now)) { stats.repeatedFrames++; return }
        if (via != TransportKind.INTERNET) offerCarried(p.relayId, p.fromLowPower, now)
        if (p.type == MeshType.NEIGHBOR || p.originId == nodeId) return
        val k = p.key
        if (seen.containsKey(k) || longSeen.containsKey(k)) {
            stats.duplicates++
            pendingRelay[k]?.let { if (p.relayId != it.firstRelay) it.otherRelays += p.relayId }
            return
        }
        seen[k] = now
        val ageS = now / 1000 - p.originTimeS
        if (ageS > config.maxAgeS || ageS < -60) { stats.tooOld++; return }

        val myCrew = crew
        if (p.type in PublicMessage.TYPES) {
            if (!publicAllowed(p.originId, now)) { stats.publicRateLimited++; return }
            val m = PublicMessage.decode(p.type, p.body) ?: run { stats.malformed++; return }
            stats.publicDelivered++
            listener.onPublic(p, m, p.hops, rssi, ageS)
        } else if (myCrew != null && p.crewId == myCrew.crewId) {
            val plain = myCrew.open(p)
            if (plain == null) { stats.authFailed++; return } // forged or corrupted: neither delivered nor relayed
            MeshMessage.decode(p.type, plain)?.let { deliver(p, it, rssi, ageS, now) }
        } else stats.foreignRelayed++

        if (via != TransportKind.INTERNET) bridge?.invoke(bytes, p)
        if (!lowPower) carry(if (p.ttl > 0) p.relayed(nodeId, 0) else p, now)
        if (p.ttl > 0 && !lowPower) {
            val policy = config.densityPolicy(links.density(now))
            val urgentType = p.type in setOf(MeshType.SOS, MeshType.ACK, MeshType.PUBLIC_SOS, MeshType.OFFICIAL, MeshType.FOUND, MeshType.FOUND_ACK, MeshType.RESPOND)
            if (!urgentType && random.nextDouble() >= policy.relayProbability) return
            val delay = when (config.relayRule) {
                RelayRule.RANDOM_SLOT -> {
                    val slotRange = config.relayRssiSpreadMs + config.relayJitterMs
                    val randomSlot = random.nextLong(slotRange + 1)
                    val strength = ((rssi + 95) / 45.0).coerceIn(0.0, 1.0)
                    val tieBreaker = ((strength - 0.5) * 0.4 * slotRange).toLong()
                    (config.relayBaseMs + randomSlot + tieBreaker).coerceAtLeast(config.relayBaseMs)
                }
                RelayRule.RSSI_FAR_FIRST -> {
                    val strength = ((rssi + 95) / 45.0).coerceIn(0.0, 1.0)
                    config.relayBaseMs + (strength * config.relayRssiSpreadMs).toLong() + random.nextLong(config.relayJitterMs + 1)
                }
            }
            val priority = when (p.type) {
                MeshType.SOS, MeshType.PUBLIC_SOS, MeshType.FOUND -> 0
                MeshType.ACK, MeshType.FOUND_ACK, MeshType.RESPOND -> 1
                MeshType.OFFICIAL, MeshType.FOUND_NOTICE -> 2
                MeshType.BEACON, MeshType.RALLY, MeshType.TEXT, MeshType.LOOKING -> 3
                MeshType.NEIGHBOR -> 4
            }
            val out = Out(p.relayed(nodeId, 0), 1, now + delay, priority, isRelay = true)
            out.firstRelay = p.relayId
            pendingRelay[k] = out
            stats.relayScheduled++
            enqueue(out)
        }
    }

    private fun deliver(p: MeshPacket, m: MeshMessage, rssi: Int, ageS: Long, now: Long) {
        stats.delivered++
        if (m is MeshMessage.Ack) {
            if (m.originId == nodeId) {
                stats.acksReceived++
                if (acked.add((m.originId.toLong() shl 32) or (m.packetId.toLong() and 0xFFFFFFFFL))) listener.onAcked(m.packetId, p.originId)
                urgent.remove(m.packetId)
            }
            return
        }
        listener.onMessage(p.originId, m, p.hops, rssi, ageS)
        if (p.type == MeshType.SOS || p.type == MeshType.RALLY) originate(MeshMessage.Ack(p.originId, p.packetId), now)
    }

    /** Next frame to put on the air. With [allowFiller], an empty link-measurement frame is sent when idle. */
    fun nextFrame(now: Long, allowFiller: Boolean = true): ByteArray? {
        val policy = config.densityPolicy(links.density(now))
        while (true) {
            val next = queue.filter { it.dueAt <= now }.minWithOrNull(compareBy<Out> { it.priority }.thenBy { it.dueAt }) ?: break
            val suppressThreshold = if (config.suppression) policy.suppressAfter else Int.MAX_VALUE
            if (next.isRelay && config.suppression && next.otherRelays.size >= suppressThreshold) {
                queue.remove(next); pendingRelay.remove(next.packet.key); stats.suppressed++
                continue
            }
            next.repeatsLeft--
            if (next.repeatsLeft <= 0) { queue.remove(next); pendingRelay.remove(next.packet.key) }
            if (next.isRelay) { stats.relayed++ }
            stats.framesOut++
            return MeshPacket.encode(next.packet.onLink(nodeId, nextLinkSeq(), lowPower))
        }
        if (!allowFiller || lowPower || hasPendingUrgent()) return null
        stats.framesOut++
        return MeshPacket.encode(MeshPacket(MeshType.NEIGHBOR, 0, 0, nodeId, nextLinkSeq(), 0, nodeId, 0, now / 1000, ByteArray(0)))
    }

    private fun nextLinkSeq(): Int { linkSeq = (linkSeq + 1) and 0xFFFF; return linkSeq }

    /** True when a priority-0 urgent frame (SOS, ACK, PUBLIC_SOS, FOUND, OFFICIAL) is due for transmission. */
    fun hasUrgentDue(now: Long): Boolean =
        queue.any { it.priority == 0 && it.dueAt <= now }

    /** True when any urgent traffic (priority <= 2 or active urgent retry) is pending. */
    fun hasPendingUrgent(): Boolean =
        queue.any { it.priority <= 2 } || urgent.isNotEmpty()

    /**
     * Determines whether a new frame should be fetched and transmitted under the Phase 3 dwell rules:
     * - Routine frames dwell on air for [MeshConfig.dwellMs] (or [MeshConfig.lowPowerDwellMs]).
     * - Priority-0 urgent frames may pre-empt the dwell early, capped at once per 250 ms.
     */
    fun shouldTransmit(
        lastFrameSentMs: Long,
        lastUrgentPreemptMs: Long,
        now: Long,
        lowPower: Boolean = this.lowPower,
    ): Boolean {
        val dwell = if (lowPower) config.lowPowerDwellMs else config.dwellMs
        val dwellExpired = (now - lastFrameSentMs) >= dwell
        val urgentDue = hasUrgentDue(now)
        val urgentPreempt = urgentDue && ((now - lastFrameSentMs) >= 250) && ((now - lastUrgentPreemptMs) >= 250)
        return dwellExpired || urgentPreempt
    }

    /**
     * True if 1M link health is poor: either no neighbor heard in the last 10 s, or every active neighbor
     * has pdr_2s < 0.5. Used alongside low density to gate LE Coded emergency bursts.
     */
    fun is1mHealthPoor(now: Long): Boolean {
        val active = links.neighbors.values.filter { now - it.lastRxMs <= 10_000 }
        if (active.isEmpty()) return true
        return active.all { links.pdr(it.id, 2_000, now) < 0.5 }
    }

    fun tick(now: Long) {
        for (u in urgent.values.toList()) {
            if (now >= u.nextRetryAt) {
                if (u.sends >= config.urgentMaxSends) { urgent.remove(u.packet.packetId); continue }
                // Receivers that already have it drop it as a duplicate; new ones in range now get it.
                queue.firstOrNull { !it.isRelay && it.packet.packetId == u.packet.packetId }?.let { it.repeatsLeft = mode.repeats }
                    ?: enqueue(Out(u.packet, mode.repeats, now, 0, isRelay = false))
                u.sends++
                val shift = (u.sends - 1).coerceIn(0, 5)
                val baseDelay = (1_000L shl shift).coerceAtMost(32_000L)
                val jitter = (random.nextDouble(-0.2, 0.2) * baseDelay).toLong()
                u.nextRetryAt = now + baseDelay + jitter
                stats.urgentResends++
            }
        }
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > config.seenMemoryMs) it.remove()
        val maxAgeMs = config.maxAgeS * 1000
        longSeen.entries.removeIf { now - it.value > maxAgeMs + 120_000L }
        carried.values.removeAll { now - it.packet.originTimeS * 1000 > maxAgeMs }
        lastHeardFrom.values.removeAll { now - it > config.newNeighborGapMs * 5 }
        lastOfferTo.values.removeAll { now - it > config.newNeighborGapMs * 5 }
        links.prune(now)
        if (now - lastModeUpdate >= 1_000) { lastModeUpdate = now; updateMode(now) }
    }

    /**
     * Optional batch model with the same inputs as [predictor] (one LinkFeatures.ALL row per neighbour, one probability
     * out each). The app sets it to the NPU-backed Link AI; when it is null or fails, [predictor] runs on the CPU.
     */
    var riskModel: ((List<DoubleArray>) -> DoubleArray?)? = null
    /** Risk at which the link mode steps up; the app sets them from the bundled model's held-out thresholds. */
    var riskMedium = 0.3
    var riskHigh = 0.6

    /** Neighbour risk per active neighbour (batch model, else the CPU predictor, else 1 - PDR over 2 s). */
    fun neighborRisks(now: Long): Map<Int, Double> {
        val active = links.neighbors.values.filter { now - it.lastRxMs <= 10_000 }
        if (active.isEmpty()) return emptyMap()
        val rows = active.map { links.features(it.id, now, queue.size, mode.coded) }
        val p = riskModel?.invoke(rows)?.takeIf { it.size == rows.size }
            ?: predictor?.let { pr -> DoubleArray(rows.size) { pr.predict(rows[it]) } }
            ?: DoubleArray(rows.size) { 1 - links.pdr(active[it].id, 2_000, now) }
        return active.indices.associate { active[it].id to p[it] }
    }

    /**
     * Picks the link mode from the median neighbour risk, with hysteresis: step up at once, step down only
     * after 10 s of lower risk. No neighbours at all also means "reach further". Long range needs chip support.
     */
    fun updateMode(now: Long) {
        val risks = neighborRisks(now).values.sorted()
        val r = if (risks.isEmpty()) 1.0 else risks[risks.size / 2]
        risk = if (risks.isEmpty()) null else r
        val want = when {
            r >= riskHigh && config.codedSupported -> LinkMode.HIGH
            r >= riskMedium -> LinkMode.MEDIUM
            else -> LinkMode.NORMAL
        }
        if (want.ordinal >= mode.ordinal) { mode = want; lowRiskSince = now }
        else if (now - lowRiskSince >= 10_000) { mode = want; lowRiskSince = now }
    }

    val queueSize get() = queue.size
    val carriedCount get() = carried.size

    /** Keeps urgent packets (help requests, found reports, acknowledgements, official alerts, meeting points). */
    private fun carry(p: MeshPacket, now: Long) {
        if (p.type !in CARRY_TYPES) return
        longSeen[p.key] = now
        carried[p.key] = Carried(p, now)
        while (carried.size > config.carryMax) carried.remove(carried.keys.first())
    }

    /**
     * Healing across gaps in the crowd. Managed flooding only reaches phones in range at that moment; people move,
     * so every phone carries urgent packets for their lifetime and offers them when (a) a phone it has not heard for
     * a minute appears (someone walked into range, or a split group rejoined), or (b) a low-power member wakes and
     * transmits (friend role): then everything stored since its previous frame. Offers go out as relays, so other
     * carriers offering the same packet suppress each other, and phones that already have it drop it as a duplicate.
     */
    private fun offerCarried(from: Int, fromLowPower: Boolean, now: Long) {
        val prev = lastHeardFrom.put(from, now)
        if (lowPower || carried.isEmpty() || from == nodeId) return
        val picks = if (fromLowPower) {
            if (now - (lastOfferTo[from] ?: 0L) < config.offerEveryMs) return
            stats.friendPolls++
            val since = (prev ?: 0L) - 1_000
            carried.values.filter { it.at >= since }
        } else {
            if (prev != null && now - prev < config.newNeighborGapMs) return
            if (now - lastNewcomerOffer < config.offerEveryMs) return
            lastNewcomerOffer = now
            carried.values.toList()
        }
        lastOfferTo[from] = now
        for (c in picks.takeLast(config.offerMax)) {
            if (queue.any { it.packet.key == c.packet.key }) continue
            val out = Out(c.packet, 1, now + config.relayBaseMs + random.nextLong(config.relayJitterMs * 2 + 1), 1, isRelay = true)
            out.firstRelay = from
            pendingRelay[c.packet.key] = out
            stats.carriedOffered++
            enqueue(out)
        }
    }
    val pendingUrgent get() = urgent.size

    companion object {
        val CARRY_TYPES = setOf(MeshType.SOS, MeshType.ACK, MeshType.RALLY, MeshType.PUBLIC_SOS, MeshType.RESPOND, MeshType.FOUND, MeshType.FOUND_ACK, MeshType.OFFICIAL)
    }

    private fun enqueue(o: Out) {
        queue += o
        while (queue.size > config.maxQueue) {
            val victim = queue.filter { it.isRelay }.maxByOrNull { it.priority } ?: queue.first()
            queue.remove(victim); pendingRelay.remove(victim.packet.key); stats.queueDropped++
        }
    }
}
