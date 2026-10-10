package app.linkfill.core.estimate

import app.linkfill.core.protocol.LocationFix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** What the receiver should say about the sender's position right now. */
enum class LocationState { FRESH, ESTIMATED, STALE, UNAVAILABLE }

/** A position that was NOT measured: a dead-reckoning guess from the last verified fixes. */
data class Estimate(val latitude: Double, val longitude: Double, val radiusM: Double, val basedOnAgeMs: Long)

/**
 * Deterministic gap filling between verified updates (no machine learning): constant velocity from the
 * last fix's reported speed and bearing, or from the last two fixes, capped at a walking-crowd speed.
 * Uncertainty grows with time; once it passes [maxRadiusM] the estimate is withdrawn rather than shown.
 */
class GapEstimator(
    private val uncertaintyGrowthMps: Double = 1.5,
    private val maxSpeedMps: Double = 3.0,
    private val maxRadiusM: Double = 50.0,
    private val maxAgeMs: Long = 60_000,
) {
    fun estimate(last: LocationFix, previous: LocationFix?, ageMs: Long): Estimate? {
        if (ageMs < 0 || ageMs > maxAgeMs) return null
        val radius = last.accuracyM + uncertaintyGrowthMps * ageMs / 1000.0
        if (radius > maxRadiusM) return null
        val (vNorth, vEast) = velocity(last, previous)
        val t = ageMs / 1000.0
        val (lat, lon) = offset(last.latitude, last.longitude, vNorth * t, vEast * t)
        return Estimate(lat, lon, radius, ageMs)
    }

    private fun velocity(last: LocationFix, previous: LocationFix?): Pair<Double, Double> {
        var vn = 0.0
        var ve = 0.0
        if (!last.speedMps.isNaN() && !last.bearingDeg.isNaN()) {
            val b = Math.toRadians(last.bearingDeg.toDouble())
            vn = last.speedMps * cos(b); ve = last.speedMps * sin(b)
        } else if (previous != null) {
            val dt = (last.fixTimeMs - previous.fixTimeMs) / 1000.0
            if (dt in 0.5..20.0) {
                val (n, e) = displacement(previous, last)
                vn = n / dt; ve = e / dt
            }
        }
        val speed = hypot(vn, ve)
        if (speed > maxSpeedMps) { vn *= maxSpeedMps / speed; ve *= maxSpeedMps / speed }
        return vn to ve
    }

    companion object {
        private const val EARTH_M = 6_371_000.0

        /** North and east metres from a to b (equirectangular; fine over a few kilometres). */
        fun displacement(a: LocationFix, b: LocationFix): Pair<Double, Double> = displacement(a.latitude, a.longitude, b.latitude, b.longitude)

        fun displacement(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
            val north = Math.toRadians(lat2 - lat1) * EARTH_M
            val east = Math.toRadians(lon2 - lon1) * EARTH_M * cos(Math.toRadians((lat1 + lat2) / 2))
            return north to east
        }

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val (n, e) = displacement(lat1, lon1, lat2, lon2)
            return sqrt(n * n + e * e)
        }

        /** Compass bearing in degrees (0 = north) from point 1 to point 2. */
        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val (n, e) = displacement(lat1, lon1, lat2, lon2)
            return (Math.toDegrees(atan2(e, n)) + 360) % 360
        }

        fun offset(lat: Double, lon: Double, northM: Double, eastM: Double): Pair<Double, Double> =
            (lat + Math.toDegrees(northM / EARTH_M)) to (lon + Math.toDegrees(eastM / (EARTH_M * cos(Math.toRadians(lat)))))

        fun state(lastVerifiedAgeMs: Long?, estimateAvailable: Boolean, freshMs: Long = 5_000, maxStaleMs: Long = 10 * 60_000): LocationState = when {
            lastVerifiedAgeMs == null || lastVerifiedAgeMs > maxStaleMs -> LocationState.UNAVAILABLE
            lastVerifiedAgeMs <= freshMs -> LocationState.FRESH
            estimateAvailable -> LocationState.ESTIMATED
            else -> LocationState.STALE
        }
    }
}
