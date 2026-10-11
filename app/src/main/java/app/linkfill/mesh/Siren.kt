package app.linkfill.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * A loud rising-and-falling siren on the alarm stream, so people nearby can hear where the person in trouble is,
 * even when the phone is on silent. Alarm volume goes to maximum while it plays and is restored afterwards.
 * The camera flash blinks the international SOS pattern (three short, three long, three short) with it, so the person
 * can also be seen in the dark or a noisy crowd. Dots are 0.2 s on / 0.2 s off (2.5 Hz, under the 3 Hz that can trigger
 * photosensitive seizures). The flash needs no camera permission and is skipped silently on phones without one, or when
 * another app holds the camera.
 */
class Siren(private val context: Context) {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var flashing = false
    private var step = 0
    /** On/off durations in ms, starting with "on": S (dot dot dot), O (dash dash dash), S, then a pause before it repeats. */
    private val morse0 = longArrayOf(200, 200, 200, 200, 200, 600, 600, 200, 600, 200, 600, 600, 200, 200, 200, 200, 200, 1500)

    private var pattern = morse0

    private val torchCamera: String? by lazy { findTorch() }
    private fun torchId() = torchCamera

    private fun findTorch(): String? = try {
        camera?.cameraIdList?.firstOrNull { id -> camera.getCameraCharacteristics(id).let { it.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } }
    } catch (_: CameraAccessException) { null }

    private fun torch(id: String, on: Boolean) = try { camera?.setTorchMode(id, on); true } catch (_: CameraAccessException) { false } catch (_: IllegalArgumentException) { false }

    private val blink = object : Runnable {
        override fun run() {
            if (!flashing) return
            val id = torchId() ?: return
            torch(id, step % 2 == 0) // even steps are "on"
            val d = pattern[step % pattern.size]
            step++
            handler.postDelayed(this, d)
        }
    }

    /** Starts the blinking alone (used by the debug preview to test the flash without sound or sending anything). */
    /** [simple]: a slow even blink (0.5 s on, 0.5 s off) for "light up so I can find you", instead of the SOS pattern. */
    fun startFlash(simple: Boolean = false) { if (flashing) return; pattern = if (simple) longArrayOf(500, 500) else morse0; flashing = true; step = 0; handler.post(blink) }

    fun stopFlash() {
        flashing = false; handler.removeCallbacks(blink)
        torchId()?.let { torch(it, false) }
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var savedVolume = -1
    val playing get() = track != null

    fun start(sound: Boolean = true, flash: Boolean = true, simpleBlink: Boolean = false) {
        if (flash) startFlash(simpleBlink)
        if (!sound || track != null) return
        val rate = 22_050
        val pcm = ShortArray(rate * 2) // one 2 s wail: 650 Hz up to 1500 Hz and back
        var phase = 0.0
        for (i in pcm.indices) {
            val t = i.toDouble() / pcm.size
            val f = 650 + 850 * (if (t < 0.5) t * 2 else (1 - t) * 2)
            phase += 2 * PI * f / rate
            pcm[i] = (sin(phase) * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        audio?.let {
            savedVolume = it.getStreamVolume(AudioManager.STREAM_ALARM)
            try { it.setStreamVolume(AudioManager.STREAM_ALARM, it.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (_: SecurityException) { } // Do Not Disturb can refuse
        }
        t.play()
        track = t
    }

    fun stop() {
        stopFlash()
        val t = track ?: return
        track = null
        try { t.stop() } catch (_: IllegalStateException) { }
        t.release()
        if (savedVolume >= 0) try { audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: SecurityException) { }
        savedVolume = -1
    }
}
