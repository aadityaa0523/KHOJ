package app.linkfill.core.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Anti-entropy cache summary for store-carry-forward healing (Phase 5).
 * Exchanged in place of blind multi-packet offers when nodes meet or reconnect.
 */
data class CacheSummary(
    val epoch: Short,
    val count: Int,
    val isExact: Boolean,
    val bloom: ByteArray,           // 64 bytes if !isExact, empty if isExact
    val digest: ByteArray,          // 16 bytes truncated SHA-256 over sorted keys
    val oldestTimeS: Long,          // 4 bytes
    val newestTimeS: Long,          // 4 bytes
    val exactKeys: IntArray = IntArray(0), // 4 bytes each if isExact
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheSummary) return false
        if (epoch != other.epoch || count != other.count || isExact != other.isExact) return false
        if (!bloom.contentEquals(other.bloom)) return false
        if (!digest.contentEquals(other.digest)) return false
        if (oldestTimeS != other.oldestTimeS || newestTimeS != other.newestTimeS) return false
        return exactKeys.contentEquals(other.exactKeys)
    }

    override fun hashCode(): Int {
        var result = epoch.toInt()
        result = 31 * result + count
        result = 31 * result + isExact.hashCode()
        result = 31 * result + bloom.contentHashCode()
        result = 31 * result + digest.contentHashCode()
        result = 31 * result + oldestTimeS.hashCode()
        result = 31 * result + newestTimeS.hashCode()
        result = 31 * result + exactKeys.contentHashCode()
        return result
    }

    companion object {
        const val BLOOM_BYTES = 64
        const val BLOOM_BITS = 512
        const val NUM_HASHES = 7
        const val DIGEST_BYTES = 16

        /** Computes 7 bit indices in 0..511 using double hashing over SHA-256(key). */
        fun hashIndices(key: Long): IntArray {
            val md = MessageDigest.getInstance("SHA-256")
            val bb = ByteBuffer.allocate(8).putLong(key)
            val hash = md.digest(bb.array())
            val h1 = ByteBuffer.wrap(hash, 0, 4).int and 0x7FFFFFFF
            val h2 = (ByteBuffer.wrap(hash, 4, 4).int and 0x7FFFFFFF) or 1
            return IntArray(NUM_HASHES) { i ->
                ((h1 + i * h2) and 0x7FFFFFFF) % BLOOM_BITS
            }
        }

        fun truncateKey(key: Long): Int =
            ((key ushr 32) xor (key and 0xFFFFFFFFL)).toInt()

        /** Builds a 512-bit Bloom filter byte array for a collection of 64-bit keys. */
        fun buildBloom(keys: Collection<Long>): ByteArray {
            val bits = ByteArray(BLOOM_BYTES)
            for (k in keys) {
                for (idx in hashIndices(k)) {
                    val byteIdx = idx / 8
                    val bitIdx = idx % 8
                    bits[byteIdx] = (bits[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                }
            }
            return bits
        }

        /** Checks if a key is present in the Bloom filter (false if definitely absent). */
        fun bloomContains(bloom: ByteArray, key: Long): Boolean {
            if (bloom.size < BLOOM_BYTES) return false
            for (idx in hashIndices(key)) {
                val byteIdx = idx / 8
                val bitIdx = idx % 8
                if ((bloom[byteIdx].toInt() and (1 shl bitIdx)) == 0) return false
            }
            return true
        }

        /** Computes the 16-byte digest over sorted 64-bit keys. */
        fun computeDigest(keys: Collection<Long>): ByteArray {
            val sorted = keys.sorted()
            val md = MessageDigest.getInstance("SHA-256")
            val bb = ByteBuffer.allocate(sorted.size * 8)
            for (k in sorted) bb.putLong(k)
            val full = md.digest(bb.array())
            return full.copyOf(DIGEST_BYTES)
        }

        fun encode(s: CacheSummary): ByteArray {
            val size = if (s.isExact) {
                2 + 1 + 1 + DIGEST_BYTES + 4 + 4 + (s.exactKeys.size * 4)
            } else {
                2 + 1 + 1 + DIGEST_BYTES + 4 + 4 + BLOOM_BYTES
            }
            val buf = ByteBuffer.allocate(size)
            buf.putShort(s.epoch)
            buf.put(s.count.toByte())
            buf.put(if (s.isExact) 1.toByte() else 0.toByte())
            buf.put(s.digest)
            buf.putInt(s.oldestTimeS.toInt())
            buf.putInt(s.newestTimeS.toInt())
            if (s.isExact) {
                for (k in s.exactKeys) buf.putInt(k)
            } else {
                buf.put(s.bloom)
            }
            return buf.array()
        }

        fun decode(bytes: ByteArray): CacheSummary? {
            if (bytes.size < 2 + 1 + 1 + DIGEST_BYTES + 4 + 4) return null
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val epoch = buf.short
                val count = buf.get().toInt() and 0xFF
                val isExact = buf.get().toInt() == 1
                val digest = ByteArray(DIGEST_BYTES).also { buf.get(it) }
                val oldestTimeS = buf.int.toLong() and 0xFFFFFFFFL
                val newestTimeS = buf.int.toLong() and 0xFFFFFFFFL
                if (isExact) {
                    val remainingInts = buf.remaining() / 4
                    val exactKeys = IntArray(remainingInts) { buf.int }
                    CacheSummary(epoch, count, true, ByteArray(0), digest, oldestTimeS, newestTimeS, exactKeys)
                } else {
                    if (buf.remaining() < BLOOM_BYTES) return null
                    val bloom = ByteArray(BLOOM_BYTES).also { buf.get(it) }
                    CacheSummary(epoch, count, false, bloom, digest, oldestTimeS, newestTimeS)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun fromPackets(epoch: Short, packets: Collection<MeshPacket>, isExact: Boolean = false): CacheSummary {
            val keys = packets.map { it.key }
            val count = keys.size.coerceAtMost(255)
            val digest = computeDigest(keys)
            val oldest = packets.minOfOrNull { it.originTimeS } ?: 0L
            val newest = packets.maxOfOrNull { it.originTimeS } ?: 0L
            return if (isExact) {
                val exact = keys.map { truncateKey(it) }.toIntArray()
                CacheSummary(epoch, count, true, ByteArray(0), digest, oldest, newest, exact)
            } else {
                val bloom = buildBloom(keys)
                CacheSummary(epoch, count, false, bloom, digest, oldest, newest)
            }
        }
    }
}
