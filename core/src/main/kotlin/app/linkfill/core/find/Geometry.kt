package app.linkfill.core.find

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Angle helpers. All angles in degrees; compass bearings are clockwise from north. */
object Angles {
    fun normalize360(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    /** Maps to (-180, 180]. Positive means "turn right (clockwise)". */
    fun normalize180(d: Double): Double {
        val n = normalize360(d)
        return if (n > 180.0) n - 360.0 else n
    }

    /**
     * Where the target is relative to where the phone points: 0 = straight ahead, +90 = to the right,
     * -90 = to the left, 180 = behind. [bearingTrueDeg] and [headingTrueDeg] must both be referenced to
     * TRUE north; mixing a magnetic heading with a true bearing gives the wrong arrow wherever declination is not 0.
     */
    fun relative(bearingTrueDeg: Double, headingTrueDeg: Double): Double = normalize180(bearingTrueDeg - headingTrueDeg)

    /** true = magnetic + declination (declination is positive east of true north). */
    fun magneticToTrue(magneticDeg: Double, declinationDeg: Double): Double = normalize360(magneticDeg + declinationDeg)

    /** Circular difference a - b in (-180, 180]. */
    fun diff(a: Double, b: Double): Double = normalize180(a - b)

    fun toRadians(d: Double) = d * PI / 180.0
    fun toDegrees(r: Double) = r * 180.0 / PI
}

/** A position fix with the metadata needed to judge how far it can be trusted. */
data class LocFix(val lat: Double, val lon: Double, val accuracyM: Double, val atMs: Long)

object Bearing {
    /** Distance between two fixes in metres (equirectangular, fine for the few-kilometre range of a crowd). */
    fun distanceM(a: LocFix, b: LocFix): Double = app.linkfill.core.estimate.GapEstimator.distanceM(a.lat, a.lon, b.lat, b.lon)

    /** True-north bearing from [from] to [to], in degrees clockwise. */
    fun trueBearing(from: LocFix, to: LocFix): Double = app.linkfill.core.estimate.GapEstimator.bearingDeg(from.lat, from.lon, to.lat, to.lon)

    /**
     * Angular uncertainty of a GPS-derived bearing. Each fix is only known to within its reported accuracy radius
     * (Android's `accuracy` is a 68% radius), so the combined position error is sqrt(a² + b²), and the bearing can be
     * off by about asin(error / distance). Inside the error radius the bearing is meaningless (180°).
     */
    fun uncertaintyDeg(distanceM: Double, accuracyAM: Double, accuracyBM: Double): Double {
        val err = sqrt(accuracyAM * accuracyAM + accuracyBM * accuracyBM)
        if (distanceM <= err) return 180.0
        return Angles.toDegrees(asin((err / distanceM).coerceIn(0.0, 1.0)))
    }
}

/**
 * Circular exponential smoothing for an on-screen arrow. Uses a time constant instead of a fixed alpha so the
 * smoothing does not depend on how often measurements arrive, and unwraps across ±180° so a target passing
 * "behind" the user does not spin the arrow the long way round.
 */
class AngleSmoother(private val timeConstantMs: Double = 350.0) {
    private var value: Double? = null
    private var lastMs = 0L

    fun reset() { value = null }

    /** Returns the smoothed angle in (-180, 180], or null when no data has been added since reset. */
    fun update(angleDeg: Double, tMs: Long): Double {
        val v = value
        if (v == null) { value = angleDeg; lastMs = tMs; return angleDeg }
        val dt = (tMs - lastMs).coerceAtLeast(0).toDouble()
        lastMs = tMs
        val alpha = 1.0 - Math.exp(-dt / timeConstantMs)
        val next = Angles.normalize180(v + alpha * Angles.diff(angleDeg, v))
        value = next
        return next
    }

    /** Gyro-style compensation: the phone turned by [deltaDeg], so every stored relative angle shifts the opposite way. */
    fun rotateBy(deltaDeg: Double) { value = value?.let { Angles.normalize180(it - deltaDeg) } }
}

/** Heading of the way the phone points, computed from the rotation-vector sensor, for any holding posture. */
data class HeadingSolution(val magneticDeg: Double, val tiltDeg: Double, val posture: Posture)

enum class Posture { FLAT, UPRIGHT }

object Orientation {
    /**
     * Compass heading from an Android rotation-vector sample ([rv] = x, y, z[, w] as delivered by `TYPE_ROTATION_VECTOR`,
     * referenced to MAGNETIC north). [displayRotation] is `Surface.ROTATION_*` as 0, 1, 2, 3.
     *
     * `SensorManager.getOrientation` azimuth is wrong when the phone is held upright, which is how people hold a phone to
     * walk towards something. So the "pointing direction" depends on posture:
     * - FLAT (screen up, within 40° of horizontal): the top edge of the screen, which depends on display rotation.
     * - UPRIGHT: the direction the back camera faces (the user faces the same way).
     * Returns null for a degenerate (zero) quaternion.
     */
    fun heading(rv: FloatArray, displayRotation: Int): HeadingSolution? {
        if (rv.size < 3) return null
        val x = rv[0].toDouble(); val y = rv[1].toDouble(); val z = rv[2].toDouble()
        val w = if (rv.size >= 4 && rv[3] != 0f) rv[3].toDouble() else sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-6) return null
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        // Rotation matrix mapping device axes to world axes (X east, Y north, Z up); same as SensorManager.getRotationMatrixFromVector.
        val r00 = 1 - 2 * (qy * qy + qz * qz); val r01 = 2 * (qx * qy - qz * qw); val r02 = 2 * (qx * qz + qy * qw)
        val r10 = 2 * (qx * qy + qz * qw); val r11 = 1 - 2 * (qx * qx + qz * qz); val r12 = 2 * (qy * qz - qx * qw)
        val r22 = 1 - 2 * (qx * qx + qy * qy)
        val flat = abs(r22) >= 0.766 // cos 40°
        val (east, north) = if (flat) {
            // Device-frame direction of the top of the screen for each display rotation.
            val (ux, uy) = when (displayRotation and 3) { 0 -> 0.0 to 1.0; 1 -> -1.0 to 0.0; 2 -> 0.0 to -1.0; else -> 1.0 to 0.0 }
            (r00 * ux + r01 * uy) to (r10 * ux + r11 * uy)
        } else {
            -r02 to -(r12) // back-camera direction: world components of the device's -Z axis
        }
        if (abs(east) < 1e-9 && abs(north) < 1e-9) return null
        return HeadingSolution(Angles.normalize360(Angles.toDegrees(atan2(east, north))), Angles.toDegrees(kotlin.math.acos(abs(r22).coerceIn(0.0, 1.0))), if (flat) Posture.FLAT else Posture.UPRIGHT)
    }
}
