package app.linkfill.core.find

import kotlin.math.floor

/**
 * Direction from Bluetooth signal strength while the user turns on the spot ("body-shadow" direction finding).
 * With the phone held at the chest, the body blocks 2.4 GHz signal from behind, so the received signal is strongest
 * when the user faces the other phone. Each Bluetooth reading is filed under the compass heading it was taken at; once
 * most of a full turn is covered and one direction is clearly stronger than the rest, that direction is the answer.
 *
 * It refuses rather than guesses: partial turns, a flat signal (very close, or reflections everywhere) and heading
 * gaps give no direction. The 6 dB contrast threshold and the uncertainty mapping are uncalibrated defaults
 * (docs/FIND_MEMBER_TEST_PLAN.md S18). Time is passed in; not thread-safe.
 */
class SpinFinder(private val windowMs: Long = 25_000, private val bins: Int = 12, private val minContrastDb: Double = 6.0) {
    private class S(val t: Long, val headingDeg: Double, val rssi: Int)
    private val samples = ArrayDeque<S>()

    data class Result(val bearingTrueDeg: Double?, val uncertaintyDeg: Double?, val coverage: Double, val contrastDb: Double)

    fun reset() = samples.clear()

    fun add(tMs: Long, headingTrueDeg: Double, rssi: Int) {
        if (rssi >= 0 || rssi < -127 || headingTrueDeg.isNaN()) return
        samples.addLast(S(tMs, Angles.normalize360(headingTrueDeg), rssi))
        while (samples.isNotEmpty() && tMs - samples.first().t > windowMs) samples.removeFirst()
    }

    fun solve(now: Long): Result {
        while (samples.isNotEmpty() && now - samples.first().t > windowMs) samples.removeFirst()
        val width = 360.0 / bins
        val byBin = samples.groupBy { floor(it.headingDeg / width).toInt() % bins }
        val med = DoubleArray(bins) { Double.NaN }
        for ((b, list) in byBin) if (list.size >= 2) med[b] = list.map { it.rssi }.sorted()[list.size / 2].toDouble()
        val filled = med.indices.filter { !med[it].isNaN() }
        val coverage = filled.size.toDouble() / bins
        if (coverage < 0.75) return Result(null, null, coverage, 0.0)
        // Smooth with filled circular neighbours so one lucky reading cannot win on its own.
        val smooth = DoubleArray(bins) { i ->
            if (med[i].isNaN()) Double.NaN else {
                val parts = listOf(med[(i + bins - 1) % bins] to 0.25, med[i] to 0.5, med[(i + 1) % bins] to 0.25).filter { !it.first.isNaN() }
                parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
            }
        }
        // Front-to-back: the strongest direction against the weakest, which is what the body's shadow produces.
        val contrast = filled.maxOf { smooth[it] } - filled.minOf { smooth[it] }
        if (contrast < minContrastDb) return Result(null, null, coverage, contrast)
        // Weighted circular mean over every measured direction: the whole shadow curve, not one noisy top bin.
        val floor = filled.minOf { smooth[it] }
        var sx = 0.0; var sy = 0.0
        for (i in filled) { val w = (smooth[i] - floor).let { it * it }; val a = Math.toRadians((i + 0.5) * width); sx += w * kotlin.math.sin(a); sy += w * kotlin.math.cos(a) }
        val bearing = Angles.normalize360(Math.toDegrees(kotlin.math.atan2(sx, sy)))
        val unc = (75.0 - 4.0 * contrast).coerceIn(25.0, 60.0) // stronger contrast, sharper answer
        return Result(bearing, unc, coverage, contrast)
    }
}
