package app.linkfill.ui

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.R
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.mesh.CrewMember
import app.linkfill.core.voice.VoiceTarget
import app.linkfill.find.FindUi
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Prototype v3 radar geometry: a 390 x 560 viewBox, log-scale rings, far people pinned to the edge.
private const val PROTO_W = 390f
private fun protoRadius(d: Double): Float {
    val c = d.coerceIn(3.0, 250.0)
    return (36 + (ln(c) - ln(3.0)) / (ln(250.0) - ln(3.0)) * 118).toFloat()
}

@Composable
private fun agoShort(ms: Long): String = if (ms < 60_000) stringResource(R.string.secs, (ms / 1000).toInt()) else stringResource(R.string.mins, (ms / 60_000).toInt())

private data class Bubble(val m: CrewMember, val index: Int, val pl: Placed?, val stale: Boolean, val warn: Boolean)

/**
 * Nearby, as in prototype v3: a map-like radar fills the screen (safe-zone disc, your heading cone, sonar pulses, people as
 * bubbles at their true bearing on a log distance scale, far people pinned to the edge with an arrow, people not heard
 * recently as dashed ghosts), and a glass list slides up over it in three snap heights. People with no known position are
 * listed but not drawn. Distances and directions are GPS-based and the list says how old each one is.
 */
@Composable
fun NearbyScreen(
    s: MeshUiState, mesh: MeshController, findingId: Int?, setNight: (Boolean) -> Unit,
    onFind: (Int) -> Unit, onSos: () -> Unit, onRewind: () -> Unit, onBatteryGuidance: () -> Unit,
    onOpen: (VoiceTarget) -> Unit,
) {
    val p = LocalCall.current
    val me = s.myFix
    var voice by remember { mutableStateOf(false) }
    if (voice) VoiceDialog(s, mesh, setNight = setNight, onFind = onFind, onSos = onSos, onOpen = onOpen, onDismiss = { voice = false })

    val bubbles = s.members.mapIndexed { i, m ->
        val pl = if (me != null && MeshController.hasPosition(m.lat, m.lon)) Placed(RadarPoint(m.originId.toLong(), m.name, m.lat, m.lon, false, false, ""),
            GapEstimator.distanceM(me.latitude, me.longitude, m.lat, m.lon), GapEstimator.bearingDeg(me.latitude, me.longitude, m.lat, m.lon)) else null
        Bubble(m, i, pl, s.now - m.heardAt >= 45_000, warnOutside(m, s))
    }
    val live = bubbles.count { !it.stale }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val hPx = with(density) { maxHeight.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        val peek = hPx * 0.40f; val mid = hPx * 0.60f; val full = hPx * 0.90f
        val sheetH = remember(hPx) { Animatable(peek) }
        val scope = rememberCoroutineScope()
        val frac = ((sheetH.value - peek) / (full - peek)).coerceIn(0f, 1f)
        // k: pixels per prototype unit. The 250 m ring (radius 154) must fit above the peeking sheet, below the top bar.
        val topBar = with(density) { 112.dp.toPx() }
        val k = min(wPx / PROTO_W, (hPx - peek - topBar) / (2 * 190f))
        val cx = wPx / 2f
        val cy = topBar + (hPx - peek - topBar) / 2f

        // Radar layer, lifted and shrunk a little as the sheet rises.
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = -(sheetH.value - peek) * 0.55f
            val sc = 1f - (sheetH.value - peek) / (full - peek) * 0.28f
            scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, cy / hPx)
        }) {
            RadarLayer(bubbles, s, k, cx, cy, findingId, onFind)
        }

        // Top bar: group pill, voice, SOS.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(44.dp).shadow(6.dp, RoundedCornerShape(22.dp), ambientColor = p.shadowColor, spotColor = p.shadowColor).clip(RoundedCornerShape(22.dp))
                    .background(p.glassStrong).border(BorderStroke(1.dp, p.gline), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.nearby_group), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.ink)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nearby_count, live), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.inkMuted)
            }
            Spacer(Modifier.weight(1f))
            GlassIconButton(KI.MIC, stringResource(R.string.voice_mic)) { voice = true }
            Spacer(Modifier.width(8.dp))
            SosPillButton(onClick = onSos)
        }

        // Alerts that must not be missed sit above the radar, not inside the list.
        Column(Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 64.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.running) Row(verticalAlignment = Alignment.CenterVertically) {
                LivePill(stringResource(R.string.status_off), p.alarm)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilledTonalButton(onClick = mesh::start, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.turn_on)) }
            }
            s.alerts.firstOrNull()?.let { a -> AlarmCard(a, s, onDismiss = { mesh.dismissAlert(a.key) }, onRewind = if (a.kind == "LOST" && s.rewindFrames > 0) onRewind else null) }
            s.seekers.entries.firstOrNull()?.let { (id, who) ->
                Glass(rim = heatRim(), padding = PaddingValues(14.dp)) {
                    Text(stringResource(R.string.looking_for_you, who), style = MaterialTheme.typography.titleMedium, color = p.ink)
                    Spacer(Modifier.height(8.dp))
                    CallButton(stringResource(R.string.find_them, who), { onFind(id) }, icon = KI.WALK, tone = Tone.HOT)
                }
            }
        }

        // The glass list.
        val expanded = sheetH.value >= full - 4f
        var lastDy = 0f
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { sheetH.value.toDp() })
                .shadow(18.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), ambientColor = p.shadowColor, spotColor = p.shadowColor)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(p.card.copy(alpha = if (p.night) 0.90f else 0.92f))
                .border(BorderStroke(1.dp, p.gline), RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)),
        ) {
            Column(
                Modifier.fillMaxWidth().pointerInput(hPx) {
                    detectVerticalDragGestures(
                        onDragStart = { lastDy = 0f },
                        onVerticalDrag = { _, dy -> lastDy = -dy; scope.launch { sheetH.snapTo((sheetH.value - dy).coerceIn(peek * 0.7f, full)) } },
                        onDragEnd = {
                            val target = listOf(peek, mid, full).minBy { abs(it - (sheetH.value + lastDy * 14f)) }
                            scope.launch { sheetH.animateTo(target, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                    )
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(width = 38.dp, height = 5.dp).clip(CircleShape).background(p.hair))
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.nearby_family), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = p.ink, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.nearby_sort), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.inkMuted)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState(), enabled = expanded).padding(bottom = 24.dp)) {
                if (s.members.isEmpty()) Text(stringResource(R.string.family_empty), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted, modifier = Modifier.padding(20.dp))
                val ordered = bubbles.sortedWith(compareBy<Bubble>({ it.stale }, { it.pl?.distanceM ?: Double.MAX_VALUE }))
                for ((i, b) in ordered.withIndex()) {
                    FamilyRow(b, s, divider = i > 0) { onFind(b.m.originId) }
                }
                if (!s.batteryUnrestricted) Box(Modifier.padding(16.dp)) {
                    Glass(rim = heatRim(), padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KIcon(KI.BATTERY, p.gold, 28.dp); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.battery_guidance_title), style = MaterialTheme.typography.titleMedium, color = p.ink, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.battery_guidance_summary), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        CallButton(stringResource(R.string.battery_guidance_btn), onBatteryGuidance, icon = KI.SETTINGS, tone = Tone.HOT)
                    }
                }
                s.meetPoint?.let { mp ->
                    val d = me?.let { GapEstimator.distanceM(it.latitude, it.longitude, mp.lat, mp.lon) }
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Glass(padding = PaddingValues(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KIcon(KI.PIN, p.gold, 28.dp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.meet_point), style = MaterialTheme.typography.titleMedium, color = p.ink)
                                    Text("${mp.label} (${mp.from})" + if (d != null) " · ${d.roundToInt()} m" else "", style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Outside the group circle, with both GPS errors allowed for (the same rule the alarm uses). */
private fun warnOutside(m: CrewMember, s: MeshUiState): Boolean {
    val me = s.myFix ?: return false
    if (!MeshController.hasPosition(m.lat, m.lon)) return false
    val d = GapEstimator.distanceM(me.latitude, me.longitude, m.lat, m.lon)
    return s.now - m.heardAt < 45_000 && d - me.accuracyM - m.accuracyM > s.circleM
}

@Composable
private fun RadarLayer(bubbles: List<Bubble>, s: MeshUiState, k: Float, cx: Float, cy: Float, findingId: Int?, onFind: (Int) -> Unit) {
    val p = LocalCall.current
    val still = rememberReducedMotion()
    val ping = if (still) null else rememberInfiniteTransition(label = "ping").animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val heading = s.headingDeg ?: 0f
    val summary = bubbles.filter { it.pl != null }.joinToString { "${it.m.name} ${it.pl!!.distanceM.roundToInt()} m" }
    val density = LocalDensity.current
    val ringLabel = remember(p.night) { android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER } }
    Box(Modifier.fillMaxSize().semantics { contentDescription = "Radar: $summary" }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cx, cy)
            val rc = protoRadius(s.circleM.toDouble()) * k
            // Safe-zone disc.
            drawCircle(p.green.copy(alpha = 0.10f), rc, c)
            drawCircle(p.green.copy(alpha = 0.60f), rc, c, style = Stroke(1.5.dp.toPx()))
            // Distance rings and their labels.
            ringLabel.textSize = 10.sp.toPx(); ringLabel.color = p.inkMuted.toArgb()
            for (d in listOf(10, 50, 200)) {
                val r = protoRadius(d.toDouble()) * k
                drawCircle(p.hair, r, c, style = Stroke(1.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText("$d m", c.x, c.y - r - 4.dp.toPx(), ringLabel)
            }
            // Sonar pulses.
            val pr = protoRadius(200.0) * k
            for (off in listOf(0f, 0.5f)) {
                val t = ((ping?.value ?: 0.35f) + off) % 1f
                drawCircle(p.blue.copy(alpha = (1f - t) * 0.5f), pr * (0.1f + 0.9f * t), c, style = Stroke(2.dp.toPx()))
            }
            // Heading cone: where the phone faces is always straight up.
            val cone = Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x - 62 * k, c.y - 150 * k)
                val r = 162 * k
                val start = Math.toDegrees(atan2(-150.0, -62.0)).toFloat()
                arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), start, 2 * (-90f - start), false)
                close()
            }
            drawPath(cone, Brush.verticalGradient(listOf(p.blue.copy(alpha = 0f), p.blue.copy(alpha = 0.38f)), startY = c.y - 162 * k, endY = c.y))
            // Me.
            drawCircle(Color(0x33000000), 12.dp.toPx(), c + Offset(0f, 3.dp.toPx()))
            drawCircle(Color.White, 10.75f * k.coerceAtLeast(0.8f), c)
            drawCircle(p.blue, 7.25f * k.coerceAtLeast(0.8f), c)
            if (s.headingDeg == null) {
                ringLabel.color = p.ink.toArgb(); ringLabel.textSize = 12.sp.toPx()
                drawContext.canvas.nativeCanvas.drawText("N", c.x, c.y - protoRadius(250.0) * k - 10.dp.toPx(), ringLabel)
            }
        }
        // Circle label.
        val rc = protoRadius(s.circleM.toDouble()) * k
        Text(
            stringResource(R.string.circle_ring, s.circleM), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = p.green,
            modifier = Modifier.offset { IntOffset((cx - 48.dp.toPx()).roundToInt(), (cy - rc - 12.dp.toPx()).roundToInt()) }.width(96.dp)
                .clip(RoundedCornerShape(11.dp)).background(p.card).border(1.dp, p.hair, RoundedCornerShape(11.dp)).padding(vertical = 3.dp),
            textAlign = TextAlign.Center, maxLines = 1,
        )
        // Nearest drawn last-to-first so near people sit on top of far ones.
        for (b in bubbles.filter { it.pl != null }.sortedByDescending { it.pl!!.distanceM }) {
            val pl = b.pl!!
            val rel = (pl.bearingDeg - heading) * Math.PI / 180
            val r = protoRadius(pl.distanceM) * k
            val tx = cx + (r * sin(rel)).toFloat(); val ty = cy - (r * cos(rel)).toFloat()
            val x by androidx.compose.animation.core.animateFloatAsState(tx, tween(900, easing = LinearEasing), label = "x")
            val y by androidx.compose.animation.core.animateFloatAsState(ty, tween(900, easing = LinearEasing), label = "y")
            BubbleView(b, pl, (pl.bearingDeg - heading).toFloat(), selected = findingId == b.m.originId, still = still,
                modifier = Modifier.offset { IntOffset((x - 44.dp.toPx()).roundToInt(), (y - 24.dp.toPx()).roundToInt()) }) { onFind(b.m.originId) }
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

@Composable
private fun BubbleView(b: Bubble, pl: Placed, relDeg: Float, selected: Boolean, still: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val p = LocalCall.current
    val edge = pl.distanceM > 250
    val size = if (selected) 48.dp else 42.dp
    val pulse = if (selected && !still) rememberInfiniteTransition(label = "sel").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "s") else null
    Column(modifier.width(88.dp).clickable(onClick = onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(size).graphicsLayer {
                val t = pulse?.value ?: 0.4f; scaleX = 1f + 0.7f * t; scaleY = 1f + 0.7f * t; alpha = 0.8f * (1f - t)
            }.border(2.5.dp, memberColor(b.index), CircleShape))
            Box(Modifier.shadow(4.dp, CircleShape).border(3.dp, Color.White, CircleShape).padding(3.dp)) { MemberAvatar(b.m.name, colorIndex = b.index, size = size - 6.dp, stale = b.stale) }
            if (edge) Canvas(Modifier.size(84.dp).graphicsLayer { rotationZ = relDeg }) {
                val c = center; val w = 14.dp.toPx()
                drawPath(Path().apply { moveTo(c.x, c.y - 37.dp.toPx()); lineTo(c.x + w / 2, c.y - 28.dp.toPx()); lineTo(c.x - w / 2, c.y - 28.dp.toPx()); close() }, memberColor(b.index))
            }
            if (b.warn) Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).size(16.dp).clip(CircleShape).background(p.orange).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Text(b.m.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.ground.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
        Text(if (b.stale) stringResource(R.string.nearby_last_seen) else "${pl.distanceM.roundToInt()} m", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = p.inkMuted,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.ground.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
    }
}

@Composable
private fun FamilyRow(b: Bubble, s: MeshUiState, divider: Boolean, onTap: () -> Unit) {
    val p = LocalCall.current
    val pl = b.pl
    val status = when {
        b.stale -> stringResource(R.string.nearby_stale, agoShort(s.now - b.m.heardAt))
        b.warn -> stringResource(R.string.nearby_outside, agoShort(s.now - b.m.heardAt))
        else -> stringResource(R.string.nearby_heard, agoShort(s.now - b.m.heardAt))
    }
    val statusColor = when { b.stale -> p.inkMuted; b.warn -> p.orange; else -> p.green }
    Box {
        if (divider) Box(Modifier.padding(start = 79.dp).fillMaxWidth().height(1.dp).background(p.hair))
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).pressFeedback(0.98f).clickable(onClick = onTap).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(b.m.name, colorIndex = b.index, size = 46.dp, stale = b.stale)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(b.m.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pl != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${pl.distanceM.roundToInt()}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = p.ink)
                    Text(" m", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.inkMuted, modifier = Modifier.padding(bottom = 2.dp))
                }
                if (!b.stale && s.headingDeg != null) { Spacer(Modifier.width(10.dp)); MiniArrow((pl.bearingDeg - s.headingDeg).toFloat(), p.blue) }
            }
        }
    }
}

// ------------------------------------------------------------------ shell pieces

data class GlassTab(val icon: KI, val label: String, val selected: Boolean, val onClick: () -> Unit)

/** The floating glass tab bar of prototype v3: a rounded pill, the current tab lit in blue on a soft inset. */
@Composable
fun GlassTabBar(tabs: List<GlassTab>, modifier: Modifier = Modifier) {
    val p = LocalCall.current
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier.fillMaxWidth().height(64.dp).shadow(if (p.night) 14.dp else 10.dp, shape, ambientColor = p.shadowColor, spotColor = p.shadowColor)
            .clip(shape).background(p.glassStrong).border(BorderStroke(1.dp, p.gline), shape).padding(5.dp),
    ) {
        for (t in tabs) {
            val tint = if (t.selected) p.blue else p.inkMuted
            Column(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(27.dp))
                    .background(if (t.selected) p.inkMuted.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(onClick = t.onClick),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                KIcon(t.icon, tint, 22.dp)
                Text(t.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Finding Papa, tap to open": a Find in progress stays one tap away from every tab. */
@Composable
fun FindMiniBar(ui: FindUi, index: Int, onOpen: () -> Unit, onStop: () -> Unit) {
    val p = LocalCall.current
    val shape = RoundedCornerShape(29.dp)
    Row(
        Modifier.fillMaxWidth().height(58.dp).shadow(10.dp, shape, ambientColor = p.shadowColor, spotColor = p.shadowColor).clip(shape)
            .background(p.glassStrong).border(BorderStroke(1.dp, p.gline), shape).pressFeedback(0.98f).clickable(onClick = onOpen).padding(start = 11.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(ui.targetName, colorIndex = index, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.mini_finding, ui.targetName), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.mini_open), fontSize = 12.sp, color = p.inkMuted)
        }
        ui.state.distanceM?.let { d ->
            Text("${d.roundToInt()}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
            Text(" m", fontSize = 13.sp, color = p.inkMuted, modifier = Modifier.padding(end = 4.dp))
        }
        ui.arrowSmoothedDeg?.let { MiniArrow(it.toFloat(), p.blue) }
        androidx.compose.material3.IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { KIcon(KI.CLOSE, p.inkMuted, 20.dp, stringResource(R.string.find_stop)) }
    }
}

/** SOS status that must stay visible on every tab: waiting for GPS, who is coming, and the way to stop the siren. */
@Composable
fun SosStatus(s: MeshUiState, mesh: MeshController) {
    val p = LocalCall.current
    if (!(s.sosWaitingForGps && s.now - s.lastSosAt < 600_000) && s.mySosResponders.isEmpty() && !s.sirenOn) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.sosWaitingForGps && s.now - s.lastSosAt < 600_000) Text(stringResource(R.string.sos_no_gps), style = MaterialTheme.typography.bodyMedium, color = p.orange)
        for (r in s.mySosResponders) Text(stringResource(R.string.help_coming_body, r), style = MaterialTheme.typography.titleMedium, color = p.ok)
        if (s.sirenOn) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(p.red).pressFeedback().clickable(onClick = mesh::stopSiren),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                KIcon(KI.SIREN, Color.White, 24.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.stop_siren), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White)
            }
        }
    }
}
