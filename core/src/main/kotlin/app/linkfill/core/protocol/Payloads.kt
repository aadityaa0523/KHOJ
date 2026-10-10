package app.linkfill.core.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

class PayloadException(message: String) : Exception(message)

/** A measured position from the sender's location provider. NaN speed/bearing means "not reported". */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val fixTimeMs: Long,
) {
    fun isValid(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracyM.isFinite() && accuracyM >= 0f && accuracyM < 100_000f &&
            (speedMps.isNaN() || speedMps in 0f..150f) &&
            (bearingDeg.isNaN() || bearingDeg in 0f..360f) &&
            fixTimeMs > 0

    companion object { const val SIZE = 36 }
}

/** An earlier, possibly lost, fix carried again inside a later message (application-level redundancy). */
data class RedundantFix(val seq: Int, val messageId: Long, val fix: LocationFix) {
    companion object { const val SIZE = 4 + 8 + LocationFix.SIZE }
}

class LocationPayload(val current: LocationFix, val redundant: List<RedundantFix>)

class AckEntry(val seq: Int, val messageId: Long)

class Hello(val publicKey: ByteArray, val resumeSessionId: Long?, val resumeProof: ByteArray?)

class Accept(val publicKey: ByteArray, val confirmTag: ByteArray)

/** One shard of a Reed-Solomon coded test payload. */
class DataChunk(
    val transferId: Int,
    val k: Int,
    val m: Int,
    val index: Int,
    val totalLength: Int,
    val sha256: ByteArray,
    val data: ByteArray,
)

object Payloads {
    const val MAX_REDUNDANT = 4
    const val MAX_ACKS = 32
    const val MAX_RECOVERY_SEQS = 32
    private const val MAX_KEY = 256
    private const val TAG = 32

    fun encodeLocation(current: LocationFix, redundant: List<RedundantFix>): ByteArray {
        require(redundant.size <= MAX_REDUNDANT)
        val b = ByteBuffer.allocate(LocationFix.SIZE + 1 + redundant.size * RedundantFix.SIZE)
        b.putFix(current)
        b.put(redundant.size.toByte())
        for (r in redundant) { b.putInt(r.seq); b.putLong(r.messageId); b.putFix(r.fix) }
        return b.array()
    }

    fun decodeLocation(bytes: ByteArray): LocationPayload = parse(bytes) { b ->
        val current = b.getFix()
        val n = b.get().toInt() and 0xFF
        if (n > MAX_REDUNDANT) throw PayloadException("too many redundant fixes")
        val redundant = List(n) { RedundantFix(b.int, b.long, b.getFix()) }
        if (redundant.any { it.seq <= 0 }) throw PayloadException("bad redundant seq")
        LocationPayload(current, redundant)
    }

    fun encodeAck(entries: List<AckEntry>): ByteArray {
        require(entries.size in 1..MAX_ACKS)
        val b = ByteBuffer.allocate(1 + entries.size * 12)
        b.put(entries.size.toByte())
        for (e in entries) { b.putInt(e.seq); b.putLong(e.messageId) }
        return b.array()
    }

    fun decodeAck(bytes: ByteArray): List<AckEntry> = parse(bytes) { b ->
        val n = b.get().toInt() and 0xFF
        if (n !in 1..MAX_ACKS) throw PayloadException("bad ack count")
        List(n) { AckEntry(b.int, b.long) }
    }

    fun encodeSeqs(seqs: List<Int>): ByteArray {
        require(seqs.size in 1..MAX_RECOVERY_SEQS)
        val b = ByteBuffer.allocate(1 + seqs.size * 4)
        b.put(seqs.size.toByte())
        seqs.forEach(b::putInt)
        return b.array()
    }

    fun decodeSeqs(bytes: ByteArray): List<Int> = parse(bytes) { b ->
        val n = b.get().toInt() and 0xFF
        if (n !in 1..MAX_RECOVERY_SEQS) throw PayloadException("bad seq count")
        List(n) { b.int }.also { if (it.any { s -> s <= 0 }) throw PayloadException("bad seq") }
    }

    /** Heartbeat: the lowest sequence number the sender can still recover (everything below is settled). */
    fun encodeHeartbeat(lowWater: Int): ByteArray = ByteBuffer.allocate(4).putInt(lowWater).array()

    fun decodeHeartbeat(bytes: ByteArray): Int = parse(bytes) { it.int }

    fun encodeError(code: Int): ByteArray = byteArrayOf(code.toByte())

    fun decodeError(bytes: ByteArray): Int = parse(bytes) { it.get().toInt() and 0xFF }

    fun encodeHello(h: Hello): ByteArray {
        val resume = h.resumeSessionId != null && h.resumeProof != null
        val b = ByteBuffer.allocate(2 + h.publicKey.size + 1 + if (resume) 8 + TAG else 0)
        b.putShort(h.publicKey.size.toShort()); b.put(h.publicKey)
        b.put(if (resume) 1 else 0)
        if (resume) { b.putLong(h.resumeSessionId!!); b.put(h.resumeProof!!) }
        return b.array()
    }

    fun decodeHello(bytes: ByteArray): Hello = parse(bytes) { b ->
        val key = b.getSized()
        when (b.get().toInt()) {
            0 -> Hello(key, null, null)
            1 -> Hello(key, b.long, ByteArray(TAG).also { b.get(it) })
            else -> throw PayloadException("bad resume flag")
        }
    }

    fun encodeAccept(a: Accept): ByteArray {
        require(a.confirmTag.size == TAG)
        return ByteBuffer.allocate(2 + a.publicKey.size + TAG)
            .putShort(a.publicKey.size.toShort()).put(a.publicKey).put(a.confirmTag).array()
    }

    fun decodeAccept(bytes: ByteArray): Accept = parse(bytes) { b ->
        Accept(b.getSized(), ByteArray(TAG).also { b.get(it) })
    }

    fun encodeChunk(c: DataChunk): ByteArray {
        require(c.sha256.size == 32)
        return ByteBuffer.allocate(4 + 3 + 4 + 32 + c.data.size).apply {
            putInt(c.transferId); put(c.k.toByte()); put(c.m.toByte()); put(c.index.toByte())
            putInt(c.totalLength); put(c.sha256); put(c.data)
        }.array()
    }

    fun decodeChunk(bytes: ByteArray): DataChunk = parse(bytes) { b ->
        val id = b.int
        val k = b.get().toInt() and 0xFF
        val m = b.get().toInt() and 0xFF
        val index = b.get().toInt() and 0xFF
        val total = b.int
        if (k !in 1..64 || m !in 0..64 || index >= k + m || total !in 0..65_536) throw PayloadException("bad chunk header")
        val hash = ByteArray(32).also { b.get(it) }
        val data = ByteArray(b.remaining()).also { b.get(it) }
        DataChunk(id, k, m, index, total, hash, data)
    }

    private fun ByteBuffer.putFix(f: LocationFix) {
        putDouble(f.latitude); putDouble(f.longitude); putFloat(f.accuracyM)
        putFloat(f.speedMps); putFloat(f.bearingDeg); putLong(f.fixTimeMs)
    }

    private fun ByteBuffer.getFix(): LocationFix =
        LocationFix(double, double, float, float, float, long).also {
            if (!it.isValid()) throw PayloadException("invalid location values")
        }

    private fun ByteBuffer.getSized(): ByteArray {
        val n = short.toInt() and 0xFFFF
        if (n !in 1..MAX_KEY) throw PayloadException("bad key length")
        return ByteArray(n).also { get(it) }
    }

    private inline fun <T> parse(bytes: ByteArray, block: (ByteBuffer) -> T): T {
        val b = ByteBuffer.wrap(bytes)
        val result = try {
            block(b)
        } catch (_: BufferUnderflowException) {
            throw PayloadException("truncated payload")
        }
        if (b.hasRemaining()) throw PayloadException("trailing bytes")
        return result
    }
}
