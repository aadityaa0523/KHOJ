package app.linkfill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.linkfill.session.Role
import app.linkfill.session.SessionState

@Composable
fun HomeScreen(
    state: SessionState,
    playServicesOk: Boolean,
    onName: (String) -> Unit,
    onShare: () -> Unit,
    onFind: () -> Unit,
    onDiagnostics: () -> Unit,
    onMesh: () -> Unit,
) {
    Page("LinkFill AI", action = { TextButton(onClick = onDiagnostics) { Text("Diagnostics") } }) {
        Text("Resilient location sharing when connectivity fails.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Share your live location with one nearby phone, directly between the two phones. " +
                "No mobile data, no account and no server. If messages go missing, the app detects it, recovers what it can, " +
                "and always tells you how old a position is.",
            style = MaterialTheme.typography.bodyLarge,
        )

        if (!playServicesOk) {
            InfoCard(title = "This phone is not supported") {
                Text("LinkFill AI needs Google Play services for nearby connections and location. It is missing or out of date on this phone.")
            }
        }

        OutlinedTextField(
            value = state.localName,
            onValueChange = onName,
            label = { Text("Your name (shown to the other phone)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare, enabled = playServicesOk, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Share My Location", fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(onClick = onFind, enabled = playServicesOk, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Find Someone", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onMesh, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("KHOJ Crew Mesh (multi-hop, longer range)", fontWeight = FontWeight.SemiBold)
            }
        }

        InfoCard(title = "Status") {
            Text(
                state.notice ?: when (state.role) {
                    Role.NONE -> "Ready. Nothing is being shared."
                    else -> "Ready."
                },
            )
            Text("AI recovery model: ${state.modelStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        InfoCard(title = "Privacy") {
            Text(
                "Your location is sent only after you accept one specific phone and both of you confirm the same code. " +
                    "It goes only to that phone, encrypted. Sharing stops when either of you presses Stop, after 2 hours, " +
                    "or if the phones stay disconnected for 2 minutes. Nothing is kept after the session ends.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Connection and recovery diagnostics") }
    }
}

@Composable
fun PermissionScreen(role: Role, denied: Boolean, onContinue: () -> Unit, onOpenSettings: () -> Unit, onCancel: () -> Unit) {
    Page("Permissions", onBack = onCancel) {
        Text(
            when (role) {
                Role.SENDER -> "To share your location with a nearby phone, LinkFill AI needs:"
                Role.RECEIVER -> "To find a nearby phone and show its location, LinkFill AI needs:"
                Role.NONE -> "To run the crew mesh (send, receive and relay over Bluetooth), KHOJ needs:"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        InfoCard {
            Text("Nearby devices (Bluetooth and Wi-Fi)", fontWeight = FontWeight.SemiBold)
            Text("To find the other phone and connect directly, without the internet.")
            Text("Location", fontWeight = FontWeight.SemiBold)
            Text(if (role == Role.SENDER) "To read your position from GPS so you can share it." else "To point you toward the other phone. On older Android versions, it is also required for nearby discovery.")
            Text("Notifications (optional)", fontWeight = FontWeight.SemiBold)
            Text("To show a notification with a Stop button whenever a session is active.")
        }
        Text("Nothing is shared until you confirm a specific phone.", style = MaterialTheme.typography.bodyMedium)
        if (denied) {
            InfoCard(title = "Some permissions were not granted") {
                Text("LinkFill AI cannot connect without them. You can allow them again, or open Settings if Android stopped asking.")
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (denied) "Try again" else "Continue") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}
