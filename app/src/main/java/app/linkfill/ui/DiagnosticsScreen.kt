package app.linkfill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.estimate.LocationState
import app.linkfill.session.LinkSession
import app.linkfill.session.LossProfile
import app.linkfill.session.Phase
import app.linkfill.session.PolicyMode
import app.linkfill.session.Role
import app.linkfill.session.SessionState

@Composable
fun DiagnosticsScreen(state: SessionState, session: LinkSession, onBack: () -> Unit) {
    val s = state.stats
    Page("Diagnostics", onBack = onBack) {
        LossBanner(state.loss)
        InfoCard(title = "Connection") {
            KeyValue("Role", when (state.role) { Role.SENDER -> "Sharing"; Role.RECEIVER -> "Receiving"; Role.NONE -> "None" })
            KeyValue("State", state.phase.name.lowercase().replaceFirstChar { it.uppercase() })
            KeyValue("Discovery", when (state.phase) { Phase.ADVERTISING -> "Advertising"; Phase.DISCOVERING, Phase.RECONNECTING -> "Searching"; else -> "Off" })
            KeyValue("Peer", state.peerName ?: "-")
            KeyValue("Smoothed round trip / timeout", "${s.srttMs?.let { "$it ms" } ?: "-"} / ${s.rtoMs?.let { "$it ms" } ?: "-"}")
            KeyValue("Bytes sent / received", "${s.bytesSent} / ${s.bytesReceived}")
        }
        InfoCard(title = "Sending") {
            KeyValue("Messages created", "${s.messagesSent}")
            KeyValue("Handed to Nearby (transport)", "${s.handedToTransport}")
            KeyValue("Transport failures", "${s.transportFailures}")
            KeyValue("Acknowledged by the other app", "${s.acked}")
            KeyValue("Timeouts", "${s.timeouts}")
            KeyValue("Retransmissions (of them requested)", "${s.retransmissions} (${s.nackRetransmissions})")
            KeyValue("Redundant copies sent", "${s.redundantFixesSent}")
            KeyValue("Superseded by a newer location", "${s.superseded}")
            KeyValue("Marked stale (gave up)", "${s.markedStale}")
            KeyValue("Expired unacknowledged", "${s.expiredUnacked}")
            KeyValue("Waiting for acknowledgement", "${s.pending}")
        }
        InfoCard(title = "Receiving") {
            KeyValue("Messages accepted", "${s.messagesReceived}")
            KeyValue("Acknowledgements sent", "${s.acksSent}")
            KeyValue("Duplicates discarded", "${s.duplicatesDiscarded}")
            KeyValue("Missing messages detected", "${s.gapsDetected}")
            KeyValue("Missing right now", "${s.missingNow}")
            KeyValue("Recovery requests sent", "${s.recoveryRequestsSent}")
            KeyValue("Recovered by retransmission", "${s.recoveredByRetransmission}")
            KeyValue("Recovered from redundant copies", "${s.recoveredByRedundancy}")
            KeyValue("Arrived late, out of order", "${s.lateArrivals}")
            KeyValue("Settled by sender (superseded)", "${s.skippedSettled}")
            KeyValue("Not recovered", "${s.unrecovered}")
            KeyValue("Rejected messages", s.rejected)
            KeyValue("Last recovery method", s.lastRecovery)
        }
        InfoCard(title = "Location shown") {
            val age = state.peerFixReceivedAt?.let { state.now - it }
            val estimated = state.peerFix != null && age != null && age > 5_000 &&
                GapEstimator().estimate(state.peerFix, state.previousPeerFix, age) != null
            KeyValue("Current position is", when (GapEstimator.state(age, estimated)) {
                LocationState.FRESH -> "Measured (fresh)"
                LocationState.ESTIMATED -> "Estimated (dead reckoning)"
                LocationState.STALE -> "Measured, but stale"
                LocationState.UNAVAILABLE -> if (state.role == Role.SENDER) "Not applicable (sending)" else "Unavailable"
            })
        }
        InfoCard(title = "Recovery policy (AI)") {
            Text("Decides what to do when a location update is not acknowledged in time: retry now, wait and retry, add redundancy, or give up and send the newer one.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in PolicyMode.entries) {
                    FilterChip(
                        selected = state.policyMode == m,
                        onClick = { session.setPolicy(m) },
                        enabled = m != PolicyMode.MODEL || state.modelAvailable,
                        label = { Text(m.label) },
                    )
                }
            }
            KeyValue("AI inference available", if (state.modelAvailable) "Yes" else "No")
            Text(state.modelStatus, style = MaterialTheme.typography.bodySmall)
            KeyValue("Decisions", s.decisions)
            KeyValue("Model abstained (rules used)", "${s.mlFallbacks}")
            KeyValue("Average inference time on this phone", s.inferenceMicros?.let { "%.1f µs".format(it) } ?: "no decisions yet")
            KeyValue("Last decision", s.lastDecision ?: "-")
            if (state.decisionLog.isNotEmpty()) {
                Text("Recent decisions", style = MaterialTheme.typography.labelLarge)
                for (line in state.decisionLog) Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        InfoCard(title = "Test tools") {
            Text("Simulated loss drops messages inside this app, before they reach Nearby, so you can watch recovery work. Real radio loss is handled by Nearby itself and is not visible to apps.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in LossProfile.entries) FilterChip(selected = state.loss == p, onClick = { session.setLoss(p) }, label = { Text(p.label) })
            }
            KeyValue("Dropped by simulation", "${s.droppedBySimulation}")
            Text("Reed-Solomon test: send a 4 KB payload as 10 data + 4 parity chunks, once, with no retransmission.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = session::sendFecTest, enabled = state.phase == Phase.ACTIVE, modifier = Modifier.fillMaxWidth()) { Text("Send FEC test payload") }
            state.fecResult?.let { Text(it) }
        }
    }
}
