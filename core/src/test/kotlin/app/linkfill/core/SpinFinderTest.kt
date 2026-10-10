package app.linkfill.core

import app.linkfill.core.find.Angles
import app.linkfill.core.find.FindInputs
import app.linkfill.core.find.FindMode
import app.linkfill.core.find.FindResolver
import app.linkfill.core.find.HeadingFix
import app.linkfill.core.find.Note
import app.linkfill.core.find.RssiTracker
import app.linkfill.core.find.Source
import app.linkfill.core.find.SpinFinder
import app.linkfill.core.find.WalkFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

/** Synthetic turns only: these check the geometry and the refusals, not real body-shadow strength (field test S18). */
class SpinFinderTest {
    private val t0 = 2_000_000_000L

    /** One slow turn of [degrees] over 12 s, readings every 100 ms; the body costs up to [shadowDb] when facing away. */
    private fun turn(target: Double, degrees: Double = 360.0, shadowDb: Double = 12.0, noise: Int = 3, seed: Int = 1): SpinFinder {
        val f = SpinFinder(); val rnd = Random(seed)
        for (k in 0 until 120) {
            val h = Angles.normalize360(17.0 + degrees * k / 120)
            val off = Math.toRadians(Angles.diff(h, target))
            f.add(t0 + k * 100L, h, (-55 - shadowDb * (1 - cos(off)) / 2 + rnd.nextInt(-noise, noise + 1)).toInt())
        }
        return f
    }

    @Test fun aFullTurnPointsAtTheStrongestDirection() {
        for ((target, seed) in listOf(70.0 to 1, 200.0 to 2, 352.0 to 3)) { // 352 checks the wrap at north
            val r = turn(target, seed = seed).solve(t0 + 12_000)
            assertNotNull("target $target", r.bearingTrueDeg)
            assertTrue("target $target got ${r.bearingTrueDeg}", abs(Angles.diff(r.bearingTrueDeg!!, target)) <= 20.0)
            assertTrue(r.uncertaintyDeg!! in 25.0..60.0)
        }
    }

    @Test fun halfATurnGivesNoDirectionButReportsProgress() {
        val r = turn(70.0, degrees = 180.0).solve(t0 + 12_000)
        assertNull(r.bearingTrueDeg)
        assertTrue(r.coverage in 0.4..0.6)
    }

    @Test fun aFlatSignalGivesNoDirection() {
        val r = turn(70.0, shadowDb = 1.0).solve(t0 + 12_000)
        assertNull("no body shadow means no direction", r.bearingTrueDeg)
        assertEquals(1.0, r.coverage, 1e-9)
    }

    @Test fun oldReadingsExpire() {
        val f = turn(70.0)
        assertNull(f.solve(t0 + 60_000).bearingTrueDeg)
    }

    @Test fun resolverDrawsTheTurnArrowRelativeToWhereThePhoneFacesAndAsksToTurnWithoutOne() {
        val tr = RssiTracker(); for (k in 0 until 20) tr.add(t0 - 2_000 + k * 100L, -60)
        val heading = HeadingFix(trueDeg = 30.0, uncertaintyDeg = 10.0, atMs = t0)
        val base = FindInputs(t0, null, tr.snapshot(t0), null, null, heading)
        val without = FindResolver.resolve(base)
        assertEquals(FindMode.BLE_PROXIMITY, without.mode); assertNull(without.arrowDeg); assertTrue(Note.TURN_TO_FIND in without.notes)
        val with = FindResolver.resolve(base.copy(turn = WalkFix(120.0, 30.0, Double.NaN, t0 - 5_000)))
        assertEquals(90.0, with.arrowDeg!!, 1e-9) // target at 120 true, facing 30: a quarter turn right
        assertEquals(40.0, with.arrowUncertaintyDeg!!, 1e-9)
        assertEquals(Source.BLE_TURN, with.arrowSource)
        val stale = FindResolver.resolve(base.copy(turn = WalkFix(120.0, 30.0, Double.NaN, t0 - 60_000)))
        assertNull(stale.arrowDeg)
        val noCompass = FindResolver.resolve(base.copy(heading = heading.copy(uncertaintyDeg = 80.0), turn = WalkFix(120.0, 30.0, Double.NaN, t0)))
        assertNull("an arrow needs a usable compass", noCompass.arrowDeg)
    }
}
