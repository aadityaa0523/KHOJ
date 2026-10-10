package app.linkfill.core.policy

import java.io.InputStream
import kotlin.math.abs

/**
 * Recovery policy backed by a trained network that predicts, for each action, the cost of taking it:
 * seconds until the receiver holds this fix or a newer one, plus a small charge per extra byte.
 * It picks the cheapest action.
 *
 * Reject option (after Chow's rule, as used for BLE bit-error correction by Almeida et al., Sensors 2025):
 * when the best two predictions are too close, or the input is far outside the training data, the model
 * abstains and the [fallback] rules decide. Abstentions are counted and shown, never hidden.
 */
class MlRecoveryPolicy(
    val model: Mlp,
    private val fallback: RecoveryPolicy = DeterministicRecoveryPolicy(),
    private val rejectMarginS: Double = 0.05,
    private val maxAbsZ: Double = 6.0,
) : RecoveryPolicy {
    override val name = "On-device model (MLP)"

    override fun decide(f: RecoveryFeatures): Decision {
        val start = System.nanoTime()
        val x = f.vector()
        val outOfRange = model.normalise(x).any { abs(it) > maxAbsZ }
        val cost = model.forward(x)
        val elapsed = System.nanoTime() - start
        val order = cost.indices.sortedBy { cost[it] }
        val margin = cost[order[1]] - cost[order[0]]
        if (outOfRange || margin < rejectMarginS) {
            val d = fallback.decide(f)
            return d.copy(source = "ml-rejected (${if (outOfRange) "unfamiliar input" else "low margin"}) -> ${d.source}", margin = margin, inferenceNanos = elapsed)
        }
        return Decision(RecoveryAction.valueOf(model.outputNames[order[0]]), "ml", margin, elapsed)
    }

    companion object {
        const val RESOURCE = "/app/linkfill/core/policy/recovery_policy_v1.mlp"

        sealed interface Load {
            class Ready(val policy: MlRecoveryPolicy) : Load
            class Unavailable(val reason: String) : Load
        }

        /** Loads and checks the bundled model. A missing or incompatible model is reported, not faked. */
        fun load(stream: InputStream?): Load {
            if (stream == null) return Load.Unavailable("model file not bundled")
            val model = try {
                Mlp.read(stream)
            } catch (e: ModelFormatException) {
                return Load.Unavailable("model file rejected: ${e.message}")
            }
            if (model.featureNames != RecoveryFeatures.NAMES) return Load.Unavailable("model expects different input features")
            if (model.outputNames.toSet() != RecoveryAction.entries.map { it.name }.toSet() || model.outputNames.size != RecoveryAction.entries.size) {
                return Load.Unavailable("model predicts different actions")
            }
            return Load.Ready(MlRecoveryPolicy(model))
        }

        fun loadBundled(): Load = load(MlRecoveryPolicy::class.java.getResourceAsStream(RESOURCE))
    }
}
