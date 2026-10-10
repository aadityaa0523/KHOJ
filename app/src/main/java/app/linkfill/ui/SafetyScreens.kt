package app.linkfill.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.Role
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState
import app.linkfill.mesh.RewindFrame

private val Warn = Color(0xFFFFF1D6)
private val WarnText = Color(0xFF4A3000)
private val Danger = Color(0xFFFFE0DC)
private val DangerText = Color(0xFF5F1410)

private fun whereFrom(s: MeshUiState, lat: Double, lon: Double): String {
    val me = s.myFix ?: return ""
    val d = GapEstimator.distanceM(me.latitude, me.longitude, lat, lon)
    val b = GapEstimator.bearingDeg(me.latitude, me.longitude, lat, lon)
    return "%.0f m %s".format(d, listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((b + 22.5) % 360) / 45).toInt()])
}

/** Big banner while a fall/panic countdown runs: one tap cancels. */
@Composable
fun CountdownBanner(s: MeshUiState, mesh: MeshController) {
    val kind = s.countdown ?: return
    val left = ((s.countdownEndsAt - s.now) / 1000).coerceAtLeast(0)
    InfoCard(container = Danger) {
        Text("${kind.label}: sending SOS in $left s", color = DangerText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("If you are fine, cancel now. Otherwise your crew, volunteers and police nearby will be alerted with your location.", color = DangerText)
        Button(onClick = mesh::cancelCountdown, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("I'm OK, cancel", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun OfficialAlerts(s: MeshUiState) {
    if (s.officials.isEmpty()) return
    InfoCard(title = "Official alerts (verified police signature)", container = Color(0xFFDDE8FF)) {
        for (o in s.officials.take(3)) Text("${clockTime(o.at)}  ${o.text}", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RoleCard(s: MeshUiState, mesh: MeshController, onScanPolice: () -> Unit) {
    InfoCard(title = "I am a...") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in Role.entries) FilterChip(selected = s.role == r, onClick = { if (r == Role.POLICE && !s.policeUnlocked) onScanPolice() else mesh.setRole(r) }, label = { Text(r.label) })
        }
        Text(
            when (s.role) {
                Role.MEMBER -> "Member: your crew sees you; volunteers and police see your SOS."
                Role.VOLUNTEER -> "Volunteer: your phone is part of the backbone (always relaying), you see SOS alerts nearby, and you can scan wristbands."
                Role.POLICE -> "Police: everything volunteers have, plus signed official alerts every phone trusts."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!s.policeUnlocked) Text("Police mode needs the police key QR (one-time scan).", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PendantCard(s: MeshUiState, mesh: MeshController, onRewind: () -> Unit, needCamera: () -> Unit) {
    InfoCard(title = "Pendant safety") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wear the phone as a pendant", fontWeight = FontWeight.SemiBold)
                Text("Detects a fall, or 4 hard shakes as a panic signal, then counts down 10 s (Hindi and English) before sending SOS. The camera keeps the last 3 minutes of photos, in memory only, for Rewind.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.pendant, onCheckedChange = { on -> if (on) needCamera(); mesh.setPendant(on) })
        }
        if (s.pendant) {
            Text(if (s.running) "Safety monitor on. Rewind: ${s.rewindFrames} photos kept." else "Starts with the mesh.", style = MaterialTheme.typography.bodySmall)
            s.rewindError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onRewind, enabled = s.rewindFrames > 0, modifier = Modifier.fillMaxWidth()) { Text("Rewind: what did the pendant see?") }
        }
    }
}

/** Family side: make wristbands for children or elders who carry no phone. */
@Composable
fun BandsCard(s: MeshUiState, mesh: MeshController, onShowBand: (Band) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var meet by rememberSaveable { mutableStateOf("") }
    InfoCard(title = "Wristbands for children and elders") {
        Text("For anyone in your family without a phone. Print the band. If a volunteer scans it, only this phone gets the \"found\" alert with their location. Anyone without the app can still read the name and number.", style = MaterialTheme.typography.bodySmall)
        for (b in s.bands) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${b.name}${if (b.age > 0) ", ${b.age}" else ""} · meet at ${b.meetingPoint}", modifier = Modifier.weight(1f))
                TextButton(onClick = { onShowBand(b) }) { Text("Show") }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name on band") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, label = { Text("Age") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp))
            OutlinedTextField(phone, { phone = it }, label = { Text("Family phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(meet, { meet = it }, label = { Text("Meeting point (e.g. Blue tent, Sector 4)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(
            onClick = { mesh.createBand(name, age.toIntOrNull() ?: 0, phone, meet)?.let { onShowBand(it); name = ""; age = "" } },
            enabled = name.isNotBlank() && phone.isNotBlank(), modifier = Modifier.fillMaxWidth(),
        ) { Text("Create wristband") }
    }
}

@Composable
fun BandScreen(band: Band, mesh: MeshController, onBack: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf<Boolean?>(null) }
    Page("Wristband: ${band.name}", onBack = onBack) {
        QrImage(band.toQrText(), "Wristband QR code for ${band.name}")
        Text("${band.name}${if (band.age > 0) ", ${band.age}" else ""}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("If lost, call: ${band.phone}")
        Text("Meet at: ${band.meetingPoint}")
        Button(onClick = { saved = saveBandImage(context, band) }, modifier = Modifier.fillMaxWidth()) { Text("Save printable band to Gallery") }
        saved?.let { Text(if (it) "Saved to Pictures/KHOJ. Print it, or show this screen to laminate." else "Could not save the image.") }
        TextButton(onClick = { mesh.deleteBand(band.bandId); onBack() }) { Text("Delete this band") }
    }
}

/** What a volunteer sees after scanning a band. */
@Composable
fun FoundBandScreen(band: Band, s: MeshUiState, mesh: MeshController, onBack: () -> Unit) {
    val context = LocalContext.current
    var note by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    Page("Found: ${band.name}", onBack = onBack) {
        InfoCard(container = MaterialTheme.colorScheme.primaryContainer) {
            Text("${band.name}${if (band.age > 0) ", ${band.age} years" else ""}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Family's meeting point: ${band.meetingPoint}")
            Text("Family phone: ${band.phone}")
        }
        OutlinedTextField(note, { note = it.take(40) }, label = { Text("Note for the family (e.g. safe, at Ghat 4 desk)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { mesh.reportFound(band, note); sent = true }, enabled = !sent, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (sent) "Alert sent" else "Alert the family with my location", fontWeight = FontWeight.SemiBold)
        }
        val r = s.sentReports.firstOrNull { it.name == band.name }
        if (sent) Text(if (r?.familyReceived == true) "The family's phone received it." else "Sent over the mesh. Repeating until the family's phone confirms (if it is in reach of the mesh).", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${band.phone}"))) }, modifier = Modifier.fillMaxWidth()) { Text("Call the family (if there is network)") }
        Text("Only the family's phone can read the alert. Nothing about the family's location is on the band.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Volunteer and police tools. */
@Composable
fun ResponderDesk(s: MeshUiState, mesh: MeshController, onScanBand: () -> Unit) {
    var description by rememberSaveable { mutableStateOf("") }
    var alert by rememberSaveable { mutableStateOf("") }
    InfoCard(title = "SOS nearby (${s.sosBoard.size})", container = if (s.sosBoard.isNotEmpty()) Danger else MaterialTheme.colorScheme.surfaceVariant) {
        if (s.sosBoard.isEmpty()) Text("No one has asked for help.")
        for (a in s.sosBoard.take(8)) {
            Text("${a.kind.label}: ${a.label}", fontWeight = FontWeight.Bold, color = DangerText)
            Text("${whereFrom(s, a.lat, a.lon)} · ${ago(s.now - a.at)}${if (a.responders.isNotEmpty()) " · responding: ${a.responders.joinToString()}" else ""}", style = MaterialTheme.typography.bodySmall)
            if (!a.iResponded) Button(onClick = { mesh.respond(a) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("I'm going") }
        }
    }
    InfoCard(title = "Found someone?") {
        Button(onClick = onScanBand, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Scan their wristband", fontWeight = FontWeight.SemiBold) }
        for (r in s.sentReports.take(5)) Text("${r.name}: ${if (r.familyReceived) "family's phone received the alert" else "alert sent, waiting for the family's phone"} (${ago(s.now - r.at)})", style = MaterialTheme.typography.bodySmall)
        Text("No wristband? Describe them; every KHOJ phone nearby shows it.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(description, { description = it.take(60) }, label = { Text("e.g. boy ~6, red shirt, says name Raju") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { mesh.registerFound(description); description = "" }, enabled = description.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Broadcast found-person notice") }
    }
    if (s.role == Role.POLICE) {
        InfoCard(title = "Official alert (signed)") {
            Text("Every KHOJ phone verifies your signature; fake alerts are dropped.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(alert, { alert = it.take(80) }, label = { Text("e.g. Ghat 4 closed, use Ghat 6") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { mesh.sendOfficial(alert); alert = "" }, enabled = alert.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send to everyone") }
        }
    }
}

/** Member side: who is coming for my SOS, and found-person notices to recognise family members. */
@Composable
fun MemberHelp(s: MeshUiState) {
    if (s.mySosResponders.isNotEmpty()) {
        InfoCard(title = "Help is coming", container = Color(0xFFDDF5E4)) { for (r in s.mySosResponders) Text("$r is on the way to you.") }
    }
    if (s.notices.isNotEmpty()) {
        InfoCard(title = "People found nearby (no wristband)") {
            for (n in s.notices.take(6)) Text("${clockTime(n.at)}  ${n.text} · ${whereFrom(s, n.lat, n.lon)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ScanScreen(title: String, hint: String, onResult: (String) -> Unit, onBack: () -> Unit) {
    Page(title, onBack = onBack) {
        Text(hint)
        QrScanner(onResult)
    }
}

@Composable
fun RewindScreen(frames: List<RewindFrame>, s: MeshUiState, onBack: () -> Unit) {
    Page("Rewind", onBack = onBack) {
        Text("What the pendant saw, newest first. These photos stay on this phone, in memory, and are erased when the mesh stops.", style = MaterialTheme.typography.bodySmall)
        if (frames.isEmpty()) Text("No photos yet.")
        for (f in frames) {
            val bmp = remember(f.atMs) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }
            InfoCard {
                Text("${ago(s.now - f.atMs)} · ${clockTime(f.atMs)}" + (if (f.lat != null && f.lon != null) " · ${whereFrom(s, f.lat, f.lon)} from here" else ""), fontWeight = FontWeight.SemiBold)
                bmp?.let { Image(it, contentDescription = "Pendant photo from ${ago(s.now - f.atMs)}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
            }
        }
    }
}

@Composable
fun WarnCard(text: String) { InfoCard(container = Warn) { Text(text, color = WarnText) } }
