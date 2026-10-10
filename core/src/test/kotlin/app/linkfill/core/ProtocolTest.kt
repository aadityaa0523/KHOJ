package app.linkfill.core

import app.linkfill.core.protocol.AckEntry
import app.linkfill.core.protocol.Decoded
import app.linkfill.core.protocol.Envelope
import app.linkfill.core.protocol.Hello
import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.protocol.MessageCodec
import app.linkfill.core.protocol.MessageType
import app.linkfill.core.protocol.PayloadException
import app.linkfill.core.protocol.Payloads
import app.linkfill.core.protocol.RedundantFix
import app.linkfill.core.protocol.RejectReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val now = 1_700_000_000_000L
    private val fix = LocationFix(17.385, 78.4867, 6.5f, 1.2f, 45f, now)

    private fun env(type: MessageType = MessageType.LOCATION_UPDATE, seq: Int = 1, body: ByteArray = byteArrayOf(1, 2, 3), created: Long = now, ttl: Long = 5000) =
        Envelope(type, 42L, 7, seq, 99L, created, created + ttl, body)

    private fun reason(bytes: ByteArray, peerNow: Long? = now) = (MessageCodec.decode(bytes, peerNow) as Decoded.Rejected).reason

    @Test fun envelopeRoundTrip() {
        val e = env(body = Payloads.encodeLocation(fix, emptyList())).with(flags = Envelope.FLAG_RETRANSMIT)
        val d = (MessageCodec.decode(MessageCodec.encode(e), now) as Decoded.Ok).envelope
        assertEquals(e.type, d.type); assertEquals(42L, d.sessionId); assertEquals(7, d.senderId)
        assertEquals(1, d.seq); assertEquals(99L, d.messageId); assertEquals(e.expiresAt, d.expiresAt)
        assertTrue(d.isRetransmission)
        assertArrayEquals(e.body, d.body)
    }

    @Test fun rejectsMalformedAndOversized() {
        val good = MessageCodec.encode(env())
        assertEquals(RejectReason.TOO_SHORT, reason(good.copyOf(10)))
        assertEquals(RejectReason.TOO_LARGE, reason(ByteArray(MessageCodec.HEADER_SIZE + MessageCodec.MAX_BODY + 1)))
        assertEquals(RejectReason.BAD_MAGIC, reason(good.copyOf().also { it[0] = 0 }))
        assertEquals(RejectReason.UNSUPPORTED_VERSION, reason(good.copyOf().also { it[2] = 9 }))
        assertEquals(RejectReason.UNKNOWN_TYPE, reason(good.copyOf().also { it[3] = 77 }))
        assertEquals(RejectReason.LENGTH_MISMATCH, reason(good + byteArrayOf(0)))
    }

    @Test fun rejectsExpiredFutureAndBadTimestamps() {
        assertEquals(RejectReason.EXPIRED, reason(MessageCodec.encode(env(created = now - 20_000))))
        assertEquals(RejectReason.FROM_FUTURE, reason(MessageCodec.encode(env(created = now + 60_000))))
        assertEquals(RejectReason.BAD_TIMESTAMPS, reason(MessageCodec.encode(env(ttl = MessageCodec.MAX_TTL_MS + 1))))
        assertEquals(RejectReason.BAD_TIMESTAMPS, reason(MessageCodec.encode(env(seq = 0)))) // reliable type needs a seq
        // Handshake messages skip the freshness check (clock offset not known yet).
        assertTrue(MessageCodec.decode(MessageCodec.encode(env(created = now - 20_000)), null) is Decoded.Ok)
    }

    @Test fun locationPayloadRoundTripWithRedundancy() {
        val older = RedundantFix(4, 1234L, fix.copy(fixTimeMs = now - 1000))
        val p = Payloads.decodeLocation(Payloads.encodeLocation(fix, listOf(older)))
        assertEquals(fix, p.current)
        assertEquals(older, p.redundant.single())
    }

    @Test fun invalidCoordinatesAreRejected() {
        val bad = Payloads.encodeLocation(fix, emptyList()).also { b -> java.nio.ByteBuffer.wrap(b).putDouble(0, 123.0) }
        assertThrows { Payloads.decodeLocation(bad) }
        val nan = Payloads.encodeLocation(fix, emptyList()).also { b -> java.nio.ByteBuffer.wrap(b).putDouble(8, Double.NaN) }
        assertThrows { Payloads.decodeLocation(nan) }
        assertThrows { Payloads.decodeLocation(Payloads.encodeLocation(fix, emptyList()) + byteArrayOf(0)) }
        assertThrows { Payloads.decodeLocation(ByteArray(5)) }
    }

    @Test fun otherPayloadsRoundTrip() {
        val acks = Payloads.decodeAck(Payloads.encodeAck(listOf(AckEntry(3, 9L), AckEntry(4, 10L))))
        assertEquals(listOf(3 to 9L, 4 to 10L), acks.map { it.seq to it.messageId })
        assertEquals(listOf(5, 6), Payloads.decodeSeqs(Payloads.encodeSeqs(listOf(5, 6))))
        assertEquals(17, Payloads.decodeHeartbeat(Payloads.encodeHeartbeat(17)))
        val h = Payloads.decodeHello(Payloads.encodeHello(Hello(byteArrayOf(1, 2), 5L, ByteArray(32) { 3 })))
        assertEquals(5L, h.resumeSessionId)
        assertNull(Payloads.decodeHello(Payloads.encodeHello(Hello(byteArrayOf(1), null, null))).resumeProof)
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (_: PayloadException) { return }
        throw AssertionError("expected PayloadException")
    }
}
