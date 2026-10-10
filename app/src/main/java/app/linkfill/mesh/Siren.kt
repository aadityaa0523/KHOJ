package app.linkfill.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * A loud rising-and-falling siren on the alarm stream, so people nearby can hear where the person in trouble is,
 * even when the phone is on silent. Alarm volume goes to maximum while it plays and is restored afterwards.
 */
class Siren(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var savedVolume = -1
    val playing get() = track != null

    fun start() {
        if (track != null) return
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
        val t = track ?: return
        track = null
        try { t.stop() } catch (_: IllegalStateException) { }
        t.release()
        if (savedVolume >= 0) try { audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: SecurityException) { }
        savedVolume = -1
    }
}
