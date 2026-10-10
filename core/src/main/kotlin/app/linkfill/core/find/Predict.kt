package app.linkfill.core.find

import app.linkfill.core.estimate.GapEstimator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Activity / cadence classification to replace the fixed 1.8 m/s speed limit.
 */
enum class MotionClass(val maxSpeedMps: Double, val sigmaV: Double, val sigmaThetaRad: Double) {
    STILL(0.3, 0.1, 0.1),
    WALKING(1.8, 0.4, 0.35),
    RUNNING(4.5, 0.8, 0.5);

    companion object {
        fun fromCadence(stepsPerSec: Double): MotionClass = when {
            stepsPerSec < 0.5 -> STILL
            stepsPerSec <= 2.2 -> WALKING
            else -> RUNNING
        }
    }
}

/**
 * Where someone probably is now, when their positions stopped arriving (out of range, or packets missed):
 * recent walking velocity carried forward from the last fix for up to [COAST_S] (people stop and turn).
 *
 * Age-aware uncertainty grows following the research model (Research A section F):
 *   sigma^2(t) = sigma^2_GNSS + t^2 * sigma^2_v + (v * t)^2 * sigma^2_theta + q_motion * t
 *
 * When the uncertainty radius exceeds [MAX_UNCERTAINTY_M] (30 m), prediction stops and returns null,
 * prompting the UI to fall back to BLE proximity / turn modes or the searching dial.
 */
object Predict {
    const val MAX_SPEED_MPS = 1.8
    const val MAX_UNCERTAINTY_M = 30.0
    const val COAST_S = 30.0
    /** Fixes older than this before the newest one do not inform the velocity. */
    const val TRACK_S = 60.0
    /** Process noise rate for unobserved turns and stops (m^2 / s). */
    const val Q_MOTION = 0.5

    fun position(
        track: List<LocFix>,
        now: Long,
        motionClass: MotionClass = MotionClass.WALKING,
        maxUncertaintyM: Double = MAX_UNCERTAINTY_M,
    ): LocFix? {
        val last = track.maxByOrNull { it.atMs } ?: return null
        val dt = ((now - last.atMs) / 1000.0).coerceAtLeast(0.0)
        // Velocity from the oldest fix in the window that is at least 5 s older than the newest.
        val first = track.filter { (last.atMs - it.atMs) / 1000.0 in 5.0..TRACK_S }.minByOrNull { it.atMs }
        var vE = 0.0; var vN = 0.0; var speed = 0.0
        if (first != null) {
            val span = (last.atMs - first.atMs) / 1000.0
            val d = GapEstimator.distanceM(first.lat, first.lon, last.lat, last.lon)
            // A move smaller than the two fixes' accuracy is GPS noise, not walking.
            if (d > first.accuracyM + last.accuracyM) {
                val b = Math.toRadians(GapEstimator.bearingDeg(first.lat, first.lon, last.lat, last.lon))
                speed = (d / span).coerceAtMost(motionClass.maxSpeedMps)
                vE = speed * sin(b); vN = speed * cos(b)
            }
        }
        val coast = dt.coerceAtMost(COAST_S)
        val (lat, lon) = if (vE == 0.0 && vN == 0.0) last.lat to last.lon else GapEstimator.offset(last.lat, last.lon, vN * coast, vE * coast)

        // Age-aware uncertainty formula from Research A section F:
        // sigma^2(t) = sigma^2_GNSS + t^2 * sigma^2_v + (v * t)^2 * sigma^2_theta + q_motion * t
        val sigma = uncertaintyRadius(last.accuracyM, dt, speed, motionClass)

        // Beyond 30 m uncertainty, stop using the predicted position
        if (sigma > maxUncertaintyM) return null
        return LocFix(lat, lon, sigma, now)
    }

    /** Analytical uncertainty radius without threshold cutoff. */
    fun uncertaintyRadius(
        accuracyM: Double,
        dt: Double,
        speed: Double = 0.0,
        motionClass: MotionClass = MotionClass.WALKING,
    ): Double {
        val sigmaGnss2 = accuracyM * accuracyM
        val t2 = dt * dt
        val sigmaV2 = motionClass.sigmaV * motionClass.sigmaV
        val vt = speed * dt
        val sigmaTheta2 = motionClass.sigmaThetaRad * motionClass.sigmaThetaRad
        val sigma2 = sigmaGnss2 + t2 * sigmaV2 + (vt * vt) * sigmaTheta2 + Q_MOTION * dt
        return sqrt(sigma2)
    }
}
