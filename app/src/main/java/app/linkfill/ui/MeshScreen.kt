package app.linkfill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.mesh.CombineMode
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.TransportKind
import app.linkfill.core.mesh.LinkMode
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.Role
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState

private enum class Sub { NONE, SCAN_BAND, SCAN_POLICE, REWIND, SHOW_BAND, FOUND_BAND }

/** Crew Mesh plus its sub-screens (scanner, wristband, found report, Rewind). Asks for the camera only when needed. */
@Composable
fun MeshScreen(state: MeshUiState, mesh: MeshController, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sub by rememberSaveable { mutableStateOf(Sub.NONE) }
    var bandText by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var afterCamera by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterCamera?.invoke() else scanError = "Camera permission is needed for this."
        afterCamera = null
    }
    fun withCamera(action: () -> Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) action()
        else { afterCamera = action; cameraLauncher.launch(android.Manifest.permission.CAMERA) }
    }
    fun openScanner(target: Sub) = withCamera { mesh.rewind.pause(); scanError = null; sub = target }
    fun closeScanner() { mesh.rewind.resume(); sub = Sub.NONE }

    androidx.activity.compose.BackHandler(enabled = sub != Sub.NONE) { if (sub == Sub.SCAN_BAND || sub == Sub.SCAN_POLICE) closeScanner() else sub = Sub.NONE }
    when (sub) {
        Sub.SCAN_BAND -> return ScanScreen("Scan wristband", scanError ?: "Point the camera at the QR code on the band.", onResult = { text ->
            if (Band.parse(text) != null) { bandText = text; mesh.rewind.resume(); sub = Sub.FOUND_BAND } else scanError = "That QR code is not a KHOJ wristband."
        }, onBack = ::closeScanner)
        Sub.SCAN_POLICE -> return ScanScreen("Police key", scanError ?: "Scan the police key QR issued by the authority.", onResult = { text ->
            if (mesh.unlockPolice(text)) closeScanner() else scanError = "Not a valid police key for this app."
        }, onBack = ::closeScanner)
        Sub.REWIND -> return RewindScreen(mesh.rewind.snapshot, state, onBack = { sub = Sub.NONE })
        Sub.SHOW_BAND -> Band.parse(bandText)?.let { return BandScreen(it, mesh, onBack = { sub = Sub.NONE }) }
        Sub.FOUND_BAND -> Band.parse(bandText)?.let { return FoundBandScreen(it, state, mesh, onBack = { sub = Sub.NONE }) }
        Sub.NONE -> Unit
    }
    MeshHome(
        state, mesh, onBack,
        onScanBand = { openScanner(Sub.SCAN_BAND) },
        onScanPolice = { openScanner(Sub.SCAN_POLICE) },
        onRewind = { sub = Sub.REWIND },
        onShowBand = { b -> bandText = b.toQrText(); sub = Sub.SHOW_BAND },
        needCamera = { withCamera { if (state.running) mesh.setPendant(true) } },
        scanError = scanError,
    )
}

@Composable
private fun MeshHome(
    state: MeshUiState,
    mesh: MeshController,
    onBack: () -> Unit,
    onScanBand: () -> Unit,
    onScanPolice: () -> Unit,
    onRewind: () -> Unit,
    onShowBand: (Band) -> Unit,
    needCamera: () -> Unit,
    scanError: String?,
) {
    var joinCode by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var rallyLabel by rememberSaveable { mutableStateOf("") }
    var confirmSos by rememberSaveable { mutableStateOf(false) }
    var codeError by rememberSaveable { mutableStateOf(false) }
    var relayUrl by rememberSaveable { mutableStateOf(state.relayUrl) }

    Page("KHOJ", onBack = onBack) {
        CountdownBanner(state, mesh)
        OfficialAlerts(state)
        Text(
            "Your crew's phones pass messages to each other, and every phone running KHOJ relays them further, " +
                "hop by hop, with no mobile network. Only your crew can read them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.error?.let { InfoCard(container = Color(0xFFFFE0DC)) { Text(it, color = Color(0xFF5F1410)) } }

        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(state.name, mesh::setName, label = { Text("Your name (max 12 letters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        RoleCard(state, mesh, onScanPolice)

        val code = state.crewCode
        if (code == null) {
            InfoCard(title = "Join or create a crew") {
                Text("Everyone in your family or group must use the same crew code. Share it in person.")
                Button(onClick = { mesh.createCrew() }, modifier = Modifier.fillMaxWidth()) { Text("Create a new crew") }
                OutlinedTextField(joinCode, { joinCode = it; codeError = false }, label = { Text("Crew code, e.g. ABCD-EFGH-JKLM-NPQR") }, singleLine = true, isError = codeError, modifier = Modifier.fillMaxWidth())
                if (codeError) Text("That is not a valid crew code.", color = MaterialTheme.colorScheme.error)
                FilledTonalButton(onClick = { codeError = !mesh.joinCrew(joinCode) }, modifier = Modifier.fillMaxWidth()) { Text("Join crew") }
                if (state.role != Role.MEMBER) Text("As a ${state.role.label.lowercase()} you can start without a crew.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.role == Role.MEMBER) return@Page
        } else {
            InfoCard(title = "Crew code") {
                Text(CrewKey.pretty(code), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("Show this to your crew so they can join. Anyone with the code can read your crew's messages.", style = MaterialTheme.typography.bodySmall)
                if (!state.running) TextButton(onClick = mesh::leaveCrew) { Text("Leave this crew") }
            }
            BandsCard(state, mesh, onShowBand)
        }
        PendantCard(state, mesh, onRewind, needCamera)
        if (state.running) {
            MemberHelp(state)
            if (state.role != Role.MEMBER) ResponderDesk(state, mesh, onScanBand)
        }

        InfoCard(title = "This phone") {
            KeyValue("Bluetooth 5 extended advertising", if (state.extendedSupported) "Yes" else "No (mesh unavailable)")
            KeyValue("Long range (LE Coded)", if (state.codedSupported) "Supported" else "Not supported")
            KeyValue("Max advertisement size", "${state.maxAdvertisingBytes} bytes")
        }

        if (!state.batteryUnrestricted) {
            InfoCard(title = "Let the mesh run in the background", container = Color(0xFFFFF1D6)) {
                Text("This phone's battery manager may pause the mesh when the screen is off, so you stop relaying for others. Allow KHOJ to run unrestricted.", color = Color(0xFF4A3000))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Button(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)))
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow background running") }
            }
        }

        InfoCard(title = "Radios (one mesh, many links)") {
            Text(
                "Like Google Nearby, KHOJ uses several radios behind one mesh. It picks the best link, and sends on several at once " +
                    "when the link is risky or the message is urgent. Duplicates are dropped automatically. Mobile data works as a bridge: " +
                    "any crew phone with signal uploads packets and downloads the crew's packets from far away.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (t in state.transports) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.kind.label, fontWeight = FontWeight.SemiBold)
                        val status = when {
                            !t.supported -> "Not supported on this phone"
                            !t.enabled -> "Off"
                            !t.up -> "Waiting: ${t.note}"
                            else -> "On · ${if (t.kind == TransportKind.INTERNET) "relay" else "${t.peers} peer${if (t.peers == 1) "" else "s"}"} · delivery %.0f%%".format(t.quality * 100)
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        Text("sent ${t.sent}" + (if (t.kind != TransportKind.BLE) ", acknowledged ${t.delivered}, failed ${t.failed}" else "") + ", received ${t.received} · ${t.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (t.kind != TransportKind.BLE) Switch(checked = t.enabled, onCheckedChange = { on -> mesh.setTransport(t.kind, on) }, enabled = t.supported)
                }
            }
            Text("How to combine", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in CombineMode.entries) FilterChip(selected = state.combineMode == m, onClick = { mesh.setCombineMode(m) }, label = { Text(m.label) })
            }
            OutlinedTextField(relayUrl, { relayUrl = it }, label = { Text("Relay server URL (for mobile data), e.g. https://...") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { mesh.setRelayUrl(relayUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Save relay URL") }
            if (state.lastChoice.isNotEmpty()) {
                Text("Last: ${state.lastChoice}", style = MaterialTheme.typography.bodySmall)
                state.combinedEstimate?.let { Text("Estimated chance at least one link delivers: %.0f%% (assumes links fail independently)".format(it * 100), style = MaterialTheme.typography.bodySmall) }
            }
        }

        if (!state.running) {
            Button(onClick = mesh::start, enabled = state.extendedSupported, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start mesh", fontWeight = FontWeight.SemiBold) }
            return@Page
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { confirmSos = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("SOS", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = mesh::stop, modifier = Modifier.weight(1f).height(56.dp)) { Text("Stop mesh") }
        }
        if (confirmSos) {
            AlertDialog(
                onDismissRequest = { confirmSos = false },
                title = { Text("Send SOS to your crew?") },
                text = { Text("Your position goes to every crew member the mesh can reach, and repeats until someone acknowledges it.") },
                confirmButton = { Button(onClick = { mesh.sendSos(); confirmSos = false }) { Text("Send SOS") } },
                dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Cancel") } },
            )
        }

        InfoCard(title = "Crew (${state.members.size})") {
            if (state.members.isEmpty()) Text("No crew member heard yet. They need the same crew code and the mesh started.")
            val me = state.myFix
            for (m in state.members) {
                val age = (state.now - m.heardAt) / 1000
                val where = me?.let { "%.0f m %s".format(GapEstimator.distanceM(it.latitude, it.longitude, m.lat, m.lon), arrow(GapEstimator.bearingDeg(it.latitude, it.longitude, m.lat, m.lon))) } ?: "your GPS pending"
                KeyValue(m.name, "$where · ${age}s ago · ${m.hops} hop${if (m.hops == 1) "" else "s"} · ${m.battery}%")
            }
        }

        InfoCard(title = "Meet or message") {
            OutlinedTextField(rallyLabel, { rallyLabel = it }, label = { Text("Meeting point name (e.g. Blue tent)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { mesh.sendRally(rallyLabel.ifBlank { "Here" }, 10); rallyLabel = "" }, modifier = Modifier.fillMaxWidth()) { Text("Everyone meet at my position in 10 min") }
            OutlinedTextField(message, { message = it }, label = { Text("Message to crew") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { mesh.sendText(message); message = "" }, modifier = Modifier.fillMaxWidth()) { Text("Send") }
        }

        InfoCard(title = "Feed") {
            if (state.feed.isEmpty()) Text("Nothing yet.")
            for (f in state.feed.take(15)) {
                val color = when (f.kind) { "SOS" -> MaterialTheme.colorScheme.error; "RALLY" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface }
                Text("${clockTime(f.at)}  ${f.fromName}: ${f.text}", color = color)
                if (f.mine && f.kind != "TEXT") Text(if (f.ackedBy.isEmpty()) "Waiting for a crew member to receive it..." else "Received by ${f.ackedBy.distinct().joinToString()}", style = MaterialTheme.typography.bodySmall)
                if (!f.mine) Text("via ${f.hops} relay${if (f.hops == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
            }
        }

        InfoCard(title = "Link and range") {
            KeyValue("Mode", state.mode.label + if (state.mode == LinkMode.HIGH && !state.coded) " (no LE Coded on this phone)" else "")
            KeyValue("Predicted loss risk (median neighbour)", state.risk?.let { "%.0f%%".format(it * 100) } ?: "no neighbours")
            Text("Loss predictor: ${state.predictorStatus}", style = MaterialTheme.typography.bodySmall)
            Text("Nearby KHOJ phones (any crew)", style = MaterialTheme.typography.labelLarge)
            if (state.neighbors.isEmpty()) Text("None in range.", style = MaterialTheme.typography.bodySmall)
            for (n in state.neighbors.take(8)) {
                KeyValue("Phone ${Integer.toHexString(n.id).takeLast(4)}", "${n.rssi} dBm · delivered %.0f%% · risk %s".format(n.pdr2s * 100, n.risk?.let { "%.0f%%".format(it * 100) } ?: "-"))
            }
            Text(state.stats, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("Link log: ${state.logRows} labelled rows in ${state.logPath} (for retraining on real phones).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun arrow(bearing: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((bearing + 22.5) % 360) / 45).toInt()]
