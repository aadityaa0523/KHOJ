package app.linkfill.core.mesh

import app.linkfill.core.security.SessionCrypto
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A crew (family or group) shares one secret, exchanged in person as a 16-character code.
 * Relays outside the crew forward its packets but cannot read or forge them.
 *
 * shortcut: one static key per crew, no forward secrecy or member removal; upgrade to per-member keys
 * if the app is used beyond small groups that trust each other.
 */
class CrewKey private constructor(val code: String) {
    private val key: SecretKeySpec
    /** Separate key for point-to-point tunnels (random nonces), so its nonce space never meets the packet nonces. */
    private val tunnelKey: SecretKeySpec
    /** Public crew tag in every packet header, so members can filter. It links packets of the same crew. */
    val crewId: Int

    init {
        val secret = decodeCode(code)
        val okm = SessionCrypto.hkdf("KHOJ crew v1".toByteArray(), secret, "crew".toByteArray(), 68)
        key = SecretKeySpec(okm.copyOfRange(0, 32), "AES")
        crewId = ByteBuffer.wrap(okm, 32, 4).int
        tunnelKey = SecretKeySpec(okm.copyOfRange(36, 68), "AES")
    }

    /** AES-GCM with a 128-bit tag (the only length Apple CryptoKit accepts, for iOS interop). The nonce is (originId, packetId), unique per sender as long as packetIds never repeat. */
    fun seal(p: MeshPacket, plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, p).run { updateAAD(p.aad()); doFinal(plaintext) }

    fun open(p: MeshPacket): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, p).run { updateAAD(p.aad()); doFinal(p.body) }
    } catch (_: GeneralSecurityException) {
        null
    }

    /** Seals an arbitrary blob for the crew: random 12-byte nonce, then ciphertext and a 16-byte tag. */
    fun sealBlob(aad: ByteArray, plaintext: ByteArray, random: java.security.SecureRandom = java.security.SecureRandom()): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, tunnelKey, GCMParameterSpec(128, nonce)); updateAAD(aad) }
        return nonce + c.doFinal(plaintext)
    }

    fun openBlob(aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < 12 + 16) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, tunnelKey, GCMParameterSpec(128, sealed, 0, 12)); updateAAD(aad) }
            c.doFinal(sealed, 12, sealed.size - 12)
        } catch (_: GeneralSecurityException) { null }
    }

    private fun cipher(mode: Int, p: MeshPacket) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val nonce = ByteBuffer.allocate(12).putInt(p.originId).putInt(p.packetId).putInt(p.crewId).array()
        init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O, 1/I
        const val TAG_BYTES = 16

        fun create(random: SecureRandom = SecureRandom()): CrewKey =
            CrewKey(String(CharArray(16) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        /** Accepts the code with or without spaces or dashes, any case. Null if it is not a valid code. */
        fun fromCode(input: String): CrewKey? {
            val c = input.uppercase().filter { it.isLetterOrDigit() }
            if (c.length != 16 || c.any { it !in ALPHABET }) return null
            return CrewKey(c)
        }

        private fun decodeCode(c: String): ByteArray = c.map { ALPHABET.indexOf(it).toByte() }.toByteArray() // 80 bits of entropy

        fun pretty(code: String) = code.chunked(4).joinToString("-")
    }
}
