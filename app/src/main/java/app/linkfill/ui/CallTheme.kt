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
 * KHOJ Design System: Refined iOS-style glass materials, spring motion, and precision UI.
 * Palettes, member colors, and heat scale copied directly from docs/design/khoj_prototype_v3.html.
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
    val card: Color = if (night) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
    val grp: Color = if (night) Color(0xFF000000) else Color(0xFFF2F2F7),
    val hair: Color = if (night) Color(0x21FFFFFF) else Color(0x293C3C43),
    val blue: Color = Color(0xFF0A84FF),
    val green: Color = if (night) Color(0xFF30D158) else Color(0xFF30B350),
    val orange: Color = if (night) Color(0xFFFF9F0A) else Color(0xFFE58A00),
    val red: Color = Color(0xFFFF3B30),
    val gline: Color = if (night) Color(0x29FFFFFF) else Color(0xBFFFFFFF),
    val shadowColor: Color = if (night) Color(0x73000000) else Color(0x24141E32),
)

// Prototype v3 Night: --bg:#0B0C0E, --fg:#fff, --sec:rgba(235,235,245,.6), --glass:rgba(40,40,46,.55), --gline:rgba(255,255,255,.16), --card:#1C1C1E, --green:#30D158, --orange:#FF9F0A, --shadow:0 10px 30px rgba(0,0,0,.45)
val NightCall = CallPalette(
    night = true,
    ground = Color(0xFF0B0C0E),
    aurora = Color(0xFF1B2024),
    aurora2 = Color(0xFF101316),
    ink = Color(0xFFFFFFFF),
    inkMuted = Color(0x99EBEBF5),
    glass = Color(0x8C28282E),
    glassStrong = Color(0xD91C1C1E),
    line = Color(0x29FFFFFF),
    sky = Color(0xFF4FB6F5),
    violet = Color(0xFFBF5AF2),
    fire = Color(0xFFFF8A1F),
    gold = Color(0xFFFFC23D),
    alarm = Color(0xFFFF3B30),
    onAlarm = Color.White,
    ok = Color(0xFF30D158),
    stale = Color(0x3DFFFFFF),
    card = Color(0xFF1C1C1E),
    grp = Color(0xFF000000),
    hair = Color(0x21FFFFFF),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF3B30),
    gline = Color(0x29FFFFFF),
    shadowColor = Color(0x73000000),
)

// Prototype v3 Day: --bg:#EEF2F6, --fg:#0B0B0F, --sec:rgba(60,60,67,.62), --glass:rgba(255,255,255,.62), --gline:rgba(255,255,255,.75), --hair:rgba(60,60,67,.16), --card:#fff, --grp:#F2F2F7, --blue:#0A84FF, --green:#30B350, --orange:#E58A00, --red:#FF3B30, --shadow:0 10px 30px rgba(20,30,50,.14)
val DayCall = CallPalette(
    night = false,
    ground = Color(0xFFEEF2F6),
    aurora = Color(0xFFFAFCFE),
    aurora2 = Color(0xFFD6E0E8),
    ink = Color(0xFF0B0B0F),
    inkMuted = Color(0x9E3C3C43),
    glass = Color(0x9EFFFFFF),
    glassStrong = Color(0xF2FFFFFF),
    line = Color(0xBFFFFFFF),
    sky = Color(0xFF4FB6F5),
    violet = Color(0xFFBF5AF2),
    fire = Color(0xFFFF8A1F),
    gold = Color(0xFFFFC23D),
    alarm = Color(0xFFFF3B30),
    onAlarm = Color.White,
    ok = Color(0xFF30B350),
    stale = Color(0x293C3C43),
    card = Color(0xFFFFFFFF),
    grp = Color(0xFFF2F2F7),
    hair = Color(0x293C3C43),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30B350),
    orange = Color(0xFFE58A00),
    red = Color(0xFFFF3B30),
    gline = Color(0xBFFFFFFF),
    shadowColor = Color(0x24141E32),
)

val LocalCall = staticCompositionLocalOf { NightCall }

/** Member avatar color palette from prototype v3 (Maa, Papa, Aarav, Didi, Nani). Assigned cyclically by index. */
val MEMBER_COLORS = listOf(
    Color(0xFFFF6482), // Maa
    Color(0xFF32ADE6), // Papa
    Color(0xFFFF9F0A), // Aarav
    Color(0xFFBF5AF2), // Didi
    Color(0xFFA2845E), // Nani
)

fun memberColor(index: Int): Color = MEMBER_COLORS[Math.floorMod(index, MEMBER_COLORS.size)]

fun memberGradient(color: Color): Brush =
    Brush.verticalGradient(listOf(lerp(color, Color.White, 0.28f), color))

/** Bricolage Grotesque (OFL, bundled in res/font so it works offline). Indic scripts fall back to the phone's Noto fonts. */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(w: Int) = Font(R.font.bricolage, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Bricolage = FontFamily(bricolage(400), bricolage(500), bricolage(600), bricolage(700), bricolage(800))

private fun callTypography(): Typography {
    val t = Typography()
    fun TextStyle.b(w: FontWeight? = null, ls: Float? = null) = copy(fontFamily = Bricolage, fontWeight = w ?: fontWeight, letterSpacing = ls?.sp ?: letterSpacing)
    return Typography(
        displayLarge = t.displayLarge.b(FontWeight.Bold, -1.5f),
        displayMedium = t.displayMedium.b(FontWeight.Bold, -1f),
        displaySmall = t.displaySmall.b(FontWeight.Bold, -0.5f),
        headlineLarge = t.headlineLarge.b(FontWeight.Bold, -0.8f),
        headlineMedium = t.headlineMedium.b(FontWeight.Bold, -0.4f),
        headlineSmall = t.headlineSmall.b(FontWeight.SemiBold),
        titleLarge = t.titleLarge.b(FontWeight.Bold),
        titleMedium = t.titleMedium.b(FontWeight.SemiBold),
        titleSmall = t.titleSmall.b(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.b(FontWeight.Normal).copy(fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.b(FontWeight.Normal).copy(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.b(FontWeight.Medium).copy(fontSize = 13.sp),
        labelLarge = t.labelLarge.b(FontWeight.Bold),
        labelMedium = t.labelMedium.b(FontWeight.SemiBold),
        labelSmall = t.labelSmall.b(FontWeight.Medium),
    )
}

/** Material theme driven by the Call palette, so stock buttons, switches, fields and the navigation bar belong to the world. */
@Composable
fun CallTheme(night: Boolean, content: @Composable () -> Unit) {
    val p = if (night) NightCall else DayCall
    val scheme = if (night) darkColorScheme(
        primary = p.blue, onPrimary = Color.White, primaryContainer = Color(0xFF1C1C1E), onPrimaryContainer = p.ink,
        secondary = p.green, onSecondary = Color.Black, secondaryContainer = Color(0xFF28282E), onSecondaryContainer = p.ink,
        tertiary = p.orange, onTertiary = Color.Black,
        background = p.ground, onBackground = p.ink, surface = p.card, onSurface = p.ink,
        surfaceVariant = Color(0xFF28282E), onSurfaceVariant = p.inkMuted, surfaceContainer = Color(0xFF1C1C1E),
        outline = p.hair, outlineVariant = p.line, error = p.alarm, onError = Color.White,
    ) else lightColorScheme(
        primary = p.blue, onPrimary = Color.White, primaryContainer = Color(0xFFF2F2F7), onPrimaryContainer = p.ink,
        secondary = p.green, onSecondary = Color.White, secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = p.ink,
        tertiary = p.orange, onTertiary = Color.White,
        background = p.ground, onBackground = p.ink, surface = p.card, onSurface = p.ink,
        surfaceVariant = Color(0xFFF2F2F7), onSurfaceVariant = p.inkMuted, surfaceContainer = Color(0xFFFFFFFF),
        outline = p.hair, outlineVariant = p.line, error = p.alarm, onError = Color.White,
    )
    CompositionLocalProvider(LocalCall provides p) {
        MaterialTheme(colorScheme = scheme, typography = callTypography(), content = content)
    }
}

/**
 * Distance color scale from prototype v3 heat(d):
 * Piecewise log-scale between 5 m and 200 m:
 * #FF8A1F (near, 5m) -> #FFC23D (~16m) -> #34D399 (~55m) -> #4FB6F5 (far, 200m).
 * Never used on its own; always paired with numbers, words, or directional arrow.
 */
fun heat(p: CallPalette, distanceM: Double?): Color {
    if (distanceM == null) return p.stale
    val d = distanceM.coerceIn(5.0, 200.0)
    val t = ((ln(d) - ln(5.0)) / (ln(200.0) - ln(5.0))).toFloat().coerceIn(0f, 1f)
    val s0 = Color(0xFFFF8A1F) // Near (5 m)
    val s1 = Color(0xFFFFC23D) // ~16 m
    val s2 = Color(0xFF34D399) // ~55 m
    val s3 = Color(0xFF4FB6F5) // Far (200 m)
    val x = t * 3f
    val i = x.toInt().coerceAtMost(2)
    val frac = x - i
    return when (i) {
        0 -> lerp(s0, s1, frac)
        1 -> lerp(s1, s2, frac)
        else -> lerp(s2, s3, frac)
    }
}

/** Returns the background colors for Find screen that darkens the heat color to keep white text passing 4.5:1. */
fun findBackgroundGradients(heatColor: Color): Pair<Color, Color> {
    val hi = lerp(heatColor, Color.Black, 0.40f)
    val base = lerp(heatColor, Color.Black, 0.66f)
    return hi to base
}

/** True when the phone's "Remove animations" setting is on; all animations then hold still. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The ground matching prototype v3 .ground: subtle radial gradient from center-top outward. */
@Composable
fun CallBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalCall.current
    val still = rememberReducedMotion()
    val drift = if (still) null else rememberInfiniteTransition(label = "aurora").animateFloat(
        0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift"
    )
    Box(modifier.fillMaxSize().background(p.ground)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = drift?.value ?: 0.3f
            if (p.night) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1B2024), Color(0xFF101316), Color(0xFF0B0C0E)),
                        center = Offset(size.width * (0.45f + 0.1f * k), size.height * 0.35f),
                        radius = size.width * 1.1f
                    )
                )
            } else {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFFAFCFE), Color(0xFFE4ECF2), Color(0xFFD6E0E8)),
                        center = Offset(size.width * (0.45f + 0.1f * k), size.height * 0.35f),
                        radius = size.width * 1.1f
                    )
                )
            }
        }
        content()
    }
}

