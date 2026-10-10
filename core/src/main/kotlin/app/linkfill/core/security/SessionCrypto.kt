package app.linkfill.core.security

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts and authenticates message bodies. The header bytes are bound in as associated data. */
interface Sealer {
    fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray
    /** Returns null when the body was tampered with, replayed into another header, or keyed differently. */
    fun open(aad: ByteArray, body: ByteArray): ByteArray?
}

/** No encryption. Only for the link simulator, which never leaves the JVM. */
object PlainSealer : Sealer {
    override fun seal(aad: ByteArray, plaintext: ByteArray) = plaintext
    override fun open(aad: ByteArray, body: ByteArray) = body
}

/** AES-256-GCM with a random 96-bit nonce per message; separate keys per direction. */
class AesGcmSealer(sendKey: ByteArray, receiveKey: ByteArray, private val random: SecureRandom = SecureRandom()) : Sealer {
    private val send = SecretKeySpec(sendKey, "AES")
    private val receive = SecretKeySpec(receiveKey, "AES")

    override fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, send, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun open(aad: ByteArray, body: ByteArray): ByteArray? {
        if (body.size < OVERHEAD) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, receive, GCMParameterSpec(TAG_BITS, body, 0, NONCE))
            cipher.updateAAD(aad)
            cipher.doFinal(body, NONCE, body.size - NONCE)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    companion object {
        private const val NONCE = 12
        private const val TAG_BITS = 128
        const val OVERHEAD = NONCE + TAG_BITS / 8
    }
}

/** Keys derived for one session. `resume` lets the same pair re-connect without a second confirmation. */
class SessionKeys(
    val initiatorToResponder: ByteArray,
    val responderToInitiator: ByteArray,
    val confirm: ByteArray,
    val resume: ByteArray,
)

/**
 * Session key agreement on top of a Nearby connection the users have already verified by comparing
 * the authentication digits. Ephemeral ECDH (P-256) + HKDF-SHA256; the digits are mixed into the
 * key derivation, so a man-in-the-middle that produced different digits also produces different keys.
 */
object SessionCrypto {
    private const val INFO = "LinkFill v1 session"

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun deriveKeys(
        own: KeyPair,
        peerPublic: ByteArray,
        sessionId: Long,
        authDigits: String,
        initiatorPublic: ByteArray,
        responderPublic: ByteArray,
    ): SessionKeys {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
        val info = INFO.toByteArray() + authDigits.toByteArray() + initiatorPublic + responderPublic
        val okm = hkdf(longBytes(sessionId), shared, info, 128)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), okm.copyOfRange(64, 96), okm.copyOfRange(96, 128))
    }

    /** Responder proves it derived the same keys. */
    fun confirmTag(keys: SessionKeys, initiatorPublic: ByteArray, responderPublic: ByteArray): ByteArray =
        hmac(keys.confirm, "accept".toByteArray(), initiatorPublic, responderPublic)

    /** Initiator proves it held the previous session's keys, so a dropped link can resume without re-pairing. */
    fun resumeProof(previous: SessionKeys, newSessionId: Long, newInitiatorPublic: ByteArray): ByteArray =
        hmac(previous.resume, "resume".toByteArray(), longBytes(newSessionId), newInitiatorPublic)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    private fun longBytes(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
}
