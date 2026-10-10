package app.linkfill.core.security

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Public-key pieces for people outside a crew: a volunteer encrypting a "found" report to a wristband's
 * key, and police signing official alerts. P-256 throughout; public keys travel compressed (33 bytes) so
 * they fit in a QR code and a 200-byte mesh packet.
 */
object PublicCrypto {
    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
    }
    private val p: BigInteger = (params.curve.field as java.security.spec.ECFieldFp).p
    const val COMPRESSED = 33

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun compress(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        val x = unsigned32(w.affineX)
        return byteArrayOf(if (w.affineY.testBit(0)) 3 else 2) + x
    }

    /** Rebuilds a public key from 33 bytes; null if the bytes are not a point on the curve. */
    fun decompress(b: ByteArray): PublicKey? {
        if (b.size != COMPRESSED || (b[0].toInt() != 2 && b[0].toInt() != 3)) return null
        val x = BigInteger(1, b.copyOfRange(1, 33))
        if (x >= p) return null
        val a = params.curve.a; val bb = params.curve.b
        val rhs = x.pow(3).add(a.multiply(x)).add(bb).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p) // p = 3 mod 4
        if (y.multiply(y).mod(p) != rhs) return null
        if (y.testBit(0) != (b[0].toInt() == 3)) y = p.subtract(y)
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        } catch (_: GeneralSecurityException) { null }
    }

    fun privateFromPkcs8(b: ByteArray): PrivateKey? = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b))
    } catch (_: GeneralSecurityException) { null }

    /**
     * Encrypts to [recipient] (ECIES: ephemeral ECDH, HKDF, AES-256-GCM with [aad]).
     * Output: ephemeral public key (33) | nonce (12) | ciphertext + tag (16).
     */
    fun sealTo(recipient: PublicKey, plaintext: ByteArray, aad: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val eph = newKeyPair(random)
        val ephC = compress(eph.public)
        val key = derive(eph.private, recipient, ephC, aad)
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(aad) }
        return ephC + nonce + c.doFinal(plaintext)
    }

    /** Null if not addressed to [own], tampered with, or malformed. */
    fun openWith(own: PrivateKey, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < COMPRESSED + 12 + 16) return null
        val ephC = sealed.copyOfRange(0, COMPRESSED)
        val eph = decompress(ephC) ?: return null
        return try {
            val key = derive(own, eph, ephC, aad)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, COMPRESSED, 12)); updateAAD(aad)
            }
            c.doFinal(sealed, COMPRESSED + 12, sealed.size - COMPRESSED - 12)
        } catch (_: GeneralSecurityException) { null }
    }

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(signature) }
    } catch (_: GeneralSecurityException) { false }

    private fun derive(own: PrivateKey, peer: PublicKey, ephC: ByteArray, aad: ByteArray): SecretKeySpec {
        val shared = KeyAgreement.getInstance("ECDH").run { init(own); doPhase(peer, true); generateSecret() }
        return SecretKeySpec(SessionCrypto.hkdf(ephC, shared, "KHOJ ecies v1".toByteArray() + aad, 32), "AES")
    }

    private fun unsigned32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
