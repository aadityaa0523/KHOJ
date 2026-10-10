package app.linkfill.core.tools

import app.linkfill.core.security.PublicCrypto
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Creates the police/authority signing key for official alerts. The public half is bundled in the app
 * (every phone verifies alerts with it); the private half goes to secrets/ (git-ignored) as text and as a
 * QR code that a police phone scans once to unlock "send official alert".
 * Entry point for `./gradlew :core:makeAuthorityKey`. Rerunning replaces the key: rebuild the app afterwards.
 */
fun main(args: Array<String>) {
    val publicOut = File(args[0])
    val secretsDir = File(args[1]).also { it.mkdirs() }
    val kp = PublicCrypto.newKeyPair()
    publicOut.parentFile?.mkdirs()
    publicOut.writeText(Base64.getEncoder().encodeToString(PublicCrypto.compress(kp.public)) + "\n")
    val secret = POLICE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(kp.private.encoded)
    File(secretsDir, "authority_private.txt").writeText(secret + "\n")
    val matrix = QRCodeWriter().encode(secret, BarcodeFormat.QR_CODE, 600, 600)
    val img = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
    ImageIO.write(img, "png", File(secretsDir, "authority_police_qr.png"))
    println("public key -> ${publicOut.absolutePath}\nprivate key + police QR -> ${secretsDir.absolutePath} (keep secret, never commit)")
}

const val POLICE_PREFIX = "KHOJ-POLICE1:"
