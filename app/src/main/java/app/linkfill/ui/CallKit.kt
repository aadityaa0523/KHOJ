package app.linkfill.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ glass

/**
 * Frosted panel: translucent fill over the aurora, a light rim (or a heat-gradient rim for the one panel that owns
 * the screen) and a soft offset shadow, so it reads as a sheet floating above the ground, not a flat card.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    rim: Brush? = null,
    fill: Color? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalCall.current
    Column(
        modifier
            .shadow(if (p.night) 14.dp else 6.dp, shape, ambientColor = Color(0x66050528), spotColor = Color(0x80050528))
            .clip(shape)
            .background(fill ?: p.glass)
            .border(BorderStroke(1.dp, rim ?: Brush.verticalGradient(listOf(p.line.copy(alpha = p.line.alpha * 2.2f), p.line))), shape)
            .padding(padding),
        content = content,
    )
}

/** The rim of the dominant panel: ember gold through violet into sky, the whole heat scale at once. */
@Composable
fun heatRim(): Brush { val p = LocalCall.current; return Brush.linearGradient(listOf(p.gold.copy(alpha = 0.9f), p.violet.copy(alpha = 0.75f), p.sky.copy(alpha = 0.65f))) }

// ------------------------------------------------------------------ icons

/** Line icons drawn on a 24-unit grid, one stroke weight, so the app needs no icon library and no emoji. */
enum class KI { RADAR, GROUP, HELP, SETTINGS, SUN, MOON, QR, SCAN, BAND, REWIND, PIN, BELL, CHEVRON, SHIELD, PLUS, GLOBE, BATTERY, WALK, CHECK, CLOSE, PHONE, SIREN }

@Composable
fun KIcon(kind: KI, tint: Color = LocalCall.current.ink, size: Dp = 24.dp, label: String? = null) {
    Canvas(Modifier.size(size).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, o(x1, y1), o(x2, y2), 2f * u, StrokeCap.Round)
        fun ring(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, o(x, y), style = st)
        fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, o(x, y))
        fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = st)
        fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) = drawArc(tint, start, sweep, false, o(x - r, y - r), Size(2 * r * u, 2 * r * u), style = st)
        when (kind) {
            KI.RADAR -> { dot(12f, 12f, 2.2f); arc(12f, 12f, 5.5f, 200f, 140f); arc(12f, 12f, 9.5f, 195f, 150f); arc(12f, 12f, 5.5f, 20f, 140f); arc(12f, 12f, 9.5f, 15f, 150f) }
            KI.GROUP -> { ring(9f, 8f, 3.2f); path { moveTo(3f, 20f); cubicTo(3f, 15f, 15f, 15f, 15f, 20f) }; ring(17f, 9f, 2.5f); path { moveTo(16f, 14.5f); cubicTo(19f, 14.5f, 21f, 16f, 21f, 19f) } }
            KI.HELP -> { ring(12f, 12f, 9f); line(12f, 7.5f, 12f, 16.5f); line(7.5f, 12f, 16.5f, 12f) }
            KI.SETTINGS -> { ring(12f, 12f, 3f); for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); line(12f + 6f * kotlin.math.cos(a).toFloat(), 12f + 6f * kotlin.math.sin(a).toFloat(), 12f + 9f * kotlin.math.cos(a).toFloat(), 12f + 9f * kotlin.math.sin(a).toFloat()) } }
            KI.SUN -> { ring(12f, 12f, 4f); for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); line(12f + 7f * kotlin.math.cos(a).toFloat(), 12f + 7f * kotlin.math.sin(a).toFloat(), 12f + 9.5f * kotlin.math.cos(a).toFloat(), 12f + 9.5f * kotlin.math.sin(a).toFloat()) } }
            KI.MOON -> path { moveTo(15f, 3.5f); cubicTo(8f, 4f, 5f, 13f, 9.5f, 18f); cubicTo(13f, 21.5f, 19f, 20.5f, 20.5f, 16f); cubicTo(14f, 17.5f, 10.5f, 9f, 15f, 3.5f); close() }
            KI.QR -> { drawRect(tint, o(3f, 3f), Size(7f * u, 7f * u), style = st); drawRect(tint, o(14f, 3f), Size(7f * u, 7f * u), style = st); drawRect(tint, o(3f, 14f), Size(7f * u, 7f * u), style = st); dot(15.5f, 15.5f, 1.4f); dot(19.5f, 15.5f, 1.4f); dot(15.5f, 19.5f, 1.4f); dot(19.5f, 19.5f, 1.4f) }
            KI.SCAN -> { path { moveTo(3f, 8f); lineTo(3f, 3f); lineTo(8f, 3f) }; path { moveTo(16f, 3f); lineTo(21f, 3f); lineTo(21f, 8f) }; path { moveTo(21f, 16f); lineTo(21f, 21f); lineTo(16f, 21f) }; path { moveTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f) }; line(6f, 12f, 18f, 12f) }
            KI.BAND -> { drawRoundRect(tint, o(2.5f, 8f), Size(19f * u, 8f * u), androidx.compose.ui.geometry.CornerRadius(4f * u), style = st); drawRect(tint, o(9f, 9.5f), Size(6f * u, 5f * u)) }
            KI.REWIND -> { path { moveTo(11f, 7f); lineTo(5f, 12f); lineTo(11f, 17f); close() }; path { moveTo(19f, 7f); lineTo(13f, 12f); lineTo(19f, 17f); close() } }
            KI.PIN -> { path { moveTo(12f, 21.5f); cubicTo(5f, 14f, 5f, 10f, 5f, 9.5f); cubicTo(5f, 5.5f, 8f, 2.5f, 12f, 2.5f); cubicTo(16f, 2.5f, 19f, 5.5f, 19f, 9.5f); cubicTo(19f, 10f, 19f, 14f, 12f, 21.5f); close() }; ring(12f, 9.5f, 2.6f) }
            KI.BELL -> { path { moveTo(6f, 17f); lineTo(6f, 11f); cubicTo(6f, 7f, 8.5f, 4.5f, 12f, 4.5f); cubicTo(15.5f, 4.5f, 18f, 7f, 18f, 11f); lineTo(18f, 17f); lineTo(20f, 19f); lineTo(4f, 19f); close() }; line(10.5f, 21.5f, 13.5f, 21.5f) }
            KI.CHEVRON -> path { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
            KI.SHIELD -> { path { moveTo(12f, 2.5f); lineTo(20f, 5.5f); lineTo(20f, 11f); cubicTo(20f, 16f, 16.5f, 19.5f, 12f, 21.5f); cubicTo(7.5f, 19.5f, 4f, 16f, 4f, 11f); lineTo(4f, 5.5f); close() }; path { moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(15.5f, 9.5f) } }
            KI.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            KI.GLOBE -> { ring(12f, 12f, 9f); drawOval(tint, o(8f, 3f), Size(8f * u, 18f * u), style = st); line(3f, 12f, 21f, 12f) }
            KI.BATTERY -> { drawRoundRect(tint, o(2.5f, 7f), Size(17f * u, 10f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = st); line(21.5f, 10.5f, 21.5f, 13.5f) }
            KI.WALK -> { ring(13f, 4f, 2f); path { moveTo(13f, 7.5f); lineTo(11f, 14f); lineTo(14.5f, 17f); lineTo(15.5f, 22f) }; path { moveTo(11f, 14f); lineTo(8.5f, 21.5f) }; path { moveTo(8f, 11f); lineTo(12.5f, 8.5f); lineTo(16.5f, 11.5f) } }
            KI.CHECK -> path { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) }
            KI.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            KI.PHONE -> path { moveTo(6.5f, 3f); lineTo(9.5f, 3.5f); lineTo(11f, 8f); lineTo(8.5f, 9.5f); cubicTo(9.5f, 12f, 12f, 14.5f, 14.5f, 15.5f); lineTo(16f, 13f); lineTo(20.5f, 14.5f); lineTo(21f, 17.5f); cubicTo(21f, 19.5f, 19.5f, 21f, 17.5f, 21f); cubicTo(9f, 20.5f, 3.5f, 15f, 3f, 6.5f); cubicTo(3f, 4.5f, 4.5f, 3f, 6.5f, 3f); close() }
            KI.SIREN -> { path { moveTo(6f, 18f); lineTo(6f, 12f); cubicTo(6f, 8.5f, 8.5f, 6f, 12f, 6f); cubicTo(15.5f, 6f, 18f, 8.5f, 18f, 12f); lineTo(18f, 18f) }; line(4f, 18.5f, 20f, 18.5f); line(12f, 2f, 12f, 3.5f); line(4f, 5f, 5f, 6f); line(20f, 5f, 19f, 6f) }
        }
    }
}

// ------------------------------------------------------------------ scaffolds

/** Top bar for inner pages: the back arrow (system Back does the same), a title, optional actions. */
@Composable
fun CallTopBar(title: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BackArrow(onBack)
        if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall, color = LocalCall.current.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp))
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A scrolling page inside the world: aurora ground, safe insets, a top bar, and content that rises in. */
@Composable
fun CallPage(title: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, bottomPad: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    CallBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CallTopBar(title, onBack, actions)
            Rise { Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
            Spacer(Modifier.height(bottomPad))
        }
    }
}

/**
 * A tab inside the main shell: no back arrow (the navigation bar is the way out), a large title, scrolling content.
 * The shell already paints the ground and handles the bottom insets.
 */
@Composable
fun TabPage(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = LocalCall.current.ink, modifier = Modifier.weight(1f))
            actions()
        }
        Rise { Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
        Spacer(Modifier.height(16.dp))
    }
}

/** One authored entrance: content fades in while sliding up 24 dp with an exponential ease-out. Visible at once when motion is off. */
@Composable
fun Rise(delayMs: Int = 0, content: @Composable () -> Unit) {
    val still = rememberReducedMotion()
    val a = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) a.animateTo(1f, tween(520, delayMs, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = 0.15f + 0.85f * a.value; translationY = (1 - a.value) * 24.dp.toPx() }) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = LocalCall.current.ink, modifier = modifier.padding(top = 6.dp))

// ------------------------------------------------------------------ actions

enum class Tone { PRIMARY, QUIET, ALARM, HOT }

/** The big action button, 64 dp tall: a label that names the action, with an optional drawn icon. */
@Composable
fun CallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: KI? = null, tone: Tone = Tone.PRIMARY, enabled: Boolean = true) {
    val p = LocalCall.current
    val shape = RoundedCornerShape(20.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) { KIcon(icon, tint = if (tone == Tone.QUIET) p.ink else Color.White, size = 22.dp); Spacer(Modifier.width(10.dp)) }
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
    val m = modifier.fillMaxWidth().heightIn(min = 64.dp)
    when (tone) {
        Tone.QUIET -> OutlinedButton(onClick, m, enabled = enabled, shape = shape, border = BorderStroke(1.dp, p.line.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = p.glass, contentColor = p.ink), content = content)
        else -> Button(onClick, m, enabled = enabled, shape = shape, content = content, colors = ButtonDefaults.buttonColors(
            containerColor = when (tone) { Tone.ALARM -> p.alarm; Tone.HOT -> p.fire; else -> p.violet }, contentColor = Color.White))
    }
}

/** A small round glass button for a drawn icon (48 dp target). */
@Composable
fun GlassIconButton(kind: KI, label: String, onClick: () -> Unit) {
    val p = LocalCall.current
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(p.glass).border(1.dp, p.line, CircleShape)) { KIcon(kind, p.ink, 22.dp, label) }
}

/** A row that opens something: drawn icon, title, optional detail, chevron. 64 dp tall. */
@Composable
fun NavRow(icon: KI, title: String, detail: String? = null, onClick: () -> Unit) {
    val p = LocalCall.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(p.glass).border(1.dp, p.line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(p.violet.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { KIcon(icon, p.ink, 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
        }
        KIcon(KI.CHEVRON, p.inkMuted, 20.dp)
    }
}

/** Initial in a heat-coloured disc: who it is, and how near, at a glance. */
@Composable
fun HeatDisc(name: String, color: Color, size: Dp = 40.dp, dashed: Boolean = false) {
    val p = LocalCall.current
    Box(Modifier.size(size).clip(CircleShape).background(if (dashed) Color.Transparent else color).border(if (dashed) 1.5.dp else 0.dp, p.stale, CircleShape),
        contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp, color = if (dashed) p.stale else Color(0xFF12123A))
    }
}

/** Compact status pill with a live dot. */
@Composable
fun LivePill(text: String, color: Color) {
    val p = LocalCall.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(p.glass).border(1.dp, p.line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Draws a heat ripple: [count] broken sound arcs around [center], phase 0..1 pushing them outward. */
fun DrawScope.soundArcs(center: Offset, maxR: Float, phase: Float, colors: List<Color>, count: Int = 5, width: Float = 2.5f) {
    for (i in 0 until count) {
        val f = ((i + phase) / count)
        val r = maxR * f
        if (r < 6f) continue
        val c = colors[(i * colors.size / count).coerceAtMost(colors.size - 1)].copy(alpha = (1f - f) * 0.85f)
        val start = (i * 63f + phase * 40f) % 360f
        for (seg in 0 until 3) drawArc(c, start + seg * 120f, 96f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(width * density, cap = StrokeCap.Round))
    }
}
