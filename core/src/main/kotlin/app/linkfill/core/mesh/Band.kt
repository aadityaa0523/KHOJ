package app.linkfill.core.mesh

import app.linkfill.core.security.PublicCrypto
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.Base64

/**
 * What a child's (or elder's) printed wristband QR carries. A volunteer's phone reads it fully offline:
 * the name, age, a phone number to call, the family's meeting point, and the family's public key, so the
 * volunteer can send a "found" report that only the family's phone can decrypt.
 * Nothing on the band reveals where the family is.
 */
data class Band(
    val crewId: Int,
    val bandId: Int,
    val familyKey: ByteArray, // compressed P-256 public key, 33 bytes
    val name: String,
    val age: Int,
    val phone: String,
    val meetingPoint: String,
) {
    fun publicKey(): PublicKey? = PublicCrypto.decompress(familyKey)

    fun toQrText(): String {
        val n = name.utf8(24); val ph = phone.filter { it.isDigit() || it == '+' }.take(15).toByteArray(); val mp = meetingPoint.utf8(40)
        val b = ByteBuffer.allocate(4 + 4 + 33 + 1 + 1 + n.size + 1 + ph.size + 1 + mp.size)
        b.putInt(crewId); b.putInt(bandId); b.put(familyKey); b.put(age.coerceIn(0, 120).toByte())
        b.put(n.size.toByte()); b.put(n); b.put(ph.size.toByte()); b.put(ph); b.put(mp.size.toByte()); b.put(mp)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b.array())
    }

    override fun equals(other: Any?) = other is Band && other.toQrText() == toQrText()
    override fun hashCode() = toQrText().hashCode()

    companion object {
        const val PREFIX = "KHOJ-BAND1:"

        /** Null for anything that is not a valid KHOJ band (other QR codes, damaged prints). */
        fun parse(text: String): Band? {
            if (!text.startsWith(PREFIX)) return null
            return try {
                val b = ByteBuffer.wrap(Base64.getUrlDecoder().decode(text.removePrefix(PREFIX)))
                val crew = b.int; val id = b.int
                val key = ByteArray(33).also { b.get(it) }
                val age = b.get().toInt() and 0xFF
                val name = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }, Charsets.UTF_8)
                val phone = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }, Charsets.US_ASCII)
                val meet = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }, Charsets.UTF_8)
                if (b.hasRemaining() || PublicCrypto.decompress(key) == null || name.isBlank()) null
                else Band(crew, id, key, name, age, phone, meet)
            } catch (_: IllegalArgumentException) { null } catch (_: BufferUnderflowException) { null }
        }

        private fun String.utf8(max: Int): ByteArray = toByteArray(Charsets.UTF_8).let { if (it.size > max) it.copyOf(max) else it }
    }
}

/** A volunteer's "found" report, sealed to the band's family key. */
data class FoundReport(val reportId: Long, val bandId: Int, val lat: Double, val lon: Double, val atS: Long, val desk: String, val note: String) {
    fun encode(): ByteArray {
        val d = desk.toByteArray(Charsets.UTF_8).let { if (it.size > 24) it.copyOf(24) else it }
        val n = note.toByteArray(Charsets.UTF_8).let { if (it.size > 40) it.copyOf(40) else it }
        return ByteBuffer.allocate(8 + 4 + 8 + 4 + 1 + d.size + 1 + n.size).apply {
            putLong(reportId); putInt(bandId); putInt((lat * 1e7).toInt()); putInt((lon * 1e7).toInt()); putInt(atS.toInt())
            put(d.size.toByte()); put(d); put(n.size.toByte()); put(n)
        }.array()
    }

    companion object {
        fun decode(b: ByteArray): FoundReport? = try {
            val bb = ByteBuffer.wrap(b)
            val r = FoundReport(bb.long, bb.int, bb.int / 1e7, bb.int / 1e7, bb.int.toLong() and 0xFFFFFFFFL,
                String(ByteArray(bb.get().toInt() and 0xFF).also { bb.get(it) }, Charsets.UTF_8),
                String(ByteArray(bb.get().toInt() and 0xFF).also { bb.get(it) }, Charsets.UTF_8))
            r.takeIf { !bb.hasRemaining() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        } catch (_: BufferUnderflowException) { null }

        /** Binds the ciphertext to its crew and band, so a report cannot be replayed against another band. */
        fun aad(crewId: Int, bandId: Int) = ByteBuffer.allocate(8).putInt(crewId).putInt(bandId).array()
    }
}
