package app.linkfill.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.linkfill.R
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.find.Circle
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.Role
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState
import app.linkfill.mesh.Notice

// ------------------------------------------------------------------ shared pieces

@Composable
private fun agoLabel(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return stringResource(R.string.seen_ago, if (t < 60_000) stringResource(R.string.secs, (t / 1000).toInt()) else stringResource(R.string.mins, (t / 60_000).toInt()))
}

/** Distance to a point and the arrow angle relative to where this phone faces; the compass word only when the phone has no compass. */
@Composable
private fun whereText(s: MeshUiState, lat: Double, lon: Double): Pair<String, Float?> {
    if (!MeshController.hasPosition(lat, lon)) return stringResource(R.string.position_unknown) to null
    val me = s.myFix ?: return "" to null
    val d = GapEstimator.distanceM(me.latitude, me.longitude, lat, lon)
    val b = GapEstimator.bearingDeg(me.latitude, me.longitude, lat, lon)
    val word = stringArrayResource(R.array.compass)[(((b + 22.5) % 360) / 45).toInt()]
    return (if (s.headingDeg != null) "${d.toInt()} m" else stringResource(R.string.metres_dir, d.toInt(), word)) to s.headingDeg?.let { (b - it).toFloat() }
}

/** A white rounded panel around a QR code, so it scans on the dark ground. */
@Composable
private fun QrPanel(text: String, description: String) {
    Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp)) { QrImage(text, description) }
}

@Composable
private fun Hairline() { Box(Modifier.fillMaxWidth().height(1.dp).background(LocalCall.current.line)) }

/** A tappable frosted tile (language, role). Selected tiles take the violet rim. */
@Composable
private fun GlassTile(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, center: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val p = LocalCall.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape).background(if (selected) p.violet.copy(alpha = 0.28f) else p.glass)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.violet else p.line, shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
        content = content,
    )
}

/** The camera view inside a rounded frame with white corner brackets, used by every scan page. */
@Composable
private fun ScanFrame(onResult: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxWidth().clip(shape)) {
        QrScanner(onResult)
        Canvas(Modifier.matchParentSize()) {
            val m = 28.dp.toPx(); val l = 36.dp.toPx(); val w = 4.dp.toPx()
            val c = Color.White
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(c, Offset(x, y), Offset(x + dx * l, y), w, StrokeCap.Round); drawLine(c, Offset(x, y), Offset(x, y + dy * l), w, StrokeCap.Round)
            }
            corner(m, m, 1f, 1f); corner(size.width - m, m, -1f, 1f); corner(m, size.height - m, 1f, -1f); corner(size.width - m, size.height - m, -1f, -1f)
        }
    }
}

@Composable
private fun NoticeRow(icon: KI, tint: Color, text: String, sub: String) = ActivityRow(icon, tint, text, sub, divider = false, onClick = null)

/** Notices or official alerts as quiet rows in one Glass. */
@Composable
private fun NoticeList(s: MeshUiState, items: List<Notice>, icon: KI, tint: Color) {
    if (items.isEmpty()) return
    InsetCard {
        items.forEachIndexed { i, n ->
            if (i > 0) Hairline()
            val where = if (n.lat == 0.0 && n.lon == 0.0) "" else whereText(s, n.lat, n.lon).first
            NoticeRow(icon, tint, n.text, listOf(where, agoLabel(s.now - n.at)).filter { it.isNotBlank() }.joinToString("  "))
        }
    }
}

/** Title, description and a Material switch: the whole row toggles. */
/**
 * Android only lets Nearby and GPS run with the screen off if location is allowed "all the time" (otherwise Nearby fails
 * with error 8034). Shown until granted; on Android 11+ the system sends the user to the app's permission page.
 */
@Composable
private fun BackgroundLocationRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { granted = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED }
    if (granted) return
    NavRow(KI.PIN, stringResource(R.string.bg_location_title), stringResource(R.string.bg_location_desc)) { ask.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
}

@Composable
private fun SettingRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalCall.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ------------------------------------------------------------------ setup

private enum class SetupStep { LANGUAGE, ABOUT, FAMILY, PERMISSIONS }

@Composable
fun SetupFlow(s: MeshUiState, mesh: MeshController) {
    val context = LocalContext.current
    val p = LocalCall.current
    var step by rememberSaveable { mutableStateOf(SetupStep.LANGUAGE) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeError by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingPermissions(context).isEmpty()) { mesh.completeSetup(); mesh.start() }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    when (step) {
        SetupStep.LANGUAGE -> CallPage(null, null) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LogoMark(76.dp)
                Spacer(Modifier.height(14.dp))
                Text("KHOJ", fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = 6.sp, color = p.ink)
                Text("खोज", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = p.blue)
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted, modifier = Modifier.padding(top = 8.dp))
            }
            SectionTitle(stringResource(R.string.choose_language))
            InsetCard {
                LANGUAGES.forEachIndexed { i, (tag, name) ->
                    Box {
                        if (i > 0) Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(1.dp).background(p.hair))
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).pressFeedback(0.98f).clickable { setAppLanguage(context, tag); step = SetupStep.ABOUT }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.weight(1f))
                            KIcon(KI.CHEVRON, p.inkMuted, 20.dp)
                        }
                    }
                }
            }
        }
        SetupStep.ABOUT -> CallPage(stringResource(R.string.your_name), onBack = { step = SetupStep.LANGUAGE }) {
            OutlinedTextField(s.name, mesh::setName, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
            SectionTitle(stringResource(R.string.i_am))
            InsetCard {
                ActivityRow(KI.GROUP, p.blue, stringResource(R.string.role_member), null, divider = false, chevron = true) { mesh.setRole(Role.MEMBER); step = SetupStep.FAMILY }
                ActivityRow(KI.SHIELD, p.green, stringResource(R.string.role_volunteer), null, divider = true, chevron = true) { mesh.setRole(Role.VOLUNTEER); step = SetupStep.PERMISSIONS }
            }
            Text(stringResource(R.string.helper_desc), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
        }
        SetupStep.FAMILY -> CallPage(stringResource(R.string.family_group), onBack = { if (scanning) scanning = false else step = SetupStep.ABOUT }) {
            val qr = mesh.crewQr()
            if (scanning) {
                Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                ScanFrame { text -> if (mesh.joinFromQr(text)) { scanning = false; step = SetupStep.PERMISSIONS } }
            } else if (qr != null) {
                InsetCard(padding = PaddingValues(16.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.family_show_qr), style = MaterialTheme.typography.bodyLarge, color = p.ink, textAlign = TextAlign.Center)
                        QrPanel(qr, stringResource(R.string.family_show_qr))
                        s.crewCode?.let { Text(CrewKey.pretty(it), style = MaterialTheme.typography.headlineMedium, color = p.ink, textAlign = TextAlign.Center) }
                    }
                }
                CallButton(stringResource(R.string.next), { step = SetupStep.PERMISSIONS })
            } else {
                CallButton(stringResource(R.string.family_create), { mesh.createCrew() }, icon = KI.PLUS)
                CallButton(stringResource(R.string.family_scan), {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true else camera.launch(Manifest.permission.CAMERA)
                }, icon = KI.SCAN, tone = Tone.QUIET)
                OutlinedTextField(code, { code = it; codeError = false }, label = { Text(stringResource(R.string.family_code)) }, singleLine = true, isError = codeError, modifier = Modifier.fillMaxWidth())
                if (codeError) Text(stringResource(R.string.bad_code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                CallButton(stringResource(R.string.join), { if (mesh.joinCrew(code)) step = SetupStep.PERMISSIONS else codeError = true }, tone = Tone.QUIET)
            }
        }
        SetupStep.PERMISSIONS -> CallPage(stringResource(R.string.permissions_title), onBack = { step = if (s.role == Role.MEMBER) SetupStep.FAMILY else SetupStep.ABOUT }) {
            InsetCard(padding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for ((k, col) in listOf(KI.RADAR to p.blue, KI.PIN to p.green, KI.BELL to p.orange, KI.SCAN to p.violet)) RoleIcon(k, col)
                    }
                    Text(stringResource(R.string.permissions_body), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                }
            }
            CallButton(stringResource(R.string.start_khoj), {
                if (missingPermissions(context).isEmpty()) { mesh.completeSetup(); mesh.start() } else perms.launch(allPermissions().toTypedArray())
            }, icon = KI.RADAR)
        }
    }
}

/** A white icon on a rounded coloured tile, as in the prototype's lists. */
@Composable
private fun RoleIcon(kind: KI, color: Color) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(color), contentAlignment = Alignment.Center) { KIcon(kind, Color.White, 28.dp) }
}

// ------------------------------------------------------------------ group tab

@Composable
fun GroupTab(s: MeshUiState, mesh: MeshController, onScanFamily: () -> Unit) {
    val p = LocalCall.current
    val qr = mesh.crewQr()
    TabPage(stringResource(R.string.family_group)) {
        // (a) the group pass
        if (qr != null) {
            FamilyPass(qr, s.crewCode, s)
            Text(stringResource(R.string.family_show_qr), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            CallButton(stringResource(R.string.family_create), { mesh.createCrew() }, icon = KI.PLUS)
            CallButton(stringResource(R.string.family_scan), onScanFamily, icon = KI.SCAN, tone = Tone.QUIET)
        }

        // (b) the group circle picker
        SectionTitle(stringResource(R.string.circle_title))
        Text(stringResource(R.string.circle_desc), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
        SegmentedControl(Circle.RADII_M, s.circleM, { mesh.setCircle(it) }, label = { "$it m" })

        // (b2) family may light up this phone
        SectionTitle(stringResource(R.string.light_section))
        Glass(Modifier.fillMaxWidth()) { SettingRow(stringResource(R.string.light_allow_title), stringResource(R.string.light_allow_desc), s.allowLight, mesh::setAllowLight) }
        var lightSelf by remember { mutableStateOf(false) }
        if (lightSelf) LightUpDialog("", self = true, onSend = { m, d -> lightSelf = false; mesh.lightSelf(m, d) }, onDismiss = { lightSelf = false })
        CallButton(stringResource(R.string.light_self_button), { lightSelf = true }, icon = KI.BOLT, tone = Tone.QUIET)

        // (c) the meeting point
        SectionTitle(stringResource(R.string.meet_point))
        s.meetPoint?.let { mp ->
            val (text, angle) = whereText(s, mp.lat, mp.lon)
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (angle != null) { DirectionArrow(angle, 72.dp); Spacer(Modifier.width(14.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text("${mp.label} (${mp.from})", style = MaterialTheme.typography.titleMedium, color = p.ink)
                        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                    }
                }
            }
        }
        CallButton(stringResource(R.string.set_meet_here), mesh::meetHere, icon = KI.PIN, enabled = s.running && s.myFix != null)

        // (d) members in detail
        SectionTitle(stringResource(R.string.my_family))
        if (s.members.isEmpty()) Text(stringResource(R.string.family_empty), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
        else InsetCard(padding = PaddingValues(horizontal = 14.dp)) {
            s.members.forEachIndexed { i, m ->
                if (i > 0) Hairline()
                val me = s.myFix
                val dist = if (me != null && MeshController.hasPosition(m.lat, m.lon)) GapEstimator.distanceM(me.latitude, me.longitude, m.lat, m.lon) else null
                val (where, angle) = whereText(s, m.lat, m.lon)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(m.name, colorIndex = i, size = 44.dp, stale = dist == null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium, color = p.ink)
                        if (where.isNotBlank()) Text(where, style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (m.battery in 0..100) { KIcon(KI.BATTERY, p.inkMuted, 16.dp); Text("${m.battery}%", style = MaterialTheme.typography.bodySmall, color = p.inkMuted); Spacer(Modifier.width(6.dp)) }
                            KIcon(KI.RADAR, p.inkMuted, 16.dp); Text("${m.hops}", style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
                            Spacer(Modifier.width(6.dp))
                            Text(agoLabel(s.now - m.heardAt), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
                        }
                    }
                    if (angle != null) DirectionArrow(angle, 48.dp)
                }
            }
        }

        // (e) join another group
        if (qr != null) NavRow(KI.SCAN, stringResource(R.string.family_scan), onClick = onScanFamily)
    }
}

/** The family pass of prototype v3: a dark Wallet-style card with the join QR, the code, the members, and a slow sheen. */
@Composable
private fun FamilyPass(qr: String, code: String?, s: MeshUiState) {
    val still = rememberReducedMotion()
    val sheen = if (still) null else rememberInfiniteTransition(label = "sheen").animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "s")
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.fillMaxWidth().shadow(18.dp, shape, ambientColor = Color(0x59141E32), spotColor = Color(0x59141E32)).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF26323A), Color(0xFF0F1519)), Offset.Zero, Offset(900f, 1100f))),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.pass_label), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = Color(0xA6FFFFFF))
            Text(stringResource(R.string.nearby_group), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = Color.White, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(128.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(9.dp)) { QrImage(qr, stringResource(R.string.family_show_qr)) }
                Spacer(Modifier.width(16.dp))
                code?.let { Text(CrewKey.pretty(it).replace("-", "\n"), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 1.sp, color = Color.White) }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy((-9).dp)) {
                for ((i, m) in s.members.take(6).withIndex()) Box(Modifier.border(2.dp, Color(0xFF1A2228), CircleShape)) {
                    MemberAvatar(m.name, colorIndex = i, size = 30.dp)
                }
            }
        }
        if (sheen != null) Canvas(Modifier.matchParentSize()) {
            val x = size.width * sheen.value
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x17FFFFFF), Color.Transparent), Offset(x - 160f, 0f), Offset(x + 160f, size.height * 0.4f)))
        }
    }
}

/** The child's band as a dark pass: the QR to scan, who to call and where to meet. */
@Composable
private fun BandPass(band: Band) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.fillMaxWidth().shadow(18.dp, shape, ambientColor = Color(0x59141E32), spotColor = Color(0x59141E32)).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF26323A), Color(0xFF0F1519)), Offset.Zero, Offset(900f, 1100f))),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.band_pass_label), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = Color(0xA6FFFFFF), modifier = Modifier.fillMaxWidth())
            Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(10.dp)) { QrImage(band.toQrText(), band.name) }
            Text(stringResource(R.string.call_number, band.phone), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
            Text(stringResource(R.string.meet_at, band.meetingPoint), style = MaterialTheme.typography.titleMedium, color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
        }
    }
}

/** Four dashed rings (30/50/100/200 m); the chosen one glows in its heat colour and is labelled. */
@Composable
private fun CirclePicker(selectedM: Int) {
    val p = LocalCall.current
    val tm = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelMedium
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "$selectedM m" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2 - 6.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))
        val radii = Circle.RADII_M
        fun ringR(i: Int) = maxR * (i + 1) / radii.size
        for (i in radii.indices.reversed()) {
            val m = radii[i]; val r = ringR(i); val col = heat(p, m.toDouble())
            if (m == selectedM) {
                drawCircle(col.copy(alpha = 0.14f), r, c)
                drawCircle(col.copy(alpha = 0.28f), r, c, style = Stroke(10.dp.toPx()))
                drawCircle(col, r, c, style = Stroke(3.dp.toPx(), pathEffect = dash))
            } else drawCircle(p.stale.copy(alpha = 0.6f), r, c, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
        }
        drawCircle(p.gold, 7.dp.toPx(), c)
        drawCircle(p.gold.copy(alpha = 0.35f), 13.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        for (i in radii.indices) {
            val m = radii[i]; val sel = m == selectedM
            val res = tm.measure("$m m", label.copy(color = if (sel) heat(p, m.toDouble()) else p.inkMuted, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold))
            drawText(res, topLeft = Offset(c.x - res.size.width / 2f, c.y - ringR(i) + 6.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------ help tab

@Composable
fun HelpTab(s: MeshUiState, mesh: MeshController, onScanBand: () -> Unit, onBands: () -> Unit, onRewind: () -> Unit) {
    val p = LocalCall.current
    TabPage(stringResource(R.string.found_someone)) {
        InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
                CallButton(stringResource(R.string.found_someone), onScanBand, icon = KI.SCAN)
            }
        }
        NavRow(KI.BAND, stringResource(R.string.child_bands), onClick = onBands)
        if (s.pendant) Box(Modifier.alpha(if (s.rewindFrames > 0) 1f else 0.5f)) {
            NavRow(KI.REWIND, stringResource(R.string.rewind), "(${s.rewindFrames})") { if (s.rewindFrames > 0) onRewind() }
        }
        if (s.mySosResponders.isNotEmpty()) {
            SectionTitle(stringResource(R.string.help_coming))
            InsetCard {
                s.mySosResponders.forEachIndexed { i, r ->
                    if (i > 0) Hairline()
                    NoticeRow(KI.CHECK, p.ok, stringResource(R.string.help_coming_body, r), "")
                }
            }
        }
        if (s.notices.isNotEmpty()) {
            SectionTitle(stringResource(R.string.people_found))
            NoticeList(s, s.notices.take(5), KI.PIN, p.sky)
        }
        if (s.officials.isNotEmpty()) {
            SectionTitle(stringResource(R.string.official_alert))
            NoticeList(s, s.officials, KI.SHIELD, p.sky)
        }
    }
}

// ------------------------------------------------------------------ settings tab

@Composable
fun SettingsTab(s: MeshUiState, mesh: MeshController, night: Boolean, onNight: (Boolean) -> Unit, simple: Boolean, onSimple: (Boolean) -> Unit, onScanFamily: () -> Unit, onScanPolice: () -> Unit, onBatteryGuidance: () -> Unit = {}, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    val p = LocalCall.current
    TabPage(stringResource(R.string.settings)) {
        ProfilePanel(s, mesh, onScanPolice)

        SectionTitle(stringResource(R.string.language))
        Glass(Modifier.fillMaxWidth()) {
            val prefs = remember { context.getSharedPreferences("mesh", Context.MODE_PRIVATE) }
            var lang by remember { mutableStateOf(prefs.getString("lang", null) ?: java.util.Locale.getDefault().language) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in LANGUAGES.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((tag, name) in row) FilterChip(
                        selected = lang == tag, onClick = { lang = tag; setAppLanguage(context, tag) },
                        label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(name, maxLines = 1) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
                SegmentedControl(listOf(false, true), night, onNight, label = { if (it) context.getString(R.string.theme_night) else context.getString(R.string.theme_day) })
            }
        }

        Glass(Modifier.fillMaxWidth()) { SettingRow(stringResource(R.string.simple_mode), stringResource(R.string.simple_mode_desc), simple, onSimple) }

        Glass(Modifier.fillMaxWidth()) {
            SettingRow(stringResource(R.string.pendant_mode), stringResource(R.string.pendant_desc), s.pendant, mesh::setPendant)
            if (s.role == Role.MEMBER) {
                Hairline()
                SettingRow(stringResource(R.string.battery_saver), stringResource(R.string.battery_saver_desc), s.batterySaver, mesh::setBatterySaver)
            }
        }

        BackgroundLocationRow()
        NavRow(KI.BATTERY, stringResource(R.string.battery_guidance_title), if (s.batteryUnrestricted) stringResource(R.string.battery_guidance_exempted) else stringResource(R.string.battery_guidance_summary), onClick = onBatteryGuidance)

        if (s.running) CallButton(stringResource(R.string.stop_khoj), mesh::stop, tone = Tone.QUIET)
        else CallButton(stringResource(R.string.turn_on), mesh::start, icon = KI.RADAR)
        NavRow(KI.SETTINGS, stringResource(R.string.advanced), onClick = onAdvanced)
        MeshHealthRow(s)
    }
}

/** Quiet text row at the very bottom of Settings: live numbers from this phone's mesh, nothing invented. */
@Composable
private fun MeshHealthRow(s: MeshUiState) {
    val p = LocalCall.current
    var open by remember { mutableStateOf(false) }
    Text(stringResource(R.string.health_title), fontSize = 13.sp, color = p.inkMuted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true }.padding(top = 14.dp))
    if (!open) return
    val lines = buildList {
        add("Mesh " + if (s.running) "on" else "off")
        add("Phones heard now: ${s.neighbors.size}")
        for (n in s.neighbors.take(8)) add("  ${Integer.toHexString(n.id).takeLast(4)}  ${n.rssi} dBm  delivery ${(n.pdr2s * 100).toInt()}% (last 2 s)")
        add("Group members: ${s.members.size}")
        for (m in s.members.take(8)) add("  ${m.name}: ${m.hops} hop(s), ${m.rssi} dBm, heard ${(s.now - m.heardAt) / 1000} s ago")
        for (t in s.transports.filter { it.enabled }) add("${t.kind.name}: " + (if (t.up) "up" else "down") + ", peers ${t.peers}, sent ${t.sent}, received ${t.received}")
        add(s.stats)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.health_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { for (l in lines) Text(l, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, color = p.ink) } },
        confirmButton = { androidx.compose.material3.TextButton({ open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.light_cancel)) } },
    )
}

/** Who I am: big initial, name, and member/helper choice (police only after the official key is scanned). */
@Composable
private fun ProfilePanel(s: MeshUiState, mesh: MeshController, onScanPolice: () -> Unit) {
    val p = LocalCall.current
    val context = LocalContext.current
    InsetCard(padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(s.name.ifBlank { "?" }, size = 72.dp)
                Spacer(Modifier.width(16.dp))
                OutlinedTextField(s.name, mesh::setName, label = { Text(stringResource(R.string.your_name)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.i_am), style = MaterialTheme.typography.titleMedium, color = p.ink)
            // One helper role: volunteers, staff, security and police share a screen; the official key only adds signed alerts.
            val helper = s.role != Role.MEMBER
            SegmentedControl(listOf(false, true), helper, { isHelper -> mesh.setRole(if (!isHelper) Role.MEMBER else if (s.policeUnlocked) Role.POLICE else Role.VOLUNTEER) }, label = { context.getString(if (it) R.string.role_volunteer else R.string.role_member) }, height = 56.dp)
            if (s.role == Role.POLICE) Row(verticalAlignment = Alignment.CenterVertically) {
                KIcon(KI.SHIELD, p.ok, 22.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.role_police), style = MaterialTheme.typography.titleMedium, color = p.ok)
            }
            if (helper && !s.policeUnlocked) {
                CallButton(stringResource(R.string.scan_police_key), onScanPolice, icon = KI.SCAN, tone = Tone.QUIET)
                Text(stringResource(R.string.police_locked), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
            }
        }
    }
}

// ------------------------------------------------------------------ helper tabs

@Composable
fun HelperScanTab(s: MeshUiState, mesh: MeshController, onScanBand: () -> Unit) {
    val p = LocalCall.current
    var description by rememberSaveable { mutableStateOf("") }
    TabPage(stringResource(R.string.scan_band)) {
        CallButton(stringResource(R.string.scan_band), onScanBand, icon = KI.SCAN)
        if (s.sentReports.isNotEmpty()) InsetCard {
            s.sentReports.take(5).forEachIndexed { i, r ->
                if (i > 0) Hairline()
                NoticeRow(
                    if (r.familyReceived) KI.CHECK else KI.BELL, if (r.familyReceived) p.ok else p.inkMuted,
                    r.name, stringResource(if (r.familyReceived) R.string.family_received else R.string.alert_waiting),
                )
            }
        }
        InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.found_no_band), style = MaterialTheme.typography.titleMedium, color = p.ink)
                OutlinedTextField(description, { description = it.take(60) }, label = { Text(stringResource(R.string.describe_person)) }, modifier = Modifier.fillMaxWidth())
                CallButton(stringResource(R.string.send_everyone), { mesh.registerFound(description); description = "" }, icon = KI.BELL, enabled = description.isNotBlank())
            }
        }
    }
}

@Composable
fun HelperAlertsTab(s: MeshUiState, mesh: MeshController) {
    val p = LocalCall.current
    var official by rememberSaveable { mutableStateOf("") }
    TabPage(stringResource(R.string.official_alert)) {
        if (s.role == Role.POLICE) InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.police_alert), style = MaterialTheme.typography.titleMedium, color = p.ink)
                OutlinedTextField(official, { official = it.take(80) }, label = { Text(stringResource(R.string.police_alert_hint)) }, modifier = Modifier.fillMaxWidth())
                CallButton(stringResource(R.string.send_everyone), { mesh.sendOfficial(official); official = "" }, icon = KI.SHIELD, enabled = official.isNotBlank())
            }
        }
        NoticeList(s, s.officials, KI.SHIELD, p.sky)
        if (s.notices.isNotEmpty()) {
            SectionTitle(stringResource(R.string.people_found))
            NoticeList(s, s.notices.take(5), KI.PIN, p.sky)
        }
    }
}

// ------------------------------------------------------------------ bands

@Composable
fun BandsPage(s: MeshUiState, mesh: MeshController, onShow: (Band) -> Unit, onBack: () -> Unit) {
    val p = LocalCall.current
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var meet by rememberSaveable { mutableStateOf("") }
    CallPage(stringResource(R.string.child_bands), onBack) {
        if (s.bands.isNotEmpty()) InsetCard {
            s.bands.forEachIndexed { i, b -> ActivityRow(KI.BAND, p.blue, "${b.name}${if (b.age > 0) ", ${b.age}" else ""}", b.meetingPoint, divider = i > 0, chevron = true) { onShow(b) } }
        }
        InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.make_band), style = MaterialTheme.typography.titleLarge, color = p.ink)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.band_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.band_age)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.band_phone)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(meet, { meet = it }, label = { Text(stringResource(R.string.band_meet)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        CallButton(stringResource(R.string.make_band), { mesh.createBand(name, age.toIntOrNull() ?: 0, phone, meet)?.let { onShow(it); name = ""; age = "" } },
            icon = KI.PLUS, enabled = name.isNotBlank() && phone.isNotBlank())
    }
}

@Composable
fun ShowBandPage(band: Band, mesh: MeshController, onBack: () -> Unit) {
    val context = LocalContext.current
    val p = LocalCall.current
    var saved by remember { mutableStateOf(false) }
    CallPage(band.name, onBack) {
        BandPass(band)
        CallButton(if (saved) stringResource(R.string.band_saved) else stringResource(R.string.save_band), { saved = saveBandImage(context, band) }, icon = if (saved) KI.CHECK else KI.BAND)
        TextButton(onClick = { mesh.deleteBand(band.bandId); onBack() }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.delete), color = p.alarm)
        }
    }
}

@Composable
fun FoundBandPage(band: Band, s: MeshUiState, mesh: MeshController, onBack: () -> Unit) {
    val context = LocalContext.current
    val p = LocalCall.current
    var sent by rememberSaveable { mutableStateOf(false) }
    CallPage(band.name, onBack) {
        InsetCard(padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${band.name}${if (band.age > 0) ", ${band.age}" else ""}", style = MaterialTheme.typography.displayMedium, color = p.ink)
                Text(stringResource(R.string.meet_at, band.meetingPoint), style = MaterialTheme.typography.titleLarge, color = p.ink)
                Text(stringResource(R.string.call_number, band.phone), style = MaterialTheme.typography.titleLarge, color = p.ink)
            }
        }
        CallButton(stringResource(R.string.alert_family), { mesh.reportFound(band, ""); sent = true }, icon = KI.BELL, enabled = !sent)
        if (sent) {
            val received = s.sentReports.firstOrNull { it.name == band.name }?.familyReceived == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                KIcon(if (received) KI.CHECK else KI.BELL, if (received) p.ok else p.inkMuted, 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (received) R.string.family_received else R.string.alert_waiting), style = MaterialTheme.typography.titleMedium, color = if (received) p.ok else p.ink)
            }
        }
        CallButton(stringResource(R.string.call_family), { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${band.phone}"))) }, icon = KI.PHONE, tone = Tone.QUIET)
    }
}

// ------------------------------------------------------------------ rewind and scanning

@Composable
fun RewindPage(s: MeshUiState, mesh: MeshController, onBack: () -> Unit) {
    val p = LocalCall.current
    val frames = mesh.rewind.snapshot
    CallPage(stringResource(R.string.rewind), onBack) {
        Text(stringResource(R.string.rewind_desc), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
        if (frames.isEmpty()) Text(stringResource(R.string.rewind_empty), style = MaterialTheme.typography.bodyLarge, color = p.ink)
        for (f in frames) {
            val bmp = remember(f.atMs) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }
            val where = if (f.lat != null && f.lon != null) whereText(s, f.lat, f.lon).first else ""
            InsetCard {
                bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                Text(listOf(agoLabel(s.now - f.atMs), where).filter { it.isNotBlank() }.joinToString("  "), style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
fun ScanPage(title: String, message: String, onBack: () -> Unit, onResult: (String) -> Unit) {
    CallPage(title, onBack) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = LocalCall.current.ink)
        ScanFrame(onResult)
    }
}

/**
 * Deep-links to OEM autostart / power management / app settings where an intent exists.
 * Priority order targets Vivo/iQOO FuntouchOS & OriginOS components, then standard Android intents.
 */
fun openBatterySettings(context: Context) {
    val intents = listOf(
        // Vivo / iQOO autostart manager
        Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
        // Vivo / iQOO high background power consumption
        Intent().setComponent(android.content.ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingTabActivity")),
        // Standard Android battery optimization request
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
        // Standard Android application details settings
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) { }
    }
}

@Composable
fun BatteryGuidancePage(s: MeshUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val p = LocalCall.current
    CallPage(stringResource(R.string.battery_guidance_title), onBack) {
        InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KIcon(KI.BATTERY, if (s.batteryUnrestricted) p.ok else p.gold, 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.battery_guidance_heading),
                        style = MaterialTheme.typography.titleLarge,
                        color = p.ink,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    stringResource(R.string.battery_guidance_why),
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.ink,
                )
                if (s.batteryUnrestricted) {
                    LivePill(stringResource(R.string.battery_guidance_exempted), p.ok)
                } else {
                    LivePill(stringResource(R.string.battery_guidance_summary), p.gold)
                }
            }
        }

        InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.battery_guidance_vivo_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = p.ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.battery_guidance_vivo_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.inkMuted,
                )
            }
        }

        InsetCard(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.battery_guidance_cost_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = p.ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.battery_guidance_cost_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.inkMuted,
                )
            }
        }

        CallButton(
            stringResource(R.string.battery_guidance_btn),
            onClick = { openBatterySettings(context) },
            icon = KI.SETTINGS,
        )
    }
}

