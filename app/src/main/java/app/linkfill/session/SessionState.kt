package app.linkfill.session

import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.reliability.Delivery
import app.linkfill.core.sim.GilbertElliott
import kotlin.random.Random

enum class Role { NONE, SENDER, RECEIVER }

enum class Phase { IDLE, ADVERTISING, DISCOVERING, CONFIRMING, CONNECTING, SECURING, ACTIVE, RECONNECTING, ENDED }

/** Test-only loss injected at the application layer, so recovery can be shown on demand. */
enum class LossProfile(val label: String, private val params: DoubleArray?) {
    OFF("Off", null),
    MILD("Bursty, about 12% loss", doubleArrayOf(0.05, 0.30, 0.02, 0.70)),
    HARSH("Harsh bursty, about 31% loss", doubleArrayOf(0.12, 0.25, 0.05, 0.85));

    fun model(): GilbertElliott? = params?.let { GilbertElliott(it[0], it[1], it[2], it[3], Random.Default) }
}

enum class PolicyMode(val label: String) { MODEL("On-device model"), RULES("Deterministic rules") }

data class PeerItem(val endpointId: String, val name: String)

data class ConnectionRequest(val endpointId: String, val name: String, val digits: String, val incoming: Boolean)

/** A copy of the counters for the UI (the live counters are mutated on the main thread). */
data class StatsView(
    val messagesSent: Int = 0,
    val transmissions: Int = 0,
    val retransmissions: Int = 0,
    val nackRetransmissions: Int = 0,
    val acked: Int = 0,
    val timeouts: Int = 0,
    val markedStale: Int = 0,
    val superseded: Int = 0,
    val expiredUnacked: Int = 0,
    val redundantFixesSent: Int = 0,
    val messagesReceived: Int = 0,
    val acksSent: Int = 0,
    val duplicatesDiscarded: Int = 0,
    val gapsDetected: Int = 0,
    val recoveredByRetransmission: Int = 0,
    val recoveredByRedundancy: Int = 0,
    val lateArrivals: Int = 0,
    val unrecovered: Int = 0,
    val skippedSettled: Int = 0,
    val recoveryRequestsSent: Int = 0,
    val rejected: String = "none",
    val srttMs: Long? = null,
    val rtoMs: Long? = null,
    val pending: Int = 0,
    val missingNow: Int = 0,
    val handedToTransport: Int = 0,
    val transportFailures: Int = 0,
    val droppedBySimulation: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val decisions: String = "none yet",
    val mlFallbacks: Int = 0,
    val inferenceMicros: Double? = null,
    val lastRecovery: String = "none yet",
    val lastDecision: String? = null,
)

data class SessionState(
    val role: Role = Role.NONE,
    val phase: Phase = Phase.IDLE,
    val localName: String = "",
    val peers: List<PeerItem> = emptyList(),
    val request: ConnectionRequest? = null,
    val peerName: String? = null,
    val notice: String? = null,
    val myFix: LocationFix? = null,
    val peerFix: LocationFix? = null,
    val peerFixReceivedAt: Long? = null,
    val peerFixVia: Delivery? = null,
    val previousPeerFix: LocationFix? = null,
    val headingDeg: Float? = null,
    val lastAckAt: Long? = null,
    val stats: StatsView = StatsView(),
    val loss: LossProfile = LossProfile.OFF,
    val policyMode: PolicyMode = PolicyMode.RULES,
    val modelStatus: String = "",
    val modelAvailable: Boolean = false,
    val decisionLog: List<String> = emptyList(),
    val fecResult: String? = null,
    val now: Long = System.currentTimeMillis(),
)
