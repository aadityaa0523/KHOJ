package app.linkfill.core.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

const val PROTOCOL_VERSION = 1

/**
 * Message types. `reliable` messages carry a per-session sequence number and are acknowledged;
 * the others use seq 0 and are idempotent (ACK, heartbeat, recovery request) or self-checking
 * (FEC chunks). Everything after the handshake is encrypted and integrity-protected.
 */
enum class MessageType(val code: Int, val reliable: Boolean, val encrypted: Boolean) {
    SESSION_HELLO(1, reliable = false, encrypted = false),
    SESSION_ACCEPT(2, reliable = false, encrypted = false),
    LOCATION_UPDATE(3, reliable = true, encrypted = true),
    MESSAGE_ACK(4, reliable = false, encrypted = true),
    RECOVERY_REQUEST(5, reliable = false, encrypted = true),
    HEARTBEAT(6, reliable = false, encrypted = true),
    STOP_SHARING(7, reliable = true, encrypted = true),
    ERROR(8, reliable = false, encrypted = true),
    DATA_CHUNK(9, reliable = false, encrypted = true);

    companion object {
        fun fromCode(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}

/** One application message. `body` is plaintext before sealing and ciphertext on the wire. */
class Envelope(
    val type: MessageType,
    val sessionId: Long,
    val senderId: Int,
    val seq: Int,
    val messageId: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val body: ByteArray,
    val flags: Int = 0,
    val version: Int = PROTOCOL_VERSION,
) {
    val isRetransmission: Boolean get() = flags and FLAG_RETRANSMIT != 0

    fun with(body: ByteArray = this.body, flags: Int = this.flags) =
        Envelope(type, sessionId, senderId, seq, messageId, createdAt, expiresAt, body, flags, version)

    companion object {
        const val FLAG_RETRANSMIT = 1
    }
}

enum class RejectReason {
    TOO_SHORT, TOO_LARGE, BAD_MAGIC, UNSUPPORTED_VERSION, UNKNOWN_TYPE, LENGTH_MISMATCH,
    BAD_TIMESTAMPS, EXPIRED, FROM_FUTURE, WRONG_SESSION, WRONG_SENDER, AUTH_FAILED, BAD_PAYLOAD, NOT_READY,
}

sealed interface Decoded {
    class Ok(val envelope: Envelope) : Decoded
    class Rejected(val reason: RejectReason) : Decoded
}

/**
 * Wire format (big-endian), 47-byte header then body:
 * magic "LF" u16 | version u8 | type u8 | flags u8 | sessionId i64 | senderId i32 | seq i32 |
 * messageId i64 | createdAt i64 | expiresAt i64 | bodyLength u16 | body
 */
object MessageCodec {
    const val MAGIC = 0x4C46
    const val HEADER_SIZE = 47
    const val MAX_BODY = 1024
    const val MAX_TTL_MS = 10 * 60_000L
    /** Tolerated clock difference between the two phones after the handshake offset is applied. */
    const val CLOCK_SKEW_MS = 5_000L

    /** Authenticated header bytes: the whole header except the body length, which sealing changes. */
    fun aad(e: Envelope): ByteArray = header(e, 0).copyOf(HEADER_SIZE - 2)

    fun encode(e: Envelope): ByteArray {
        require(e.body.size <= MAX_BODY) { "body too large: ${e.body.size}" }
        return header(e, e.body.size) + e.body
    }

    /**
     * Parses and validates [bytes]. [peerNow] is the current time on the peer's clock; null skips the
     * freshness checks (used only for handshake messages, before the clock offset is known).
     */
    fun decode(bytes: ByteArray, peerNow: Long?): Decoded {
        if (bytes.size < HEADER_SIZE) return Decoded.Rejected(RejectReason.TOO_SHORT)
        if (bytes.size > HEADER_SIZE + MAX_BODY) return Decoded.Rejected(RejectReason.TOO_LARGE)
        val b = ByteBuffer.wrap(bytes)
        try {
            if (b.short.toInt() and 0xFFFF != MAGIC) return Decoded.Rejected(RejectReason.BAD_MAGIC)
            val version = b.get().toInt() and 0xFF
            if (version != PROTOCOL_VERSION) return Decoded.Rejected(RejectReason.UNSUPPORTED_VERSION)
            val type = MessageType.fromCode(b.get().toInt() and 0xFF)
                ?: return Decoded.Rejected(RejectReason.UNKNOWN_TYPE)
            val flags = b.get().toInt() and 0xFF
            val sessionId = b.long
            val senderId = b.int
            val seq = b.int
            val messageId = b.long
            val createdAt = b.long
            val expiresAt = b.long
            val bodyLength = b.short.toInt() and 0xFFFF
            if (bodyLength != bytes.size - HEADER_SIZE) return Decoded.Rejected(RejectReason.LENGTH_MISMATCH)
            if (seq < 0 || (type.reliable && seq == 0) || createdAt <= 0 || expiresAt <= createdAt ||
                expiresAt - createdAt > MAX_TTL_MS
            ) return Decoded.Rejected(RejectReason.BAD_TIMESTAMPS)
            if (peerNow != null) {
                if (peerNow > expiresAt + CLOCK_SKEW_MS) return Decoded.Rejected(RejectReason.EXPIRED)
                if (createdAt > peerNow + CLOCK_SKEW_MS) return Decoded.Rejected(RejectReason.FROM_FUTURE)
            }
            val body = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            return Decoded.Ok(Envelope(type, sessionId, senderId, seq, messageId, createdAt, expiresAt, body, flags, version))
        } catch (_: BufferUnderflowException) {
            return Decoded.Rejected(RejectReason.TOO_SHORT)
        }
    }

    private fun header(e: Envelope, bodyLength: Int): ByteArray = ByteBuffer.allocate(HEADER_SIZE).apply {
        putShort(MAGIC.toShort())
        put(e.version.toByte())
        put(e.type.code.toByte())
        put(e.flags.toByte())
        putLong(e.sessionId)
        putInt(e.senderId)
        putInt(e.seq)
        putLong(e.messageId)
        putLong(e.createdAt)
        putLong(e.expiresAt)
        putShort(bodyLength.toShort())
    }.array()
}
