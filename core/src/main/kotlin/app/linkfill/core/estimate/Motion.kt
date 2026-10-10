package app.linkfill.core.estimate

import kotlin.math.sqrt

private const val G = 9.81

/**
 * Classic three-stage fall detector on accelerometer magnitude: a short free fall (well under 1 g),
 * then an impact (over ~2.2 g) within 0.8 s, then 2 s with little movement (lying still). Each stage alone
 * happens in daily life; the sequence rarely does. A detection only starts a cancellable countdown.
 */
class FallDetector(
    private val freeFallG: Double = 0.45,
    private val freeFallMinMs: Long = 60,
    private val impactG: Double = 2.2,
    private val impactWindowMs: Long = 800,
    private val stillMs: Long = 2_000,
    private val stillStdMs2: Double = 1.2,
) {
    private enum class Stage { IDLE, FREE_FALL, AWAIT_IMPACT, AWAIT_STILL }
    private var stage = Stage.IDLE
    private var stageStart = 0L
    private val still = ArrayList<Double>()

    /** Feed one sample (m/s^2). Returns true once when a fall is detected. */
    fun add(tMs: Long, ax: Double, ay: Double, az: Double): Boolean {
        val g = sqrt(ax * ax + ay * ay + az * az) / G
        when (stage) {
            Stage.IDLE -> if (g < freeFallG) { stage = Stage.FREE_FALL; stageStart = tMs }
            Stage.FREE_FALL -> when {
                g < freeFallG -> if (tMs - stageStart >= freeFallMinMs) { stage = Stage.AWAIT_IMPACT; stageStart = tMs }
                else -> stage = Stage.IDLE
            }
            Stage.AWAIT_IMPACT -> when {
                g > impactG -> { stage = Stage.AWAIT_STILL; stageStart = tMs; still.clear() }
                tMs - stageStart > impactWindowMs -> stage = Stage.IDLE
            }
            Stage.AWAIT_STILL -> {
                if (tMs - stageStart < 300) return false // let the impact ring down
                still += g * G
                if (tMs - stageStart >= 300 + stillMs) {
                    val m = still.average()
                    val sd = sqrt(still.sumOf { (it - m) * (it - m) } / still.size)
                    stage = Stage.IDLE
                    return sd < stillStdMs2
                }
            }
        }
        return false
    }
}

/**
 * Panic gesture: shake the pendant hard [peaks] times within [windowMs]. Peaks must be at least 150 ms
 * apart and above [peakG] (well above walking, which stays under ~1.6 g on the chest). Works with the
 * screen off and needs no button, because Android does not let apps read volume keys in the background.
 */
class ShakeDetector(private val peakG: Double = 2.5, private val peaks: Int = 4, private val windowMs: Long = 2_000) {
    private val times = ArrayDeque<Long>()
    private var above = false

    fun add(tMs: Long, ax: Double, ay: Double, az: Double): Boolean {
        val g = sqrt(ax * ax + ay * ay + az * az) / G
        if (g > peakG && !above) {
            above = true
            if (times.isEmpty() || tMs - times.last() >= 150) times.addLast(tMs)
        } else if (g < peakG * 0.7) above = false
        while (times.isNotEmpty() && tMs - times.first() > windowMs) times.removeFirst()
        if (times.size >= peaks) { times.clear(); return true }
        return false
    }
}
