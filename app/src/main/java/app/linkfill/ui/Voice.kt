package app.linkfill.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.linkfill.R
import app.linkfill.mesh.MeshController
import app.linkfill.mesh.MeshUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Phone text-to-speech in the phone's language. [say] returns false (and tells the user) when that language has no voice installed. */
class Speaker(private val context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }

    fun say(text: String): Boolean {
        val loc = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val ok = ready && tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE && tts.setLanguage(loc) >= TextToSpeech.LANG_AVAILABLE
        if (!ok) { Toast.makeText(context, context.getString(R.string.voice_no_voice), Toast.LENGTH_LONG).show(); return false }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "khoj")
        return true
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val sp = remember { Speaker(context) }
    DisposableEffect(sp) { onDispose { sp.shutdown() } }
    return sp
}

/**
 * The sentence spoken on the Find screen. [arrowDeg] is null whenever Find does not trust a direction, and then only the
 * distance is said: the voice never claims more than the screen does. Steps are distance / 0.7 m, said as "about".
 */
@Composable
fun spokenFind(name: String, distanceM: Double?, arrowDeg: Double?): String {
    if (distanceM == null) return stringResource(R.string.voice_no_distance, name)
    val steps = (distanceM / 0.7).roundToInt().coerceAtLeast(1)
    val a = arrowDeg?.let { ((it % 360) + 540) % 360 - 180 }
    return when {
        a == null -> stringResource(R.string.voice_far, name, steps)
        abs(a) < 30 -> stringResource(R.string.voice_ahead, name, steps)
        abs(a) > 150 -> stringResource(R.string.voice_behind, name, steps)
        a < 0 -> stringResource(R.string.voice_left, name, steps)
        else -> stringResource(R.string.voice_right, name, steps)
    }
}

private enum class VStage { CHOOSE, LISTEN, HEARD, ERROR }

/**
 * Hold-to-talk replacement: two choices, "say a command" (find someone, SOS) and "say a message" (sent to the group as
 * text). Recognition is the phone's own and prefers offline; it needs the microphone permission, asked only here.
 * Nothing is done until the user taps the confirm button, and SOS only opens the normal SOS countdown.
 */
@Composable
fun VoiceDialog(s: MeshUiState, mesh: MeshController, onFind: (Int) -> Unit, onSos: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(VStage.CHOOSE) }
    var message by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun listen(asMessage: Boolean) {
        message = asMessage
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { stage = VStage.ERROR; return }
        stage = VStage.LISTEN
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                stage = if (heard.isBlank()) VStage.ERROR else VStage.HEARD
            }
            override fun onError(error: Int) { stage = VStage.ERROR }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, (context.resources.configuration.locales[0] ?: Locale.getDefault()).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
        recognizer = r
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen(message) else stage = VStage.ERROR }
    fun start(asMessage: Boolean) {
        message = asMessage
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen(asMessage) else mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // What the command means: SOS words, or a group member's name.
    val text = heard.lowercase()
    val sos = !message && listOf("sos", "help", "emergency", "एसओएस", "मदद", "बचाओ", "सहायता", "एमरजेंसी", "எஸ்ஓஎஸ்", "உதவி", "காப்பாற்று", "ஆபத்து").any { text.contains(it) }
    val member = if (!message && !sos) s.members.firstOrNull { text.contains(it.name.lowercase()) } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (stage == VStage.CHOOSE) R.string.voice_title else if (message) R.string.voice_message else R.string.voice_command)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (stage) {
                    VStage.CHOOSE -> {
                        Text(stringResource(R.string.voice_choose), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ start(false) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_command), style = MaterialTheme.typography.titleMedium) }
                        TextButton({ start(true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_message), style = MaterialTheme.typography.titleMedium) }
                    }
                    VStage.LISTEN -> Text(stringResource(R.string.voice_listening), style = MaterialTheme.typography.headlineSmall)
                    VStage.HEARD -> {
                        Text("“$heard”", style = MaterialTheme.typography.headlineSmall)
                        if (!message && !sos && member == null) Text(stringResource(R.string.voice_not_understood), style = MaterialTheme.typography.bodyMedium)
                        else Text(stringResource(R.string.voice_confirm_note), style = MaterialTheme.typography.bodyMedium)
                    }
                    VStage.ERROR -> Text(stringResource(R.string.voice_error), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when {
                stage == VStage.HEARD && message -> TextButton({ mesh.sendText(heard); onDismiss() }) { Text(stringResource(R.string.voice_send)) }
                stage == VStage.HEARD && sos -> TextButton({ onDismiss(); onSos() }) { Text(stringResource(R.string.voice_open_sos)) }
                stage == VStage.HEARD && member != null -> TextButton({ onDismiss(); onFind(member.originId) }) { Text(stringResource(R.string.voice_find, member.name)) }
                stage == VStage.HEARD || stage == VStage.ERROR -> TextButton({ stage = VStage.CHOOSE }) { Text(stringResource(R.string.voice_again)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.light_cancel)) } },
    )
}
