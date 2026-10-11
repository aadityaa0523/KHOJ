package app.linkfill.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.R
import app.linkfill.core.find.FindMode
import app.linkfill.core.find.Level
import app.linkfill.core.find.Note
import app.linkfill.core.find.Proximity
import app.linkfill.core.find.Trend
import app.linkfill.find.FindUi
import kotlin.math.roundToInt

@Composable
private fun levelText(l: Level?) = when (l) { Level.HIGH -> stringResource(R.string.find_confidence_high); Level.MEDIUM -> stringResource(R.string.find_confidence_medium); Level.LOW -> stringResource(R.string.find_confidence_low); null -> "" }

@Composable
private fun noteText(n: Note): String? = when (n) {
    Note.RANGING_STALE -> stringResource(R.string.find_note_ranging_stale)
    Note.NO_ANGLE_FROM_HARDWARE -> stringResource(R.string.find_note_no_angle)
    Note.ANGLE_UNTRUSTED -> stringResource(R.string.find_note_angle_untrusted)
    Note.GPS_TOO_CLOSE_FOR_DIRECTION -> stringResource(R.string.find_note_gps_close)
    Note.GPS_DIRECTION_UNCERTAIN -> stringResource(R.string.find_note_gps_uncertain)
    Note.HEADING_UNRELIABLE -> stringResource(R.string.find_note_heading_unreliable)
    Note.MY_LOCATION_MISSING -> stringResource(R.string.find_note_my_loc_missing)
    Note.MY_LOCATION_OLD -> stringResource(R.string.find_note_my_loc_old)
    Note.TARGET_POSITION_OLD -> stringResource(R.string.find_note_target_old)
    Note.BLE_NOT_HEARD -> stringResource(R.string.find_note_ble_not_heard)
    Note.RSSI_LOW_CONFIDENCE -> stringResource(R.string.find_note_rssi_low)
    Note.WALK_MORE -> stringResource(R.string.find_note_walk_more)
    Note.WALK_SIDEWAYS -> stringResource(R.string.find_note_walk_sideways)
    Note.WALK_AMBIGUOUS -> stringResource(R.string.find_note_walk_ambiguous)
    Note.WALK_POOR_FIT -> stringResource(R.string.find_note_walk_poor)
    Note.TURN_TO_FIND, Note.SIGNAL_IMPROVING, Note.SIGNAL_WORSENING -> null // shown by the turn coach and the trend line
}

@Composable
private fun agoText(ms: Long) = if (ms < 60_000) stringResource(R.string.secs, (ms / 1000).toInt()) else stringResource(R.string.mins, (ms / 60_000).toInt())

/** Heat for the whole field: measured distance when there is one, else the Bluetooth closeness band, else none. */
private fun fieldHeat(p: CallPalette, distanceM: Double?, prox: Proximity?): Color? = when {
    distanceM != null -> heat(p, distanceM)
    prox == Proximity.VERY_CLOSE -> p.gold
    prox == Proximity.NEAR -> p.fire
    prox == Proximity.MEDIUM -> p.violet
    prox == Proximity.FAR -> p.sky
    else -> null
}

/**
 * Find, as in prototype v3: the whole screen is one colour that shifts with distance (blue far, green, yellow, orange near),
 * darkened so white text always reads. The arrow and its wedge appear only in modes where FindResolver allows a direction;
 * every other mode shows what it does know (distance, closeness, a last position) and says why the rest is withheld.
 */
@Composable
fun FindScreen(ui: FindUi?, onStop: () -> Unit, needRangingPermission: Boolean, onAllowRanging: () -> Unit, onTrustAngle: (Boolean) -> Unit, onGroundTruth: (Double?) -> Unit, onLight: (Int, Int) -> Unit = { _, _ -> }, onBack: () -> Unit = onStop) {
    val p0 = LocalCall.current
    var askLight by remember { mutableStateOf(false) }
    val speaker = rememberSpeaker()
    if (ui == null) { CallPage(stringResource(R.string.find_mode_none), onStop) {}; return }
    val s = ui.state
    val base = fieldHeat(p0, s.distanceM, s.proximity) ?: Color(0xFF5B8DB8)
    val field by animateColorAsState(base, tween(900), label = "field")
    val (hi, deep) = findBackgroundGradients(field)
    val still = rememberReducedMotion()
    val drift = if (still) null else rememberInfiniteTransition(label = "blob").animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    val sentence = spokenFind(ui.targetName, s.distanceM, ui.arrowSmoothedDeg)
    // The whole screen is white-on-colour, so every component below reads white inks.
    val fp = p0.copy(ink = Color.White, inkMuted = Color(0xC7FFFFFF), glass = Color(0x2EFFFFFF), glassStrong = Color(0x33FFFFFF), line = Color(0x4DFFFFFF), gline = Color(0x4DFFFFFF))
    CompositionLocalProvider(LocalCall provides fp) {
        if (askLight) LightUpDialog(ui.targetName, onSend = { m, d -> onLight(m, d); askLight = false }, onDismiss = { askLight = false })
        Box(Modifier.fillMaxSize().background(deep)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(listOf(hi, deep), Offset(size.width * 0.5f, size.height * 0.22f), size.width * 1.3f))
                val k = drift?.value ?: 0.4f
                val r = 220.dp.toPx()
                val a = Offset(-20.dp.toPx() + 70.dp.toPx() * k, 230.dp.toPx() + 90.dp.toPx() * k)
                val b = Offset(size.width + 20.dp.toPx() - 60.dp.toPx() * k, size.height - 160.dp.toPx() - 80.dp.toPx() * k)
                drawCircle(Brush.radialGradient(listOf(field.copy(alpha = 0.34f), Color.Transparent), a, r), r, a)
                drawCircle(Brush.radialGradient(listOf(field.copy(alpha = 0.34f), Color.Transparent), b, r), r, b)
            }
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color(0x30FFFFFF)).border(1.dp, Color(0x4DFFFFFF), CircleShape).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { KIcon(KI.CHEVRON, Color.White, 30.dp, stringResource(R.string.back), Modifier.graphicsLayer { rotationZ = 180f }) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ui.targetName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                        ui.linkMedium?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xBFFFFFFF), maxLines = 1) }
                    }
                    Spacer(Modifier.size(44.dp))
                }
                // A position heard in the last 30 s is a GPS reading, not a prediction: label it as such.
                val fresh = (s.targetFixAgeMs ?: Long.MAX_VALUE) <= app.linkfill.core.find.FindResolver.TARGET_RECENT_MS
                ModeChip(if (s.mode == FindMode.PREDICTED && fresh) FindMode.LOCATION_BEARING else s.mode, s.arrowSource == app.linkfill.core.find.Source.BLE_TURN)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Hero(ui) }

                // Why: every withheld claim is explained.
                val notes = s.notes.mapNotNull { noteText(it) }
                if (notes.isNotEmpty() || needRangingPermission) Glass(padding = PaddingValues(14.dp)) {
                    for (n in notes) Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(Color.White)); Spacer(Modifier.width(10.dp))
                        Text(n, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                    if (needRangingPermission) { Spacer(Modifier.height(8.dp)); CallButton(stringResource(R.string.find_allow_ranging), onAllowRanging, icon = KI.RADAR) }
                }
                Sources(ui, onTrustAngle, onGroundTruth)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FindGlassButton(null, KI.SPEAKER, stringResource(R.string.voice_speak), Modifier.size(60.dp)) { speaker.say(sentence) }
                FindGlassButton(stringResource(R.string.light_button), KI.BOLT, null, Modifier.weight(1f)) { askLight = true }
                FindGlassButton(stringResource(R.string.find_stop), KI.CLOSE, null, Modifier.weight(1f), onStop)
            }
            }
        }
    }
}

/** The frosted white button of the Find screen: a label, an icon, or both; at least 60 dp tall. */
@Composable
private fun FindGlassButton(text: String?, icon: KI, description: String?, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(Color(0x38FFFFFF)).border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(30.dp)).pressFeedback().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        KIcon(icon, Color.White, 22.dp, description)
        if (text != null) { Spacer(Modifier.width(8.dp)); Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1) }
    }
}

@Composable
private fun ModeChip(mode: FindMode, turning: Boolean) {
    val p = LocalCall.current
    val label = when (mode) {
        FindMode.PRECISION_DIRECTION -> R.string.find_mode_precision_dir
        FindMode.PRECISION_DISTANCE -> R.string.find_mode_precision_dist
        FindMode.RANGE_WALK -> R.string.find_mode_walk
        FindMode.LOCATION_BEARING -> R.string.find_mode_gps
        FindMode.BLE_PROXIMITY -> if (turning) R.string.find_mode_turn else R.string.find_mode_ble
        FindMode.PREDICTED -> R.string.find_mode_predicted
        FindMode.UNAVAILABLE -> R.string.find_mode_none
    }
    val live = mode == FindMode.PRECISION_DIRECTION || mode == FindMode.PRECISION_DISTANCE || mode == FindMode.RANGE_WALK || turning
    LivePill(stringResource(label), if (live) p.ok else if (mode == FindMode.UNAVAILABLE || mode == FindMode.PREDICTED) p.stale else p.sky)
}

/**
 * Arrow first, in every mode: the navigator always shows, pointing wherever the best available source says, with a wedge
 * as wide as that source is unsure. With no direction at all it shows a sweeping needle, never a fixed fake arrow.
 * Below it, one line of the most useful number for the mode.
 */
@Composable
private fun Hero(ui: FindUi) {
    val p = LocalCall.current
    val s = ui.state
    val precise = s.arrowSource == app.linkfill.core.find.Source.UWB
    val onTarget = ui.arrowSmoothedDeg?.let { kotlin.math.abs(app.linkfill.core.find.Angles.normalize180(it)) < 9 } == true && (s.distanceM ?: 0.0) > 15
    Dial(ui.arrowSmoothedDeg, s.arrowUncertaintyDeg, dim = !precise, onTarget = onTarget)
    if (ui.arrowSmoothedDeg != null) Steer(ui.arrowSmoothedDeg)
    else Text(stringResource(R.string.find_searching), style = MaterialTheme.typography.headlineSmall, color = p.ink, textAlign = TextAlign.Center)
    when (s.mode) {
        FindMode.PRECISION_DIRECTION, FindMode.PRECISION_DISTANCE, FindMode.RANGE_WALK -> {
            BigDistance(s.distanceM, null)
            Text(stringResource(R.string.find_measured_by, s.distanceSource?.label ?: "") + " · " + levelText(s.rangingConfidence), style = MaterialTheme.typography.bodyLarge, color = p.ink)
        }
        FindMode.LOCATION_BEARING -> {
            BigDistance(s.distanceM, s.distanceUncertaintyM)
            s.targetFixAgeMs?.let { Text(stringResource(R.string.find_gps_age, agoText(it)), style = MaterialTheme.typography.bodyLarge, color = p.ink) }
        }
        FindMode.BLE_PROXIMITY -> {
            Text(closenessWord(s.proximity), style = MaterialTheme.typography.headlineMedium, color = p.ink)
            Text(trendWord(s.trend), style = MaterialTheme.typography.titleMedium, color = p.ink)
        }
        FindMode.PREDICTED -> {
            BigDistance(s.distanceM, s.distanceUncertaintyM)
            s.targetFixAgeMs?.let { Text(stringResource(if (it <= app.linkfill.core.find.FindResolver.TARGET_RECENT_MS) R.string.find_gps_age else R.string.find_predicted_age, agoText(it)), style = MaterialTheme.typography.bodyLarge, color = p.ink) }
        }
        FindMode.UNAVAILABLE -> Unit
    }
    if (s.mode != FindMode.BLE_PROXIMITY && s.mode != FindMode.UNAVAILABLE) s.distanceM?.let { Text(stringResource(R.string.find_steps, (it / 0.7).roundToInt().coerceAtLeast(1)), style = MaterialTheme.typography.titleMedium, color = p.inkMuted) }
    if (ui.arrowSmoothedDeg != null) s.arrowSource?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium, color = p.inkMuted, textAlign = TextAlign.Center) }
}

/** The Find arrow of prototype v3: a white arrow in a thin ring, a soft wedge as wide as the direction is unsure, rings that pulse when you point straight at them. */
@Composable
private fun Dial(angleDeg: Double?, uncertaintyDeg: Double?, dim: Boolean, onTarget: Boolean) {
    val still = rememberReducedMotion()
    val ring = if (still || !onTarget) null else rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "r")
    Box(Modifier.size(270.dp).semantics { contentDescription = "Precision Navigator" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val k = size.minDimension / 300f
            drawCircle(Color.White.copy(alpha = 0.22f), 138f * k, c, style = Stroke(1.5.dp.toPx()))
            if (ring != null) for (off in listOf(0f, 0.5f)) {
                val t = (ring.value + off) % 1f
                drawCircle(Color.White.copy(alpha = 0.9f * (1f - t)), 120f * k * (0.7f + 0.65f * t), c, style = Stroke(2.dp.toPx()))
            }
            if (angleDeg != null) rotate(angleDeg.toFloat()) {
                val unc = uncertaintyDeg?.coerceIn(0.0, 90.0)?.toFloat()
                if (unc != null && unc > 0f) drawArc(Color.White.copy(alpha = 0.13f), -90f - unc, unc * 2, true, Offset(c.x - 145f * k, c.y - 145f * k), Size(290f * k, 290f * k))
                val arrow = Path().apply { moveTo(c.x, c.y - 92f * k); for ((x, y) in listOf(56f to 30f, 14f to 14f, 14f to 88f, -14f to 88f, -14f to 14f, -56f to 30f)) lineTo(c.x + x * k, c.y + y * k); close() }
                val a = 1f // direction trust is shown by the wedge and the mode chip, not by a see-through arrow
                val shadow = Path().apply { addPath(arrow, Offset(0f, 10f * k)) }
                drawPath(shadow, Color.Black.copy(alpha = 0.18f * a))
                val brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = a), Color.White.copy(alpha = 0.72f * a)), startY = c.y - 92f * k, endY = c.y + 88f * k)
                drawPath(arrow, brush)
                drawPath(arrow, brush, style = Stroke(12f * k, join = StrokeJoin.Round))
            } else {
                // Searching: a faint needle sweeps round so it is obvious no direction is being claimed yet.
                drawCircle(Color.White.copy(alpha = 0.18f), 100f * k, c, style = Stroke(2.dp.toPx()))
            }
        }
        if (angleDeg == null) Searching()
    }
}

@Composable
private fun Searching() {
    val still = rememberReducedMotion()
    val sweep = if (still) null else rememberInfiniteTransition(label = "sweep").animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "needle")
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2
        rotate(sweep?.value ?: 0f) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f)), center), -150f, 60f, true, Offset(center.x - r * 0.8f, center.y - r * 0.8f), Size(r * 1.6f, r * 1.6f))
            val w = size.width; val h = size.height
            drawPath(Path().apply { moveTo(w / 2, h * 0.15f); lineTo(w * 0.75f, h * 0.65f); lineTo(w / 2, h * 0.55f); lineTo(w * 0.25f, h * 0.65f); close() }, Color.White.copy(alpha = 0.25f))
        }
    }
}

/** The distance, as large as the screen allows, counting up or down as it changes; with GPS error it is "about N m (A M m)" and smaller. */
@Composable
private fun BigDistance(m: Double?, uncertaintyM: Double?) {
    val p = LocalCall.current
    if (m == null) return
    val shown by animateIntAsState(m.roundToInt(), tween(700), label = "roll")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$shown", fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = if (shown >= 1000) 84.sp else 112.sp, lineHeight = if (shown >= 1000) 84.sp else 112.sp, letterSpacing = (-7).sp, color = p.ink)
        Text(" m", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = p.inkMuted, modifier = Modifier.padding(bottom = 14.dp))
    }
    if (uncertaintyM != null) Text(stringResource(R.string.find_plusminus, uncertaintyM.roundToInt()), style = MaterialTheme.typography.titleMedium, color = p.inkMuted)
    }
}

@Composable
private fun closenessWord(prox: Proximity?) = when (prox) { Proximity.VERY_CLOSE -> stringResource(R.string.find_prox_very_close); Proximity.NEAR -> stringResource(R.string.find_prox_near); Proximity.MEDIUM -> stringResource(R.string.find_prox_medium); Proximity.FAR -> stringResource(R.string.find_prox_far); null -> stringResource(R.string.find_trend_unknown) }

@Composable
private fun trendWord(trend: Trend) = when (trend) { Trend.IMPROVING -> stringResource(R.string.find_trend_improving); Trend.WORSENING -> stringResource(R.string.find_trend_worsening); Trend.STEADY -> stringResource(R.string.find_trend_steady); Trend.UNKNOWN -> stringResource(R.string.find_trend_unknown) }

/** AirTag-style words under the arrow: ahead within 15 degrees, otherwise which way to turn. */
@Composable
private fun Steer(angleDeg: Double?) {
    val p = LocalCall.current
    if (angleDeg == null) return
    val a = app.linkfill.core.find.Angles.normalize180(angleDeg)
    Text(stringResource(when { kotlin.math.abs(a) <= 15 -> R.string.find_ahead; kotlin.math.abs(a) >= 150 -> R.string.find_turn_around; a > 0 -> R.string.find_turn_right; else -> R.string.find_turn_left }),
        style = MaterialTheme.typography.headlineSmall, color = p.ink)
}



/** "What this is based on": every input with its real value, folded away; test tools inside. */
@Composable
private fun Sources(ui: FindUi, onTrustAngle: (Boolean) -> Unit, onGroundTruth: (Double?) -> Unit) {
    val p = LocalCall.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
        Text(stringResource(R.string.find_sources), color = p.ink, modifier = Modifier.weight(1f)); KIcon(KI.CHEVRON, p.ink, 18.dp)
    }
    if (!open) return
    val s = ui.state
    Glass(padding = PaddingValues(14.dp)) {
        @Composable fun line(k: String, v: String) = Row(Modifier.padding(vertical = 3.dp)) {
            Text(k, style = MaterialTheme.typography.labelLarge, color = p.inkMuted, modifier = Modifier.width(110.dp)); Text(v, style = MaterialTheme.typography.bodyMedium, color = p.ink)
        }
        line("Ranging", ui.caps.summary())
        line("Link", "${ui.phase.name.lowercase().replace('_', ' ')} · ${ui.phaseDetail}")
        line("Phone link", ui.linkMedium ?: if (ui.directLink) "direct" else "none")
        line("Bluetooth", ui.rssi?.let { if (ui.bluetoothHeard) "heard, ${it.filtered?.roundToInt()} dBm filtered (raw ${it.rawLast}), ±${"%.1f".format(it.stdDb)} dB" else "not heard for ${agoText(it.ageMs)}" } ?: "not heard")
        line("GPS", "you ±${ui.myFixAccuracyM?.roundToInt() ?: "?"} m; their fix ${s.targetFixAgeMs?.let { agoText(it) } ?: "none"} old")
        line("Compass", ui.headingUncertainty?.let { stringResource(R.string.find_heading_unc, it.roundToInt()) + if (ui.declinationKnown) ", true north" else ", declination unknown" } ?: "no heading")
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.find_test_tools), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use ranging angle for the arrow (units not yet validated)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = p.ink)
            Switch(checked = ui.trustAngle, onCheckedChange = onTrustAngle)
        }
        var gt by remember { mutableStateOf(ui.groundTruthM?.toString() ?: "") }
        OutlinedTextField(gt, { gt = it; onGroundTruth(it.toDoubleOrNull()) }, label = { Text("Ground-truth distance (m), tags the log") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Session ${ui.sessionId} · log: ${ui.logPath}", style = MaterialTheme.typography.labelSmall, color = p.inkMuted)
    }
}
