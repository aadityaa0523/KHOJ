package app.linkfill.core.policy

import kotlin.random.Random

/** What to do when a location update has not been acknowledged in time. */
enum class RecoveryAction { RETRY_NOW, WAIT_RETRY, ADD_REDUNDANCY, MARK_STALE }

/**
 * Everything the policy may look at. All of it is observable by the application itself
 * (acknowledgement timing, timeouts, retries, disconnects, its own queue); no radio-layer metrics.
 */
data class RecoveryFeatures(
    val rttMeanMs: Double,
    val rttVarMs: Double,
    val timeoutRate: Double,
    val retrySuccessRate: Double,
    val consecutiveTimeouts: Int,
    val disconnectsLast60s: Int,
    val queueLength: Int,
    val messageAgeMs: Long,
    val ttlMs: Long,
    val attempt: Int,
    val maxAttempts: Int,
    val msSinceLastAck: Long,
) {
    /** Scaled feature vector, in the order of [NAMES]. Changing this order requires retraining. */
    fun vector(): DoubleArray = doubleArrayOf(
        rttMeanMs / 1000.0,
        rttVarMs / 1000.0,
        timeoutRate,
        retrySuccessRate,
        consecutiveTimeouts / 5.0,
        disconnectsLast60s / 5.0,
        queueLength / 10.0,
        messageAgeMs.toDouble() / ttlMs.coerceAtLeast(1),
        attempt.toDouble() / maxAttempts.coerceAtLeast(1),
        msSinceLastAck.coerceAtMost(10_000) / 5000.0,
    )

    companion object {
        val NAMES = listOf(
            "rtt_mean_s", "rtt_var_s", "timeout_rate", "retry_success_rate", "consecutive_timeouts",
            "disconnects_60s", "queue_length", "age_fraction", "attempt_fraction", "since_last_ack",
        )
    }
}

/** A decision plus where it came from, for the diagnostics screen and the experiment report. */
data class Decision(
    val action: RecoveryAction,
    val source: String,
    val margin: Double? = null,
    val inferenceNanos: Long = 0,
)

interface RecoveryPolicy {
    val name: String
    fun decide(f: RecoveryFeatures): Decision
}

/** Always the same action: the "plain retry" baseline uses WAIT_RETRY (exponential backoff). */
class FixedPolicy(private val action: RecoveryAction, override val name: String) : RecoveryPolicy {
    override fun decide(f: RecoveryFeatures) = Decision(action, "fixed")
}

/** Hand-written rules. This is the baseline the model must beat, and its fallback when unsure. */
class DeterministicRecoveryPolicy : RecoveryPolicy {
    override val name = "Deterministic rules"

    override fun decide(f: RecoveryFeatures): Decision {
        val action = when {
            f.attempt >= f.maxAttempts - 1 || f.messageAgeMs > f.ttlMs * 0.8 -> RecoveryAction.MARK_STALE
            f.timeoutRate >= 0.4 && f.consecutiveTimeouts >= 2 -> RecoveryAction.ADD_REDUNDANCY
            f.consecutiveTimeouts >= 2 -> RecoveryAction.WAIT_RETRY
            else -> RecoveryAction.RETRY_NOW
        }
        return Decision(action, "deterministic")
    }
}

/** Uniformly random actions. Used only to collect unbiased training data in the simulator. */
class ExplorationPolicy(private val random: Random) : RecoveryPolicy {
    override val name = "Exploration (training only)"
    override fun decide(f: RecoveryFeatures) =
        Decision(RecoveryAction.entries[random.nextInt(RecoveryAction.entries.size)], "explore")
}
