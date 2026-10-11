package app.linkfill.ui

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.linkfill.find.FindUi
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.linkfill.R
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.Role
import app.linkfill.core.mesh.SosKind
import app.linkfill.mesh.CrewMember
import app.linkfill.mesh.HomeAlert
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState
import app.linkfill.requiredPermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Languages offered, in their own script. Tag is the Android locale tag. */
val LANGUAGES = listOf("en" to "English", "hi" to "हिन्दी", "mr" to "मराठी", "te" to "తెలుగు", "bn" to "বাংলা", "ta" to "தமிழ்")

fun setAppLanguage(context: Context, tag: String) {
    context.getSharedPreferences("mesh", Context.MODE_PRIVATE).edit().putString("lang", tag).apply()
    if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    else (context as? android.app.Activity)?.recreate() // MainActivity applies the saved language in attachBaseContext
}

internal fun allPermissions() = requiredPermissions() + Manifest.permission.CAMERA +
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

internal fun missingPermissions(context: Context) = requiredPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

@Composable
private fun agoText(ms: Long): String = if (ms < 60_000) stringResource(R.string.secs, (ms / 1000).toInt()) else stringResource(R.string.mins, (ms / 60_000).toInt())

@Composable
private fun compassWord(bearing: Double) = stringArrayResource(R.array.compass)[(((bearing + 22.5) % 360) / 45).toInt()]

/** The direction is the arrow drawn beside the distance; a compass word ("NE") appears only on a phone with no compass. */
@Composable
private fun dirWord(bearing: Double, s: MeshUiState) = if (s.headingDeg != null) "" else " " + compassWord(bearing)

// ------------------------------------------------------------------ entry

private enum class KSub { NONE, SCAN_BAND, FOUND_BAND, BANDS, SHOW_BAND, REWIND, SCAN_FAMILY, SCAN_POLICE, FIND, BATTERY_GUIDANCE, SOS }

private enum class Tab(val icon: KI, val label: Int) {
    RADAR(KI.RADAR, R.string.tab_radar), ACTIVITY(KI.BELL, R.string.tab_activity), GROUP(KI.GROUP, R.string.tab_group), HELP(KI.HELP, R.string.tab_help),
    BOARD(KI.SIREN, R.string.tab_board), SCAN(KI.SCAN, R.string.tab_scan), ALERTS(KI.BELL, R.string.tab_alerts), SETTINGS(KI.SETTINGS, R.string.settings),
}

private val MEMBER_TABS = listOf(Tab.RADAR, Tab.ACTIVITY, Tab.GROUP, Tab.HELP, Tab.SETTINGS)
private val HELPER_TABS = listOf(Tab.BOARD, Tab.SCAN, Tab.ALERTS, Tab.SETTINGS)

@Composable
fun KhojApp(s: MeshUiState, mesh: MeshController, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mesh", Context.MODE_PRIVATE) }
    var night by rememberSaveable { mutableStateOf(prefs.getBoolean("night", false)) }
    val setNight = { on: Boolean -> night = on; prefs.edit().putBoolean("night", on).apply() }
    CallTheme(night) {
        var splash by rememberSaveable { mutableStateOf(true) }
        if (splash) { Splash { splash = false }; return@CallTheme }
        if (!s.setupDone) { SetupFlow(s, mesh); return@CallTheme }
        LaunchedEffect(s.setupDone) { if (!s.running && missingPermissions(context).isEmpty()) mesh.start() }
        if (s.countdown != null) { SosScreen(s, mesh, onDismiss = mesh::cancelCountdown); return@CallTheme }
        s.lit?.let { LitScreen(it, onStop = mesh::stopLight); return@CallTheme }
        AlarmBeat(s.alerts) { Main(s, mesh, night, setNight, onAdvanced) }
    }
}

@Composable
private fun Main(s: MeshUiState, mesh: MeshController, night: Boolean, setNight: (Boolean) -> Unit, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    val tabs = if (s.role == Role.MEMBER) MEMBER_TABS else HELPER_TABS
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()
    var sub by rememberSaveable { mutableStateOf(KSub.NONE) }
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
    fun home() { mesh.rewind.resume(); sub = KSub.NONE }
    BackHandler(enabled = sub != KSub.NONE || tab != tabs.first()) {
        when (sub) {
            KSub.NONE -> tab = tabs.first()
            KSub.SHOW_BAND -> sub = KSub.BANDS
            KSub.FIND -> home()
            KSub.SOS -> home()
            else -> home()
        }
    }
    val notBand = stringResource(R.string.not_band)
    val badCode = stringResource(R.string.bad_code)
    when (sub) {
        KSub.FIND -> FindScreen(findUi, onStop = { mesh.stopFind(); home() }, needRangingPermission = Build.VERSION.SDK_INT >= 36 && context.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED,
            onAllowRanging = { ranging.launch(Manifest.permission.RANGING) }, onTrustAngle = mesh::setTrustAngle, onGroundTruth = mesh::setGroundTruth, onBack = ::home,
            onLight = { m, d -> findUi?.let { f -> mesh.sendLight(f.targetId, m, d, f.targetName) } })
        KSub.SCAN_BAND -> ScanPage(stringResource(R.string.scan_band), scanMsg ?: stringResource(R.string.scan_hint), ::home) { text ->
            if (Band.parse(text) != null) { bandText = text; mesh.rewind.resume(); sub = KSub.FOUND_BAND } else scanMsg = notBand
        }
        KSub.SCAN_FAMILY -> ScanPage(stringResource(R.string.family_scan), scanMsg ?: stringResource(R.string.scan_hint), ::home) { text -> if (mesh.joinFromQr(text)) home() else scanMsg = badCode }
        KSub.SCAN_POLICE -> ScanPage(stringResource(R.string.scan_police_key), scanMsg ?: stringResource(R.string.scan_hint), ::home) { text -> if (mesh.unlockPolice(text)) home() else scanMsg = badCode }
        KSub.FOUND_BAND -> Band.parse(bandText)?.let { FoundBandPage(it, s, mesh, ::home) } ?: home()
        KSub.BANDS -> BandsPage(s, mesh, onShow = { b -> bandText = b.toQrText(); sub = KSub.SHOW_BAND }, onBack = ::home)
        KSub.SHOW_BAND -> Band.parse(bandText)?.let { ShowBandPage(it, mesh) { sub = KSub.BANDS } } ?: home()
        KSub.REWIND -> RewindPage(s, mesh, ::home)
        KSub.BATTERY_GUIDANCE -> BatteryGuidancePage(s, ::home)
        KSub.SOS -> SosScreen(s, mesh, onDismiss = ::home)
        KSub.NONE -> Shell(s, mesh, tabs, tab, { tab = it }, onOpenSos = { sub = KSub.SOS }, findUi = findUi, onOpenFind = { sub = KSub.FIND }, onStopFind = { mesh.stopFind() }) {
            when (tab) {
                Tab.RADAR -> NearbyScreen(s, mesh, findUi?.targetId, setNight, onFind = ::startFinding, onSos = { sub = KSub.SOS }, onRewind = { sub = KSub.REWIND },
                    onBatteryGuidance = { sub = KSub.BATTERY_GUIDANCE },
                    onOpen = { t -> when (t) { app.linkfill.core.voice.VoiceTarget.GROUP -> tab = Tab.GROUP; app.linkfill.core.voice.VoiceTarget.SETTINGS -> tab = Tab.SETTINGS; app.linkfill.core.voice.VoiceTarget.BANDS -> sub = KSub.BANDS; app.linkfill.core.voice.VoiceTarget.REWIND -> sub = KSub.REWIND; app.linkfill.core.voice.VoiceTarget.RADAR -> tab = Tab.RADAR } })
                Tab.ACTIVITY -> ActivityTab(s, mesh, night, setNight, onFind = ::startFinding, onSos = { sub = KSub.SOS }, onRewind = { sub = KSub.REWIND },
                    onOpen = { t -> when (t) { app.linkfill.core.voice.VoiceTarget.GROUP -> tab = Tab.GROUP; app.linkfill.core.voice.VoiceTarget.SETTINGS -> tab = Tab.SETTINGS; app.linkfill.core.voice.VoiceTarget.BANDS -> sub = KSub.BANDS; app.linkfill.core.voice.VoiceTarget.REWIND -> sub = KSub.REWIND; app.linkfill.core.voice.VoiceTarget.RADAR -> tab = Tab.RADAR } })
                Tab.GROUP -> GroupTab(s, mesh, onScanFamily = { scan(KSub.SCAN_FAMILY) })
                Tab.HELP -> HelpTab(s, mesh, onScanBand = { scan(KSub.SCAN_BAND) }, onBands = { sub = KSub.BANDS }, onRewind = { sub = KSub.REWIND })
                Tab.BOARD -> BoardTab(s, mesh, night, setNight)
                Tab.SCAN -> HelperScanTab(s, mesh, onScanBand = { scan(KSub.SCAN_BAND) })
                Tab.ALERTS -> HelperAlertsTab(s, mesh)
                Tab.SETTINGS -> SettingsTab(s, mesh, night, setNight, onScanFamily = { scan(KSub.SCAN_FAMILY) }, onScanPolice = { scan(KSub.SCAN_POLICE) }, onBatteryGuidance = { sub = KSub.BATTERY_GUIDANCE }, onAdvanced = onAdvanced)
            }
        }
    }
}

/** The frame every tab lives in: the ground, the tab, SOS status, a mini bar for a Find in progress, and the floating glass tab bar. */
@Composable
private fun Shell(
    s: MeshUiState, mesh: MeshController, tabs: List<Tab>, tab: Tab, onTab: (Tab) -> Unit, onOpenSos: () -> Unit,
    findUi: FindUi?, onOpenFind: () -> Unit, onStopFind: () -> Unit, content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOpenSos provides onOpenSos) {
        CallBackground {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Box(Modifier.weight(1f)) { content() }
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SosStatus(s, mesh)
                    findUi?.let { f -> FindMiniBar(f, s.members.indexOfFirst { it.originId == f.targetId }.coerceAtLeast(0), onOpenFind, onStopFind) }
                    GlassTabBar(tabs.map { t -> GlassTab(t.icon, stringResource(t.label), t == tab) { onTab(t) } })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ splash

/** Loader: the wordmark calls out once, its sound arcs rippling across the screen, while the mesh starts. */
@Composable
private fun Splash(onDone: () -> Unit) {
    val p = LocalCall.current
    val still = rememberReducedMotion()
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (still) delay(500) else ripple.animateTo(1f, tween(1500, easing = LinearEasing))
        onDone()
    }
    CallBackground {
        Canvas(Modifier.fillMaxSize()) {
            soundArcs(center, size.maxDimension * 0.75f, ripple.value * 2.4f % 1f, listOf(p.gold, p.fire, p.violet, p.sky), count = 6, width = 3f)
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("KHOJ", fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, letterSpacing = 8.sp, color = p.ink,
                modifier = Modifier.graphicsLayer { alpha = if (still) 1f else 0.4f + 0.6f * minOf(1f, ripple.value * 3f) })
            Text("खोज", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = p.gold)
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
        }
    }
}

// ------------------------------------------------------------------ alarm beat

/**
 * A new emergency (someone lost, out of the circle, or an SOS) inverts the whole frame for one beat, so it cannot be
 * missed even at a glance; then the calm alarm card carries the detail. Off when the phone asks for no animation.
 */
@Composable
private fun AlarmBeat(alerts: List<HomeAlert>, content: @Composable () -> Unit) {
    val still = rememberReducedMotion()
    val seen = remember { mutableSetOf<String>().apply { addAll(alerts.map { it.key }) } }
    val beat = remember { Animatable(0f) }
    LaunchedEffect(alerts.map { it.key }) {
        val fresh = alerts.filter { it.key !in seen && (it.kind == "LOST" || it.kind == "SOS") }
        seen += alerts.map { it.key }
        if (fresh.isNotEmpty() && !still) { beat.snapTo(1f); delay(380); beat.snapTo(0f) }
    }
    val on = beat.value > 0.5f
    Box(Modifier.fillMaxSize().graphicsLayer {
        renderEffect = if (on && Build.VERSION.SDK_INT >= 31) RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))).asComposeRenderEffect() else null
    }) {
        content()
        if (on && Build.VERSION.SDK_INT < 31) Box(Modifier.fillMaxSize().background(LocalCall.current.alarm.copy(alpha = 0.85f)))
    }
}

// ------------------------------------------------------------------ radar tab (members)

@Composable
private fun RadarHeader(s: MeshUiState, mesh: MeshController, night: Boolean, setNight: (Boolean) -> Unit, onSos: () -> Unit = {}, onFind: (Int) -> Unit = {}, onOpen: (app.linkfill.core.voice.VoiceTarget) -> Unit = {}, modifier: Modifier = Modifier) {
    val p = LocalCall.current
    var voice by remember { mutableStateOf(false) }
    if (voice) VoiceDialog(s, mesh, setNight = setNight, onFind = onFind, onSos = onSos, onOpen = onOpen, onDismiss = { voice = false })
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("KHOJ", fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = 4.sp, color = p.ink)
            Text("खोज", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = p.gold, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            Spacer(Modifier.weight(1f))
            GlassIconButton(KI.MIC, stringResource(R.string.voice_mic)) { voice = true }
            Spacer(Modifier.width(8.dp))
            SosPillButton(onClick = onSos)
            Spacer(Modifier.width(8.dp))
            GlassIconButton(if (night) KI.SUN else KI.MOON, stringResource(if (night) R.string.theme_day else R.string.theme_night)) { setNight(!night) }
        }
        val live = s.members.count { s.now - it.heardAt < 45_000 }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LivePill((if (s.running) stringResource(R.string.status_on, live) else stringResource(R.string.status_off)) +
                if (s.running && s.lowPower) " · " + stringResource(R.string.low_power_on) else "", if (s.running) p.ok else p.alarm)
            if (!s.running) { Spacer(Modifier.width(8.dp)); FilledTonalButton(onClick = mesh::start, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.turn_on)) } }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Small direction arrow in the person's heat colour, relative to where the phone faces. */
@Composable
internal fun MiniArrow(angleDeg: Float, color: Color) {
    Canvas(Modifier.size(34.dp).semantics { contentDescription = "Direction ${angleDeg.roundToInt()} degrees" }) {
        rotate(angleDeg) {
            val w = size.width; val h = size.height
            drawPath(Path().apply { moveTo(w / 2, h * 0.08f); lineTo(w * 0.82f, h * 0.84f); lineTo(w / 2, h * 0.64f); lineTo(w * 0.18f, h * 0.84f); close() }, color)
        }
    }
}

/** Alarms are solid and calm: one colour per kind, a big title, the place, an arrow when it is known, a 48 dp close. */
@Composable
internal fun AlarmCard(a: HomeAlert, s: MeshUiState, onDismiss: () -> Unit, onRewind: (() -> Unit)?) {
    val p = LocalCall.current
    val bg = when (a.kind) { "FOUND", "HELP" -> p.ok; "OFFICIAL" -> p.violet; else -> p.alarm }
    val fg = if (a.kind == "FOUND" || a.kind == "HELP") Color(0xFF032B1A) else Color.White
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KIcon(when (a.kind) { "OFFICIAL" -> KI.SHIELD; "FOUND", "HELP" -> KI.CHECK; else -> KI.SIREN }, fg, 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(a.title, color = fg, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { KIcon(KI.CLOSE, fg, 22.dp, stringResource(R.string.dismiss)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            Text(a.body, color = fg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val me = s.myFix
            if (a.lat != null && a.lon != null && me != null && s.headingDeg != null)
                MiniArrow((GapEstimator.bearingDeg(me.latitude, me.longitude, a.lat, a.lon) - s.headingDeg).toFloat(), fg)
        }
        Text(stringResource(R.string.seen_ago, agoText(s.now - a.at)), color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
        if (onRewind != null) OutlinedButton(onClick = onRewind, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_rewind), color = fg) }
    }
}

// ------------------------------------------------------------------ helper board

/** Helpers: people asking for help nearby, on the same radar, then as rows ranked by distance with "I'm going". */
@Composable
private fun BoardTab(s: MeshUiState, mesh: MeshController, night: Boolean, setNight: (Boolean) -> Unit) {
    val p = LocalCall.current
    val me = s.myFix
    val pts = s.sosBoard.filter { MeshController.hasPosition(it.lat, it.lon) }
        .map { a -> RadarPoint((a.originId.toLong() shl 32) or (a.packetId.toLong() and 0xFFFFFFFFL), a.label, a.lat, a.lon, false, false, a.kind.label) }
    val placed = if (me != null) place(pts, me.latitude, me.longitude) else emptyList()
    fun dist(lat: Double, lon: Double) = if (me != null && MeshController.hasPosition(lat, lon)) GapEstimator.distanceM(me.latitude, me.longitude, lat, lon) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RadarHeader(s, mesh, night, setNight)
        for (a in s.alerts) AlarmCard(a, s, onDismiss = { mesh.dismissAlert(a.key) }, onRewind = null)
        Text(stringResource(R.string.sos_nearby), style = MaterialTheme.typography.headlineSmall, color = p.ink)
        if (s.sosBoard.isEmpty()) Text(stringResource(R.string.none_needing_help), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
        else Glass(rim = heatRim(), padding = PaddingValues(14.dp)) {
            GuideWords(placed, stringResource(R.string.nearest), stringResource(R.string.farthest))
            CallRadar(placed, s.headingDeg, null, me != null, stringResource(R.string.find_note_my_loc_missing), "", stringResource(R.string.you), onTap = {}, modifier = Modifier.fillMaxWidth().height(300.dp))
        }
        for (a in s.sosBoard.sortedBy { dist(it.lat, it.lon) ?: Double.MAX_VALUE }) {
            val d = dist(a.lat, a.lon)
            val b = if (me != null && d != null) GapEstimator.bearingDeg(me.latitude, me.longitude, a.lat, a.lon) else null
            Glass(padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeatDisc(a.label, heat(p, d), 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${a.kind.label}: ${a.label}", style = MaterialTheme.typography.titleLarge, color = p.ink)
                        Text((if (d != null && b != null) "${d.roundToInt()} m${dirWord(b, s)}" else stringResource(R.string.position_unknown)) + " · " +
                            stringResource(R.string.seen_ago, agoText(s.now - a.at)), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                        if (a.responders.isNotEmpty()) Text(a.responders.joinToString(), style = MaterialTheme.typography.labelLarge, color = p.ok)
                    }
                    if (b != null && s.headingDeg != null) MiniArrow((b - s.headingDeg).toFloat(), heat(p, d))
                }
                if (!a.iResponded) { Spacer(Modifier.height(10.dp)); CallButton(stringResource(R.string.im_going), { mesh.respond(a) }, icon = KI.WALK, tone = Tone.ALARM) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ SOS

/**
 * The SOS dock under every tab. Idle: a red pill you hold for 2 seconds; the fill sweeps across while you hold, and
 * letting go early cancels. After sending: the siren control and who is coming. Plain on purpose: not a wow moment.
 */
@Composable
private fun SosDock(s: MeshUiState, mesh: MeshController, onOpenSos: () -> Unit = {}) {
    val p = LocalCall.current
    val recent = s.now - s.lastSosAt < 120_000
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.sosWaitingForGps && s.now - s.lastSosAt < 600_000) {
            Text(stringResource(R.string.sos_no_gps), style = MaterialTheme.typography.bodyMedium, color = if (p.night) Color(0xFFFF8A96) else p.alarm)
        }
        for (r in s.mySosResponders) {
            Text(stringResource(R.string.help_coming_body, r), style = MaterialTheme.typography.titleMedium, color = p.ok)
        }
        if (s.sirenOn) {
            Button(
                onClick = mesh::stopSiren,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.red, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(64.dp).pressFeedback()
            ) {
                KIcon(KI.SIREN, Color.White, 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.stop_siren), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        } else {
            HoldSos(sent = recent, onTap = onOpenSos) {
                mesh.sendSos(SosKind.MANUAL)
            }
        }
    }
}

@Composable
private fun HoldSos(sent: Boolean, onTap: () -> Unit = {}, onSos: () -> Unit) {
    val p = LocalCall.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.sos_hold)
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(6.dp, shape, ambientColor = Color(0x66FF3B30), spotColor = Color(0x80FF3B30))
            .clip(shape)
            .background(p.red)
            .pressFeedback()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onPress = {
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(2000, easing = LinearEasing))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSos()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        scope.launch { progress.snapTo(0f) }
                    }
                )
            }
            .semantics { contentDescription = label; onClick(label) { onTap(); true } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value.coerceAtLeast(0.001f)).background(Color(0x55FFFFFF)))
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0x33000000))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                KIcon(KI.SIREN, Color.White, 22.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (sent) stringResource(R.string.sos_active) else label,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                Text(
                    text = if (sent) stringResource(R.string.sos_sent) else stringResource(R.string.sos_hint),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Full-screen SOS Flow matching prototype v3 sosScreen:
 * - Radial deep emergency red background: radial-gradient(120% 80% at 50% 30%, #FF5A4F, #D1241A 70%, #9E1810)
 * - Countdown state:
 *   - 290 dp circular countdown with animated white progress arc from -90 deg
 *   - Huge 140sp tabular numeral in center
 *   - Bold title "Sending SOS" (or "Fall detected" / "Panic signal")
 *   - Subtitle "The siren starts and your family gets your location."
 *   - 64 dp tall glass "Cancel" button
 * - SOS Sent state (when countdown finishes or s.sirenOn):
 *   - Expanding ripple waves (.rip)
 *   - 130 dp white checkmark disc (.chk) with red check icon and pop animation
 *   - 40sp bold "SOS sent"
 *   - Subtitle "The siren is on and your family's phones have your location."
 *   - 64 dp tall glass "Stop siren" button
 */
@Composable
private fun SosScreen(s: MeshUiState, mesh: MeshController, onDismiss: () -> Unit) {
    val still = rememberReducedMotion()
    val isAutoCountdown = s.countdown != null
    var manualSecondsLeft by remember { mutableStateOf(5) }
    val countdownAnim = remember { Animatable(1f) }
    val isDone = s.sirenOn || (!isAutoCountdown && manualSecondsLeft <= 0)

    LaunchedEffect(isAutoCountdown, isDone) {
        if (!isAutoCountdown && !isDone) {
            launch {
                countdownAnim.animateTo(0f, tween(5000, easing = LinearEasing))
            }
            for (i in 5 downTo 1) {
                manualSecondsLeft = i
                delay(1000)
            }
            manualSecondsLeft = 0
            mesh.sendSos(SosKind.MANUAL)
        }
    }

    val ripTransition = rememberInfiniteTransition(label = "rip")
    val ripPhase by if (still || !isDone) remember { mutableStateOf(0f) } else ripTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "ripPhase"
    )

    val popScale = remember { Animatable(if (still) 1f else 0.3f) }
    LaunchedEffect(isDone) {
        if (isDone && !still) {
            popScale.snapTo(0.3f)
            popScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    val secondsToDisplay = if (isAutoCountdown) {
        ((s.countdownEndsAt - s.now) / 1000).coerceAtLeast(0).toInt()
    } else {
        manualSecondsLeft
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF9E1810))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.3f)
            val r = size.width * 0.85f
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFFFF5A4F),
                    0.7f to Color(0xFFD1241A),
                    1.0f to Color(0xFF9E1810),
                    center = c,
                    radius = r
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            if (!isDone) {
                Box(
                    modifier = Modifier.size(290.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val strokeW = 10.dp.toPx()
                        val ringR = 128.dp.toPx()
                        drawCircle(
                            color = Color(0x40FFFFFF),
                            radius = ringR,
                            style = Stroke(width = strokeW)
                        )
                        val sweep = if (isAutoCountdown) {
                            (secondsToDisplay / 10f).coerceIn(0f, 1f) * 360f
                        } else {
                            countdownAnim.value * 360f
                        }
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "$secondsToDisplay",
                        fontFamily = Bricolage,
                        fontSize = 140.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-8).sp,
                        color = Color.White
                    )
                }

                Spacer(Modifier.height(28.dp))

                Text(
                    text = stringResource(
                        if (s.countdown == SosKind.FALL) R.string.countdown_fall
                        else if (s.countdown == SosKind.PANIC) R.string.countdown_panic
                        else R.string.sos_sending
                    ),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = Color.White
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        if (isAutoCountdown) R.string.countdown_body
                        else R.string.sos_sending_desc,
                        secondsToDisplay
                    ),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(290.dp)
                        .padding(top = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (!still) {
                            val minR = 65.dp.toPx()
                            val maxR = 175.dp.toPx()
                            for (step in 0..2) {
                                val phase = (ripPhase + step * 0.333f) % 1f
                                val currentR = minR + (maxR - minR) * phase
                                val alpha = (1f - phase) * 0.75f
                                drawCircle(
                                    color = Color.White.copy(alpha = alpha),
                                    radius = currentR,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .graphicsLayer {
                                scaleX = popScale.value
                                scaleY = popScale.value
                            }
                            .shadow(16.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        KIcon(KI.CHECK, tint = Color(0xFFD1241A), size = 68.dp)
                    }
                }

                Spacer(Modifier.height(30.dp))

                Text(
                    text = stringResource(R.string.sos_sent_title),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = Color.White
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.sos_sent_desc),
                    fontSize = 17.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                if (s.sosWaitingForGps && s.now - s.lastSosAt < 600_000) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.sos_no_gps),
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                for (r in s.mySosResponders) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.help_coming_body, r),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF30D158),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0x38FFFFFF))
                    .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(32.dp))
                    .pressFeedback()
                    .clickable {
                        if (isDone) {
                            mesh.stopSiren()
                        } else if (isAutoCountdown) {
                            mesh.cancelCountdown()
                        }
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (isDone) R.string.stop_siren
                        else if (isAutoCountdown) R.string.cancel_sos
                        else R.string.cancel
                    ),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
