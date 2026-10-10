package app.linkfill.mesh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import app.linkfill.R
import app.linkfill.core.estimate.FallDetector
import app.linkfill.core.estimate.ShakeDetector
import app.linkfill.core.mesh.SosKind
import java.util.Locale

/**
 * Pendant safety: watches the accelerometer for a fall or a panic shake, then runs a 10-second spoken
 * countdown in the app language with vibration. If nobody cancels, [onAlarm] fires the SOS.
 * The countdown is the guard against false alarms (sitting down hard, playful shaking).
 */
class SafetyMonitor(private val context: Context, private val onCountdown: (SosKind?, Long) -> Unit, private val onAlarm: (SosKind) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var fall = FallDetector()
    private var shake = ShakeDetector()
    private var tts: TextToSpeech? = null
    private var pending: SosKind? = null
    private var deadline = 0L
    var running = false
        private set

    fun start() {
        if (running) return
        val acc = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        fall = FallDetector(); shake = ShakeDetector()
        sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME) // about 50 Hz
        // Speak in the app's language; fall back to English when the phone has no voice for it.
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val want = context.resources.configuration.locales[0]
            val ok = tts?.setLanguage(Locale(want.language, "IN"))
            if (ok == null || ok < TextToSpeech.LANG_AVAILABLE) tts?.language = Locale.ENGLISH
        }
        running = true
    }

    fun stop() {
        sensors?.unregisterListener(this)
        tts?.shutdown(); tts = null
        running = false
        cancel()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp / 1_000_000
        val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
        val fell = fall.add(t, x, y, z)
        val shook = shake.add(t, x, y, z)
        if (pending == null && (fell || shook)) begin(if (fell) SosKind.FALL else SosKind.PANIC)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun begin(kind: SosKind) {
        pending = kind
        deadline = System.currentTimeMillis() + COUNTDOWN_MS
        onCountdown(kind, deadline)
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 300, 400, 300, 400), -1)) } catch (_: SecurityException) { }
        val prompt = context.getString(if (kind == SosKind.FALL) R.string.tts_fall else R.string.tts_panic)
        tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "prompt")
        tts?.speak(prompt, TextToSpeech.QUEUE_ADD, null, "repeat")
    }

    /** Called every tick by the controller; fires the alarm when the countdown runs out. */
    fun tick(now: Long) {
        val k = pending ?: return
        if (now >= deadline) { pending = null; onCountdown(null, 0); onAlarm(k) }
    }

    /** Manual trigger from the SOS button skips nothing: it is already a deliberate action. */
    fun cancel() {
        if (pending == null) return
        pending = null
        tts?.stop()
        vibrator?.cancel()
        onCountdown(null, 0)
    }

    companion object { const val COUNTDOWN_MS = 10_000L }
}
