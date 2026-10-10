package app.linkfill.core

import app.linkfill.core.policy.Decision
import app.linkfill.core.policy.FixedPolicy
import app.linkfill.core.policy.RecoveryAction
import app.linkfill.core.policy.RecoveryFeatures
import app.linkfill.core.policy.RecoveryPolicy
import app.linkfill.core.protocol.Decoded
import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.protocol.MessageCodec
import app.linkfill.core.protocol.MessageType
import app.linkfill.core.protocol.RejectReason
import app.linkfill.core.reliability.ChannelConfig
import app.linkfill.core.reliability.ChannelListener
import app.linkfill.core.reliability.Delivery
import app.linkfill.core.reliability.ReliableChannel
import app.linkfill.core.reliability.ReplayWindow
import app.linkfill.core.security.AesGcmSealer
import app.linkfill.core.security.Sealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReliableChannelTest {
    private val t0 = 1_700_000_000_000L
    private val k1 = ByteArray(32) { 1 }
    private val k2 = ByteArray(32) { 2 }

    /** Two channels wired through in-memory queues, with switchable loss in each direction. */
    private inner class Pair(policy: RecoveryPolicy = FixedPolicy(RecoveryAction.WAIT_RETRY, "retry"), config: ChannelConfig = ChannelConfig()) {
        val toB = ArrayDeque<ByteArray>()
        val toA = ArrayDeque<ByteArray>()
        var dropToB = false
        var dropToA = false
        val received = mutableListOf<Triple<Int, LocationFix, Delivery>>()
        var stopped = false
        val a = ReliableChannel(5L, 1, 2, AesGcmSealer(k1, k2), { if (!dropToB) toB += it }, policy, Random(1), object : ChannelListener {}, config = config)
        val b = ReliableChannel(5L, 2, 1, AesGcmSealer(k2, k1), { if (!dropToA) toA += it }, policy, Random(2), object : ChannelListener {
            override fun onLocation(seq: Int, fix: LocationFix, delivery: Delivery) { received += Triple(seq, fix, delivery) }
            override fun onStopSharing() { stopped = true }
        }, config = config)

        fun pump(now: Long) {
            while (toB.isNotEmpty() || toA.isNotEmpty()) {
                while (toB.isNotEmpty()) b.receive(toB.removeFirst(), now)
                while (toA.isNotEmpty()) a.receive(toA.removeFirst(), now)
            }
        }

        fun run(from: Long, to: Long, step: Long = 10) {
            var t = from
            while (t <= to) { a.tick(t); b.tick(t); pump(t); t += step }
        }
    }

    private fun fix(t: Long) = LocationFix(17.0, 78.0, 5f, Float.NaN, Float.NaN, t)

    @Test fun deliversAndAcknowledges() {
        val p = Pair()
        p.a.sendLocation(fix(t0), t0)
        p.pump(t0)
        assertEquals(1, p.received.size)
        assertEquals(Delivery.DIRECT, p.received[0].third)
        assertEquals(1, p.a.stats.acked)
        assertTrue(p.a.isSettled())
    }

    @Test fun duplicatesAreDiscardedButReacknowledged() {
        val p = Pair()
        p.a.sendLocation(fix(t0), t0)
        val copy = p.toB.first().copyOf()
        p.pump(t0)
        p.b.receive(copy, t0 + 5) // replayed / duplicated message
        assertEquals(1, p.received.size)
        assertEquals(1, p.b.stats.duplicatesDiscarded)
        assertEquals(2, p.b.stats.acksSent)
    }

    @Test fun lostAckLeadsToRetransmitThatIsDeduplicated() {
        val p = Pair()
        p.dropToA = true
        p.a.sendLocation(fix(t0), t0)
        p.pump(t0)
        p.dropToA = false
        p.run(t0, t0 + 3000)
        assertEquals("receiver processes the fix once", 1, p.received.size)
        assertTrue(p.a.stats.retransmissions >= 1)
        assertTrue(p.b.stats.duplicatesDiscarded >= 1)
        assertTrue(p.a.isSettled())
    }

    @Test fun retriesAreBoundedThenMarkedStale() {
        val p = Pair(config = ChannelConfig(maxAttempts = 3, locationTtlMs = 60_000))
        p.dropToB = true
        p.a.sendLocation(fix(t0), t0)
        p.run(t0, t0 + 59_000)
        assertEquals(3, p.a.stats.transmissions)
        assertEquals(2, p.a.stats.retransmissions)
        assertEquals(1, p.a.stats.markedStale)
    }

    @Test fun backoffGrowsAndStaysBounded() {
        val p = Pair()
        val rto = p.a.rto()
        repeat(50) {
            val b1 = p.a.backoff(1); val b5 = p.a.backoff(5)
            assertTrue(b1 in (rto)..(rto * 2))
            assertTrue(b5 <= p.a.config.maxRtoMs)
            assertTrue(b5 >= p.a.config.maxRtoMs / 2)
        }
    }

    @Test fun expiredLocationIsNotRetransmitted() {
        val p = Pair(config = ChannelConfig(locationTtlMs = 1000))
        p.dropToB = true
        p.a.sendLocation(fix(t0), t0)
        p.run(t0, t0 + 1000)
        val before = p.a.stats.transmissions
        p.run(t0 + 1010, t0 + 10_000)
        assertEquals(before, p.a.stats.transmissions)
    }

    @Test fun newerLocationSupersedesOlderOne() {
        val p = Pair()
        p.dropToB = true
        p.a.sendLocation(fix(t0), t0)
        p.a.sendLocation(fix(t0 + 1000), t0 + 1000)
        assertEquals(1, p.a.stats.superseded)
        assertEquals(1, p.a.pendingCount)
    }

    @Test fun gapIsDetectedAndRecoveredByRequest() {
        val p = Pair(policy = FixedPolicy(RecoveryAction.MARK_STALE, "no sender retry"), config = ChannelConfig(locationTtlMs = 5000))
        p.a.sendLocation(fix(t0), t0)
        p.pump(t0)
        p.dropToB = true
        p.a.sendStop(t0 + 10) // seq 2 is lost
        p.dropToB = false
        p.a.sendLocation(fix(t0 + 20), t0 + 20) // seq 3 arrives, receiver sees the gap
        p.pump(t0 + 20)
        assertEquals(1, p.b.stats.gapsDetected)
        p.run(t0 + 30, t0 + 2000)
        assertTrue(p.b.stats.recoveryRequestsSent >= 1)
        assertEquals(1, p.b.stats.recoveredByRetransmission)
        assertTrue(p.stopped)
    }

    @Test fun redundancyRecoversALostFix() {
        val p = Pair(config = ChannelConfig(alwaysRedundant = true))
        p.dropToB = true
        p.a.sendLocation(fix(t0), t0) // lost
        p.dropToB = false
        p.a.sendLocation(fix(t0 + 1000), t0 + 1000) // carries the lost one
        p.pump(t0 + 1000)
        assertEquals(1, p.b.stats.recoveredByRedundancy)
        assertTrue(p.received.any { it.third == Delivery.REDUNDANCY && it.second.fixTimeMs == t0 })
        assertEquals(2, p.a.stats.acked)
    }

    @Test fun messagesFromAnotherSessionOrKeyAreRejected() {
        val p = Pair()
        val stranger = ReliableChannel(6L, 1, 2, AesGcmSealer(k1, k2), { p.b.receive(it, t0) }, FixedPolicy(RecoveryAction.WAIT_RETRY, ""), Random(3), object : ChannelListener {})
        stranger.sendLocation(fix(t0), t0)
        assertEquals(1, p.b.stats.rejected[RejectReason.WRONG_SESSION])
        val forger = ReliableChannel(5L, 1, 2, AesGcmSealer(ByteArray(32) { 9 }, k2), { p.b.receive(it, t0) }, FixedPolicy(RecoveryAction.WAIT_RETRY, ""), Random(4), object : ChannelListener {})
        forger.sendLocation(fix(t0), t0)
        assertEquals(1, p.b.stats.rejected[RejectReason.AUTH_FAILED])
        assertTrue(p.received.isEmpty())
    }

    @Test fun tamperedHeaderFailsAuthentication() {
        val p = Pair()
        p.a.sendLocation(fix(t0), t0)
        val bytes = p.toB.removeFirst()
        bytes[25] = (bytes[25] + 1).toByte() // inside messageId, part of the authenticated header
        p.b.receive(bytes, t0)
        assertEquals(1, p.b.stats.rejected[RejectReason.AUTH_FAILED])
    }

    @Test fun stopSharingIsDeliveredReliably() {
        val p = Pair()
        p.dropToB = true
        p.a.sendStop(t0)
        p.dropToB = false
        p.run(t0, t0 + 3000)
        assertTrue(p.stopped)
        assertTrue(p.a.isSettled())
    }

    @Test fun policyIsConsultedOnTimeout() {
        val seen = mutableListOf<RecoveryFeatures>()
        val spy = object : RecoveryPolicy {
            override val name = "spy"
            override fun decide(f: RecoveryFeatures): Decision { seen += f; return Decision(RecoveryAction.MARK_STALE, "test") }
        }
        val p = Pair(policy = spy)
        p.dropToB = true
        p.a.sendLocation(fix(t0), t0)
        p.run(t0, t0 + 2000)
        assertEquals(1, seen.size)
        assertEquals(1, seen[0].attempt)
        assertEquals(1, p.a.stats.decisions[RecoveryAction.MARK_STALE])
    }

    @Test fun replayWindowTracksGapsAndDuplicates() {
        val w = ReplayWindow()
        w.mark(1); w.mark(4)
        assertEquals(listOf(2, 3), w.missing())
        assertEquals(ReplayWindow.Status.DUPLICATE, w.status(4))
        w.mark(2)
        assertEquals(listOf(3), w.missing())
        w.mark(100)
        assertEquals(ReplayWindow.Status.TOO_OLD, w.status(3))
        assertFalse(w.isReceived(99))
    }

    @Test fun sealerRejectsWrongAad() {
        val s: Sealer = AesGcmSealer(k1, k1)
        val sealed = s.seal(byteArrayOf(1), byteArrayOf(5, 6))
        assertEquals(null, s.open(byteArrayOf(2), sealed))
        assertTrue(s.open(byteArrayOf(1), sealed)!!.contentEquals(byteArrayOf(5, 6)))
        // Envelope header is authenticated: the decoder still parses it, so the check must be the AAD.
        assertTrue(MessageCodec.decode(ByteArray(10), null) is Decoded.Rejected)
        assertEquals(MessageType.LOCATION_UPDATE.code, 3)
    }
}
