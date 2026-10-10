package app.linkfill.ui

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.R
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.Role
import app.linkfill.core.mesh.SosKind
import app.linkfill.mesh.HomeAlert
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState
import app.linkfill.requiredPermissions
import kotlinx.coroutines.launch

private val SosRed = Color(0xFFC62828)
private val Ok = Color(0xFF1B7F4B)

/** Languages offered, in their own script. Tag is the Android locale tag. */
val LANGUAGES = listOf("en" to "English", "hi" to "हिन्दी", "mr" to "मराठी", "te" to "తెలుగు", "bn" to "বাংলা", "ta" to "தமிழ்")

fun setAppLanguage(context: Context, tag: String) {
    context.getSharedPreferences("mesh", Context.MODE_PRIVATE).edit().putString("lang", tag).apply()
    if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    else (context as? android.app.Activity)?.recreate() // MainActivity applies the saved language in attachBaseContext
}

private fun allPermissions() = requiredPermissions() + Manifest.permission.CAMERA +
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

private fun missingPermissions(context: Context) = requiredPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

@Composable
private fun agoText(ms: Long): String = if (ms < 60_000) stringResource(R.string.secs, (ms / 1000).toInt()) else stringResource(R.string.mins, (ms / 60_000).toInt())

@Composable
private fun direction(s: MeshUiState, lat: Double, lon: Double): Pair<String, Float?> {
    if (!MeshController.hasPosition(lat, lon)) return stringResource(R.string.position_unknown) to null
    val me = s.myFix ?: return "" to null
    val d = GapEstimator.distanceM(me.latitude, me.longitude, lat, lon)
    val b = GapEstimator.bearingDeg(me.latitude, me.longitude, lat, lon)
    val word = stringArrayResource(R.array.compass)[(((b + 22.5) % 360) / 45).toInt()]
    return stringResource(R.string.metres_dir, d.toInt(), word) to s.headingDeg?.let { (b - it).toFloat() }
}

/** Screen scaffold with big title and generous spacing. */
@Composable
private fun Screen(title: String?, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (title != null || onBack != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackArrow(onBack)
            if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(72.dp)) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

// ------------------------------------------------------------------ entry

private enum class KSub { HOME, SCAN_BAND, FOUND_BAND, BANDS, SHOW_BAND, REWIND, SETTINGS, SCAN_FAMILY, SCAN_POLICE, FIND }

@Composable
fun KhojApp(s: MeshUiState, mesh: MeshController, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    if (!s.setupDone) { SetupFlow(s, mesh); return }
    LaunchedEffect(s.setupDone) { if (!s.running && missingPermissions(context).isEmpty()) mesh.start() }
    if (s.countdown != null) { CountdownScreen(s, mesh); return }

    var sub by rememberSaveable { mutableStateOf(KSub.HOME) }
    var bandText by rememberSaveable { mutableStateOf("") }
    var scanMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var afterCamera by remember { mutableStateOf<(() -> Unit)?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) afterCamera?.invoke(); afterCamera = null }
    val findUi by mesh.findUi.collectAsStateWithLifecycle()
    val ranging = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mesh.find.refreshCapabilities() }
    fun startFinding(id: Int) {
        // Android 16's ranging permission is asked when it is first needed; without it Find still works from GPS and Bluetooth.
        if (Build.VERSION.SDK_INT >= 36 && context.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) ranging.launch(Manifest.permission.RANGING)
        mesh.startFind(id); sub = KSub.FIND
    }
    fun scan(target: KSub) {
        val go = { mesh.rewind.pause(); scanMsg = null; sub = target }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) go() else { afterCamera = go; camera.launch(Manifest.permission.CAMERA) }
    }
    fun home() { mesh.rewind.resume(); sub = KSub.HOME }
    BackHandler(enabled = sub != KSub.HOME) { if (sub == KSub.SHOW_BAND) sub = KSub.BANDS else { if (sub == KSub.FIND) mesh.stopFind(); home() } }

    val notBand = stringResource(R.string.not_band)
    val badCode = stringResource(R.string.bad_code)
    when (sub) {
        KSub.FIND -> FindScreen(findUi, onStop = { mesh.stopFind(); home() }, needRangingPermission = Build.VERSION.SDK_INT >= 36 && context.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED,
            onAllowRanging = { ranging.launch(Manifest.permission.RANGING) }, onTrustAngle = mesh::setTrustAngle, onGroundTruth = mesh::setGroundTruth)
        KSub.HOME -> if (s.role == Role.MEMBER) MemberHome(s, mesh, onScan = { scan(KSub.SCAN_BAND) }, onBands = { sub = KSub.BANDS }, onRewind = { sub = KSub.REWIND }, onSettings = { sub = KSub.SETTINGS }, onFind = ::startFinding)
        else ResponderHome(s, mesh, onScan = { scan(KSub.SCAN_BAND) }, onSettings = { sub = KSub.SETTINGS })
        KSub.SCAN_BAND -> Screen(stringResource(R.string.scan_band), ::home) {
            Text(scanMsg ?: stringResource(R.string.scan_hint), fontSize = 18.sp)
            QrScanner { text -> if (Band.parse(text) != null) { bandText = text; mesh.rewind.resume(); sub = KSub.FOUND_BAND } else scanMsg = notBand }
        }
        KSub.SCAN_FAMILY -> Screen(stringResource(R.string.family_scan), ::home) {
            Text(scanMsg ?: stringResource(R.string.scan_hint), fontSize = 18.sp)
            QrScanner { text -> if (mesh.joinFromQr(text)) home() else scanMsg = badCode }
        }
        KSub.SCAN_POLICE -> Screen(stringResource(R.string.scan_police_key), ::home) {
            Text(scanMsg ?: stringResource(R.string.scan_hint), fontSize = 18.sp)
            QrScanner { text -> if (mesh.unlockPolice(text)) home() else scanMsg = badCode }
        }
        KSub.FOUND_BAND -> Band.parse(bandText)?.let { FoundBand(it, s, mesh, ::home) } ?: home()
        KSub.BANDS -> Bands(s, mesh, onShow = { b -> bandText = b.toQrText(); sub = KSub.SHOW_BAND }, onBack = ::home)
        KSub.SHOW_BAND -> Band.parse(bandText)?.let { ShowBand(it, mesh) { sub = KSub.BANDS } } ?: home()
        KSub.REWIND -> Rewind(s, mesh, ::home)
        KSub.SETTINGS -> Settings(s, mesh, onScanFamily = { scan(KSub.SCAN_FAMILY) }, onScanPolice = { scan(KSub.SCAN_POLICE) }, onAdvanced = onAdvanced, onBack = ::home)
    }
}

// ------------------------------------------------------------------ setup

private enum class Step { LANGUAGE, ABOUT, FAMILY, PERMISSIONS }

@Composable
private fun SetupFlow(s: MeshUiState, mesh: MeshController) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.LANGUAGE) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeError by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingPermissions(context).isEmpty()) { mesh.completeSetup(); mesh.start() }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    when (step) {
        Step.LANGUAGE -> Screen(null) {
            Text("KHOJ", fontSize = 48.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.tagline), fontSize = 20.sp)
            Text(stringResource(R.string.choose_language), style = MaterialTheme.typography.titleLarge)
            for ((tag, name) in LANGUAGES) BigButton(name, { setAppLanguage(context, tag); step = Step.ABOUT }, color = MaterialTheme.colorScheme.secondary)
        }
        Step.ABOUT -> Screen(stringResource(R.string.your_name), onBack = { step = Step.LANGUAGE }) {
            OutlinedTextField(s.name, mesh::setName, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.i_am), style = MaterialTheme.typography.titleLarge)
            BigButton(stringResource(R.string.role_member), { mesh.setRole(Role.MEMBER); step = Step.FAMILY })
            BigButton(stringResource(R.string.role_volunteer), { mesh.setRole(Role.VOLUNTEER); step = Step.PERMISSIONS }, color = MaterialTheme.colorScheme.secondary)
            Text(stringResource(R.string.helper_desc), style = MaterialTheme.typography.bodyMedium)
        }
        Step.FAMILY -> Screen(stringResource(R.string.family_group), onBack = { step = Step.ABOUT }) {
            val qr = mesh.crewQr()
            if (scanning) {
                Text(stringResource(R.string.scan_hint), fontSize = 18.sp)
                QrScanner { text -> if (mesh.joinFromQr(text)) { scanning = false; step = Step.PERMISSIONS } }
                BackArrow { scanning = false }
            } else if (qr != null) {
                Text(stringResource(R.string.family_show_qr), fontSize = 18.sp)
                QrImage(qr, stringResource(R.string.family_show_qr))
                BigButton(stringResource(R.string.next), { step = Step.PERMISSIONS })
            } else {
                BigButton(stringResource(R.string.family_create), { mesh.createCrew() })
                BigButton(stringResource(R.string.family_scan), {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true else camera.launch(Manifest.permission.CAMERA)
                }, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(code, { code = it; codeError = false }, label = { Text(stringResource(R.string.family_code)) }, singleLine = true, isError = codeError, modifier = Modifier.fillMaxWidth())
                if (codeError) Text(stringResource(R.string.bad_code), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { if (mesh.joinCrew(code)) step = Step.PERMISSIONS else codeError = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.join)) }
            }
        }
        Step.PERMISSIONS -> Screen(stringResource(R.string.permissions_title), onBack = { step = if (s.role == Role.MEMBER) Step.FAMILY else Step.ABOUT }) {
            Text(stringResource(R.string.permissions_body), fontSize = 18.sp)
            BigButton(stringResource(R.string.start_khoj), {
                if (missingPermissions(context).isEmpty()) { mesh.completeSetup(); mesh.start() } else perms.launch(allPermissions().toTypedArray())
            })
        }
    }
}

// ------------------------------------------------------------------ home (member)

@Composable
private fun StatusStrip(s: MeshUiState, mesh: MeshController) {
    val on = s.running
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background((if (on) Ok else SosRed).copy(alpha = 0.14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) Ok else SosRed))
        Spacer(Modifier.width(10.dp))
        Text((if (on) stringResource(R.string.status_on, s.members.count { s.now - it.heardAt < 45_000 }) else stringResource(R.string.status_off)) +
            if (on && s.lowPower) " · " + stringResource(R.string.low_power_on) else "", fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (!on) TextButton(onClick = mesh::start) { Text(stringResource(R.string.turn_on)) }
    }
}

@Composable
private fun Alerts(s: MeshUiState, mesh: MeshController, onRewind: (() -> Unit)?) {
    for (a in s.alerts) AlertCard(a, s, onDismiss = { mesh.dismissAlert(a.key) }, onRewind = if (a.kind == "LOST" && s.rewindFrames > 0) onRewind else null)
}

@Composable
private fun AlertCard(a: HomeAlert, s: MeshUiState, onDismiss: () -> Unit, onRewind: (() -> Unit)?) {
    val (bg, fg) = when (a.kind) {
        "FOUND", "HELP" -> Color(0xFFDDF5E4) to Color(0xFF0B3D21)
        "OFFICIAL" -> Color(0xFFDDE8FF) to Color(0xFF0D2A66)
        else -> Color(0xFFFFE0DC) to Color(0xFF5F1410)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("✕", color = fg, fontSize = 20.sp) }
        }
        Text(a.body, color = fg, fontSize = 17.sp)
        if (a.lat != null && a.lon != null) {
            val (_, angle) = direction(s, a.lat, a.lon)
            if (angle != null) DirectionArrow(angle, 72.dp)
        }
        Text(stringResource(R.string.seen_ago, agoText(s.now - a.at)), color = fg, fontSize = 14.sp)
        if (onRewind != null) OutlinedButton(onClick = onRewind) { Text(stringResource(R.string.open_rewind), color = fg) }
    }
}

/** While the SOS siren sounds: one huge button to silence it, and whether the position is still missing. */
@Composable
private fun SirenBar(s: MeshUiState, mesh: MeshController) {
    if (s.sosWaitingForGps && s.now - s.lastSosAt < 600_000) Text(stringResource(R.string.sos_no_gps), fontSize = 17.sp, color = SosRed, fontWeight = FontWeight.Bold)
    if (s.sirenOn) Button(onClick = mesh::stopSiren, colors = ButtonDefaults.buttonColors(containerColor = Color.Black), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(88.dp)) { Text(stringResource(R.string.stop_siren), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black) }
}

/** Press and hold for 2 seconds; the ring fills while holding. Releasing early cancels. */
@Composable
private fun HoldSos(onSos: () -> Unit, size: Int = 220) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.sos_hold)
    val short = stringResource(R.string.sos_hold_short)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(SosRed)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(2000, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSos()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            }
            .semantics { contentDescription = label; onClick(label) { onSos(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) { drawArc(Color.White, -90f, 360f * progress.value, false, style = Stroke(12.dp.toPx())) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS", color = Color.White, fontSize = (size / 4).sp, fontWeight = FontWeight.Black)
            Text(short, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

@Composable
private fun MemberHome(s: MeshUiState, mesh: MeshController, onScan: () -> Unit, onBands: () -> Unit, onRewind: () -> Unit, onSettings: () -> Unit, onFind: (Int) -> Unit) {
    Screen(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KHOJ", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings), fontSize = 18.sp) }
        }
        StatusStrip(s, mesh)
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.beingFoundBy?.let { who -> InfoCard(container = Color(0xFFDDE8FF)) {
            Text(stringResource(R.string.being_found_by, who), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D2A66))
            OutlinedButton(onClick = mesh::pauseBeingFound) { Text(stringResource(R.string.pause_being_found), color = Color(0xFF0D2A66)) }
        } }
        Alerts(s, mesh, onRewind)
        SirenBar(s, mesh)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HoldSos({ mesh.sendSos(SosKind.MANUAL) }) }
        Text(
            if (s.now - s.lastSosAt < 120_000) stringResource(R.string.sos_sent) else stringResource(R.string.sos_hint),
            fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            fontWeight = if (s.now - s.lastSosAt < 120_000) FontWeight.Bold else FontWeight.Normal,
        )
        for (r in s.mySosResponders) Text(stringResource(R.string.help_coming_body, r), color = Ok, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        InfoCard(title = stringResource(R.string.my_family)) {
            if (s.members.isEmpty()) Text(stringResource(R.string.family_empty), fontSize = 17.sp)
            for (m in s.members) {
                val (text, angle) = direction(s, m.lat, m.lon)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    if (angle != null) DirectionArrow(angle, 56.dp) else Spacer(Modifier.width(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("$text · ${stringResource(R.string.seen_ago, agoText(s.now - m.heardAt))}", fontSize = 16.sp)
                    }
                    Button(onClick = { onFind(m.originId) }, shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.find_button), fontWeight = FontWeight.Bold) }
                }
            }
        }
        InfoCard(title = stringResource(R.string.meet_point)) {
            s.meetPoint?.let { mp ->
                val (text, angle) = direction(s, mp.lat, mp.lon)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (angle != null) DirectionArrow(angle, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("${mp.label} (${mp.from}) · $text", fontSize = 18.sp)
                }
            }
            FilledTonalButton(onClick = mesh::meetHere, enabled = s.running && s.myFix != null, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.set_meet_here), fontSize = 17.sp) }
        }
        BigButton(stringResource(R.string.found_someone), onScan, color = MaterialTheme.colorScheme.secondary)
        BigButton(stringResource(R.string.child_bands), onBands, color = MaterialTheme.colorScheme.secondary)
        if (s.pendant) BigButton("${stringResource(R.string.rewind)} (${s.rewindFrames})", onRewind, color = MaterialTheme.colorScheme.secondary, enabled = s.rewindFrames > 0)
        if (s.notices.isNotEmpty()) InfoCard(title = stringResource(R.string.people_found)) {
            for (n in s.notices.take(5)) Text("${n.text} · ${direction(s, n.lat, n.lon).first}", fontSize = 16.sp)
        }
    }
}

// ------------------------------------------------------------------ home (volunteer / police)

@Composable
private fun ResponderHome(s: MeshUiState, mesh: MeshController, onScan: () -> Unit, onSettings: () -> Unit) {
    var description by rememberSaveable { mutableStateOf("") }
    var official by rememberSaveable { mutableStateOf("") }
    Screen(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KHOJ · ${stringResource(if (s.role == Role.POLICE) R.string.role_police else R.string.role_volunteer)}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings), fontSize = 18.sp) }
        }
        StatusStrip(s, mesh)
        Alerts(s, mesh, null)
        InfoCard(title = stringResource(R.string.sos_nearby), container = if (s.sosBoard.isNotEmpty()) Color(0xFFFFE0DC) else MaterialTheme.colorScheme.surfaceVariant) {
            if (s.sosBoard.isEmpty()) Text(stringResource(R.string.none_needing_help), fontSize = 17.sp)
            for (a in s.sosBoard.take(8)) {
                val (text, angle) = direction(s, a.lat, a.lon)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (angle != null) DirectionArrow(angle, 56.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${a.kind.label}: ${a.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5F1410))
                        Text("$text · ${stringResource(R.string.seen_ago, agoText(s.now - a.at))}", fontSize = 16.sp)
                        if (a.responders.isNotEmpty()) Text(a.responders.joinToString(), fontSize = 14.sp)
                    }
                }
                if (!a.iResponded) BigButton(stringResource(R.string.im_going), { mesh.respond(a) }, color = SosRed)
            }
        }
        BigButton(stringResource(R.string.scan_band), onScan)
        for (r in s.sentReports.take(3)) Text("${r.name}: ${stringResource(if (r.familyReceived) R.string.family_received else R.string.alert_waiting)}", fontSize = 16.sp)
        InfoCard(title = stringResource(R.string.found_no_band)) {
            OutlinedTextField(description, { description = it.take(60) }, label = { Text(stringResource(R.string.describe_person)) }, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { mesh.registerFound(description); description = "" }, enabled = description.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.send_everyone)) }
        }
        if (s.role == Role.POLICE) InfoCard(title = stringResource(R.string.police_alert)) {
            OutlinedTextField(official, { official = it.take(80) }, label = { Text(stringResource(R.string.police_alert_hint)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { mesh.sendOfficial(official); official = "" }, enabled = official.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.send_everyone)) }
        }
        SirenBar(s, mesh)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HoldSos({ mesh.sendSos(SosKind.MANUAL) }, size = 150) }
    }
}

// ------------------------------------------------------------------ countdown

@Composable
private fun CountdownScreen(s: MeshUiState, mesh: MeshController) {
    val left = ((s.countdownEndsAt - s.now) / 1000).coerceAtLeast(0).toInt()
    Column(Modifier.fillMaxSize().background(SosRed).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(if (s.countdown == SosKind.FALL) R.string.countdown_fall else R.string.countdown_panic), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("$left", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.countdown_body, left), color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
        Button(onClick = mesh::cancelCountdown, colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(120.dp)) {
            Text(stringResource(R.string.cancel_sos), color = SosRed, fontSize = 36.sp, fontWeight = FontWeight.Black)
        }
    }
}

// ------------------------------------------------------------------ bands, found, rewind, settings

@Composable
private fun Bands(s: MeshUiState, mesh: MeshController, onShow: (Band) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var meet by rememberSaveable { mutableStateOf("") }
    Screen(stringResource(R.string.child_bands), onBack) {
        for (b in s.bands) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onShow(b) }.padding(16.dp)) {
            Text("${b.name}${if (b.age > 0) ", ${b.age}" else ""}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("QR ›", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.make_band), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.band_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.band_age)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.band_phone)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(meet, { meet = it }, label = { Text(stringResource(R.string.band_meet)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        BigButton(stringResource(R.string.make_band), { mesh.createBand(name, age.toIntOrNull() ?: 0, phone, meet)?.let { onShow(it); name = ""; age = "" } }, enabled = name.isNotBlank() && phone.isNotBlank())
    }
}

@Composable
private fun ShowBand(band: Band, mesh: MeshController, onBack: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    Screen(band.name, onBack) {
        QrImage(band.toQrText(), band.name)
        Text(stringResource(R.string.call_number, band.phone), fontSize = 20.sp)
        Text(stringResource(R.string.meet_at, band.meetingPoint), fontSize = 20.sp)
        BigButton(if (saved) stringResource(R.string.band_saved) else stringResource(R.string.save_band), { saved = saveBandImage(context, band) })
        TextButton(onClick = { mesh.deleteBand(band.bandId); onBack() }) { Text(stringResource(R.string.delete)) }
    }
}

@Composable
private fun FoundBand(band: Band, s: MeshUiState, mesh: MeshController, onBack: () -> Unit) {
    val context = LocalContext.current
    var sent by rememberSaveable { mutableStateOf(false) }
    Screen(band.name, onBack) {
        Text("${band.name}${if (band.age > 0) ", ${band.age}" else ""}", fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.meet_at, band.meetingPoint), fontSize = 20.sp)
        Text(stringResource(R.string.call_number, band.phone), fontSize = 20.sp)
        BigButton(stringResource(R.string.alert_family), { mesh.reportFound(band, ""); sent = true }, enabled = !sent)
        if (sent) {
            val received = s.sentReports.firstOrNull { it.name == band.name }?.familyReceived == true
            Text(stringResource(if (received) R.string.family_received else R.string.alert_waiting), fontSize = 18.sp, color = if (received) Ok else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        }
        BigButton(stringResource(R.string.call_family), { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${band.phone}"))) }, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun Rewind(s: MeshUiState, mesh: MeshController, onBack: () -> Unit) {
    val frames = mesh.rewind.snapshot
    Screen(stringResource(R.string.rewind), onBack) {
        Text(stringResource(R.string.rewind_desc), fontSize = 16.sp)
        if (frames.isEmpty()) Text(stringResource(R.string.rewind_empty), fontSize = 17.sp)
        for (f in frames) {
            val bmp = remember(f.atMs) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }
            Text(stringResource(R.string.seen_ago, agoText(s.now - f.atMs)) + if (f.lat != null && f.lon != null) " · ${direction(s, f.lat, f.lon).first}" else "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) }
        }
    }
}

@Composable
private fun Settings(s: MeshUiState, mesh: MeshController, onScanFamily: () -> Unit, onScanPolice: () -> Unit, onAdvanced: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Screen(stringResource(R.string.settings), onBack) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((tag, name) in LANGUAGES.take(3)) OutlinedButton(onClick = { setAppLanguage(context, tag) }, modifier = Modifier.weight(1f)) { Text(name) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((tag, name) in LANGUAGES.drop(3)) OutlinedButton(onClick = { setAppLanguage(context, tag) }, modifier = Modifier.weight(1f)) { Text(name) }
        }
        OutlinedTextField(s.name, mesh::setName, label = { Text(stringResource(R.string.your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.i_am), style = MaterialTheme.typography.titleLarge)
        // One helper role: volunteers, staff, security and police share a screen; the official key only adds signed alerts.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (helper in listOf(false, true)) {
                val label = stringResource(if (helper) R.string.role_volunteer else R.string.role_member)
                val pick = { mesh.setRole(if (!helper) Role.MEMBER else if (s.policeUnlocked) Role.POLICE else Role.VOLUNTEER) }
                if ((s.role != Role.MEMBER) == helper) Button(onClick = pick, modifier = Modifier.weight(1f)) { Text(label, textAlign = TextAlign.Center) }
                else OutlinedButton(onClick = pick, modifier = Modifier.weight(1f)) { Text(label, textAlign = TextAlign.Center) }
            }
        }
        if (s.role != Role.MEMBER && !s.policeUnlocked) {
            OutlinedButton(onClick = onScanPolice, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_police_key)) }
            Text(stringResource(R.string.police_locked), style = MaterialTheme.typography.bodySmall)
        }
        InfoCard(title = stringResource(R.string.family_group)) {
            mesh.crewQr()?.let { QrImage(it, stringResource(R.string.family_show_qr)); Text(stringResource(R.string.family_show_qr)) }
            OutlinedButton(onClick = onScanFamily, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.family_scan)) }
            if (s.crewCode == null) OutlinedButton(onClick = { mesh.createCrew() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.family_create)) }
        }
        InfoCard(title = stringResource(R.string.circle_title)) {
            Text(stringResource(R.string.circle_desc), fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (m in app.linkfill.core.find.Circle.RADII_M) {
                    if (s.circleM == m) Button(onClick = { mesh.setCircle(m) }, modifier = Modifier.weight(1f)) { Text("$m m") }
                    else OutlinedButton(onClick = { mesh.setCircle(m) }, modifier = Modifier.weight(1f)) { Text("$m m") }
                }
            }
        }
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pendant_mode), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.pendant_desc), fontSize = 15.sp)
                }
                Switch(checked = s.pendant, onCheckedChange = mesh::setPendant)
            }
        }
        if (s.role == Role.MEMBER) InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.battery_saver), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.battery_saver_desc), fontSize = 15.sp)
                }
                Switch(checked = s.batterySaver, onCheckedChange = mesh::setBatterySaver)
            }
        }
        if (s.running) OutlinedButton(onClick = mesh::stop, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.stop_khoj)) }
        else BigButton(stringResource(R.string.turn_on), mesh::start)
        TextButton(onClick = onAdvanced) { Text(stringResource(R.string.advanced)) }
    }
}
