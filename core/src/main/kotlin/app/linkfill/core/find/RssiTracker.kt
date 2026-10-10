package app.linkfill.core.find

import kotlin.math.sqrt

enum class Trend { IMPROVING, WORSENING, STEADY, UNKNOWN }

/** Rough closeness bands from filtered RSSI. Deliberately not metres: RSSI to distance is too environment-dependent. */
enum class Proximity { VERY_CLOSE, NEAR, MEDIUM, FAR }

data class RssiSnapshot(
    val rawLast: Int?,
    val filtered: Double?,
    val stdDb: Double,
    val slopeDbPerS: Double,
    val trend: Trend,
    val proximity: Proximity?,
    val samples: Int,
    val ageMs: Long,
    /** 0..1, from sample count, recency and variability. Below ~0.35 the UI must not show confident guidance. */
    val confidence: Double,
)

/** Thresholds are defaults for 1M-PHY phone-to-phone advertising at +1 dBm; they are NOT calibrated, see FIND_MEMBER_TEST_PLAN. */
class ProximityBands(val veryCloseDbm: Double = -52.0, val nearDbm: Double = -64.0, val mediumDbm: Double = -78.0, val hysteresisDb: Double = 3.0)

/**
 * Smooths noisy RSSI the way the research recommends for BLE: a median filter over the last [medianWindow] raw samples
 * removes spikes (body shadowing, one lost channel), then an exponential moving average smooths what is left.
 * It also tracks the variability and a regression slope, and only calls a trend IMPROVING/WORSENING when the slope is
 * statistically distinguishable from noise, so random fluctuations are not turned into confident "warmer / colder".
 *
 * Time is passed in. Not thread-safe.
 */
class RssiTracker(
    private val medianWindow: Int = 5,
    private val alpha: Double = 0.3,
    private val historyMs: Long = 8_000,
    private val staleMs: Long = 4_000,
    /** A reading counts as fully fresh this long; frames arrive in bursts with 1-3 s gaps when the radio is shared (measured with Nearby running). */
    private val freshMs: Long = 2_000,
    private val bands: ProximityBands = ProximityBands(),
) {
    private class P(val t: Long, val raw: Int, val filtered: Double)

    private val recentRaw = ArrayDeque<Int>()
    private val points = ArrayDeque<P>()
    private var ema: Double? = null
    private var lastProximity: Proximity? = null

    fun reset() { recentRaw.clear(); points.clear(); ema = null; lastProximity = null }

    fun add(tMs: Long, rssi: Int) {
        if (rssi >= 0 || rssi < -127) return // not a real RSSI (0 and 127 are Android's "unavailable" values)
        recentRaw.addLast(rssi); while (recentRaw.size > medianWindow) recentRaw.removeFirst()
        val med = recentRaw.sorted().let { it[it.size / 2] }.toDouble()
        val e = ema?.let { it + alpha * (med - it) } ?: med
        ema = e
        points.addLast(P(tMs, rssi, e))
        while (points.isNotEmpty() && tMs - points.first().t > historyMs) points.removeFirst()
    }

    fun snapshot(now: Long): RssiSnapshot {
        val last = points.lastOrNull() ?: return RssiSnapshot(null, null, 0.0, 0.0, Trend.UNKNOWN, null, 0, Long.MAX_VALUE, 0.0)
        val age = now - last.t
        val raws = points.map { it.raw.toDouble() }
        val mean = raws.average()
        val std = if (raws.size > 1) sqrt(raws.sumOf { (it - mean) * (it - mean) } / (raws.size - 1)) else 0.0
        val (slope, slopeSe) = regression()
        val spanS = (points.last().t - points.first().t) / 1000.0
        val trend = when {
            age > staleMs || points.size < 6 || spanS < 3.0 -> Trend.UNKNOWN
            slopeSe > 0 && slope / slopeSe > 2.5 && slope > 0.4 -> Trend.IMPROVING
            slopeSe > 0 && slope / slopeSe < -2.5 && slope < -0.4 -> Trend.WORSENING
            else -> Trend.STEADY
        }
        val recency = (1.0 - (age - freshMs).toDouble() / (staleMs - freshMs)).coerceIn(0.0, 1.0)
        val count = (points.size / 8.0).coerceIn(0.0, 1.0)
        val calm = (1.0 - std / 10.0).coerceIn(0.0, 1.0)
        val confidence = recency * (0.4 + 0.3 * count + 0.3 * calm)
        val prox = if (age > staleMs) null else proximity(last.filtered)
        return RssiSnapshot(last.raw, last.filtered, std, slope, trend, prox, points.size, age, confidence)
    }

    /** Band with hysteresis so a reading hovering at a threshold does not flip every update. */
    private fun proximity(f: Double): Proximity {
        val h = bands.hysteresisDb
        val prev = lastProximity
        val raw = when {
            f >= bands.veryCloseDbm -> Proximity.VERY_CLOSE
            f >= bands.nearDbm -> Proximity.NEAR
            f >= bands.mediumDbm -> Proximity.MEDIUM
            else -> Proximity.FAR
        }
        val result = if (prev == null || prev == raw) raw else {
            // Only leave the previous band once we are clearly past its edge.
            val edgeBetter = when (prev) { Proximity.FAR -> bands.mediumDbm; Proximity.MEDIUM -> bands.nearDbm; Proximity.NEAR -> bands.veryCloseDbm; Proximity.VERY_CLOSE -> Double.POSITIVE_INFINITY }
            val edgeWorse = when (prev) { Proximity.VERY_CLOSE -> bands.veryCloseDbm; Proximity.NEAR -> bands.nearDbm; Proximity.MEDIUM -> bands.mediumDbm; Proximity.FAR -> Double.NEGATIVE_INFINITY }
            when {
                raw.ordinal < prev.ordinal && f >= edgeBetter + h -> raw
                raw.ordinal > prev.ordinal && f <= edgeWorse - h -> raw
                else -> prev
            }
        }
        lastProximity = result
        return result
    }

    /** Least-squares slope (dB per second) of the filtered values, and its standard error. */
    private fun regression(): Pair<Double, Double> {
        val n = points.size
        if (n < 4) return 0.0 to 0.0
        val t0 = points.first().t
        val xs = points.map { (it.t - t0) / 1000.0 }
        val ys = points.map { it.filtered }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx < 1e-9) return 0.0 to 0.0
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val resid = xs.indices.sumOf { val e = ys[it] - (my + slope * (xs[it] - mx)); e * e }
        // The EMA makes neighbouring points correlated, so the plain standard error is optimistic; widen it.
        val se = sqrt(resid / (n - 2) / sxx) * 2.0
        return slope to se
    }
}

/** Emits a haptic cue when the signal has consistently improved, with a minimum gap so it never buzzes continuously. */
class ImprovementCue(private val minGapMs: Long = 4_000, private val needConsecutive: Int = 3) {
    private var run = 0
    private var lastCue = Long.MIN_VALUE / 2

    fun reset() { run = 0 }

    /** Call once per evaluation. True when a cue should fire now. */
    fun update(trend: Trend, now: Long): Boolean {
        run = if (trend == Trend.IMPROVING) run + 1 else 0
        if (run >= needConsecutive && now - lastCue >= minGapMs) { lastCue = now; run = 0; return true }
        return false
    }
}
