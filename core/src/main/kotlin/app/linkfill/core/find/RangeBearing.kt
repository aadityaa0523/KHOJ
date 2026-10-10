package app.linkfill.core.find

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Direction from walking": a distance-only ranging technology (Bluetooth Channel Sounding) cannot say which way the other
 * phone is, but distances measured from several different places can. If I measure 12 m here, 10 m after walking east, and
 * 11 m after turning north, there is essentially one spot that fits all three (the same idea as GPS trilateration, with my
 * own walking path playing the part of the satellites).
 *
 * Pieces: [StepDetector] finds steps in the accelerometer; [StepIntegrator] turns steps plus heading into a path in a local
 * east/north frame; [RangeBearingEstimator] fits the target position to the (path point, distance) pairs and says how much to
 * trust it.
 *
 * Assumptions, stated so nothing is oversold:
 * - the target stays roughly still over the window (about 20 s); a moving target shows up as a bad fit and the arrow is withheld;
 * - step length is a default (0.7 m), not calibrated, so path scale errors of 10-20% are expected;
 * - heading must be the walking direction (phone in hand or on the chest, not in a pocket);
 * - the distance noise [sigmaRangeM] is an ASSUMED value for the uncertainty maths, to be replaced by measured Channel Sounding error.
 */

/**
 * Walking steps from accelerometer magnitude (m/s²): a peak in the movement left after removing gravity, above [threshold],
 * at least [minGapMs] after the previous step. Deliberately simple; needs field validation per posture (hand, chest, pocket).
 */
class StepDetector(private val minGapMs: Long = 280, private val threshold: Double = 1.5) {
    private var gravity = 9.81
    private var prev = 0.0
    private var prev2 = 0.0
    private var prevT = 0L
    private var lastStep = Long.MIN_VALUE / 2

    /** Returns true when a step is detected (reported one sample late, at the sample after the peak). */
    fun add(tMs: Long, ax: Double, ay: Double, az: Double): Boolean {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        gravity += 0.02 * (mag - gravity)
        val dyn = mag - gravity
        val peak = prev > prev2 && prev >= dyn && prev > threshold
        val t = prevT
        prev2 = prev; prev = dyn; prevT = tMs
        if (peak && t - lastStep >= minGapMs) { lastStep = t; return true }
        return false
    }
}

/** Integrates steps into a path in metres, east/north of the starting point. */
class StepIntegrator(private val stepLengthM: Double = 0.70) {
    var east = 0.0
        private set
    var north = 0.0
        private set
    var steps = 0
        private set

    fun reset() { east = 0.0; north = 0.0; steps = 0 }

    /** [headingTrueDeg]: the direction of this step. */
    fun step(headingTrueDeg: Double) {
        val h = Angles.toRadians(headingTrueDeg)
        east += stepLengthM * sin(h); north += stepLengthM * cos(h); steps++
    }
}

data class RangeSample(val tMs: Long, val eastM: Double, val northM: Double, val distanceM: Double)

/** Where the target is, in the same local frame as the walked path. */
data class WalkSolution(
    val targetEastM: Double,
    val targetNorthM: Double,
    val rmsResidualM: Double,
    val samples: Int,
    val lateralSpreadM: Double,
)

enum class WalkIssue { NOT_ENOUGH_SAMPLES, WALK_MORE, WALK_SIDEWAYS, AMBIGUOUS, POOR_FIT, TOO_CLOSE_TO_TELL }

class WalkResult(val solution: WalkSolution?, val issue: WalkIssue?)

class RangeBearingEstimator(
    private val windowMs: Long = 20_000,
    private val minSamples: Int = 6,
    private val minPathM: Double = 3.0,
    private val minLateralM: Double = 1.8,
    private val maxRmsM: Double = 2.5,
    private val sigmaRangeM: Double = 1.0,
) {
    private val samples = ArrayDeque<RangeSample>()

    fun reset() = samples.clear()

    fun add(s: RangeSample) {
        if (s.distanceM.isNaN() || s.distanceM < 0 || s.distanceM > 200) return
        samples.addLast(s)
        while (samples.isNotEmpty() && s.tMs - samples.first().tMs > windowMs) samples.removeFirst()
    }

    fun solve(): WalkResult {
        val pts = samples.toList()
        if (pts.size < minSamples) return WalkResult(null, WalkIssue.NOT_ENOUGH_SAMPLES)
        // Geometry of the walked path: principal-axis extent. A straight path leaves a mirror ambiguity (the target could be on
        // either side of the line), so some sideways movement is required to pick one.
        val mx = pts.map { it.eastM }.average(); val my = pts.map { it.northM }.average()
        val sxx = pts.sumOf { (it.eastM - mx) * (it.eastM - mx) } / pts.size
        val syy = pts.sumOf { (it.northM - my) * (it.northM - my) } / pts.size
        val sxy = pts.sumOf { (it.eastM - mx) * (it.northM - my) } / pts.size
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val major = sqrt(max(0.0, tr / 2 + disc)) * 3.46 // ≈ full extent of a uniform spread
        val minor = sqrt(max(0.0, tr / 2 - disc)) * 3.46
        if (major < minPathM) return WalkResult(null, WalkIssue.WALK_MORE)
        if (minor < minLateralM) return WalkResult(null, WalkIssue.WALK_SIDEWAYS)

        // Coarse grid over plausible target positions, then Gauss-Newton refinement of the two best distinct basins.
        val dMax = pts.maxOf { it.distanceM }
        val r = min(dMax + 6.0, 80.0)
        val step = if (r > 40) 2.0 else 1.0
        fun cost(qx: Double, qy: Double) = pts.sumOf { val e = hypot(qx - it.eastM, qy - it.northM) - it.distanceM; e * e }
        var best = Triple(0.0, 0.0, Double.MAX_VALUE)
        val grid = ArrayList<Triple<Double, Double, Double>>()
        var gx = mx - r
        while (gx <= mx + r) { var gy = my - r; while (gy <= my + r) { val c = cost(gx, gy); grid += Triple(gx, gy, c); if (c < best.third) best = Triple(gx, gy, c); gy += step }; gx += step }
        val second = grid.filter { hypot(it.first - best.first, it.second - best.second) > 4.0 }.minByOrNull { it.third }
        val a = refine(pts, best.first, best.second)
        val b = second?.let { refine(pts, it.first, it.second) }
        val rmsA = sqrt(cost(a.first, a.second) / pts.size)
        if (b != null) {
            val rmsB = sqrt(cost(b.first, b.second) / pts.size)
            if (hypot(a.first - b.first, a.second - b.second) > 4.0 && rmsB < rmsA * 1.5 + 0.4) return WalkResult(null, WalkIssue.AMBIGUOUS)
        }
        if (rmsA > maxRmsM) return WalkResult(null, WalkIssue.POOR_FIT)
        return WalkResult(WalkSolution(a.first, a.second, rmsA, pts.size, minor), null)
    }

    private fun refine(pts: List<RangeSample>, x0: Double, y0: Double): Pair<Double, Double> {
        var x = x0; var y = y0
        repeat(12) {
            var a = 0.0; var b = 0.0; var c = 0.0; var gx = 0.0; var gy = 0.0
            for (p in pts) {
                val dx = x - p.eastM; val dy = y - p.northM
                val d = max(hypot(dx, dy), 1e-3)
                val ux = dx / d; val uy = dy / d
                val res = d - p.distanceM
                a += ux * ux; b += ux * uy; c += uy * uy; gx += ux * res; gy += uy * res
            }
            val det = a * c - b * b
            if (abs(det) < 1e-9) return x to y
            x -= (c * gx - b * gy) / det; y -= (-b * gx + a * gy) / det
        }
        return x to y
    }

    /** Bearing to the target from [fromE]/[fromN], its distance, and the angular uncertainty implied by the walked geometry. */
    fun bearingFrom(sol: WalkSolution, fromE: Double, fromN: Double): Triple<Double, Double, Double> {
        val dx = sol.targetEastM - fromE; val dy = sol.targetNorthM - fromN
        val dist = hypot(dx, dy)
        val bearing = Angles.normalize360(Angles.toDegrees(atan2(dx, dy)))
        // Position covariance from the range Jacobian: cov = sigma² (JᵀJ)⁻¹; project onto the direction perpendicular to the line of sight.
        var a = 0.0; var b = 0.0; var c = 0.0
        for (p in samples) {
            val ex = sol.targetEastM - p.eastM; val ey = sol.targetNorthM - p.northM
            val d = max(hypot(ex, ey), 1e-3)
            val ux = ex / d; val uy = ey / d
            a += ux * ux; b += ux * uy; c += uy * uy
        }
        val det = a * c - b * b
        if (det < 1e-6 || dist < 1e-3) return Triple(bearing, dist, 180.0)
        val covXX = c / det; val covXY = -b / det; val covYY = a / det
        val px = -dy / dist; val py = dx / dist // perpendicular unit vector
        val varPerp = (px * px * covXX + 2 * px * py * covXY + py * py * covYY) * sigmaRangeM * sigmaRangeM
        val sd = sqrt(max(varPerp, 0.0))
        if (dist <= 2 * sd + 0.5) return Triple(bearing, dist, 180.0)
        return Triple(bearing, dist, Angles.toDegrees(kotlin.math.atan2(2.0 * sd, dist))) // about a 2-sigma half-angle
    }
}

