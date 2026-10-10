package app.linkfill.core.find

import app.linkfill.core.mesh.CrewKey
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * A small authenticated point-to-point channel between two crew members' phones, carried over their direct Nearby
 * connection. It has two jobs: ask a crew member's phone to start a ranging session (with a visible "someone is finding
 * you" prompt on their side), and relay the Android ranging module's out-of-band negotiation bytes
 * (`TransportHandle.sendData` / `onReceiveData`).
 *
 * Frame: 'R' | from(4) | to(4) | sealed. The sealed part is AES-256-GCM under a crew-derived tunnel key with random nonce;
 * the 9 header bytes are associated data, so a frame cannot be redirected to another recipient. Inside: time(8) | kind(1) | payload.
 * Frames older than [MAX_AGE_MS] are rejected, which bounds replay of a recorded REQUEST.
 */
object Tunnel {
    const val MAGIC = 0x52
    const val MAX_AGE_MS = 30_000L
    const val MAX_PAYLOAD = 16_384

    enum class Kind(val code: Int) {
        REQUEST(1), ACCEPT(2), DECLINE(3), DATA(4), STOP(5);
        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } }
    }

    class Msg(val from: Int, val to: Int, val kind: Kind, val payload: ByteArray, val sentAtMs: Long)

    fun seal(key: CrewKey, from: Int, to: Int, kind: Kind, payload: ByteArray, nowMs: Long): ByteArray {
        require(payload.size <= MAX_PAYLOAD)
        val header = header(from, to)
        val plain = ByteBuffer.allocate(9 + payload.size).putLong(nowMs).put(kind.code.toByte()).put(payload).array()
        return header + key.sealBlob(header, plain)
    }

    /** Null for anything that is not for [me], not authentic, too old, or malformed. */
    fun open(key: CrewKey, me: Int, frame: ByteArray, nowMs: Long): Msg? {
        if (frame.size < 9 + 28 || frame.size > 9 + 28 + 9 + MAX_PAYLOAD || (frame[0].toInt() and 0xFF) != MAGIC) return null
        val bb = ByteBuffer.wrap(frame)
        return try {
            bb.get()
            val from = bb.int; val to = bb.int
            if (to != me) return null
            val plain = key.openBlob(frame.copyOfRange(0, 9), frame.copyOfRange(9, frame.size)) ?: return null
            val p = ByteBuffer.wrap(plain)
            val sent = p.long
            val kind = Kind.of(p.get().toInt() and 0xFF) ?: return null
            if (nowMs - sent > MAX_AGE_MS || sent - nowMs > MAX_AGE_MS) return null
            Msg(from, to, kind, ByteArray(p.remaining()).also { p.get(it) }, sent)
        } catch (_: BufferUnderflowException) { null }
    }

    /** True for payloads that belong to the tunnel rather than the mesh (mesh packets start with 'K'). */
    fun isTunnel(frame: ByteArray) = frame.isNotEmpty() && (frame[0].toInt() and 0xFF) == MAGIC

    private fun header(from: Int, to: Int) = ByteBuffer.allocate(9).put(MAGIC.toByte()).putInt(from).putInt(to).array()
}
