package app.linkfill.core

import app.linkfill.core.sim.BlePhyEventSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratedSimTest {

    @Test
    fun fixedPayloadGivesFullDelivery() {
        val sim = BlePhyEventSimulator(seed = 101L)
        val (deliveryRate, _) = sim.simulateSingleHopBench(
            txIsPhoneA = true,
            dwellMs = 0.0,
            isCoded = false,
            durationMs = 10_000.0,
        )
        assertEquals(1.0, deliveryRate, 0.001)
    }

    @Test
    fun updateCadenceCausesPredictableDeliveryDrop() {
        val sim = BlePhyEventSimulator(seed = 202L)
        // 1000ms dwell should have high delivery (>90%)
        val (deliv1000, _) = sim.simulateSingleHopBench(
            txIsPhoneA = true,
            dwellMs = 1000.0,
            isCoded = false,
            durationMs = 30_000.0,
        )
        assertTrue("1000ms dwell delivery should exceed 90%", deliv1000 >= 0.90)

        // 250ms dwell should drop significantly (<75%) due to scan interval alignment and update blackout
        val (deliv250, _) = sim.simulateSingleHopBench(
            txIsPhoneA = true,
            dwellMs = 250.0,
            isCoded = false,
            durationMs = 30_000.0,
        )
        assertTrue("250ms dwell delivery should drop below 75%", deliv250 < 0.75)
        assertTrue("1000ms dwell must outperform 250ms dwell", deliv1000 > deliv250)
    }

    @Test
    fun validationLadderMatchesRealPhoneBenchmarksWithin10Pp() {
        val sim = BlePhyEventSimulator(seed = 42L)
        val ladder = sim.runValidationLadder()
        assertTrue("Ladder must run all validation scenarios", ladder.size >= 4)
        for (result in ladder) {
            assertTrue(
                "Run ${result.runName} delta (${result.deltaPp} pp) must be <= 10 pp",
                result.passedValidation
            )
        }
    }

    @Test
    fun crowdScaleMaintainsDeliveryUnderAdaptivePolicy() {
        val (delivery100, tx100) = BlePhyEventSimulator.simulateCrowdScale(
            nodes = 100,
            densityPolicyTier = "Tier 2",
            relayProb = 0.7,
            suppressAfter = 3,
            trials = 3,
        )
        assertTrue("Emergency delivery must exceed 95% at 100 nodes", delivery100 >= 0.95)
        assertTrue("Transmissions must remain bounded", tx100 < 500)

        val (delivery1000, tx1000) = BlePhyEventSimulator.simulateCrowdScale(
            nodes = 1000,
            densityPolicyTier = "Tier 4",
            relayProb = 0.1,
            suppressAfter = 5,
            trials = 3,
        )
        assertTrue("Emergency delivery must exceed 95% at 1000 nodes", delivery1000 >= 0.95)
        assertTrue("Transmissions must remain bounded at 1000 nodes", tx1000 < 2000)
    }
}
