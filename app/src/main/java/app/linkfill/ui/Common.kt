package app.linkfill.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.session.LossProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Light = lightColorScheme(
    primary = Color(0xFF0E5E57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDE8),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFD3E6E2),
    onSecondaryContainer = Color(0xFF0B1F1C),
    background = Color(0xFFF6F8F7),
    surface = Color(0xFFF6F8F7),
    surfaceVariant = Color(0xFFE3EAE8),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6CB),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFCDEDE8),
    secondaryContainer = Color(0xFF314A46),
    onSecondaryContainer = Color(0xFFD3E6E2),
    background = Color(0xFF0F1514),
    surface = Color(0xFF0F1514),
    surfaceVariant = Color(0xFF26302E),
)

@Composable
fun LinkFillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize(), content = content)
    }
}

/** Back button drawn as a left arrow (no icon library needed); screen readers hear "Back". */
@Composable
fun BackArrow(onClick: () -> Unit) {
    val label = stringResource(app.linkfill.R.string.back)
    val color = MaterialTheme.colorScheme.onBackground
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(28.dp)) {
            val w = size.width; val h = size.height; val stroke = Stroke(w * 0.12f, cap = StrokeCap.Round)
            drawPath(Path().apply { moveTo(w * 0.9f, h / 2); lineTo(w * 0.1f, h / 2); moveTo(w * 0.45f, h * 0.15f); lineTo(w * 0.1f, h / 2); lineTo(w * 0.45f, h * 0.85f) }, color, style = stroke)
        }
    }
}

/** Scrollable page with a title row and an optional action on the right. */
@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackArrow(onBack)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        content()
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
fun InfoCard(title: String? = null, container: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** The pairing code, large and spaced so two people can compare it at a glance. */
@Composable
fun AuthCode(digits: String) {
    Text(
        digits.toCharArray().joinToString(" "),
        fontFamily = FontFamily.Monospace,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { contentDescription = "Pairing code ${digits.toCharArray().joinToString(" ")}" },
    )
}

@Composable
fun LossBanner(loss: LossProfile) {
    if (loss == LossProfile.OFF) return
    InfoCard(container = Color(0xFFFFE9C7)) {
        Text("Test mode: simulated loss is ON (${loss.label}).", color = Color(0xFF5C3B00), fontWeight = FontWeight.SemiBold)
        Text("Messages are being dropped on purpose inside the app to show recovery. Turn it off in Diagnostics.", color = Color(0xFF5C3B00), style = MaterialTheme.typography.bodySmall)
    }
}

/** Arrow pointing toward the other phone, relative to where this phone is facing. */
@Composable
fun DirectionArrow(angleDeg: Float, size: Dp = 140.dp) {
    val color = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(size).semantics { contentDescription = "Direction arrow, ${angleDeg.toInt()} degrees from straight ahead" }) {
        drawCircle(ring, radius = this.size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        rotate(angleDeg) {
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w / 2, h * 0.12f)
                lineTo(w * 0.72f, h * 0.62f)
                lineTo(w / 2, h * 0.52f)
                lineTo(w * 0.28f, h * 0.62f)
                close()
            }
            drawPath(p, color)
        }
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

fun clockTime(ms: Long): String = clock.format(Date(ms))

fun ago(ms: Long): String = when {
    ms < 1_000 -> "just now"
    ms < 60_000 -> "${ms / 1000} s ago"
    else -> "${ms / 60_000} min ${(ms / 1000) % 60} s ago"
}

fun coord(v: Double) = "%.6f".format(Locale.US, v)
