package app.linkfill.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.linkfill.R
import kotlin.math.ln

/**
 * "The Call": finding someone is calling their name across the crowd and hearing the answer get hotter.
 * Night is the default (indigo ground, violet aurora); Day is the same world on soft white for strong sun.
 */
@Immutable
data class CallPalette(
    val night: Boolean,
    val ground: Color,
    val aurora: Color,
    val aurora2: Color,
    val ink: Color,
    val inkMuted: Color,
    val glass: Color,
    val glassStrong: Color,
    val line: Color,
    val sky: Color,
    val violet: Color,
    val fire: Color,
    val gold: Color,
    val alarm: Color,
    val onAlarm: Color,
    val ok: Color,
    val stale: Color,
)

val NightCall = CallPalette(
    night = true, ground = Color(0xFF12123A), aurora = Color(0xFF4A34C9), aurora2 = Color(0xFF3FA9F5),
    ink = Color(0xFFF4F2FF), inkMuted = Color(0xFFBDB8E6), glass = Color(0x1FFFFFFF), glassStrong = Color(0xE01B1A55), line = Color(0x2EFFFFFF),
    sky = Color(0xFF3FA9F5), violet = Color(0xFF8C70FF), fire = Color(0xFFFF6A2B), gold = Color(0xFFFFC23D),
    alarm = Color(0xFFE5233A), onAlarm = Color.White, ok = Color(0xFF3DDC97), stale = Color(0xFF9694B4),
)

/** Day: every hue darkened until text on the light ground passes 4.5:1. */
val DayCall = CallPalette(
    night = false, ground = Color(0xFFF4F2FF), aurora = Color(0xFFC9BCFF), aurora2 = Color(0xFFBFE3FF),
    ink = Color(0xFF14123A), inkMuted = Color(0xFF4E4A78), glass = Color(0xB3FFFFFF), glassStrong = Color(0xF2FFFFFF), line = Color(0x2914123A),
    sky = Color(0xFF0E6BB8), violet = Color(0xFF5A3BE0), fire = Color(0xFFC2410C), gold = Color(0xFF9A6200),
    alarm = Color(0xFFC8102E), onAlarm = Color.White, ok = Color(0xFF0F7A4E), stale = Color(0xFF6D6A8C),
)

val LocalCall = staticCompositionLocalOf { NightCall }

/** Bricolage Grotesque (OFL, bundled in res/font so it works offline). Indic scripts fall back to the phone's Noto fonts. */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(w: Int) = Font(R.font.bricolage, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Bricolage = FontFamily(bricolage(400), bricolage(500), bricolage(600), bricolage(700), bricolage(800))

private fun callTypography(): Typography {
    val t = Typography()
    fun TextStyle.b(w: FontWeight? = null, ls: Float? = null) = copy(fontFamily = Bricolage, fontWeight = w ?: fontWeight, letterSpacing = ls?.sp ?: letterSpacing)
    return Typography(
        displayLarge = t.displayLarge.b(FontWeight.ExtraBold, -1.5f), displayMedium = t.displayMedium.b(FontWeight.ExtraBold, -1f), displaySmall = t.displaySmall.b(FontWeight.ExtraBold, -0.5f),
        headlineLarge = t.headlineLarge.b(FontWeight.ExtraBold), headlineMedium = t.headlineMedium.b(FontWeight.ExtraBold), headlineSmall = t.headlineSmall.b(FontWeight.Bold),
        titleLarge = t.titleLarge.b(FontWeight.Bold), titleMedium = t.titleMedium.b(FontWeight.Bold), titleSmall = t.titleSmall.b(FontWeight.Bold),
        bodyLarge = t.bodyLarge.b(FontWeight.Medium).copy(fontSize = 17.sp, lineHeight = 24.sp), bodyMedium = t.bodyMedium.b(FontWeight.Medium).copy(fontSize = 15.sp, lineHeight = 21.sp), bodySmall = t.bodySmall.b(FontWeight.Medium),
        labelLarge = t.labelLarge.b(FontWeight.Bold), labelMedium = t.labelMedium.b(FontWeight.SemiBold), labelSmall = t.labelSmall.b(FontWeight.SemiBold),
    )
}

/** Material theme driven by the Call palette, so stock buttons, switches, fields and the navigation bar belong to the world. */
@Composable
fun CallTheme(night: Boolean, content: @Composable () -> Unit) {
    val p = if (night) NightCall else DayCall
    val scheme = if (night) darkColorScheme(
        primary = p.violet, onPrimary = Color.White, primaryContainer = Color(0xFF3B2C9E), onPrimaryContainer = p.ink,
        secondary = p.sky, onSecondary = Color(0xFF04213A), secondaryContainer = Color(0xFF283070), onSecondaryContainer = p.ink,
        tertiary = p.gold, onTertiary = Color(0xFF2A1A00),
        background = p.ground, onBackground = p.ink, surface = p.ground, onSurface = p.ink,
        surfaceVariant = Color(0xFF242260), onSurfaceVariant = p.inkMuted, surfaceContainer = Color(0xFF1A1950),
        outline = Color(0x66FFFFFF), outlineVariant = p.line, error = Color(0xFFFF6B7A), onError = Color.Black,
    ) else lightColorScheme(
        primary = p.violet, onPrimary = Color.White, primaryContainer = Color(0xFFE2DAFF), onPrimaryContainer = p.ink,
        secondary = p.sky, onSecondary = Color.White, secondaryContainer = Color(0xFFD6ECFF), onSecondaryContainer = p.ink,
        tertiary = p.gold, onTertiary = Color.White,
        background = p.ground, onBackground = p.ink, surface = p.ground, onSurface = p.ink,
        surfaceVariant = Color(0xFFE6E2FA), onSurfaceVariant = p.inkMuted, surfaceContainer = Color(0xFFECE9FB),
        outline = Color(0x6614123A), outlineVariant = p.line, error = p.alarm, onError = Color.White,
    )
    CompositionLocalProvider(LocalCall provides p) {
        MaterialTheme(colorScheme = scheme, typography = callTypography(), content = content)
    }
}

/**
 * Distance as heat: far is cool sky blue, then violet, fire, and ember gold within a few metres. Log scale, because
 * the difference between 5 and 20 m matters far more in a crowd than 200 and 215 m. Always paired with a number or a
 * word on screen, never colour alone.
 */
fun heat(p: CallPalette, distanceM: Double?): Color {
    if (distanceM == null) return p.stale
    val t = ((ln(distanceM.coerceIn(5.0, 200.0)) - ln(5.0)) / (ln(200.0) - ln(5.0))).toFloat() // 0 near .. 1 far
    return when {
        t < 0.33f -> lerp(p.gold, p.fire, t / 0.33f)
        t < 0.66f -> lerp(p.fire, p.violet, (t - 0.33f) / 0.33f)
        else -> lerp(p.violet, p.sky, (t - 0.66f) / 0.34f)
    }
}

/** True when the phone's "Remove animations" setting is on; the Call then holds still. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The world's ground: indigo (or soft white) with a slow violet and sky aurora drifting behind everything. */
@Composable
fun CallBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalCall.current
    val still = rememberReducedMotion()
    val drift = if (still) null else rememberInfiniteTransition(label = "aurora").animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(modifier.fillMaxSize().background(p.ground)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = drift?.value ?: 0.3f
            drawRect(Brush.radialGradient(listOf(p.aurora.copy(alpha = if (p.night) 0.95f else 0.8f), Color.Transparent),
                center = Offset(size.width * (0.3f + 0.4f * k), -size.height * 0.04f), radius = size.width * 1.25f))
            drawRect(Brush.radialGradient(listOf(p.aurora2.copy(alpha = if (p.night) 0.24f else 0.55f), Color.Transparent),
                center = Offset(size.width * (1.05f - 0.25f * k), size.height * 0.5f), radius = size.width * 0.9f))
        }
        content()
    }
}
