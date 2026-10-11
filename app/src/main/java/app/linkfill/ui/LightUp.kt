package app.linkfill.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.linkfill.R
import app.linkfill.core.mesh.MeshMessage
import app.linkfill.mesh.LitState

private val DURATIONS_S = listOf(30, 60, 300)

/** Asks a family member's phone to light up. The sender picks torch, bright screen and sound, and how long. */
@Composable
fun LightUpDialog(name: String, onSend: (modes: Int, durS: Int) -> Unit, onDismiss: () -> Unit) {
    var modes by rememberSaveable { mutableIntStateOf(MeshMessage.LIGHT_TORCH or MeshMessage.LIGHT_SCREEN) }
    var dur by rememberSaveable { mutableIntStateOf(60) }
    val p = LocalCall.current
    val opts = listOf(
        MeshMessage.LIGHT_TORCH to R.string.light_torch,
        MeshMessage.LIGHT_SCREEN to R.string.light_screen,
        MeshMessage.LIGHT_SOUND to R.string.light_sound,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.light_dialog_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.light_dialog_sub), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                for ((bit, label) in opts) {
                    val on = modes and bit != 0
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(on, role = Role.Checkbox) { modes = if (it) modes or bit else modes and bit.inv() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, onCheckedChange = null)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(stringResource(R.string.light_for), style = MaterialTheme.typography.labelLarge, color = p.inkMuted, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in DURATIONS_S) FilterChip(
                        selected = dur == d, onClick = { dur = d },
                        label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (d < 60) "$d s" else "${d / 60} min", maxLines = 1) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton({ onSend(modes, dur) }, enabled = modes != 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.light_send, name)) } },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.light_cancel)) } },
    )
}

/**
 * Shown on the phone that was asked to light up. The torch and sound run from the service; this screen is the neon part:
 * full brightness, slow colour changes (one cycle takes 0.8 s, so under 3 flashes a second), and one big button to stop.
 * With "remove animations" it stays a steady colour. The text sits on a dark card so it stays readable.
 */
@Composable
fun LitScreen(lit: LitState, onStop: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        val w = activity?.window
        val old = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = old ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val magenta = Color(0xFFFF2BD6); val dark = Color(0xFF0A0A12); val cyan = Color(0xFF12F0FF)
    val neon = lit.modes and MeshMessage.LIGHT_SCREEN != 0
    val still = rememberReducedMotion()
    val colour = if (neon && !still) {
        rememberInfiniteTransition(label = "lit").animateColor(
            initialValue = magenta, targetValue = magenta,
            animationSpec = infiniteRepeatable(keyframes { durationMillis = 800; magenta at 0; dark at 200; cyan at 400; dark at 600 }, RepeatMode.Restart), label = "neon",
        ).value
    } else if (neon) magenta else dark
    Box(Modifier.fillMaxSize().background(colour).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.height(1.dp))
            Column(
                Modifier.fillMaxWidth().background(Color(0xE0080810), RoundedCornerShape(28.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.light_lit_title, lit.by), style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
                Text(stringResource(R.string.light_lit_body), style = MaterialTheme.typography.bodyLarge, color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CallButton(stringResource(R.string.light_see), onStop, modifier = Modifier.fillMaxWidth())
                CallButton(stringResource(R.string.light_off), onStop, modifier = Modifier.fillMaxWidth(), tone = Tone.QUIET)
            }
        }
    }
}
