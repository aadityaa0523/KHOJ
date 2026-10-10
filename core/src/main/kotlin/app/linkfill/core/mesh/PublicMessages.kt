package app.linkfill.core.mesh

import app.linkfill.core.security.PublicCrypto
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey

/** Why someone needs help. Shown to volunteers and police. */
enum class SosKind(val code: Int, val label: String) {
    MANUAL(0, "SOS"), FALL(1, "Fall detected"), PANIC(2, "Panic (shake)"), LOST(3, "Separated from family");
    companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: MANUAL }
}

enum class Role(val code: Int, val label: String) {
    MEMBER(0, "Member"), VOLUNTEER(1, "Helper"), POLICE(2, "Police");
    companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: MEMBER }
}

/**
 * Messages that cross crew boundaries, so they are not sealed with a crew key:
 * - [Found]: sealed to a wristband's family key (only that family can read it);
 * - [FoundAck]: the family's phone confirms it received a report (carries only the report id);
 * - [PublicSos]: location + kind, for volunteers and police (sent alongside the crew-encrypted SOS);
 * - [Respond]: a volunteer or police officer is on the way;
 * - [Official]: an alert signed by the authority key, verified by every app;
 * - [FoundNotice]: a volunteer found someone without a band; a short description for families to match.
 */
sealed interface PublicMessage {
    class Found(val bandId: Int, val sealed: ByteArray) : PublicMessage
    data class FoundAck(val reportId: Long) : PublicMessage
    data class PublicSos(val kind: SosKind, val lat: Double, val lon: Double, val label: String) : PublicMessage
    data class Respond(val sosOrigin: Int, val sosPacket: Int, val role: Role, val name: String) : PublicMessage
    class Official(val text: String, val signature: ByteArray) : PublicMessage {
        fun verify(authority: PublicKey) = PublicCrypto.verify(authority, signedBytes(text), signature)
    }
    data class FoundNotice(val lat: Double, val lon: Double, val description: String) : PublicMessage

    companion object {
        val TYPES = setOf(MeshType.FOUND, MeshType.FOUND_ACK, MeshType.PUBLIC_SOS, MeshType.RESPOND, MeshType.OFFICIAL, MeshType.FOUND_NOTICE)

        fun signedBytes(text: String) = "KHOJ official v1\n$text".toByteArray(Charsets.UTF_8)

        fun official(text: String, authority: PrivateKey): Official {
            val t = text.utf8(80).toString(Charsets.UTF_8)
            return Official(t, PublicCrypto.sign(authority, signedBytes(t)))
        }

        fun foundFor(band: Band, report: FoundReport): Found? {
            val key = band.publicKey() ?: return null
            return Found(band.bandId, PublicCrypto.sealTo(key, report.encode(), FoundReport.aad(band.crewId, band.bandId)))
        }

        fun encode(m: PublicMessage): Pair<MeshType, ByteArray> = when (m) {
            is Found -> MeshType.FOUND to ByteBuffer.allocate(4 + m.sealed.size).putInt(m.bandId).put(m.sealed).array()
            is FoundAck -> MeshType.FOUND_ACK to ByteBuffer.allocate(8).putLong(m.reportId).array()
            is PublicSos -> MeshType.PUBLIC_SOS to text(9, m.label, 30) { put(m.kind.code.toByte()); latLon(m.lat, m.lon) }
            is Respond -> MeshType.RESPOND to text(9, m.name, 16) { putInt(m.sosOrigin); putInt(m.sosPacket); put(m.role.code.toByte()) }
            is Official -> MeshType.OFFICIAL to m.text.utf8(80).let { t ->
                ByteBuffer.allocate(1 + t.size + 1 + m.signature.size).put(t.size.toByte()).put(t).put(m.signature.size.toByte()).put(m.signature).array()
            }
            is FoundNotice -> MeshType.FOUND_NOTICE to text(8, m.description, 80) { latLon(m.lat, m.lon) }
        }

        fun decode(type: MeshType, b: ByteArray): PublicMessage? = try {
            val bb = ByteBuffer.wrap(b)
            when (type) {
                MeshType.FOUND -> Found(bb.int, ByteArray(bb.remaining()).also { bb.get(it) })
                MeshType.FOUND_ACK -> FoundAck(bb.long).takeIf { !bb.hasRemaining() }
                MeshType.PUBLIC_SOS -> { val k = SosKind.of(bb.get().toInt()); val (la, lo) = bb.latLon(); PublicSos(k, la, lo, bb.rest()) }
                MeshType.RESPOND -> Respond(bb.int, bb.int, Role.of(bb.get().toInt()), bb.rest())
                MeshType.OFFICIAL -> {
                    val t = String(ByteArray(bb.get().toInt() and 0xFF).also { bb.get(it) }, Charsets.UTF_8)
                    val sig = ByteArray(bb.get().toInt() and 0xFF).also { bb.get(it) }
                    Official(t, sig).takeIf { !bb.hasRemaining() }
                }
                MeshType.FOUND_NOTICE -> { val (la, lo) = bb.latLon(); FoundNotice(la, lo, bb.rest()) }
                else -> null
            }
        } catch (_: BufferUnderflowException) { null }

        private fun String.utf8(max: Int): ByteArray = toByteArray(Charsets.UTF_8).let { if (it.size > max) it.copyOf(max) else it }

        private inline fun text(fixed: Int, s: String, max: Int, block: ByteBuffer.() -> Unit): ByteArray {
            val t = s.utf8(max)
            return ByteBuffer.allocate(fixed + t.size).apply { block(); put(t) }.array()
        }

        private fun ByteBuffer.latLon(lat: Double, lon: Double) { putInt((lat * 1e7).toInt()); putInt((lon * 1e7).toInt()) }
        private fun ByteBuffer.latLon() = (int / 1e7) to (int / 1e7)
        private fun ByteBuffer.rest() = String(ByteArray(remaining()).also { get(it) }, Charsets.UTF_8)
    }
}
