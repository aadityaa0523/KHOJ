package app.linkfill.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.core.estimate.GapEstimator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One person (or one help request) on the radar. [label] is the second line under the name. */
data class RadarPoint(val id: Long, val name: String, val lat: Double, val lon: Double, val stale: Boolean, val warn: Boolean, val label: String)

/** Where a point sits relative to me, in metres and degrees true. */
data class Placed(val point: RadarPoint, val distanceM: Double, val bearingDeg: Double)

fun place(points: List<RadarPoint>, myLat: Double, myLon: Double) = points.filter { app.linkfill.mesh.MeshController.hasPosition(it.lat, it.lon) }.map {
    Placed(it, GapEstimator.distanceM(myLat, myLon, it.lat, it.lon), GapEstimator.bearingDeg(myLat, myLon, it.lat, it.lon))
}

/** Radar scale that fits the farthest live person and the group circle, from a fixed ladder so it does not jitter. */
fun radarScaleM(placed: List<Placed>, circleM: Int): Double {
    val far = placed.filter { !it.point.stale }.maxOfOrNull { it.distanceM } ?: 0.0
    val need = maxOf(far * 1.15, circleM * 1.3)
    return listOf(25.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0).firstOrNull { it >= need } ?: 10_000.0
}

/**
 * The Call: me at the centre, sound arcs rippling outward, each person a glass chip at their true bearing (rotated so
 * "up" is where the phone faces, when the compass is usable) and a square-root distance, so the nearest people do not
 * pile onto the centre. Chip colour is distance heat; the number is always printed too. Positions are GPS and say so.
 */
@Composable
fun CallRadar(
    placed: List<Placed>,
    headingDeg: Float?,
    circleM: Int?,
    hasMyFix: Boolean,
    waitingText: String,
    circleLabel: String,
    youLabel: String,
    onTap: (RadarPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalCall.current
    val still = rememberReducedMotion()
    val phase = if (still) null else rememberInfiniteTransition(label = "call").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "arcs")
    val scale = radarScaleM(placed, circleM ?: 0)
    val heatColors = listOf(p.gold, p.fire, p.violet, p.sky, p.sky)
    val summary = placed.joinToString { "${it.point.name} ${it.distanceM.roundToInt()} m" }
    Box(modifier.semantics { contentDescription = "Radar: $summary" }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.minDimension / 2 * 0.92f
            drawCircle(Brush.radialGradient(listOf(p.fire.copy(alpha = if (p.night) 0.34f else 0.22f), p.violet.copy(alpha = 0.18f), Color.Transparent), c, maxR * 0.8f), maxR * 0.8f, c)
            soundArcs(c, maxR, phase?.value ?: 0.35f, heatColors, count = 5)
            // Fixed distance rings with their labels: what the scale means.
            for (f in listOf(0.25, 1.0)) {
                val r = (maxR * sqrt(f)).toFloat()
                drawCircle(p.line, r, c, style = Stroke(1.dp.toPx()))
            }
            if (circleM != null && circleM < scale) {
                val r = (maxR * sqrt(circleM / scale)).toFloat()
                drawCircle(p.ink.copy(alpha = 0.55f), r, c, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
            }
            // Me: an ember core.
            drawCircle(Brush.radialGradient(listOf(p.fire.copy(alpha = 0.7f), Color.Transparent), c, 34.dp.toPx()), 34.dp.toPx(), c)
            drawCircle(Brush.radialGradient(listOf(Color.White, p.gold, p.fire), c, 11.dp.toPx()), 11.dp.toPx(), c)
            // "Up" marker: where the phone faces, or north when the compass is not usable.
            drawLine(p.ink.copy(alpha = 0.7f), Offset(c.x, c.y - maxR - 2.dp.toPx()), Offset(c.x, c.y - maxR + 10.dp.toPx()), 3.dp.toPx())
        }
        // Text layers over the canvas.
        val maxDist = scale
        if (headingDeg == null) Text("N", style = MaterialTheme.typography.labelLarge, color = p.ink, modifier = Modifier.align(Alignment.TopCenter).padding(start = 18.dp))
        if (circleM != null && circleM < scale) Text(circleLabel, style = MaterialTheme.typography.labelMedium, color = p.ink,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(p.glassStrong).padding(horizontal = 8.dp, vertical = 3.dp))
        Text("${maxDist.roundToInt()} m", style = MaterialTheme.typography.labelMedium, color = p.inkMuted, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
        if (!hasMyFix) Text(waitingText, style = MaterialTheme.typography.titleMedium, color = p.ink,
            modifier = Modifier.align(Alignment.Center).padding(top = 90.dp).clip(RoundedCornerShape(12.dp)).background(p.glassStrong).padding(horizontal = 12.dp, vertical = 8.dp))
        Text(youLabel, style = MaterialTheme.typography.labelLarge, color = p.ink, modifier = Modifier.align(Alignment.Center).padding(top = 44.dp))
        RadarChips(placed, headingDeg, maxDist, onTap)
    }
}

/** Lays chips at their polar positions, nudging overlaps downward so every name stays readable. */
@Composable
private fun RadarChips(placed: List<Placed>, headingDeg: Float?, scale: Double, onTap: (RadarPoint) -> Unit) {
    val ordered = placed.sortedBy { it.distanceM }
    Layout(content = { for (pl in ordered) RadarChip(pl, onTap) }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val maxR = minOf(w, h) / 2f * 0.92f
        val loose = Constraints(maxWidth = (w * 0.55f).toInt(), maxHeight = h)
        val placeables = measurables.map { it.measure(loose) }
        val taken = ArrayList<Rect>()
        val spots = placeables.mapIndexed { i, pl ->
            val item = ordered[i]
            val rel = Math.toRadians(item.bearingDeg - (headingDeg ?: 0f))
            val r = maxR * sqrt((item.distanceM / scale).coerceIn(0.02, 1.0)).toFloat()
            var x = w / 2f + r * sin(rel).toFloat() - pl.width / 2f
            var y = h / 2f - r * cos(rel).toFloat() - pl.height / 2f
            x = x.coerceIn(0f, (w - pl.width).toFloat()); y = y.coerceIn(0f, (h - pl.height).toFloat())
            var rect = Rect(x, y, x + pl.width, y + pl.height)
            var guard = 0
            while (taken.any { it.overlaps(rect) } && guard++ < 12) {
                y = (y + pl.height * 0.6f).let { if (it + pl.height > h) 0f else it }
                rect = Rect(x, y, x + pl.width, y + pl.height)
            }
            taken += rect
            x.roundToInt() to y.roundToInt()
        }
        layout(w, h) { placeables.forEachIndexed { i, pl -> pl.place(spots[i].first, spots[i].second) } }
    }
}

@Composable
private fun RadarChip(pl: Placed, onTap: (RadarPoint) -> Unit) {
    val p = LocalCall.current
    val pt = pl.point
    val col = if (pt.stale) p.stale else heat(p, pl.distanceM)
    val shape = RoundedCornerShape(50)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .shadow(if (pt.stale) 0.dp else 10.dp, shape, ambientColor = col, spotColor = col)
                .clip(shape)
                .background(if (pt.stale) p.glass else col.copy(alpha = if (p.night) 0.28f else 0.22f))
                .border(if (pt.warn) 2.5.dp else 1.dp, if (pt.warn) p.alarm else col, shape)
                .clickable { onTap(pt) }
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeatDisc(pt.name, col, 30.dp, dashed = pt.stale)
            Spacer(Modifier.width(7.dp))
            Column {
                Text(pt.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (pt.stale) p.inkMuted else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pt.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (pt.stale) p.inkMuted else p.ink.copy(alpha = 0.85f), maxLines = 1)
            }
        }
    }
}

/** "Nearest Amma 12 m · Farthest Priya 140 m": the span in view, like a dictionary's guide words. */
@Composable
fun GuideWords(placed: List<Placed>, nearestLabel: String, farthestLabel: String) {
    val p = LocalCall.current
    val live = placed.filter { !it.point.stale }
    if (live.isEmpty()) return
    val near = live.minBy { it.distanceM }; val far = live.maxBy { it.distanceM }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        GuideWord(nearestLabel, "${near.point.name} ${near.distanceM.roundToInt()} m", heat(p, near.distanceM))
        if (live.size > 1) GuideWord(farthestLabel, "${far.point.name} ${far.distanceM.roundToInt()} m", heat(p, far.distanceM))
    }
}

@Composable
private fun GuideWord(label: String, value: String, color: Color) {
    val p = LocalCall.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = p.inkMuted)
        Spacer(Modifier.width(6.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (p.night) color else p.ink)
    }
}
