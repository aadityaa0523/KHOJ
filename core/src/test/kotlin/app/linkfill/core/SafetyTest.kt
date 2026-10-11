package app.linkfill.core

import app.linkfill.core.estimate.FallDetector
import app.linkfill.core.estimate.ShakeDetector
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.FoundReport
import app.linkfill.core.mesh.MeshConfig
import app.linkfill.core.mesh.MeshListener
import app.linkfill.core.mesh.MeshPacket
import app.linkfill.core.mesh.MeshRouter
import app.linkfill.core.mesh.PublicMessage
import app.linkfill.core.mesh.SosKind
import app.linkfill.core.security.PublicCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class SafetyTest {
    private val t0 = 2_000_000_000L

    @Test fun compressedKeysRoundTrip() {
        repeat(20) {
            val kp = PublicCrypto.newKeyPair()
            val c = PublicCrypto.compress(kp.public)
            assertEquals(33, c.size)
            assertArrayEquals(kp.public.encoded, PublicCrypto.decompress(c)!!.encoded)
        }
        assertNull(PublicCrypto.decompress(ByteArray(33) { 0x7 }))
    }

    @Test fun eciesOnlyRecipientReads() {
        val fam = PublicCrypto.newKeyPair()
        val sealed = PublicCrypto.sealTo(fam.public, "found at ghat 4".toByteArray(), byteArrayOf(1))
        assertEquals("found at ghat 4", String(PublicCrypto.openWith(fam.private, sealed, byteArrayOf(1))!!))
        assertNull(PublicCrypto.openWith(PublicCrypto.newKeyPair().private, sealed, byteArrayOf(1)))
        assertNull(PublicCrypto.openWith(fam.private, sealed, byteArrayOf(2)))
    }

    @Test fun officialAlertsVerifyOnlyWithTheAuthorityKey() {
        val authority = PublicCrypto.newKeyPair()
        val m = PublicMessage.official("Ghat 4 closed, use Ghat 6", authority.private)
        val (type, body) = PublicMessage.encode(m)
        val back = PublicMessage.decode(type, body) as PublicMessage.Official
        assertTrue(back.verify(authority.public))
        assertFalse(back.verify(PublicCrypto.newKeyPair().public))
        assertFalse(PublicMessage.Official("Ghat 6 closed", back.signature).verify(authority.public))
    }

    @Test fun bandQrRoundTripsAndRejectsJunk() {
        val fam = PublicCrypto.newKeyPair()
        val band = Band(123, 7, PublicCrypto.compress(fam.public), "Riya", 6, "+91 98765 43210", "Blue tent, Sector 4")
        val qr = band.toQrText()
        assertTrue(qr.length < 300)
        val back = Band.parse(qr)!!
        assertEquals("Riya", back.name); assertEquals("+919876543210", back.phone); assertEquals("Blue tent, Sector 4", back.meetingPoint)
        assertNull(Band.parse("https://example.com"))
        assertNull(Band.parse(Band.PREFIX + "AAAA"))
    }

    @Test fun printedBandIsAContactCardAnyCameraCanReadAndKhojStillParses() {
        val band = Band(123, 7, PublicCrypto.compress(PublicCrypto.newKeyPair().public), "Riya", 6, "+91 98765 43210", "Blue tent, Sector 4; Gate 2")
        val card = band.toPrintableQr()
        assertTrue(card.startsWith("BEGIN:VCARD\nVERSION:3.0\n")); assertTrue(card.endsWith("\nEND:VCARD"))
        assertTrue("callable number for any camera app", "\nTEL;TYPE=CELL:+919876543210\n" in card)
        assertTrue("name shows on the card", "\nFN:Lost: Riya\\, 6\n" in card)
        assertTrue("vCard special characters are escaped", "Blue tent\\, Sector 4\\; Gate 2" in card)
        assertEquals(band, Band.parse(card))
        assertTrue("small enough to print and scan on a wristband", card.length < 420)
        // A real QR encoder accepts it, so a printed band can carry it.
        com.google.zxing.qrcode.QRCodeWriter().encode(card, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
    }

    @Test fun bandQrDecodesWithAStandardReader() {
        val band = Band(5, 9, PublicCrypto.compress(PublicCrypto.newKeyPair().public), "Ramdulari", 68, "9000000000", "Blue tent, Sector 4")
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(band.toQrText(), com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else -1 }
        val src = com.google.zxing.RGBLuminanceSource(m.width, m.height, px)
        val text = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(src))).text
        assertEquals(band, Band.parse(text))
    }

    @Test fun volunteerFoundReportReachesOnlyTheFamilyOverTheMesh() {
        val familyKeys = PublicCrypto.newKeyPair()
        val familyCrew = CrewKey.create()
        val band = Band(familyCrew.crewId, 42, PublicCrypto.compress(familyKeys.public), "Riya", 6, "9876543210", "Gate 3")
        val got = mutableListOf<FoundReport>()
        val strangerSaw = mutableListOf<PublicMessage>()
        // Volunteer (no crew) -> stranger relay (other crew) -> family phone.
        val volunteer = MeshRouter(1, null, MeshConfig(), Random(1), object : MeshListener {})
        val stranger = MeshRouter(2, CrewKey.create(), MeshConfig(), Random(2), object : MeshListener {
            override fun onPublic(packet: MeshPacket, message: PublicMessage, hops: Int, rssi: Int, ageS: Long) { strangerSaw += message }
        })
        val family = MeshRouter(3, familyCrew, MeshConfig(), Random(3), object : MeshListener {
            override fun onPublic(packet: MeshPacket, message: PublicMessage, hops: Int, rssi: Int, ageS: Long) {
                if (message is PublicMessage.Found && packet.crewId == familyCrew.crewId && message.bandId == band.bandId) {
                    PublicCrypto.openWith(familyKeys.private, message.sealed, FoundReport.aad(band.crewId, band.bandId))
                        ?.let(FoundReport::decode)?.let(got::add)
                }
            }
        })
        val report = FoundReport(99L, band.bandId, 25.4358, 81.8463, t0 / 1000, "Ghat 4 desk", "crying, safe")
        volunteer.originatePublic(PublicMessage.foundFor(band, report)!!, t0, crewId = band.crewId)
        var t = t0
        while (t < t0 + 5_000) {
            volunteer.nextFrame(t, false)?.let { stranger.onFrame(it, -70, false, t) }
            stranger.nextFrame(t, false)?.let { family.onFrame(it, -70, false, t) }
            t += 250
        }
        assertEquals(report, got.single())
        // The relay saw only ciphertext.
        val sealed = strangerSaw.filterIsInstance<PublicMessage.Found>().single()
        assertFalse(String(sealed.sealed, Charsets.ISO_8859_1).contains("Ghat 4 desk"))
    }

    @Test fun publicSosRoundTripAndRateLimit() {
        val sos = PublicMessage.PublicSos(SosKind.FALL, 25.43, 81.84, "Amma")
        val (type, body) = PublicMessage.encode(sos)
        assertEquals(sos, PublicMessage.decode(type, body))
        val seen = mutableListOf<PublicMessage>()
        val rx = MeshRouter(5, null, MeshConfig(), Random(5), object : MeshListener {
            override fun onPublic(packet: MeshPacket, message: PublicMessage, hops: Int, rssi: Int, ageS: Long) { seen += message }
        })
        val spammer = MeshRouter(6, null, MeshConfig(), Random(6), object : MeshListener {})
        repeat(10) { spammer.originatePublic(sos, t0 + it) }
        var t = t0
        while (t < t0 + 4_000) { spammer.nextFrame(t, false)?.let { rx.onFrame(it, -60, false, t) }; t += 250 }
        assertEquals(5, seen.size)
        assertTrue(rx.stats.publicRateLimited >= 1)
    }

    private fun walk(det: (Long, Double, Double, Double) -> Boolean, seconds: Int, amp: Double): Boolean {
        var hit = false
        for (i in 0 until seconds * 50) { val t = i * 20L; if (det(t, 0.0, 0.0, 9.81 + amp * 9.81 * sin(2 * Math.PI * 1.8 * t / 1000.0))) hit = true }
        return hit
    }

    @Test fun fallDetectorFiresOnFallNotOnWalking() {
        assertFalse(walk(FallDetector()::add, 20, 0.5))
        val f = FallDetector()
        var t = 0L; var hit = false
        fun feed(g: Double, ms: Long) { val end = t + ms; while (t < end) { if (f.add(t, 0.0, 0.0, g * 9.81)) hit = true; t += 20 } }
        feed(1.0, 1000); feed(0.2, 300); feed(3.5, 60); feed(1.0, 3000)
        assertTrue(hit)
    }

    @Test fun shakeNeedsFourHardPeaks() {
        assertFalse(walk(ShakeDetector()::add, 20, 0.6))
        val s = ShakeDetector()
        var hit = false
        for (i in 0 until 100) { val t = i * 20L; val g = if (i % 12 < 3) 3.2 else 0.8; if (s.add(t, 0.0, 0.0, g * 9.81)) hit = true }
        assertTrue(hit)
        val once = ShakeDetector()
        assertFalse((0 until 50).any { i -> once.add(i * 20L, 0.0, 0.0, (if (i == 10) 3.5 else 1.0) * 9.81) })
        assertNotNull(once)
    }
}
