package app.linkfill.core.sim

import app.linkfill.core.policy.DeterministicRecoveryPolicy
import app.linkfill.core.policy.FixedPolicy
import app.linkfill.core.policy.RecoveryAction
import app.linkfill.core.policy.RecoveryPolicy
import app.linkfill.core.protocol.Decoded
import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.protocol.MessageCodec
import app.linkfill.core.protocol.MessageType
import app.linkfill.core.reliability.ChannelConfig
import app.linkfill.core.reliability.ChannelListener
import app.linkfill.core.reliability.DecisionRecord
import app.linkfill.core.reliability.Delivery
import app.linkfill.core.reliability.ReliableChannel
import app.linkfill.core.security.PlainSealer
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.random.Random

/**
 * Two-state Gilbert-Elliott packet-loss model: a "good" and a "bad" state with their own loss
 * probabilities, switching per packet. It reproduces bursty loss, which independent random loss does not.
 * The parameters here are assumptions, not measurements of a real Bluetooth link.
 */
class GilbertElliott(
    private val pGoodToBad: Double,
    private val pBadToGood: Double,
    private val lossGood: Double,
    private val lossBad: Double,
    private val random: Random,
) {
    var bad = false
        private set

    fun lost(): Boolean {
        bad = if (bad) random.nextDouble() >= pBadToGood else random.nextDouble() < pGoodToBad
        return random.nextDouble() < if (bad) lossBad else lossGood
    }

    /** Long-run loss rate implied by the parameters. */
    val averageLoss: Double get() {
        val piBad = pGoodToBad / (pGoodToBad + pBadToGood)
        return piBad * lossBad + (1 - piBad) * lossGood
    }
}

data class Scenario(
    val name: String,
    val pGoodToBad: Double,
    val pBadToGood: Double,
    val lossGood: Double,
    val lossBad: Double,
    val baseDelayMs: Double,
    val jitterMeanMs: Double,
    /** Disconnects the app is told about (Nearby onDisconnected), per minute. */
    val disconnectsPerMin: Double,
) {
    val averageLoss get() = GilbertElliott(pGoodToBad, pBadToGood, lossGood, lossBad, Random(0)).averageLoss

    companion object {
        val TEST = listOf(
            Scenario("Clean link (1% random loss)", 0.01, 0.99, 0.01, 0.01, 30.0, 15.0, 0.0),
            Scenario("Bursty crowd (Gilbert-Elliott)", 0.05, 0.30, 0.02, 0.70, 40.0, 25.0, 0.0),
            Scenario("Harsh bursty crowd", 0.12, 0.25, 0.05, 0.85, 60.0, 40.0, 0.0),
            Scenario("Bursty + disconnects (1/min)", 0.05, 0.30, 0.02, 0.70, 40.0, 25.0, 1.0),
        )

        /** Random scenario for training; ranges cover and exceed the test scenarios. */
        fun random(r: Random) = Scenario(
            "train",
            pGoodToBad = r.nextDouble(0.005, 0.2),
            pBadToGood = r.nextDouble(0.1, 0.7),
            lossGood = r.nextDouble(0.0, 0.06),
            lossBad = r.nextDouble(0.3, 0.95),
            baseDelayMs = r.nextDouble(15.0, 90.0),
            jitterMeanMs = r.nextDouble(5.0, 50.0),
            disconnectsPerMin = if (r.nextDouble() < 0.3) r.nextDouble(0.2, 1.5) else 0.0,
        )
    }
}

enum class Strategy(val label: String) {
    BASIC("1. Basic (no retry)"),
    RETRY("2. Bounded retry + backoff"),
    RETRY_REDUNDANCY("3. Retry + redundancy"),
    RULES("4a. Deterministic rules"),
    ML("4b. ML-assisted policy"),
}

class RunResult(
    val generated: Int,
    val deliveredInTime: Int,
    val latenciesMs: List<Long>,
    val retransmissions: Int,
    val bytes: Long,
    val firstLost: Int,
    val recovered: Int,
    val stalenessSamplesMs: List<Long>,
    val decisions: Int,
    val mlRejected: Int,
    val inferenceNanosTotal: Long,
    val inferenceCount: Int,
)

/**
 * Discrete-time simulation (10 ms steps) of two phones running the real [ReliableChannel] code.
 * The sender produces one location fix per second; the link loses and delays packets.
 */
class LinkSimulator(private val scenario: Scenario, private val seed: Long, private val durationMs: Long = 300_000) {
    private val epoch = 1_700_000_000_000L
    private var t = 0L
    private var connected = true

    private data class Event(val at: Long, val order: Long, val toReceiver: Boolean, val bytes: ByteArray)

    fun run(
        policy: RecoveryPolicy,
        config: ChannelConfig,
        decisionSink: ((DecisionRecord) -> Unit)? = null,
        arrivalsOut: MutableMap<Long, Long>? = null,
    ): RunResult {
        val rng = Random(seed)
        val geToReceiver = GilbertElliott(scenario.pGoodToBad, scenario.pBadToGood, scenario.lossGood, scenario.lossBad, Random(seed * 31 + 1))
        val geToSender = GilbertElliott(scenario.pGoodToBad, scenario.pBadToGood, scenario.lossGood, scenario.lossBad, Random(seed * 31 + 2))
        val events = PriorityQueue<Event>(compareBy<Event> { it.at }.thenBy { it.order })
        var order = 0L
        val arrivals = arrivalsOut ?: HashMap()   // fixTime -> first arrival time (sim ms)
        val firstLostFixes = HashSet<Long>()
        val seqToFix = HashMap<Int, Long>()
        var latestFixAtReceiver = Long.MIN_VALUE

        fun delay(): Long = (scenario.baseDelayMs - scenario.jitterMeanMs * ln(1 - rng.nextDouble())).toLong()

        lateinit var sender: ReliableChannel
        lateinit var receiver: ReliableChannel

        fun link(toReceiver: Boolean, bytes: ByteArray) {
            if (!connected) return
            if (toReceiver) {
                val d = MessageCodec.decode(bytes, null)
                if (d is Decoded.Ok && d.envelope.type == MessageType.LOCATION_UPDATE && !d.envelope.isRetransmission) {
                    val lost = geToReceiver.lost()
                    if (lost) seqToFix[d.envelope.seq]?.let(firstLostFixes::add)
                    if (lost) return
                } else if (geToReceiver.lost()) return
            } else if (geToSender.lost()) return
            events.add(Event(t + delay(), order++, toReceiver, bytes))
        }

        val listener = object : ChannelListener {
            override fun onLocation(seq: Int, fix: LocationFix, delivery: Delivery) {
                if (fix.fixTimeMs !in arrivals) arrivals[fix.fixTimeMs] = t
                if (fix.fixTimeMs > latestFixAtReceiver) latestFixAtReceiver = fix.fixTimeMs
            }
        }
        sender = ReliableChannel(7L, 1, 2, PlainSealer, { link(true, it) }, policy, Random(seed * 17 + 3), object : ChannelListener {}, config = config)
        receiver = ReliableChannel(7L, 2, 1, PlainSealer, { link(false, it) }, FixedPolicy(RecoveryAction.WAIT_RETRY, "n/a"), Random(seed * 17 + 4), listener, config = config)
        sender.decisionObserver = decisionSink

        var reconnectAt = 0L
        val staleness = ArrayList<Long>()
        var generated = 0
        val fixTimes = ArrayList<Long>()
        var lat = 17.385; var lon = 78.4867
        t = 0
        connected = true
        while (t <= durationMs) {
            while (events.isNotEmpty() && events.peek().at <= t) {
                val e = events.poll()
                if (!connected) continue
                if (e.toReceiver) receiver.receive(e.bytes, epoch + t) else sender.receive(e.bytes, epoch + t)
            }
            // Known disconnects: the app is told, stops sending, and resumes later.
            if (connected && scenario.disconnectsPerMin > 0 && rng.nextDouble() < scenario.disconnectsPerMin / 6000.0) {
                connected = false
                reconnectAt = t + rng.nextLong(2_000, 8_000)
                events.clear()
                sender.setConnected(false, epoch + t); receiver.setConnected(false, epoch + t)
            } else if (!connected && t >= reconnectAt) {
                connected = true
                sender.setConnected(true, epoch + t); receiver.setConnected(true, epoch + t)
            }
            if (t % 1000 == 0L && t < durationMs) {
                lat += rng.nextDouble(-0.00001, 0.00001); lon += rng.nextDouble(-0.00001, 0.00001)
                val fixTime = epoch + t
                val fix = LocationFix(lat, lon, 8f, 1.2f, 90f, fixTime)
                generated++
                fixTimes += fixTime
                if (connected) {
                    seqToFix[lastSeqPlusOne(sender)] = fixTime
                    sender.sendLocation(fix, epoch + t)
                }
            }
            sender.tick(epoch + t)
            receiver.tick(epoch + t)
            if (t % 100 == 0L && latestFixAtReceiver != Long.MIN_VALUE) staleness += epoch + t - latestFixAtReceiver
            t += 10
        }

        val ttl = config.locationTtlMs
        val latencies = fixTimes.mapNotNull { ft -> arrivals[ft]?.let { epoch + it - ft }?.takeIf { it <= ttl } }
        val recovered = firstLostFixes.count { ft -> arrivals[ft]?.let { epoch + it - ft <= ttl } == true }
        val s = sender.stats
        return RunResult(
            generated = generated,
            deliveredInTime = latencies.size,
            latenciesMs = latencies,
            retransmissions = s.retransmissions,
            bytes = s.bytesSent + receiver.stats.bytesSent,
            firstLost = firstLostFixes.size,
            recovered = recovered,
            stalenessSamplesMs = staleness,
            decisions = s.decisions.values.sum(),
            mlRejected = s.mlRejectedToFallback,
            inferenceNanosTotal = s.inferenceNanosTotal,
            inferenceCount = s.inferenceCount,
        )
    }

    // Reliable seqs are consecutive and only locations are sent here, so the next one is messagesSent + 1.
    private fun lastSeqPlusOne(c: ReliableChannel) = c.stats.messagesSent + 1

    companion object {
        fun configFor(s: Strategy) = when (s) {
            Strategy.BASIC -> ChannelConfig(retransmissionsEnabled = false)
            Strategy.RETRY_REDUNDANCY -> ChannelConfig(alwaysRedundant = true)
            else -> ChannelConfig()
        }

        fun policyFor(s: Strategy, ml: RecoveryPolicy?): RecoveryPolicy = when (s) {
            Strategy.BASIC, Strategy.RETRY, Strategy.RETRY_REDUNDANCY -> FixedPolicy(RecoveryAction.WAIT_RETRY, "Bounded retry")
            Strategy.RULES -> DeterministicRecoveryPolicy()
            Strategy.ML -> ml ?: error("ML policy not available")
        }
    }
}
