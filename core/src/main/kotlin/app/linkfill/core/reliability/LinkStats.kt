package app.linkfill.core.reliability

import app.linkfill.core.policy.Decision
import app.linkfill.core.policy.RecoveryAction
import app.linkfill.core.protocol.RejectReason

/**
 * Counters and link history for one pair of phones. It outlives a single session so that the recovery
 * policy still knows about recent disconnects after a reconnect. Not thread-safe: use from one thread.
 */
class LinkStats {
    // Sending
    var messagesSent = 0
    var transmissions = 0
    var retransmissions = 0
    var nackRetransmissions = 0
    var acked = 0
    var timeouts = 0
    var markedStale = 0
    var superseded = 0
    var expiredUnacked = 0
    var controlFailed = 0
    var redundantFixesSent = 0
    var bytesSent = 0L
    var droppedWhileDisconnected = 0

    // Receiving
    var messagesReceived = 0
    var acksSent = 0
    var duplicateAcks = 0
    var duplicatesDiscarded = 0
    var gapsDetected = 0
    var recoveredByRetransmission = 0
    var recoveredByRedundancy = 0
    var lateArrivals = 0
    var unrecovered = 0
    var skippedSettled = 0
    var recoveryRequestsSent = 0
    var bytesReceived = 0L
    val rejected = mutableMapOf<RejectReason, Int>()
    var lastRecoveryMethod: String = "none yet"

    // Policy
    val decisions = mutableMapOf<RecoveryAction, Int>()
    var mlRejectedToFallback = 0
    var inferenceNanosTotal = 0L
    var inferenceCount = 0
    var lastDecision: Decision? = null

    // Link history used as policy features
    var srttMs: Double? = null
        private set
    var rttVarMs: Double? = null
        private set
    var lastRttMs: Long? = null
        private set
    var consecutiveTimeouts = 0
        private set
    var disconnects = 0
        private set
    private val outcomes = ArrayDeque<Boolean>()      // true = acked, false = timed out
    private val retryOutcomes = ArrayDeque<Boolean>()
    private val disconnectTimes = ArrayDeque<Long>()

    fun reject(reason: RejectReason) { rejected[reason] = (rejected[reason] ?: 0) + 1 }

    /** RFC 6298 smoothed RTT. Only samples from first transmissions are used (Karn's algorithm). */
    fun recordRtt(sampleMs: Long) {
        lastRttMs = sampleMs
        val s = srttMs
        if (s == null) {
            srttMs = sampleMs.toDouble(); rttVarMs = sampleMs / 2.0
        } else {
            rttVarMs = 0.75 * rttVarMs!! + 0.25 * kotlin.math.abs(s - sampleMs)
            srttMs = 0.875 * s + 0.125 * sampleMs
        }
    }

    fun recordAck() { push(outcomes, true); consecutiveTimeouts = 0 }

    fun recordTimeout() { timeouts++; push(outcomes, false); consecutiveTimeouts++ }

    fun recordRetryResult(success: Boolean) = push(retryOutcomes, success)

    fun recordDisconnect(now: Long) { disconnects++; disconnectTimes.addLast(now); if (disconnectTimes.size > 20) disconnectTimes.removeFirst() }

    fun recordDecision(d: Decision) {
        decisions[d.action] = (decisions[d.action] ?: 0) + 1
        if (d.source.startsWith("ml-rejected")) mlRejectedToFallback++
        if (d.inferenceNanos > 0) { inferenceNanosTotal += d.inferenceNanos; inferenceCount++ }
        lastDecision = d
    }

    fun timeoutRate(): Double = if (outcomes.isEmpty()) 0.0 else outcomes.count { !it }.toDouble() / outcomes.size

    fun retrySuccessRate(): Double = if (retryOutcomes.isEmpty()) 0.5 else retryOutcomes.count { it }.toDouble() / retryOutcomes.size

    fun disconnectsSince(t: Long): Int = disconnectTimes.count { it >= t }

    private fun push(q: ArrayDeque<Boolean>, v: Boolean) { q.addLast(v); if (q.size > 20) q.removeFirst() }
}
