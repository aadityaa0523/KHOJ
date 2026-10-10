package app.linkfill.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.estimate.LocationState
import app.linkfill.core.reliability.Delivery
import app.linkfill.session.LinkSession
import app.linkfill.session.Phase
import app.linkfill.session.SessionState
import java.util.Locale

private val estimator = GapEstimator()

@Composable
fun ReceiverScreen(state: SessionState, session: LinkSession, onDiagnostics: () -> Unit) {
    Page("Find Someone", onBack = session::reset, action = { TextButton(onClick = onDiagnostics) { Text("Diagnostics") } }) {
        LossBanner(state.loss)
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        when (state.phase) {
            Phase.DISCOVERING -> {
                InfoCard(title = "Looking for phones that are sharing") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("The other person must tap Share My Location. Stay within a few tens of metres while connecting.")
                }
                if (state.peers.isEmpty()) Text("No phones found yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (p in state.peers) {
                    InfoCard {
                        Row(Modifier.fillMaxWidth().clickable { session.connectTo(p) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text("Tap to ask for their location", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Connect", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Phase.CONFIRMING -> state.request?.let { r ->
                InfoCard(title = "Confirm ${r.name}", container = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Check that this code is exactly the same on both phones:")
                    AuthCode(r.digits)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = session::confirmRequest, modifier = Modifier.weight(1f)) { Text("Codes match") }
                        OutlinedButton(onClick = session::declineRequest, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    }
                }
            }
            Phase.CONNECTING, Phase.SECURING -> InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(if (state.phase == Phase.CONNECTING) "Waiting for the other phone..." else "Securing the connection...")
                }
            }
            Phase.ACTIVE, Phase.RECONNECTING -> Active(state, session, onDiagnostics)
            Phase.ENDED, Phase.IDLE -> InfoCard(title = "Not connected") {
                Text(state.notice ?: "The session has ended.")
                Text("The shared location has been cleared from this phone.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = session::reset, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
            Phase.ADVERTISING -> Unit
        }
    }
}

@Composable
private fun Active(state: SessionState, session: LinkSession, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val fix = state.peerFix
    val age = state.peerFixReceivedAt?.let { state.now - it }
    val estimate = if (fix != null && age != null && age > 5_000) estimator.estimate(fix, state.previousPeerFix, age) else null
    val locState = GapEstimator.state(age, estimate != null)
    val name = state.peerName ?: "the other phone"

    val (pillText, pillColor) = when {
        state.phase == Phase.RECONNECTING -> "Disconnected, reconnecting" to Color(0xFFB26A00)
        locState == LocationState.FRESH -> "Live: ${state.peerName ?: "connected"}" to MaterialTheme.colorScheme.primary
        locState == LocationState.ESTIMATED -> "Updates delayed: showing an estimate" to Color(0xFFB26A00)
        locState == LocationState.STALE -> "Stale: no recent update" to MaterialTheme.colorScheme.error
        else -> "Waiting for the first location" to MaterialTheme.colorScheme.secondary
    }
    StatusPill(pillText, pillColor)

    val me = state.myFix
    InfoCard(title = "Where is $name?") {
        if (fix == null) {
            Text("No location received yet. The sender's phone may still be waiting for GPS.")
        } else if (me != null) {
            val dist = GapEstimator.distanceM(me.latitude, me.longitude, fix.latitude, fix.longitude)
            val bearing = GapEstimator.bearingDeg(me.latitude, me.longitude, fix.latitude, fix.longitude)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val heading = state.headingDeg
                    if (heading != null) DirectionArrow((bearing - heading).toFloat())
                    Text("%.0f m".format(dist), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (heading != null) "Arrow points toward the last verified position. Hold the phone flat."
                        else "Bearing %.0f° from north (no compass on this phone).".format(bearing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Both positions have GPS error: theirs ±%.0f m, yours ±%.0f m.".format(fix.accuracyM, me.accuracyM), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text("Waiting for your own GPS fix to show distance and direction.")
        }
    }

    InfoCard(title = "Last verified update") {
        if (fix == null || age == null) Text("None yet.")
        else {
            KeyValue("Received", "${clockTime(state.peerFixReceivedAt!!)} (${ago(age)})")
            KeyValue("Latitude, longitude", "${coord(fix.latitude)}, ${coord(fix.longitude)}")
            KeyValue("Reported accuracy", "±%.0f m".format(fix.accuracyM))
            KeyValue("Status", when (locState) {
                LocationState.FRESH -> "Fresh (measured)"
                LocationState.ESTIMATED, LocationState.STALE -> "Stale (measured ${ago(age)})"
                LocationState.UNAVAILABLE -> "Unavailable"
            })
            KeyValue("Arrived by", when (state.peerFixVia) {
                Delivery.DIRECT -> "Direct delivery"
                Delivery.RETRANSMISSION -> "Recovered by retransmission"
                Delivery.REDUNDANCY -> "Recovered from a redundant copy"
                null -> "-"
            })
            TextButton(onClick = {
                val label = Uri.encode(name)
                val uri = Uri.parse("geo:${coord(fix.latitude)},${coord(fix.longitude)}?q=${coord(fix.latitude)},${coord(fix.longitude)}($label)")
                try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { }
            }) { Text("Open in a maps app (offline maps only if downloaded)") }
        }
    }

    if (estimate != null) {
        InfoCard(title = "Estimated position (NOT measured)", container = Color(0xFFFFF1D6)) {
            Text(
                "No verified update for ${ago(age!!)}. Assuming they kept moving the same way, they are probably within " +
                    "%.0f m of: %s, %s.".format(Locale.US, estimate.radiusM, coord(estimate.latitude), coord(estimate.longitude)),
                color = Color(0xFF4A3000),
            )
            Text("This is dead reckoning from the last two verified fixes, not machine learning. It disappears when the uncertainty passes 50 m.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4A3000))
        }
    }

    InfoCard(title = "Connection") {
        val s = state.stats
        KeyValue("State", if (state.phase == Phase.RECONNECTING) "Reconnecting" else "Connected, encrypted")
        KeyValue("Updates received", "${s.messagesReceived}")
        KeyValue("Missing updates detected", "${s.gapsDetected}")
        KeyValue("Recovered (retransmit / redundancy)", "${s.recoveredByRetransmission} / ${s.recoveredByRedundancy}")
        KeyValue("Duplicates discarded", "${s.duplicatesDiscarded}")
    }
    Button(
        onClick = session::stop,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("Disconnect", fontWeight = FontWeight.SemiBold) }
    OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Open diagnostics") }
}
