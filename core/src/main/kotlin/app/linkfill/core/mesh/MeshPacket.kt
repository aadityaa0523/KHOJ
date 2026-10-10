package app.linkfill.core.mesh

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** What a mesh packet carries. Crew types are encrypted with the crew key; public types are described in PublicMessage. */
enum class MeshType(val code: Int) {
    BEACON(1),   // a member's position, name and status, every few seconds
    SOS(2),      // help needed here; repeated until a crew member acknowledges
    RALLY(3),    // meet here at this time
    TEXT(4),     // short message
    ACK(5),      // end-to-end acknowledgement of an SOS or RALLY
    NEIGHBOR(6), // unencrypted filler so neighbours can measure link quality when there is nothing else to send
    // Public messages (see PublicMessage): not sealed with a crew key.
    FOUND(7), FOUND_ACK(8), PUBLIC_SOS(9), RESPOND(10), OFFICIAL(11), FOUND_NOTICE(12),
    LOOKING(13); // crew: "I am trying to find <member>", so they can navigate back to me

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

/**
 * One frame on the air. The link fields ([relayId], [linkSeq], [ttl], [hops]) change at every hop;
 * the end-to-end fields ([type], [crewId], [originId], [packetId], [originTimeS], [body]) never do, and
 * are what the crew key authenticates. Designed to stay under 200 bytes, so the same packet fits a BLE
 * extended advertisement or a LoRa (Meshtastic) payload.
 */
class MeshPacket(
    val type: MeshType,
    val ttl: Int,
    val hops: Int,
    val relayId: Int,
    val linkSeq: Int,
    val crewId: Int,
    val originId: Int,
    val packetId: Int,
    val originTimeS: Long,
    val body: ByteArray,
    val flags: Int = 0,
) {
    val key: Long get() = (originId.toLong() shl 32) or (packetId.toLong() and 0xFFFFFFFFL)

    fun relayed(newRelayId: Int, newLinkSeq: Int) =
        MeshPacket(type, ttl - 1, hops + 1, newRelayId, newLinkSeq, crewId, originId, packetId, originTimeS, body, flags)

    /** Link fields for the next transmission; [lowPower] marks the transmitter (not the originator) as a low-power member. */
    fun onLink(newRelayId: Int, newLinkSeq: Int, lowPower: Boolean = false) =
        MeshPacket(type, ttl, hops, newRelayId, newLinkSeq, crewId, originId, packetId, originTimeS, body,
            (flags and FLAG_LOW_POWER.inv()) or (if (lowPower) FLAG_LOW_POWER else 0))

    /** The phone that put this frame on the air is a low-power member (it sleeps and needs a friend). */
    val fromLowPower get() = flags and FLAG_LOW_POWER != 0

    /** Bytes the crew key authenticates: the end-to-end header fields only. */
    fun aad(): ByteArray = ByteBuffer.allocate(1 + 4 + 4 + 4 + 4).apply {
        put(type.code.toByte()); putInt(crewId); putInt(originId); putInt(packetId); putInt(originTimeS.toInt())
    }.array()

    companion object {
        const val MAGIC = 0x4B // 'K'
        const val VERSION = 1
        const val HEADER = 29
        const val MAX_SIZE = 200
        const val MAX_TTL = 8
        /** Link-level flag; not covered by [aad], so relays may change it. */
        const val FLAG_LOW_POWER = 0x01

        fun encode(p: MeshPacket): ByteArray {
            require(p.body.size <= MAX_SIZE - HEADER) { "body too large" }
            return ByteBuffer.allocate(HEADER + p.body.size).apply {
                put(MAGIC.toByte()); put(VERSION.toByte()); put(p.type.code.toByte()); put(p.flags.toByte())
                put(p.ttl.toByte()); put(p.hops.toByte()); putShort(p.linkSeq.toShort())
                putInt(p.relayId); putInt(p.crewId); putInt(p.originId); putInt(p.packetId); putInt(p.originTimeS.toInt())
                put(p.body.size.toByte()); put(p.body)
            }.array()
        }

        /** Returns null for anything malformed; mesh input comes from anyone in radio range. */
        fun decode(bytes: ByteArray): MeshPacket? {
            if (bytes.size < HEADER || bytes.size > MAX_SIZE) return null
            return try {
                val b = ByteBuffer.wrap(bytes)
                if (b.get().toInt() and 0xFF != MAGIC || b.get().toInt() and 0xFF != VERSION) return null
                val type = MeshType.of(b.get().toInt() and 0xFF) ?: return null
                val flags = b.get().toInt() and 0xFF
                val ttl = b.get().toInt() and 0xFF
                val hops = b.get().toInt() and 0xFF
                val linkSeq = b.short.toInt() and 0xFFFF
                val relayId = b.int; val crewId = b.int; val originId = b.int; val packetId = b.int
                val originTime = b.int.toLong() and 0xFFFFFFFFL
                val len = b.get().toInt() and 0xFF
                if (ttl > MAX_TTL || hops > MAX_TTL || len != b.remaining()) return null
                MeshPacket(type, ttl, hops, relayId, linkSeq, crewId, originId, packetId, originTime, ByteArray(len).also { b.get(it) }, flags)
            } catch (_: BufferUnderflowException) {
                null
            }
        }
    }
}

/** Decoded contents of crew messages. Coordinates are fixed-point (1e-7 degrees) to keep packets small. */
sealed interface MeshMessage {
    /** [findable]: this person has allowed crew members to start a precise-finding session with them. */
    data class Beacon(val lat: Double, val lon: Double, val accuracyM: Int, val battery: Int, val name: String, val findable: Boolean = false) : MeshMessage
    data class Sos(val lat: Double, val lon: Double, val note: String) : MeshMessage
    data class Rally(val lat: Double, val lon: Double, val meetAtS: Long, val label: String) : MeshMessage
    data class Text(val text: String) : MeshMessage
    data class Ack(val originId: Int, val packetId: Int) : MeshMessage
    /** The sender is looking for the crew member whose current node id is [targetId]. */
    data class Looking(val targetId: Int) : MeshMessage

    companion object {
        private const val MAX_TEXT = 100

        fun encode(m: MeshMessage): Pair<MeshType, ByteArray> = when (m) {
            is Beacon -> MeshType.BEACON to buf(4 + 4 + 2 + 1 + 1, m.name) { putLatLon(m.lat, m.lon); putShort(m.accuracyM.coerceIn(0, 65_535).toShort()); put(m.battery.coerceIn(0, 100).toByte()); put(if (m.findable) 1 else 0) }
            is Sos -> MeshType.SOS to buf(8, m.note) { putLatLon(m.lat, m.lon) }
            is Rally -> MeshType.RALLY to buf(12, m.label) { putLatLon(m.lat, m.lon); putInt(m.meetAtS.toInt()) }
            is Text -> MeshType.TEXT to buf(0, m.text) {}
            is Ack -> MeshType.ACK to ByteBuffer.allocate(8).putInt(m.originId).putInt(m.packetId).array()
            is Looking -> MeshType.LOOKING to ByteBuffer.allocate(4).putInt(m.targetId).array()
        }

        fun decode(type: MeshType, b: ByteArray): MeshMessage? = try {
            val bb = ByteBuffer.wrap(b)
            when (type) {
                MeshType.BEACON -> {
                    val (la, lo) = bb.getLatLon()
                    val accuracy = bb.short.toInt() and 0xFFFF
                    val battery = bb.get().toInt()
                    val findable = bb.get().toInt() and 1 == 1
                    Beacon(la, lo, accuracy, battery, bb.rest(), findable)
                }
                MeshType.SOS -> { val (la, lo) = bb.getLatLon(); Sos(la, lo, bb.rest()) }
                MeshType.RALLY -> { val (la, lo) = bb.getLatLon(); Rally(la, lo, bb.int.toLong() and 0xFFFFFFFFL, bb.rest()) }
                MeshType.TEXT -> Text(bb.rest())
                MeshType.ACK -> Ack(bb.int, bb.int)
                MeshType.LOOKING -> Looking(bb.int)
                else -> null
            }?.takeIf { valid(it) }
        } catch (_: BufferUnderflowException) {
            null
        }

        private fun valid(m: MeshMessage): Boolean = when (m) {
            is Beacon -> okLatLon(m.lat, m.lon) && m.battery in 0..100
            is Sos -> okLatLon(m.lat, m.lon)
            is Rally -> okLatLon(m.lat, m.lon)
            else -> true
        }

        private fun okLatLon(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

        private inline fun buf(fixed: Int, text: String, block: ByteBuffer.() -> Unit): ByteArray {
            val t = text.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_TEXT) it.copyOf(MAX_TEXT) else it }
            return ByteBuffer.allocate(fixed + t.size).apply { block(); put(t) }.array()
        }

        private fun ByteBuffer.putLatLon(lat: Double, lon: Double) { putInt((lat * 1e7).toInt()); putInt((lon * 1e7).toInt()) }
        private fun ByteBuffer.getLatLon() = int / 1e7 to int / 1e7
        private fun ByteBuffer.rest() = String(ByteArray(remaining()).also { get(it) }, Charsets.UTF_8)
    }
}
