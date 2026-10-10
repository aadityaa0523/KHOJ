package app.linkfill.core.find

import app.linkfill.core.estimate.GapEstimator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where someone probably is now, when their positions stopped arriving (out of range, or packets missed): their recent
 * walking velocity carried forward from the last fix, capped at a brisk crowd walk and only for [COAST_S] (people stop
 * and turn), with the uncertainty growing at [GROWTH_MPS] for every second without news. This is a motion filter, not a
 * learned model: a learned one needs recorded walks to train on and can replace this behind the same function.
 */
object Predict {
    const val MAX_SPEED_MPS = 1.8
    const val GROWTH_MPS = 1.0
    const val COAST_S = 30.0
    /** Fixes older than this before the newest one do not inform the velocity. */
    const val TRACK_S = 60.0

    fun position(track: List<LocFix>, now: Long): LocFix? {
        val last = track.maxByOrNull { it.atMs } ?: return null
        val dt = ((now - last.atMs) / 1000.0).coerceAtLeast(0.0)
        // Velocity from the oldest fix in the window that is at least 5 s older than the newest.
        val first = track.filter { (last.atMs - it.atMs) / 1000.0 in 5.0..TRACK_S }.minByOrNull { it.atMs }
        var vE = 0.0; var vN = 0.0
        if (first != null) {
            val span = (last.atMs - first.atMs) / 1000.0
            val d = GapEstimator.distanceM(first.lat, first.lon, last.lat, last.lon)
            // A move smaller than the two fixes' accuracy is GPS noise, not walking.
            if (d > first.accuracyM + last.accuracyM) {
                val b = Math.toRadians(GapEstimator.bearingDeg(first.lat, first.lon, last.lat, last.lon))
                val speed = (d / span).coerceAtMost(MAX_SPEED_MPS)
                vE = speed * sin(b); vN = speed * cos(b)
            }
        }
        val coast = dt.coerceAtMost(COAST_S)
        val (lat, lon) = if (vE == 0.0 && vN == 0.0) last.lat to last.lon else GapEstimator.offset(last.lat, last.lon, vN * coast, vE * coast)
        // Unknown turns and stops: the circle grows at walking pace for the whole gap.
        return LocFix(lat, lon, last.accuracyM + GROWTH_MPS * dt + hypot(vE, vN) * (dt - coast).coerceAtLeast(0.0), now)
    }
}
