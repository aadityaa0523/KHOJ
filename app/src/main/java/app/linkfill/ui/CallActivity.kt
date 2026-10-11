package app.linkfill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.R
import app.linkfill.core.voice.VoiceTarget
import app.linkfill.mesh.FeedItem
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState

/**
 * Activity, as in prototype v3: everything that happened, newest first, in one inset list: alerts you can act on, then
 * messages, meeting points, and SOS calls from the group. This is also where short messages are written or spoken.
 */
@Composable
fun ActivityTab(
    s: MeshUiState, mesh: MeshController, night: Boolean, setNight: (Boolean) -> Unit,
    onFind: (Int) -> Unit, onSos: () -> Unit, onRewind: () -> Unit, onOpen: (VoiceTarget) -> Unit,
) {
    val p = LocalCall.current
    var voice by remember { mutableStateOf(false) }
    var write by remember { mutableStateOf(false) }
    if (voice) VoiceDialog(s, mesh, setNight = setNight, onFind = onFind, onSos = onSos, onOpen = onOpen, onDismiss = { voice = false })
    if (write) WriteDialog(onSend = { mesh.sendText(it); write = false }, onDismiss = { write = false })
    TabPage(stringResource(R.string.tab_activity)) {
        InsetCard {
            ActivityRow(KI.MIC, p.blue, stringResource(R.string.activity_say), stringResource(R.string.activity_say_desc), divider = false) { voice = true }
            ActivityRow(KI.PLUS, p.green, stringResource(R.string.activity_write), stringResource(R.string.activity_write_desc), divider = true) { write = true }
        }
        for (a in s.alerts) AlarmCard(a, s, onDismiss = { mesh.dismissAlert(a.key) }, onRewind = if (a.kind == "LOST" && s.rewindFrames > 0) onRewind else null)
        for ((id, who) in s.seekers) Glass(rim = heatRim(), padding = PaddingValues(14.dp)) {
            Text(stringResource(R.string.looking_for_you, who), style = MaterialTheme.typography.titleMedium, color = p.ink)
            Spacer(Modifier.height(8.dp))
            CallButton(stringResource(R.string.find_them, who), { onFind(id) }, icon = KI.WALK, tone = Tone.HOT)
        }
        Text(stringResource(R.string.activity_earlier), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.inkMuted, modifier = Modifier.padding(start = 20.dp))
        if (s.feed.isEmpty()) Text(stringResource(R.string.activity_empty), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted, modifier = Modifier.padding(horizontal = 20.dp))
        else InsetCard {
            for ((i, f) in s.feed.asReversed().take(40).withIndex()) FeedRow(f, s.now, divider = i > 0)
        }
    }
}

@Composable
private fun FeedRow(f: FeedItem, now: Long, divider: Boolean) {
    val p = LocalCall.current
    val (icon, tile) = when (f.kind) {
        "SOS" -> KI.WARN to p.red
        "LOST" -> KI.WARN to p.orange
        "RALLY" -> KI.PIN to p.green
        else -> KI.BELL to p.blue
    }
    val ago = (now - f.at).coerceAtLeast(0).let { if (it < 60_000) stringResource(R.string.secs, (it / 1000).toInt()) else stringResource(R.string.mins, (it / 60_000).toInt()) }
    ActivityRow(icon, tile, f.text, (if (f.mine) stringResource(R.string.you) else f.fromName) + " · " + stringResource(R.string.seen_ago, ago), divider, onClick = null)
}

/** The row of prototype v3 .li: a coloured rounded tile with a white icon, a title, and a quiet second line. */
@Composable
fun ActivityRow(icon: KI, tile: Color, title: String, subtitle: String?, divider: Boolean, chevron: Boolean = false, onClick: (() -> Unit)?) {
    val p = LocalCall.current
    Box {
        if (divider) Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(p.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.pressFeedback(0.98f).clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(tile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { KIcon(icon, Color.White, 22.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = p.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (chevron) KIcon(KI.CHEVRON, p.inkMuted, 20.dp)
        }
    }
}

@Composable
private fun WriteDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.activity_write)) },
        text = { OutlinedTextField(text, { text = it.take(100) }, label = { Text(stringResource(R.string.activity_hint)) }, supportingText = { Text("${text.length}/100") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onSend(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.activity_send)) } },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.light_cancel)) } },
    )
}
