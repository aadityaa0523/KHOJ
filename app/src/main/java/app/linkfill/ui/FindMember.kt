package app.linkfill.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import app.linkfill.find.LinkPhase
import kotlin.math.roundToInt

private val Good = Color(0xFF1B7F4B)
private val Amber = Color(0xFFB26A00)

/** Compass-style dial. The wedge shows how uncertain the direction is; wider means less trustworthy. */
@Composable
private fun Dial(angleDeg: Double, uncertaintyDeg: Double?, dim: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val color = if (dim) primary.copy(alpha = 0.55f) else primary
    Canvas(Modifier.size(260.dp).semantics { contentDescription = "Direction arrow, ${angleDeg.roundToInt()} degrees from straight ahead" }) {
        val r = size.minDimension / 2
        drawCircle(ring, radius = r, style = Stroke(4.dp.toPx()))
        drawCircle(ring, radius = r * 0.55f, style = Stroke(1.dp.toPx()))
        // Tick marks every 45°, with a notch at the top meaning "straight ahead".
        for (k in 0 until 8) rotate(k * 45f) { drawLine(ring, Offset(size.width / 2, 2f), Offset(size.width / 2, if (k == 0) 26.dp.toPx() else 14.dp.toPx()), 3.dp.toPx()) }
        val unc = uncertaintyDeg?.coerceIn(0.0, 90.0)?.toFloat()
        rotate(angleDeg.toFloat()) {
            if (unc != null) drawArc(color.copy(alpha = 0.18f), -90f - unc, unc * 2, true, Offset(size.width / 2 - r, size.height / 2 - r), Size(r * 2, r * 2))
            val w = size.width; val h = size.height
            val p = Path().apply { moveTo(w / 2, h * 0.10f); lineTo(w * 0.70f, h * 0.60f); lineTo(w / 2, h * 0.50f); lineTo(w * 0.30f, h * 0.60f); close() }
            drawPath(p, color)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp))
}

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
    Note.SIGNAL_IMPROVING, Note.SIGNAL_WORSENING -> null // shown by the trend line itself
}

@Composable
private fun agoText(ms: Long) = if (ms < 60_000) stringResource(R.string.secs, (ms / 1000).toInt()) else stringResource(R.string.mins, (ms / 60_000).toInt())

@Composable
fun FindScreen(ui: FindUi?, onStop: () -> Unit, needRangingPermission: Boolean, onAllowRanging: () -> Unit, onTrustAngle: (Boolean) -> Unit, onGroundTruth: (Double?) -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ui == null) { Text(stringResource(R.string.find_mode_none), fontSize = 20.sp); BackArrow(onStop); return@Column }
        val s = ui.state
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onStop)
            Text(stringResource(R.string.find_title, ui.targetName), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        val modeLabel = when (s.mode) {
            FindMode.PRECISION_DIRECTION -> R.string.find_mode_precision_dir
            FindMode.PRECISION_DISTANCE -> R.string.find_mode_precision_dist
            FindMode.RANGE_WALK -> R.string.find_mode_walk
            FindMode.LOCATION_BEARING -> R.string.find_mode_gps
            FindMode.BLE_PROXIMITY -> R.string.find_mode_ble
            FindMode.LAST_KNOWN -> R.string.find_mode_last
            FindMode.UNAVAILABLE -> R.string.find_mode_none
        }
        val live = s.mode == FindMode.PRECISION_DIRECTION || s.mode == FindMode.PRECISION_DISTANCE || s.mode == FindMode.RANGE_WALK
        Chip(stringResource(modeLabel), if (live) Good else if (s.mode == FindMode.UNAVAILABLE || s.mode == FindMode.LAST_KNOWN) Amber else MaterialTheme.colorScheme.primary)

        // --- hero, one per mode: the UI can only show what the resolver allows ---
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (s.mode) {
                FindMode.PRECISION_DIRECTION -> {
                    Dial(ui.arrowSmoothedDeg ?: 0.0, null)
                    Distance(s.distanceM, null)
                    Text(stringResource(R.string.find_measured_by, s.arrowSource?.label ?: ""), fontSize = 15.sp)
                    Chip(levelText(s.rangingConfidence), Good)
                }
                FindMode.PRECISION_DISTANCE -> {
                    Distance(s.distanceM, null)
                    Text(stringResource(R.string.find_measured_by, s.distanceSource?.label ?: ""), fontSize = 15.sp)
                    Chip(levelText(s.rangingConfidence), Good)
                    if (ui.arrowSmoothedDeg != null) { Dial(ui.arrowSmoothedDeg, s.arrowUncertaintyDeg, dim = true); Text(stringResource(R.string.find_mode_gps), fontSize = 15.sp) }
                    else Text(stringResource(R.string.find_no_direction), fontSize = 16.sp, color = Amber)
                }
                FindMode.RANGE_WALK -> {
                    Dial(ui.arrowSmoothedDeg ?: 0.0, s.arrowUncertaintyDeg, dim = true)
                    Distance(s.distanceM, null)
                    Text(stringResource(R.string.find_measured_by, s.distanceSource?.label ?: ""), fontSize = 15.sp)
                    Chip(levelText(s.rangingConfidence), Good)
                    Text(stringResource(R.string.find_walk_keep), fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                FindMode.LOCATION_BEARING -> {
                    Dial(ui.arrowSmoothedDeg ?: 0.0, s.arrowUncertaintyDeg, dim = true)
                    Distance(s.distanceM, s.distanceUncertaintyM)
                    s.targetFixAgeMs?.let { Text(stringResource(R.string.find_gps_age, agoText(it)), fontSize = 15.sp) }
                }
                FindMode.BLE_PROXIMITY -> ProximityMeter(s.proximity, s.trend)
                FindMode.LAST_KNOWN -> {
                    val word = s.lastKnownBearingTrueDeg?.let { stringArrayResource(R.array.compass)[(((it + 22.5) % 360) / 45).toInt()] } ?: ""
                    val dist = s.distanceM?.let { stringResource(R.string.find_distance, it.roundToInt()) + " " + word } ?: ""
                    Text(stringResource(R.string.find_last_known, dist.trim(), s.targetFixAgeMs?.let { agoText(it) } ?: "?"), fontSize = 22.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.find_no_direction), fontSize = 16.sp, color = Amber)
                }
                FindMode.UNAVAILABLE -> Text(stringResource(R.string.find_no_direction), fontSize = 20.sp, color = Amber, textAlign = TextAlign.Center)
            }
        }

        // --- why: every withheld claim is explained ---
        for (n in s.notes) noteText(n)?.let { Text("• $it", fontSize = 15.sp) }
        if (needRangingPermission) Button(onClick = onAllowRanging, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.find_allow_ranging)) }

        // --- data sources, with real values ---
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.find_sources), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Row { Text("Ranging: ", fontWeight = FontWeight.SemiBold); Text(ui.caps.summary(), fontSize = 14.sp) }
            Row { Text("Link: ", fontWeight = FontWeight.SemiBold); Text("${ui.phase.name.lowercase().replace('_', ' ')} · ${ui.phaseDetail}", fontSize = 14.sp) }
            Row { Text("Direct phone link: ", fontWeight = FontWeight.SemiBold); Text(if (ui.directLink) "yes" else "no", fontSize = 14.sp) }
            Row { Text("Bluetooth: ", fontWeight = FontWeight.SemiBold); Text(ui.rssi?.let { if (ui.bluetoothHeard) "heard, ${it.filtered?.roundToInt()} dBm filtered (raw ${it.rawLast}), ±${"%.1f".format(it.stdDb)} dB" else "not heard for ${agoText(it.ageMs)}" } ?: "not heard", fontSize = 14.sp) }
            Row { Text("GPS: ", fontWeight = FontWeight.SemiBold); Text("you ±${ui.myFixAccuracyM?.roundToInt() ?: "?"} m; their fix ${s.targetFixAgeMs?.let { agoText(it) } ?: "none"} old", fontSize = 14.sp) }
            Row { Text("Compass: ", fontWeight = FontWeight.SemiBold); Text(ui.headingUncertainty?.let { stringResource(R.string.find_heading_unc, it.roundToInt()) + if (ui.declinationKnown) ", true north" else ", declination unknown" } ?: "no heading", fontSize = 14.sp) }
        }

        // --- test tools ---
        var tools by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { tools = !tools }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.find_test_tools)) }
        if (tools) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use ranging angle for the arrow (units not yet validated)", modifier = Modifier.weight(1f), fontSize = 14.sp)
                Switch(checked = ui.trustAngle, onCheckedChange = onTrustAngle)
            }
            var gt by remember { mutableStateOf(ui.groundTruthM?.toString() ?: "") }
            OutlinedTextField(gt, { gt = it; onGroundTruth(it.toDoubleOrNull()) }, label = { Text("Ground-truth distance (m), tags the log") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Session ${ui.sessionId} · log: ${ui.logPath}", fontSize = 12.sp)
        }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(stringResource(R.string.find_stop), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Distance(m: Double?, uncertaintyM: Double?) {
    if (m == null) return
    Text(
        if (uncertaintyM != null) stringResource(R.string.find_distance_unc, m.roundToInt(), uncertaintyM.roundToInt()) else stringResource(R.string.find_distance, m.roundToInt()),
        fontSize = if (uncertaintyM != null) 30.sp else 52.sp, fontWeight = FontWeight.Black,
    )
}

/** Four-step closeness bar plus a trend line. Never draws an arrow: signal strength has no direction. */
@Composable
private fun ProximityMeter(p: Proximity?, trend: Trend) {
    val steps = when (p) { Proximity.VERY_CLOSE -> 4; Proximity.NEAR -> 3; Proximity.MEDIUM -> 2; Proximity.FAR -> 1; null -> 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.width(48.dp).height((28 + i * 28).dp).clip(RoundedCornerShape(10.dp)).background(if (i <= steps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
    }
    Text(when (p) { Proximity.VERY_CLOSE -> stringResource(R.string.find_prox_very_close); Proximity.NEAR -> stringResource(R.string.find_prox_near); Proximity.MEDIUM -> stringResource(R.string.find_prox_medium); Proximity.FAR -> stringResource(R.string.find_prox_far); null -> stringResource(R.string.find_trend_unknown) },
        fontSize = 30.sp, fontWeight = FontWeight.Black)
    val (txt, col) = when (trend) {
        Trend.IMPROVING -> stringResource(R.string.find_trend_improving) to Good
        Trend.WORSENING -> stringResource(R.string.find_trend_worsening) to Amber
        Trend.STEADY -> stringResource(R.string.find_trend_steady) to MaterialTheme.colorScheme.onSurface
        Trend.UNKNOWN -> stringResource(R.string.find_trend_unknown) to MaterialTheme.colorScheme.onSurface
    }
    Text(txt, fontSize = 20.sp, color = col, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.find_ble_help), fontSize = 15.sp, textAlign = TextAlign.Center)
}
