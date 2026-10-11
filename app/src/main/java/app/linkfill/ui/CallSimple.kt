package app.linkfill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.linkfill.R
import app.linkfill.core.voice.VoiceTarget
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState

/**
 * Simple mode of prototype v3, for people who do not read well or are not used to phones: big faces to tap, one giant SOS
 * block, no numbers and no maps. Tapping a face opens Find. Everything else stays in the tab bar below.
 */
@Composable
fun SimpleHome(s: MeshUiState, mesh: MeshController, setNight: (Boolean) -> Unit, onFind: (Int) -> Unit, onSos: () -> Unit, onOpen: (VoiceTarget) -> Unit) {
    val p = LocalCall.current
    var voice by remember { mutableStateOf(false) }
    if (voice) VoiceDialog(s, mesh, setNight = setNight, onFind = onFind, onSos = onSos, onOpen = onOpen, onDismiss = { voice = false })
    val faces = s.members.withIndex().sortedBy { s.now - it.value.heardAt >= 45_000 }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("KHOJ", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = p.ink, modifier = Modifier.weight(1f))
            GlassIconButton(KI.MIC, stringResource(R.string.voice_mic)) { voice = true }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (faces.isEmpty()) Text(stringResource(R.string.family_empty), fontSize = 20.sp, color = p.inkMuted, modifier = Modifier.padding(8.dp))
            for (row in faces.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((i, m) in row) {
                    val stale = s.now - m.heardAt >= 45_000
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).clickable { onFind(m.originId) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(132.dp).clip(RoundedCornerShape(38.dp)).background(if (stale) androidx.compose.ui.graphics.SolidColor(p.hair) else memberGradient(memberColor(i))),
                            contentAlignment = Alignment.Center,
                        ) { Text(m.name.take(1).uppercase(), fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = if (stale) p.inkMuted else Color.White) }
                        Text(m.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = p.ink, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                        Text(stringResource(if (stale) R.string.simple_far else R.string.simple_tap), fontSize = 14.sp, color = p.inkMuted)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        BigSosButton(onClick = onSos)
        Spacer(Modifier.size(12.dp))
    }
}
