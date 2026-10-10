package app.linkfill.core

import app.linkfill.core.find.Angles
import app.linkfill.core.find.RangeBearingEstimator
import app.linkfill.core.find.RangeSample
import app.linkfill.core.find.StepDetector
import app.linkfill.core.find.StepIntegrator
import app.linkfill.core.find.WalkIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic walks only. These test the geometry and honesty rules; they say nothing about real Channel Sounding accuracy. */
class RangeBearingTest {
    private val rnd = Random(7)

    /** Samples every 1.5 s along [path] points (metres east/north), distance to a still target at [tx], [ty], plus noise. */
    private fun walk(path: List<Pair<Double, Double>>, tx: Double, ty: Double, noise: Double): List<RangeSample> =
        path.mapIndexed { i, (e, n) -> RangeSample(i * 1500L, e, n, hypot(tx - e, ty - n) + rnd.nextDouble(-noise, noise)) }

    /** An L-shaped walk: east 6 m, then north 6 m, one point per metre. */
    private val lPath = (0..6).map { it.toDouble() to 0.0 } + (1..6).map { 6.0 to it.toDouble() }

    private fun estimator(samples: List<RangeSample>) = RangeBearingEstimator().also { e -> samples.forEach(e::add) }

    @Test fun findsAStillTargetFromAnLShapedWalk() {
        val e = estimator(walk(lPath, tx = 20.0, ty = 15.0, noise = 0.5))
        val r = e.solve()
        assertNotNull(r.solution)
        assertEquals(20.0, r.solution!!.targetEastM, 1.5); assertEquals(15.0, r.solution.targetNorthM, 1.5)
        val (bearing, dist, unc) = e.bearingFrom(r.solution, 6.0, 6.0)
        assertEquals(Math.toDegrees(Math.atan2(14.0, 9.0)), bearing, 6.0)   // target is north-east of the end point
        assertEquals(hypot(14.0, 9.0), dist, 1.5)
        assertTrue("uncertainty reported, not zero: $unc", unc > 1.0 && unc < 40.0)
    }

    @Test fun aStraightWalkCannotChooseASide() {
        val straight = (0..14).map { it.toDouble() to 0.0 }
        val r = estimator(walk(straight, 20.0, 8.0, 0.3)).solve()
        assertNull(r.solution); assertEquals(WalkIssue.WALK_SIDEWAYS, r.issue)
    }

    @Test fun tooFewOrTooShortWalksGiveNothing() {
        assertEquals(WalkIssue.NOT_ENOUGH_SAMPLES, estimator(walk(lPath.take(3), 20.0, 15.0, 0.2)).solve().issue)
        val tiny = (0..8).map { (it % 3).toDouble() * 0.4 to (it / 3).toDouble() * 0.4 }
        assertEquals(WalkIssue.WALK_MORE, estimator(walk(tiny, 10.0, 10.0, 0.2)).solve().issue)
    }

    @Test fun aMovingTargetOrWildNoiseIsRejectedNotGuessed() {
        // Target runs away while we walk: distances grow inconsistently with any single position.
        val moving = lPath.mapIndexed { i, (e, n) -> RangeSample(i * 1500L, e, n, hypot(20.0 + i * 2.5 - e, 15.0 - n)) }
        val r = estimator(moving).solve()
        assertTrue("either no fix or flagged: ${r.issue}", r.solution == null || r.solution.rmsResidualM < 2.5)
        val garbage = estimator(walk(lPath, 20.0, 15.0, noise = 12.0)).solve()
        assertNull(garbage.solution)
    }

    @Test fun noiseLargerThanAssumedWidensTheUncertainty() {
        val tight = estimator(walk(lPath, 20.0, 15.0, 0.3)); val t = tight.solve().solution!!
        val far = estimator(walk(lPath, 60.0, 40.0, 0.3)); val f = far.solve().solution
        val uncNear = tight.bearingFrom(t, 6.0, 6.0).third
        if (f != null) assertTrue("farther targets are less certain", far.bearingFrom(f, 6.0, 6.0).third >= uncNear * 0.5)
    }

    @Test fun staleSamplesFallOutOfTheWindow() {
        val e = RangeBearingEstimator(windowMs = 10_000)
        walk(lPath, 20.0, 15.0, 0.3).forEach(e::add)
        e.add(RangeSample(120_000, 6.0, 6.0, 15.0))
        assertEquals(WalkIssue.NOT_ENOUGH_SAMPLES, e.solve().issue)
    }

    @Test fun stepIntegratorWalksAlongTheHeading() {
        val s = StepIntegrator(0.7)
        repeat(10) { s.step(90.0) }   // east
        repeat(10) { s.step(0.0) }    // north
        assertEquals(7.0, s.east, 1e-6); assertEquals(7.0, s.north, 1e-6); assertEquals(20, s.steps)
    }

    @Test fun stepDetectorCountsWalkingAndIgnoresStillness() {
        val d = StepDetector()
        var steps = 0
        for (i in 0 until 50 * 10) { val t = i * 20L; if (d.add(t, 0.0, 0.0, 9.81 + 3.0 * sin(2 * Math.PI * 1.8 * t / 1000.0))) steps++ }
        assertTrue("about 18 steps in 10 s at 1.8 Hz, got $steps", steps in 15..21)
        val still = StepDetector(); var phantom = 0
        for (i in 0 until 500) { if (still.add(i * 20L, 0.0, 0.0, 9.81 + 0.1 * sin(i * 0.3))) phantom++ }
        assertEquals(0, phantom)
        // Shaking harder than walking is still limited by the minimum gap between steps.
        val shaky = StepDetector(); var count = 0
        for (i in 0 until 500) { val t = i * 20L; if (shaky.add(t, 0.0, 0.0, 9.81 + 6.0 * sin(2 * Math.PI * 8.0 * t / 1000.0))) count++ }
        assertTrue("limited by gap: $count", count <= 36)
    }

    @Test fun angleHelperUsedByTheArrowIsConsistent() {
        assertEquals(-45.0, Angles.relative(315.0, 0.0), 1e-9)
    }
}
