package app.linkfill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.linkfill.session.LinkSession
import app.linkfill.session.Phase
import app.linkfill.session.SessionState

@Composable
fun SenderScreen(state: SessionState, session: LinkSession, onDiagnostics: () -> Unit) {
    Page("Share My Location", onBack = session::reset, action = { TextButton(onClick = onDiagnostics) { Text("Diagnostics") } }) {
        LossBanner(state.loss)
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        when (state.phase) {
            Phase.ADVERTISING -> InfoCard(title = "Waiting for someone to find you") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Ask the other person to open LinkFill AI and tap Find Someone. You appear to them as \"${state.localName}\" with a number.")
                Text("Your location is not being shared yet.", fontWeight = FontWeight.SemiBold)
            }
            Phase.CONFIRMING -> state.request?.let { r ->
                InfoCard(title = "${r.name} wants to see your location", container = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Check that this code is exactly the same on both phones:")
                    AuthCode(r.digits)
                    Text("Only share if the codes match and you know this person.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = session::confirmRequest, modifier = Modifier.weight(1f)) { Text("Codes match, share") }
                        OutlinedButton(onClick = session::declineRequest, modifier = Modifier.weight(1f)) { Text("Decline") }
                    }
                }
            }
            Phase.CONNECTING, Phase.SECURING -> InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(if (state.phase == Phase.CONNECTING) "Connecting..." else "Securing the connection...")
                }
            }
            Phase.ACTIVE, Phase.RECONNECTING -> Active(state, session, onDiagnostics)
            Phase.ENDED, Phase.IDLE -> InfoCard(title = "Not sharing") {
                Text(state.notice ?: "The session has ended. Nothing is being shared.")
                Button(onClick = session::reset, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
            Phase.DISCOVERING -> Unit
        }
    }
}

@Composable
private fun Active(state: SessionState, session: LinkSession, onDiagnostics: () -> Unit) {
    val reconnecting = state.phase == Phase.RECONNECTING
    StatusPill(
        if (reconnecting) "Disconnected, reconnecting" else "Sharing with ${state.peerName ?: "the other phone"}",
        if (reconnecting) Color(0xFFB26A00) else MaterialTheme.colorScheme.primary,
    )
    if (reconnecting) {
        InfoCard {
            Text("The link dropped. Your location is not reaching the other phone right now. LinkFill AI is trying to reconnect.")
            Text("Old positions are not queued up; the newest one is sent as soon as the link is back.", style = MaterialTheme.typography.bodySmall)
        }
    }
    InfoCard(title = "Your current location") {
        val f = state.myFix
        if (f == null) Text("Waiting for a GPS fix. Go outside or near a window if this takes long.")
        else {
            KeyValue("Latitude, longitude", "${coord(f.latitude)}, ${coord(f.longitude)}")
            KeyValue("Reported accuracy", "±%.0f m".format(f.accuracyM))
            KeyValue("Measured", "${clockTime(f.fixTimeMs)} (${ago(state.now - f.fixTimeMs)})")
        }
    }
    InfoCard(title = "Delivery") {
        val s = state.stats
        KeyValue("Last acknowledged by the other phone", state.lastAckAt?.let { ago(state.now - it) } ?: "not yet")
        KeyValue("Messages sent / acknowledged", "${s.messagesSent} / ${s.acked}")
        KeyValue("Retries (timeouts + requested)", "${s.retransmissions}  (${s.nackRetransmissions} requested)")
        KeyValue("Recovery decisions", s.decisions)
        KeyValue("Gave up, newer one sent instead", "${s.markedStale}")
    }
    Button(
        onClick = session::stop,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("Stop Sharing", fontWeight = FontWeight.SemiBold) }
    OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Open diagnostics") }
}
