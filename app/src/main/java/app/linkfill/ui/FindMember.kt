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
 * Find: the whole screen becomes the heat of the search. Sound arcs pulse from the centre, faster as the person gets
 * nearer. The dial and arrow appear only in modes where FindResolver allows a direction; every other mode shows what
 * it does know (distance, closeness, a last position) and says why the rest is withheld.
 */
@Composable
fun FindScreen(ui: FindUi?, onStop: () -> Unit, needRangingPermission: Boolean, onAllowRanging: () -> Unit, onTrustAngle: (Boolean) -> Unit, onGroundTruth: (Double?) -> Unit, onLight: (Int, Int) -> Unit = { _, _ -> }) {
    val p = LocalCall.current
    var askLight by remember { mutableStateOf(false) }
    if (ui == null) { CallPage(stringResource(R.string.find_mode_none), onStop) {}; return }
    val s = ui.state
    val hot = fieldHeat(p, s.distanceM, s.proximity)
    val field by animateColorAsState(hot ?: p.ground, tween(900), label = "field")
    val still = rememberReducedMotion()
    // Nearer means a faster pulse: about 0.9 s when within a few metres, 3.2 s when far.
    val dist = s.distanceM
    val periodMs = when { dist != null -> (900 + (dist.coerceIn(2.0, 150.0) / 150.0 * 2300)).toInt(); s.proximity == Proximity.VERY_CLOSE -> 900; s.proximity == Proximity.NEAR -> 1500; else -> 3200 }
    val phase = if (still || hot == null) null else rememberInfiniteTransition(label = "find").animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "pulse")

    if (askLight) LightUpDialog(ui.targetName, onSend = { m, d -> onLight(m, d); askLight = false }, onDismiss = { askLight = false })
    Box(Modifier.fillMaxSize().background(p.ground)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.36f)
            drawRect(Brush.radialGradient(listOf(field.copy(alpha = if (p.night) 0.85f else 0.55f), field.copy(alpha = 0.18f), Color.Transparent), c, size.maxDimension * 0.75f))
            if (hot != null) soundArcs(c, size.width * 0.75f, phase?.value ?: 0.4f, listOf(Color.White, field, field), count = 4, width = 3f)
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CallTopBar(stringResource(R.string.find_title, ui.targetName), onStop)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A position heard in the last 30 s is a GPS reading, not a prediction: label it as such.
            val fresh = (s.targetFixAgeMs ?: Long.MAX_VALUE) <= app.linkfill.core.find.FindResolver.TARGET_RECENT_MS
            ModeChip(if (s.mode == FindMode.PREDICTED && fresh) FindMode.LOCATION_BEARING else s.mode, s.arrowSource == app.linkfill.core.find.Source.BLE_TURN)
                ui.linkMedium?.let { Spacer(Modifier.width(8.dp)); LivePill(it, if (it.startsWith("Wi-Fi")) p.ok else p.sky) }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Hero(ui) }

            // Why: every withheld claim is explained.
            val notes = s.notes.mapNotNull { noteText(it) }
            if (notes.isNotEmpty() || needRangingPermission) Glass(padding = PaddingValues(14.dp)) {
                for (n in notes) Row(Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(p.gold)); Spacer(Modifier.width(10.dp))
                    Text(n, style = MaterialTheme.typography.bodyLarge, color = p.ink)
                }
                if (needRangingPermission) { Spacer(Modifier.height(8.dp)); CallButton(stringResource(R.string.find_allow_ranging), onAllowRanging, icon = KI.RADAR) }
            }
            Sources(ui, onTrustAngle, onGroundTruth)
            CallButton(stringResource(R.string.light_button), { askLight = true }, tone = Tone.QUIET, icon = KI.SIREN)
            CallButton(stringResource(R.string.find_stop), onStop, tone = Tone.QUIET, icon = KI.CLOSE)
            Spacer(Modifier.height(20.dp))
        }
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
    Dial(ui.arrowSmoothedDeg, ui.headingTrue, s.arrowUncertaintyDeg, dim = !precise)
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
    if (ui.arrowSmoothedDeg != null) s.arrowSource?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium, color = p.inkMuted, textAlign = TextAlign.Center) }
}

/** Apple-style Precision Finding navigator. */
@Composable
private fun Dial(angleDeg: Double?, headingDeg: Double?, uncertaintyDeg: Double?, dim: Boolean = false) {
    val p = LocalCall.current
    Box(Modifier.size(320.dp).semantics { contentDescription = "Precision Navigator" }, contentAlignment = Alignment.Center) {
        if (angleDeg != null) {
            val isAhead = Math.abs(angleDeg % 360) < 15 || Math.abs(angleDeg % 360) > 345
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = size.minDimension / 2
                if (isAhead) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF34C759).copy(alpha = 0.3f), Color.Transparent), c, r), r, c)
                }
                rotate(angleDeg.toFloat()) {
                    val w = size.width; val h = size.height
                    val path = Path().apply { moveTo(w / 2, h * 0.15f); lineTo(w * 0.75f, h * 0.65f); lineTo(w / 2, h * 0.55f); lineTo(w * 0.25f, h * 0.65f); close() }
                    val arrowColor = if (isAhead) Color(0xFF34C759) else (if (p.night) Color.White else p.ink)
                    drawPath(path, arrowColor.copy(alpha = if (dim) 0.5f else 1f))
                    val unc = uncertaintyDeg?.coerceIn(0.0, 90.0)?.toFloat()
                    if (unc != null) {
                        drawArc(arrowColor.copy(alpha = 0.15f), -90f - unc, unc * 2, true, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f))
                    }
                }
            }
        } else {
            // Searching: a faint needle sweeps round so it is obvious no direction is being claimed yet.
            val still = rememberReducedMotion()
            val sweep = if (still) null else rememberInfiniteTransition(label = "sweep").animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "needle")
            val ink = if (p.night) Color.White else p.ink
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(ink.copy(alpha = 0.25f), r * 0.8f, center, style = Stroke(2.dp.toPx()))
                rotate(sweep?.value ?: 0f) {
                    drawArc(Brush.sweepGradient(listOf(Color.Transparent, ink.copy(alpha = 0.22f)), center), -150f, 60f, true, Offset(center.x - r * 0.8f, center.y - r * 0.8f), Size(r * 1.6f, r * 1.6f))
                    val w = size.width; val h = size.height
                    drawPath(Path().apply { moveTo(w / 2, h * 0.15f); lineTo(w * 0.75f, h * 0.65f); lineTo(w / 2, h * 0.55f); lineTo(w * 0.25f, h * 0.65f); close() }, ink.copy(alpha = 0.25f))
                }
            }
        }
    }
}

/** The distance, as large as the screen allows; with GPS error it is "about N m (A M m)" and smaller. */
@Composable
private fun BigDistance(m: Double?, uncertaintyM: Double?) {
    val p = LocalCall.current
    if (m == null) return
    if (uncertaintyM != null) Text(stringResource(R.string.find_distance_unc, m.roundToInt(), uncertaintyM.roundToInt()), style = MaterialTheme.typography.headlineMedium, color = p.ink)
    else Text(stringResource(R.string.find_distance, m.roundToInt()), fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 92.sp, lineHeight = 92.sp, color = p.ink)
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
