package app.linkfill.core.mesh

import kotlin.math.sqrt

/**
 * Per-neighbour link quality from the frames we actually receive. Every node transmits one frame per
 * slot with an increasing link sequence number, so missing numbers give the packet delivery ratio (PDR)
 * directly. Signal strength (RSSI) only exists for frames that arrived, so it is biased near the edge of
 * range; that is why PDR history is tracked alongside it.
 */
class LinkTracker(private val slotMs: Long = SLOT_MS, private val historyMs: Long = 12_000) {

    class Rx(val seq: Int, val timeMs: Long, val rssi: Int, val coded: Boolean)

    class Neighbor(val id: Int) {
        val frames = ArrayDeque<Rx>()
        var lastSeq = -1
        var lastRxMs = 0L
        var lastRssi = 0
        var framesTotal = 0
        var duplicates = 0
    }

    val neighbors = LinkedHashMap<Int, Neighbor>()

    /** Records a frame. Returns false if this exact frame was already seen (repeated advertisement). */
    fun onFrame(relayId: Int, linkSeq: Int, rssi: Int, coded: Boolean, now: Long): Boolean {
        val n = neighbors.getOrPut(relayId) { Neighbor(relayId) }
        if (n.frames.any { it.seq == linkSeq && now - it.timeMs < 2_000 }) { n.duplicates++; return false }
        n.frames.addLast(Rx(linkSeq, now, rssi, coded))
        n.lastSeq = linkSeq; n.lastRxMs = now; n.lastRssi = rssi; n.framesTotal++
        while (n.frames.isNotEmpty() && now - n.frames.first().timeMs > historyMs) n.frames.removeFirst()
        return true
    }

    fun prune(now: Long, goneMs: Long = 30_000) { neighbors.values.removeAll { now - it.lastRxMs > goneMs } }

    /** Number of distinct nodes heard directly over Bluetooth in the last 5 seconds (crowd density estimate). */
    fun density(now: Long): Int = neighbors.values.count { now - it.lastRxMs <= 5_000 }

    fun pdr(id: Int, windowMs: Long, now: Long): Double {
        val n = neighbors[id] ?: return 0.0
        val got = n.frames.count { now - it.timeMs <= windowMs }
        return (got.toDouble() / (windowMs.toDouble() / slotMs)).coerceIn(0.0, 1.0)
    }

    /** Feature vector for the loss predictor, in the order of [LinkFeatures.ALL]. */
    fun features(id: Int, now: Long, queueDepth: Int, codedMode: Boolean, distanceM: Double = Double.NaN): DoubleArray {
        val n = neighbors[id]
        val recent = n?.frames?.filter { now - it.timeMs <= 2_000 }.orEmpty()
        val older = n?.frames?.filter { now - it.timeMs <= 10_000 }.orEmpty()
        val rssi2 = recent.map { it.rssi.toDouble() }
        val rssi10 = older.map { it.rssi.toDouble() }
        val med2 = median(rssi2) ?: median(rssi10) ?: FLOOR
        val med10 = median(rssi10) ?: FLOOR
        val since = if (n == null || n.lastRxMs == 0L) 10_000.0 else (now - n.lastRxMs).toDouble().coerceAtMost(10_000.0)
        return doubleArrayOf(
            if (distanceM.isNaN()) 0.0 else distanceM,
            med2,
            rssi2.minOrNull() ?: rssi10.minOrNull() ?: FLOOR,
            std(rssi10),
            slope(older.map { (it.timeMs - now) / 1000.0 to it.rssi.toDouble() }),
            med2 - med10,
            pdr(id, 500, now),
            pdr(id, 2_000, now),
            pdr(id, 10_000, now),
            since / slotMs,
            since / 1000.0,
            if (codedMode) 1.0 else 0.0,
            queueDepth.toDouble(),
        )
    }

    companion object {
        const val SLOT_MS = 1_000L
        private const val FLOOR = -105.0

        private fun median(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted().let { it[it.size / 2] }

        private fun std(v: List<Double>): Double {
            if (v.size < 2) return 0.0
            val m = v.average()
            return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
        }

        /** Least-squares slope of RSSI against time, in dB per second. */
        private fun slope(points: List<Pair<Double, Double>>): Double {
            if (points.size < 3) return 0.0
            val mx = points.sumOf { it.first } / points.size
            val my = points.sumOf { it.second } / points.size
            val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
            if (sxx < 1e-9) return 0.0
            return points.sumOf { (it.first - mx) * (it.second - my) } / sxx
        }
    }
}

/** Names and ablation subsets, following the experiment design in the project's research brief. */
object LinkFeatures {
    val ALL = listOf(
        "distance_m", "rssi_median_2s", "rssi_min_2s", "rssi_std_10s", "rssi_slope_dbps", "rssi_drop",
        "pdr_0_5s", "pdr_2s", "pdr_10s", "consecutive_misses", "since_last_s", "coded_phy", "queue_depth",
    )

    /** Ablation variants: which columns of [ALL] each model may use. */
    val SETS = linkedMapOf(
        "distance only" to listOf(0),
        "RSSI only" to listOf(1, 2, 3, 4, 5),
        "PDR history only" to listOf(6, 7, 8, 9, 10),
        "RSSI + PDR" to listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        "full (RSSI + PDR + protocol + host)" to listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
    )
    const val DEPLOYED = "full (RSSI + PDR + protocol + host)"
}
