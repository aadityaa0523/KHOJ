package app.linkfill.core.sim

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Phone-to-phone BLE advertising link model. Every number here is an ASSUMPTION for comparing designs,
 * not a measurement of real phones:
 *  - log-distance path loss with a crowd exponent (bodies absorb 2.4 GHz),
 *  - slow shadowing as an AR(1) process plus on/off body blocking,
 *  - fast fading per advertising event,
 *  - a soft reception threshold, about 10 dB better for LE Coded S=8 (the Bluetooth SIG quotes ~4x range),
 *  - interference as Gilbert-Elliott bursts that drop packets WITHOUT lowering RSSI,
 *  - a small chance the phone's scanner simply misses an advertisement.
 */
class RadioEnv(
    val pathLossExponent: Double,
    val shadowSigmaDb: Double,
    val shadowCorrelationS: Double,
    val blockOnPerS: Double,
    val blockOffPerS: Double,
    val blockLossDb: Double,
    val interferenceGoodToBad: Double,
    val interferenceBadToGood: Double,
    val interferenceLossBad: Double,
    val scanMiss: Double = 0.05,
    val txDbm: Double = 0.0,
    val referenceLossDb: Double = 40.0,
) {
    fun meanRssi(distanceM: Double) = txDbm - referenceLossDb - 10 * pathLossExponent * log10(distanceM.coerceAtLeast(1.0))

    companion object {
        const val SENSITIVITY_1M = -94.0
        const val SENSITIVITY_CODED = -104.0

        fun decodeProbability(rssi: Double, coded: Boolean): Double =
            1.0 / (1.0 + exp(-(rssi - if (coded) SENSITIVITY_CODED else SENSITIVITY_1M) / 1.5))

        fun random(r: Random) = RadioEnv(
            pathLossExponent = r.nextDouble(2.2, 3.4),
            shadowSigmaDb = r.nextDouble(3.0, 8.0),
            shadowCorrelationS = r.nextDouble(1.0, 6.0),
            blockOnPerS = r.nextDouble(0.0, 0.15),
            blockOffPerS = r.nextDouble(0.2, 1.0),
            blockLossDb = r.nextDouble(6.0, 18.0),
            interferenceGoodToBad = r.nextDouble(0.0, 0.08),
            interferenceBadToGood = r.nextDouble(0.1, 0.5),
            interferenceLossBad = r.nextDouble(0.3, 0.9),
        )
    }
}

/** Time-varying state of one directional link (shadowing, body block, interference). */
class LinkState(private val env: RadioEnv, private val r: Random) {
    private var shadow = gauss(r) * env.shadowSigmaDb
    private var blocked = false
    private var interfered = false

    /** Advances one slot and returns (received?, reported RSSI) for [events] advertising events in the slot. */
    fun slot(distanceM: Double, coded: Boolean, slotS: Double, events: Int = 2): Pair<Boolean, Int> {
        val rho = exp(-slotS / env.shadowCorrelationS)
        shadow = rho * shadow + sqrt(1 - rho * rho) * env.shadowSigmaDb * gauss(r)
        blocked = if (blocked) r.nextDouble() >= env.blockOffPerS * slotS else r.nextDouble() < env.blockOnPerS * slotS
        interfered = if (interfered) r.nextDouble() >= env.interferenceBadToGood else r.nextDouble() < env.interferenceGoodToBad
        val mean = env.meanRssi(distanceM) + shadow - if (blocked) env.blockLossDb else 0.0
        repeat(events) {
            val rssi = mean + 2.0 * gauss(r)
            val ok = r.nextDouble() < RadioEnv.decodeProbability(rssi, coded) &&
                !(interfered && r.nextDouble() < env.interferenceLossBad) && r.nextDouble() >= env.scanMiss
            if (ok) return true to rssi.toInt()
        }
        return false to 0
    }

    companion object {
        fun gauss(r: Random): Double = sqrt(-2 * ln(1 - r.nextDouble())) * kotlin.math.cos(2 * Math.PI * r.nextDouble())
    }
}
