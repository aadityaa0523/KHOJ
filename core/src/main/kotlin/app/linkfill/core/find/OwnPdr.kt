package app.linkfill.core.find

import app.linkfill.core.estimate.GapEstimator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Weinberg stride estimation:
 * stride = K * (a_max - a_min)^(1/4)
 * clamped to realistic pedestrian bounds [0.4 m, 1.2 m].
 */
object Weinberg {
    const val DEFAULT_K = 0.43
    const val DEFAULT_STRIDE_M = 0.70

    fun strideLengthM(accelMax: Double, accelMin: Double, k: Double = DEFAULT_K): Double {
        val diff = (accelMax - accelMin).coerceAtLeast(0.0)
        val s = k * sqrt(sqrt(diff))
        return s.coerceIn(0.4, 1.2)
    }
}

/**
 * Own-phone Pedestrian Dead Reckoning (PDR) for short GPS gaps (up to [maxCoastMs] = 30 s).
 * Coasts the phone's own position between GNSS fixes using step integration and true heading.
 * Beyond 30 seconds without a fresh GNSS fix, coasting stops and returns null.
 */
class OwnPdrTracker(
    val defaultStrideM: Double = Weinberg.DEFAULT_STRIDE_M,
    val maxCoastMs: Long = 30_000L,
) {
    private var lastFix: LocFix? = null
    var eastM = 0.0
        private set
    var northM = 0.0
        private set
    var walkedDistM = 0.0
        private set
    var steps = 0
        private set

    fun onFix(fix: LocFix) {
        lastFix = fix
        eastM = 0.0
        northM = 0.0
        walkedDistM = 0.0
        steps = 0
    }

    fun onStep(headingTrueDeg: Double, strideM: Double = defaultStrideM) {
        val h = Angles.toRadians(headingTrueDeg)
        eastM += strideM * sin(h)
        northM += strideM * cos(h)
        walkedDistM += strideM
        steps++
    }

    /**
     * Estimated position at [now].
     * Returns null if no initial fix exists, or if [now] is more than [maxCoastMs] past [lastFix].
     */
    fun position(now: Long): LocFix? {
        val fix = lastFix ?: return null
        val dtMs = now - fix.atMs
        if (dtMs < 0 || dtMs > maxCoastMs) return null
        if (steps == 0) return LocFix(fix.lat, fix.lon, fix.accuracyM, now)
        val (lat, lon) = GapEstimator.offset(fix.lat, fix.lon, northM, eastM)
        val dtS = dtMs / 1000.0
        // PDR drift rate ~5% of walked distance plus 0.1 m/s process noise
        val drift = 0.05 * walkedDistM + 0.1 * dtS
        val accuracy = sqrt(fix.accuracyM * fix.accuracyM + drift * drift)
        return LocFix(lat, lon, accuracy, now)
    }

    fun reset() {
        lastFix = null
        eastM = 0.0
        northM = 0.0
        walkedDistM = 0.0
        steps = 0
    }
}
