package app.linkfill.core.mesh

/**
 * Splits mesh frames (up to [MeshPacket.MAX_SIZE] bytes) into GATT writes or notifications that fit the link's ATT
 * MTU, and joins them again. Two-byte header: frame id, then (index << 4 | count). iPhones commonly negotiate an MTU
 * of 185 (2 fragments per frame); an unnegotiated link has 23 (12 fragments).
 * The same bytes are used by any platform that joins over GATT (docs/GATT_BEARER.md).
 */
object GattFraming {
    const val HEADER = 2
    const val MAX_FRAGMENTS = 15

    /** Payload bytes per ATT write or notification at this MTU. */
    fun chunkFor(mtu: Int) = (mtu - 3 - HEADER).coerceAtLeast(1)

    fun split(frameId: Int, frame: ByteArray, mtu: Int): List<ByteArray> {
        val chunk = chunkFor(mtu)
        val count = (frame.size + chunk - 1) / chunk
        require(count in 1..MAX_FRAGMENTS) { "frame of ${frame.size} bytes needs $count fragments at MTU $mtu" }
        return List(count) { i ->
            val part = frame.copyOfRange(i * chunk, minOf(frame.size, (i + 1) * chunk))
            byteArrayOf(frameId.toByte(), ((i shl 4) or count).toByte()) + part
        }
    }

    /** One per link. Fragments of a frame must arrive in order (true for one GATT link); a broken frame is dropped. */
    class Joiner {
        private var id = -1
        private var next = 0
        private var total = 0
        private var parts = ArrayList<ByteArray>()

        /** Returns a whole frame when its last fragment arrives, else null. Never throws on hostile input. */
        fun add(fragment: ByteArray): ByteArray? {
            if (fragment.size <= HEADER) return null
            val fid = fragment[0].toInt() and 0xFF
            val idx = (fragment[1].toInt() and 0xF0) shr 4
            val count = fragment[1].toInt() and 0x0F
            if (count == 0 || idx >= count) return null
            if (idx == 0) { id = fid; next = 0; total = count; parts = ArrayList() }
            if (fid != id || idx != next || count != total) { id = -1; return null }
            parts += fragment.copyOfRange(HEADER, fragment.size)
            next++
            if (next < count) return null
            id = -1
            val out = parts.fold(ByteArray(0)) { a, b -> a + b }
            return out.takeIf { it.size <= MeshPacket.MAX_SIZE }
        }
    }
}
