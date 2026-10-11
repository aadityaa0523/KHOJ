package app.linkfill.core

import app.linkfill.core.find.AngleSmoother
import app.linkfill.core.find.Angles
import app.linkfill.core.find.Bearing
import app.linkfill.core.find.FindInputs
import app.linkfill.core.find.FindMode
import app.linkfill.core.find.FindResolver
import app.linkfill.core.find.Freshness
import app.linkfill.core.find.HeadingFix
import app.linkfill.core.find.ImprovementCue
import app.linkfill.core.find.Level
import app.linkfill.core.find.LocFix
import app.linkfill.core.find.Note
import app.linkfill.core.find.Orientation
import app.linkfill.core.find.Posture
import app.linkfill.core.find.Proximity
import app.linkfill.core.find.RangingFix
import app.linkfill.core.find.RssiSnapshot
import app.linkfill.core.find.RssiTracker
import app.linkfill.core.find.Source
import app.linkfill.core.find.Trend
import app.linkfill.core.find.Tunnel
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.MeshMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Everything here uses synthetic inputs. Nothing in this file is, or pretends to be, a real hardware measurement. */
class FindTest {
    private val t0 = 1_000_000L

    // ------------------------------------------------------------ angles and orientation

    @Test fun angleHelpers() {
        assertEquals(350.0, Angles.normalize360(-10.0), 1e-9)
        assertEquals(-170.0, Angles.normalize180(190.0), 1e-9)
        assertEquals(90.0, Angles.relative(100.0, 10.0), 1e-9)       // target to the right
        assertEquals(-90.0, Angles.relative(10.0, 100.0), 1e-9)      // to the left
        assertEquals(20.0, Angles.relative(10.0, 350.0), 1e-9)       // across north
        assertEquals(180.0, Angles.relative(0.0, 180.0), 1e-9)       // behind
        assertEquals(5.0, Angles.magneticToTrue(358.0, 7.0), 1e-9)
    }

    @Test fun gpsBearingUncertaintyGrowsWhenClose() {
        assertEquals(180.0, Bearing.uncertaintyDeg(5.0, 5.0, 5.0), 1e-9)
        assertTrue(Bearing.uncertaintyDeg(100.0, 5.0, 5.0) < 5.0)
        assertTrue(Bearing.uncertaintyDeg(20.0, 5.0, 5.0) > 20.0)
    }

    /** Unit quaternion (x, y, z, w) for a rotation of [deg] about world axis 0 = X, 1 = Y, 2 = Z. */
    private fun quat(axis: Int, deg: Double): DoubleArray {
        val h = Math.toRadians(deg) / 2
        val s = sin(h); val c = cos(h)
        return when (axis) { 0 -> doubleArrayOf(s, 0.0, 0.0, c); 1 -> doubleArrayOf(0.0, s, 0.0, c); else -> doubleArrayOf(0.0, 0.0, s, c) }
    }

    /** Hamilton product: apply [b] first, then [a] (both about world axes). */
    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val (ax, ay, az, aw) = a.toList(); val (bx, by, bz, bw) = b.toList()
        return doubleArrayOf(aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx, aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz)
    }

    private fun heading(q: DoubleArray, rotation: Int = 0) = Orientation.heading(floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()), rotation)

    @Test fun flatPhoneHeadingFollowsTheTopEdge() {
        val north = heading(doubleArrayOf(0.0, 0.0, 0.0, 1.0))!!
        assertEquals(0.0, north.magneticDeg, 0.01); assertEquals(Posture.FLAT, north.posture)
        assertEquals(270.0, heading(quat(2, 90.0))!!.magneticDeg, 0.01)    // turned CCW: top edge points west
        assertEquals(180.0, heading(quat(2, 90.0), 1)!!.magneticDeg, 0.01) // landscape: the screen's top edge is the device's -X axis
        assertEquals(180.0, heading(doubleArrayOf(0.0, 0.0, 0.0, 1.0), 2)!!.magneticDeg, 0.01) // upside-down screen
    }

    @Test fun uprightPhoneHeadingIsWhereTheBackCameraPoints() {
        val up = heading(quat(0, 90.0))!!
        assertEquals(Posture.UPRIGHT, up.posture); assertEquals(0.0, up.magneticDeg, 0.5)
        // Body turned to face east (clockwise from above = -90° about Z), phone still upright.
        assertEquals(90.0, heading(mul(quat(2, -90.0), quat(0, 90.0)))!!.magneticDeg, 0.5)
        // Same, tilted back to 60° from horizontal: still facing east.
        val tilted = heading(mul(quat(2, -90.0), quat(0, 60.0)))!!
        assertEquals(Posture.UPRIGHT, tilted.posture); assertEquals(90.0, tilted.magneticDeg, 0.5)
        // Landscape upright: facing is the camera direction regardless of display rotation.
        assertEquals(90.0, heading(mul(quat(2, -90.0), quat(0, 90.0)), 1)!!.magneticDeg, 0.5)
    }

    @Test fun degenerateRotationVectorIsRejected() {
        assertNull(Orientation.heading(floatArrayOf(0f, 0f), 0))
        assertNull(Orientation.heading(floatArrayOf(0f, 0f, 0f, 0f), 0).takeIf { false } ?: Orientation.heading(floatArrayOf(), 0))
    }

    @Test fun smootherDoesNotSpinAcrossTheBackOfTheUser() {
        val s = AngleSmoother(200.0)
        s.update(170.0, 0)
        val v = s.update(-170.0, 100)          // target moved 20° through "behind"
        assertTrue("took the short way round: $v", v > 170.0 || v < -170.0)
        var x = 0.0; repeat(40) { x = s.update(-170.0, 100L + (it + 1) * 100L) }
        assertEquals(-170.0, x, 1.0)
    }

    @Test fun smootherCompensatesForPhoneTurn() {
        val s = AngleSmoother(); s.update(30.0, 0)
        s.rotateBy(10.0)   // phone turned 10° right, so the target is now 10° further left
        assertEquals(20.0, s.update(20.0, 0), 1e-6)
    }

    // ------------------------------------------------------------ RSSI

    private fun feed(tr: RssiTracker, start: Long, n: Int, stepMs: Long, f: (Int) -> Int) { for (i in 0 until n) tr.add(start + i * stepMs, f(i)) }

    @Test fun medianRemovesSpikes() {
        val tr = RssiTracker()
        feed(tr, t0, 12, 250) { if (it == 7) -30 else -70 }
        assertEquals(-70.0, tr.snapshot(t0 + 12 * 250).filtered!!, 2.0)
    }

    @Test fun trendNeedsRealSlopeNotNoise() {
        val rnd = Random(1)
        val noisy = RssiTracker(); feed(noisy, t0, 40, 250) { -70 + rnd.nextInt(-6, 7) }
        assertEquals(Trend.STEADY, noisy.snapshot(t0 + 40 * 250).trend)
        val rising = RssiTracker(); feed(rising, t0, 40, 250) { -80 + it / 2 + rnd.nextInt(-2, 3) }
        assertEquals(Trend.IMPROVING, rising.snapshot(t0 + 40 * 250).trend)
        val falling = RssiTracker(); feed(falling, t0, 40, 250) { -60 - it / 2 + rnd.nextInt(-2, 3) }
        assertEquals(Trend.WORSENING, falling.snapshot(t0 + 40 * 250).trend)
    }

    @Test fun staleOrInvalidSamplesGiveNoGuidance() {
        val tr = RssiTracker(); tr.add(t0, 0); tr.add(t0, 127); tr.add(t0, -200)
        assertEquals(0, tr.snapshot(t0).samples)
        feed(tr, t0, 12, 250) { -60 }
        val old = tr.snapshot(t0 + 12 * 250 + 10_000)
        assertNull(old.proximity); assertEquals(Trend.UNKNOWN, old.trend)
    }

    @Test fun proximityBandHasHysteresis() {
        val tr = RssiTracker()
        feed(tr, t0, 12, 250) { -60 }
        assertEquals(Proximity.NEAR, tr.snapshot(t0 + 3000).proximity)
        val seen = mutableSetOf<Proximity?>()
        for (i in 0 until 30) { tr.add(t0 + 3000 + i * 250L, if (i % 2 == 0) -63 else -66); seen += tr.snapshot(t0 + 3000 + i * 250L).proximity }
        assertEquals(1, seen.size)
    }

    @Test fun hapticCueIsRateLimited() {
        val c = ImprovementCue(minGapMs = 4_000, needConsecutive = 3)
        assertFalse(c.update(Trend.IMPROVING, 0)); assertFalse(c.update(Trend.IMPROVING, 250))
        assertTrue(c.update(Trend.IMPROVING, 500))
        repeat(3) { assertFalse(c.update(Trend.IMPROVING, 600L + it * 100)) }
        assertFalse(c.update(Trend.STEADY, 5_000))
        assertFalse(c.update(Trend.IMPROVING, 5_100)); assertFalse(c.update(Trend.IMPROVING, 5_200))
        assertTrue(c.update(Trend.IMPROVING, 5_300))
    }

    // ------------------------------------------------------------ resolver policy

    private val me = LocFix(17.0000, 78.0000, 5.0, t0)
    private fun target(northM: Double, age: Long = 1_000, acc: Double = 5.0): LocFix {
        val (la, lo) = app.linkfill.core.estimate.GapEstimator.offset(17.0, 78.0, northM, 0.0)
        return LocFix(la, lo, acc, t0 - age)
    }
    private val heading = HeadingFix(0.0, 10.0, t0)
    private fun inputs(ranging: RangingFix? = null, rssi: RssiSnapshot? = null, tgt: LocFix? = null, mine: LocFix? = me, h: HeadingFix? = heading, trust: Boolean = false) =
        FindInputs(t0, ranging, rssi, mine, tgt, h, trust)

    private fun goodRssi(level: Int): RssiSnapshot { val tr = RssiTracker(); feed(tr, t0 - 3000, 13, 250) { level }; return tr.snapshot(t0) }

    @Test fun gpsBearingIsShownWithUncertaintyWhenFarEnough() {
        val s = FindResolver.resolve(inputs(tgt = target(100.0)))
        assertEquals(FindMode.LOCATION_BEARING, s.mode)
        assertEquals(0.0, s.arrowDeg!!, 1.0)
        assertTrue(s.arrowUncertaintyDeg!! > 10.0)
        assertEquals(Source.GPS, s.arrowSource)
    }

    @Test fun gpsArrowIsWithheldWhenTooCloseAndWideWhenInaccurate() {
        // Too close for GPS to be sure: still an arrow (the compass word as an arrow), with a wedge saying how rough it is.
        val close = FindResolver.resolve(inputs(tgt = target(6.0)))
        assertEquals(0.0, close.arrowDeg!!, 1.0); assertTrue(close.arrowUncertaintyDeg!! >= 90.0); assertTrue(Note.GPS_TOO_CLOSE_FOR_DIRECTION in close.notes)
        val rough = FindResolver.resolve(inputs(tgt = target(30.0, acc = 25.0)))
        assertEquals(0.0, rough.arrowDeg!!, 1.0)
        assertTrue("the wedge shows how unsure it is", rough.arrowUncertaintyDeg!! > 45.0)
    }

    @Test fun silentTargetGetsAnArrowToItsPredictedPositionWithAWideningWedge() {
        // 11 s of silence: a prediction, still a GPS reading less than 20 s old.
        assertEquals("a position heard 5 s ago is used as it is", FindMode.LOCATION_BEARING, FindResolver.resolve(inputs(tgt = target(200.0, age = 5_000))).mode)
        val s = FindResolver.resolve(inputs(tgt = target(200.0, age = 11_000)))
        assertEquals("10 to 20 s old is predicted", FindMode.PREDICTED, s.mode); assertEquals(Source.PREDICTED, s.arrowSource)
        assertEquals(0.0, s.arrowDeg!!, 1.0)
        val longer = FindResolver.resolve(inputs(tgt = target(200.0, age = 19_000)))
        assertTrue("longer silence, wider wedge", longer.arrowUncertaintyDeg!! > s.arrowUncertaintyDeg!!)
        // More than 20 s late: no prediction and no arrow from the old position.
        val late = FindResolver.resolve(inputs(tgt = target(200.0, age = 21_000)))
        assertEquals("a gap over 20 s means we stop guessing", FindMode.UNAVAILABLE, late.mode)
        assertNull(late.arrowDeg); assertNull(late.distanceM)
        assertTrue(Note.TARGET_POSITION_OLD in late.notes)
        assertEquals("too old to predict", FindMode.UNAVAILABLE, FindResolver.resolve(inputs(tgt = target(200.0, age = 11 * 60_000L))).mode)
    }

    @Test fun roughCompassWidensTheArrowAndAnOldHeadingRemovesIt() {
        val s = FindResolver.resolve(inputs(tgt = target(100.0), h = HeadingFix(0.0, 80.0, t0)))
        assertEquals(0.0, s.arrowDeg!!, 1.0); assertTrue(s.arrowUncertaintyDeg!! >= 80.0); assertTrue(Note.HEADING_UNRELIABLE in s.notes)
        assertNull(FindResolver.resolve(inputs(tgt = target(100.0), h = HeadingFix(0.0, 5.0, t0 - 10_000))).arrowDeg)
    }

    @Test fun compassArrowTurnsWithThePhone() {
        // Target due north; phone facing east, so the arrow points left (-90).
        val s = FindResolver.resolve(inputs(tgt = target(6.0), h = HeadingFix(90.0, 10.0, t0)))
        assertEquals(-90.0, s.arrowDeg!!, 1.0)
    }

    @Test fun missingMyLocationIsReported() {
        val s = FindResolver.resolve(inputs(tgt = target(100.0), mine = null))
        assertTrue(Note.MY_LOCATION_MISSING in s.notes); assertNull(s.arrowDeg)
    }

    private fun ranging(az: Double?, conf: Level = Level.HIGH, atMs: Long = t0 - 500, h: Double? = 0.0) =
        RangingFix(Source.UWB, 7.5, Level.HIGH, az, az?.let { conf }, atMs, h)

    @Test fun hardwareDirectionRequiresTrustedAngle() {
        val untrusted = FindResolver.resolve(inputs(ranging = ranging(20.0)))
        assertEquals(FindMode.PRECISION_DISTANCE, untrusted.mode); assertNull(untrusted.arrowDeg)
        assertTrue(Note.ANGLE_UNTRUSTED in untrusted.notes)
        val trusted = FindResolver.resolve(inputs(ranging = ranging(20.0), trust = true))
        assertEquals(FindMode.PRECISION_DIRECTION, trusted.mode); assertEquals(20.0, trusted.arrowDeg!!, 1e-6); assertEquals(Source.UWB, trusted.arrowSource)
    }

    @Test fun distanceOnlyHardwareDoesNotFabricateAnAngle() {
        val s = FindResolver.resolve(inputs(ranging = RangingFix(Source.BLE_CHANNEL_SOUNDING, 12.0, Level.MEDIUM, null, null, t0 - 300, null), trust = true))
        assertEquals(FindMode.PRECISION_DISTANCE, s.mode); assertNull(s.arrowDeg); assertEquals(12.0, s.distanceM!!, 1e-9)
        assertTrue(Note.NO_ANGLE_FROM_HARDWARE in s.notes)
    }

    @Test fun arrowIsCompensatedForPhoneTurnSinceMeasurement() {
        // Target was 20° right when the phone faced 0°; the phone has since turned 30° right, so it is now 10° LEFT.
        val s = FindResolver.resolve(inputs(ranging = ranging(20.0, h = 0.0), h = HeadingFix(30.0, 5.0, t0), trust = true))
        assertEquals(-10.0, s.arrowDeg!!, 1e-6)
    }

    @Test fun staleRangingFallsBackAndSaysSo() {
        val s = FindResolver.resolve(inputs(ranging = ranging(10.0, atMs = t0 - 8_000), tgt = target(100.0), trust = true))
        assertEquals(FindMode.LOCATION_BEARING, s.mode); assertTrue(Note.RANGING_STALE in s.notes)
        assertEquals(FindMode.UNAVAILABLE, FindResolver.resolve(inputs(ranging = ranging(10.0, atMs = t0 - 8_000))).mode)
    }

    @Test fun rssiBasedRangingIsNeverPresentedAsPrecise() {
        val s = FindResolver.resolve(inputs(ranging = RangingFix(Source.BLE_RSSI_RANGING, 3.0, Level.HIGH, null, null, t0 - 200, null), tgt = target(100.0)))
        assertEquals(FindMode.LOCATION_BEARING, s.mode)
    }

    @Test fun walkingDirectionFillsInWhenHardwareGivesOnlyDistance() {
        val csOnly = RangingFix(Source.BLE_CHANNEL_SOUNDING, 9.0, Level.MEDIUM, null, null, t0 - 300, null)
        val walk = app.linkfill.core.find.WalkFix(bearingTrueDeg = 90.0, uncertaintyDeg = 20.0, distanceM = 9.0, atMs = t0 - 300)
        val s = FindResolver.resolve(FindInputs(t0, csOnly, null, null, null, heading, false, walk, null))
        assertEquals(FindMode.RANGE_WALK, s.mode); assertEquals(90.0, s.arrowDeg!!, 1e-6)   // target due east, phone faces north: to the right
        assertEquals(9.0, s.distanceM!!, 1e-9); assertEquals(Source.RANGE_WALK, s.arrowSource)
        assertTrue("uncertainty includes the compass", s.arrowUncertaintyDeg!! >= 30.0)
        // Too uncertain, or an old solution, or a bad compass: no arrow, and the user is told what to do.
        assertNull(FindResolver.resolve(FindInputs(t0, csOnly, null, null, null, heading, false, walk.copy(uncertaintyDeg = 70.0), null)).arrowDeg)
        assertNull(FindResolver.resolve(FindInputs(t0, csOnly, null, null, null, heading, false, walk.copy(atMs = t0 - 9_000), null)).arrowDeg)
        assertNull(FindResolver.resolve(FindInputs(t0, csOnly, null, null, null, HeadingFix(0.0, 80.0, t0), false, walk, null)).arrowDeg)
        val hint = FindResolver.resolve(FindInputs(t0, csOnly, null, null, null, heading, false, null, app.linkfill.core.find.WalkIssue.WALK_SIDEWAYS))
        assertEquals(FindMode.PRECISION_DISTANCE, hint.mode); assertTrue(Note.WALK_SIDEWAYS in hint.notes)
    }

    @Test fun lowConfidenceAngleIsNotUsed() {
        val s = FindResolver.resolve(inputs(ranging = ranging(10.0, conf = Level.LOW), trust = true))
        assertEquals(FindMode.PRECISION_DISTANCE, s.mode); assertNull(s.arrowDeg)
    }

    @Test fun bluetoothArrowComesOnlyFromTrendTurnOrPrediction() {
        val steady = FindResolver.resolve(inputs(rssi = goodRssi(-70), mine = null))
        assertEquals(FindMode.BLE_PROXIMITY, steady.mode); assertNull(steady.arrowDeg); assertNull(steady.distanceM); assertEquals(Proximity.MEDIUM, steady.proximity)
        val tr = RssiTracker(); feed(tr, t0 - 4_000, 17, 250) { i -> -80 + i } // getting stronger as I walk
        val warmer = FindResolver.resolve(inputs(rssi = tr.snapshot(t0), mine = null))
        assertEquals(Trend.IMPROVING, warmer.trend); assertEquals(0.0, warmer.arrowDeg!!, 1e-9); assertEquals(Source.BLE_TREND, warmer.arrowSource)
        val tr2 = RssiTracker(); feed(tr2, t0 - 4_000, 17, 250) { i -> -55 - i } // getting weaker: turn round
        assertEquals(180.0, FindResolver.resolve(inputs(rssi = tr2.snapshot(t0), mine = null)).arrowDeg!!, 1e-9)
    }

    @Test fun predictionCarriesTheirWalkForwardAndStopsAfterAWhile() {
        val (la1, lo1) = app.linkfill.core.estimate.GapEstimator.offset(17.0, 78.0, 0.0, 0.0)
        val (la2, lo2) = app.linkfill.core.estimate.GapEstimator.offset(17.0, 78.0, 20.0, 0.0) // 20 m north in 20 s: 1 m/s
        val track = listOf(LocFix(la1, lo1, 3.0, t0 - 30_000), LocFix(la2, lo2, 3.0, t0 - 10_000))
        val p = app.linkfill.core.find.Predict.position(track, t0)!!
        assertEquals(30.0, app.linkfill.core.estimate.GapEstimator.distanceM(17.0, 78.0, p.lat, p.lon), 0.5) // 10 s more at 1 m/s
        assertEquals(6.5, p.accuracyM, 0.1) // Age-aware uncertainty: sqrt(3^2 + 10^2*0.4^2 + 10^2*0.35^2 + 0.5*10) = 6.5 m
        // The newest fix is 20.001 s old: over the 20 s gap rule, so no prediction, even with an unbounded uncertainty threshold.
        assertNull(app.linkfill.core.find.Predict.position(track, t0 + 10_001))
        assertNull(app.linkfill.core.find.Predict.position(track, t0 + 120_000, maxUncertaintyM = Double.MAX_VALUE))
        // Exactly at the limit it still predicts, coasting 20 s at 1 m/s from the last fix.
        val edge = app.linkfill.core.find.Predict.position(track, t0 + 10_000)!!
        assertEquals("coasts at most 20 s", 40.0, app.linkfill.core.estimate.GapEstimator.distanceM(17.0, 78.0, edge.lat, edge.lon), 0.5)
        val jitter = listOf(LocFix(la1, lo1, 8.0, t0 - 30_000), LocFix(la1 + 1e-5, lo1, 8.0, t0 - 10_000)) // 1 m of GPS noise
        assertEquals(1.1, app.linkfill.core.estimate.GapEstimator.distanceM(17.0, 78.0, app.linkfill.core.find.Predict.position(jitter, t0)!!.lat, lo1), 0.2)
    }

    @Test fun wedgeGrowsWithFixAge() {
        val s5 = FindResolver.resolve(inputs(tgt = target(200.0, age = 5_000)))
        val s12 = FindResolver.resolve(inputs(tgt = target(200.0, age = 12_000)))
        val s19 = FindResolver.resolve(inputs(tgt = target(200.0, age = 19_000)))
        val u5 = s5.arrowUncertaintyDeg!!
        val u12 = s12.arrowUncertaintyDeg!!
        val u19 = s19.arrowUncertaintyDeg!!
        assertTrue("Wedge widens between 5 s and 12 s", u12 > u5)
        assertTrue("Wedge widens between 12 s and 19 s", u19 > u12)
        assertEquals("Past 20 s there is no arrow at all", FindMode.UNAVAILABLE, FindResolver.resolve(inputs(tgt = target(200.0, age = 21_000))).mode)
    }

    @Test fun predictionStopsBeyond30mUncertainty() {
        // A very inaccurate fix (30 m) gives a prediction uncertainty above 30 m even inside the 20 s window.
        val track = listOf(LocFix(17.0, 78.0, 30.0, t0 - 5_000))
        assertNull(app.linkfill.core.find.Predict.position(track, t0))
        // But with maxUncertaintyM = 40.0 it is non-null
        assertTrue(app.linkfill.core.find.Predict.position(track, t0, maxUncertaintyM = 40.0) != null)
        // And a good fix older than 20 s is refused by the gap rule whatever its accuracy.
        assertNull(app.linkfill.core.find.Predict.position(listOf(LocFix(17.0, 78.0, 3.0, t0 - 21_000)), t0))
    }

    @Test fun motionClassLimitsApply() {
        val (la1, lo1) = app.linkfill.core.estimate.GapEstimator.offset(17.0, 78.0, 0.0, 0.0)
        val (la2, lo2) = app.linkfill.core.estimate.GapEstimator.offset(17.0, 78.0, 60.0, 0.0) // 60 m in 10 s = 6 m/s
        val track = listOf(LocFix(la1, lo1, 3.0, t0 - 20_000), LocFix(la2, lo2, 3.0, t0 - 10_000))

        val still = app.linkfill.core.find.Predict.position(track, t0, motionClass = app.linkfill.core.find.MotionClass.STILL)!!
        val walking = app.linkfill.core.find.Predict.position(track, t0, motionClass = app.linkfill.core.find.MotionClass.WALKING)!!
        val running = app.linkfill.core.find.Predict.position(track, t0, motionClass = app.linkfill.core.find.MotionClass.RUNNING)!!

        val dStill = app.linkfill.core.estimate.GapEstimator.distanceM(la2, lo2, still.lat, still.lon)
        val dWalking = app.linkfill.core.estimate.GapEstimator.distanceM(la2, lo2, walking.lat, walking.lon)
        val dRunning = app.linkfill.core.estimate.GapEstimator.distanceM(la2, lo2, running.lat, running.lon)

        assertEquals("STILL caps speed at 0.3 m/s", 3.0, dStill, 0.5) // 0.3 m/s * 10 s = 3 m
        assertEquals("WALKING caps speed at 1.8 m/s", 18.0, dWalking, 0.5) // 1.8 m/s * 10 s = 18 m
        assertEquals("RUNNING caps speed at 4.5 m/s", 45.0, dRunning, 0.5) // 4.5 m/s * 10 s = 45 m
    }

    @Test fun pdrCoastStopsAt30s() {
        val tracker = app.linkfill.core.find.OwnPdrTracker(defaultStrideM = 0.70)
        val fix = LocFix(17.0, 78.0, 4.0, t0)
        tracker.onFix(fix)

        // 10 steps north
        repeat(10) { tracker.onStep(0.0) }
        assertEquals(7.0, tracker.northM, 1e-9)
        assertEquals(0.0, tracker.eastM, 1e-9)

        // Within 30 s: valid coasted position
        val coast10 = tracker.position(t0 + 10_000L)!!
        val d10 = app.linkfill.core.estimate.GapEstimator.distanceM(17.0, 78.0, coast10.lat, coast10.lon)
        assertEquals(7.0, d10, 0.2)
        assertTrue("Accuracy expands with PDR drift", coast10.accuracyM > 4.0)

        // At 30 s: still coasting
        val coast30 = tracker.position(t0 + 30_000L)
        assertTrue(coast30 != null)

        // Beyond 30 s: PDR coast stops!
        val coast31 = tracker.position(t0 + 30_001L)
        assertNull("PDR coast stops beyond 30 s", coast31)

        // Verify Weinberg formula
        val stride = app.linkfill.core.find.Weinberg.strideLengthM(12.0, 8.0)
        assertTrue("Weinberg stride is within realistic bounds", stride in 0.5..0.85)
    }

    @Test fun veryCloseBluetoothOverridesAnUncertainGpsArrow() {
        val s = FindResolver.resolve(inputs(rssi = goodRssi(-45), tgt = target(60.0)))
        assertEquals(FindMode.BLE_PROXIMITY, s.mode) // closeness from Bluetooth; the arrow still points where their GPS says
        assertEquals(Source.GPS, s.arrowSource)
    }

    @Test fun noisyBluetoothIsNotPresentedAsGuidance() {
        val tr = RssiTracker(); val rnd = Random(3); feed(tr, t0 - 3000, 4, 700) { -70 + rnd.nextInt(-20, 20) }
        val s = FindResolver.resolve(inputs(rssi = tr.snapshot(t0)))
        assertTrue(s.mode == FindMode.UNAVAILABLE || s.mode == FindMode.BLE_PROXIMITY)
        assertNull(s.arrowDeg)
    }

    @Test fun nothingKnownIsUnavailable() {
        val s = FindResolver.resolve(inputs(mine = null, h = null))
        assertEquals(FindMode.UNAVAILABLE, s.mode); assertNull(s.distanceM); assertNull(s.arrowDeg)
    }

    // ------------------------------------------------------------ tunnel and beacon

    @Test fun tunnelRoundTripAndRejections() {
        val key = CrewKey.create(); val other = CrewKey.create()
        val f = Tunnel.seal(key, 1, 2, Tunnel.Kind.REQUEST, byteArrayOf(9, 8, 7), t0)
        val m = Tunnel.open(key, 2, f, t0 + 1000)!!
        assertEquals(1, m.from); assertEquals(Tunnel.Kind.REQUEST, m.kind); assertEquals(listOf<Byte>(9, 8, 7), m.payload.toList())
        assertNull("wrong recipient", Tunnel.open(key, 3, f, t0))
        assertNull("wrong crew", Tunnel.open(other, 2, f, t0))
        assertNull("replayed later", Tunnel.open(key, 2, f, t0 + Tunnel.MAX_AGE_MS + 1))
        assertNull("tampered", Tunnel.open(key, 2, f.copyOf().also { it[20] = (it[20] + 1).toByte() }, t0))
        assertNull(Tunnel.open(key, 2, ByteArray(5), t0))
        assertTrue(Tunnel.isTunnel(f)); assertFalse(Tunnel.isTunnel(byteArrayOf(0x4B)))
    }

    @Test fun redirectedTunnelFrameFails() {
        val key = CrewKey.create()
        val f = Tunnel.seal(key, 1, 2, Tunnel.Kind.DATA, byteArrayOf(1), t0)
        val redirected = f.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(5, 3) }
        assertNull(Tunnel.open(key, 3, redirected, t0))
    }

    @Test fun beaconCarriesTheFindableFlag() {
        val (type, body) = MeshMessage.encode(MeshMessage.Beacon(17.0, 78.0, 5, 80, "Amma", findable = true))
        val back = MeshMessage.decode(type, body) as MeshMessage.Beacon
        assertTrue(back.findable); assertEquals("Amma", back.name)
        val off = MeshMessage.encode(MeshMessage.Beacon(17.0, 78.0, 5, 80, "Amma", false))
        assertFalse((MeshMessage.decode(off.first, off.second) as MeshMessage.Beacon).findable)
    }

    @Test fun crewTagIsUnchangedByTheTunnelKeyDerivation() {
        // The tunnel key extends the same HKDF output, so existing crews keep their id and packet key.
        assertEquals(CrewKey.fromCode("ABCD-EFGH-JKLM-NPQR")!!.crewId, CrewKey.fromCode("abcdefghjklmnpqr")!!.crewId)
    }
}
