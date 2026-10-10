package app.linkfill.core

import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.LinkFeatures
import app.linkfill.core.mesh.LinkTracker
import app.linkfill.core.mesh.LossPredictor
import app.linkfill.core.mesh.MeshConfig
import app.linkfill.core.mesh.MeshListener
import app.linkfill.core.mesh.MeshMessage
import app.linkfill.core.mesh.MeshPacket
import app.linkfill.core.mesh.MeshRouter
import app.linkfill.core.mesh.MeshType
import app.linkfill.core.mesh.Metrics
import app.linkfill.core.sim.LinkDataset
import app.linkfill.core.mesh.PublicMessage
import app.linkfill.core.mesh.SosKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshTest {
    private val t0 = 2_000_000_000L

    private class Inbox : MeshListener {
        val got = mutableListOf<Pair<MeshMessage, Int>>()
        val publicGot = mutableListOf<Pair<PublicMessage, Int>>()
        val acks = mutableListOf<Int>()
        override fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) { got += message to hops }
        override fun onAcked(packetId: Int, byOriginId: Int) { acks += packetId }
        override fun onPublic(packet: MeshPacket, message: PublicMessage, hops: Int, rssi: Int, ageS: Long) { publicGot += message to hops }
    }

    /** A chain of phones where each hears only its neighbours: 0 - 1 - 2 - ... */
    private class Chain(n: Int, crews: List<CrewKey?>, config: MeshConfig = MeshConfig()) {
        val inboxes = List(n) { Inbox() }
        val routers = List(n) { MeshRouter(100 + it, crews[it], config, Random(it), inboxes[it]) }
        var transmissions = 0

        fun run(from: Long, ms: Long) {
            var t = from
            while (t <= from + ms) {
                for (i in routers.indices) {
                    val f = routers[i].nextFrame(t, allowFiller = false) ?: continue
                    transmissions++
                    if (i > 0) routers[i - 1].onFrame(f, -70, false, t)
                    if (i < routers.size - 1) routers[i + 1].onFrame(f, -70, false, t)
                }
                routers.forEach { it.tick(t) }
                t += 250
            }
        }
    }

    @Test fun packetRoundTripAndRejectsGarbage() {
        val p = MeshPacket(MeshType.TEXT, 5, 1, 9, 77, 3, 4, 5, 6, byteArrayOf(1, 2, 3))
        val d = MeshPacket.decode(MeshPacket.encode(p))!!
        assertEquals(listOf(5L, 1, 9, 77, 3, 4, 5, 6), listOf(d.ttl, d.hops, d.relayId, d.linkSeq, d.crewId, d.originId, d.packetId, d.originTimeS).map { it.toLong() })
        assertNull(MeshPacket.decode(ByteArray(10)))
        assertNull(MeshPacket.decode(MeshPacket.encode(p).also { it[0] = 0 }))
        assertNull(MeshPacket.decode(MeshPacket.encode(p) + byteArrayOf(9)))
        assertNull(MeshPacket.decode(ByteArray(300)))
    }

    @Test fun messagesRoundTrip() {
        for (m in listOf(
            MeshMessage.Beacon(25.3176, 82.9739, 12, 80, "Amma"),
            MeshMessage.Sos(25.3, 82.9, "near ghat 4"),
            MeshMessage.Rally(25.3, 82.9, 1_800_000_000L, "Blue tent"),
            MeshMessage.Text("hello"),
            MeshMessage.Ack(5, 6),
            MeshMessage.Looking(-123456),
        )) {
            val (type, bytes) = MeshMessage.encode(m)
            val back = MeshMessage.decode(type, bytes)
            if (m is MeshMessage.Beacon && back is MeshMessage.Beacon) {
                assertEquals(m.lat, back.lat, 1e-6); assertEquals(m.name, back.name)
            } else assertEquals(m, back)
        }
    }

    @Test fun crewCodeParsesAndSealsOnlyForTheCrew() {
        val a = CrewKey.create()
        val same = CrewKey.fromCode(CrewKey.pretty(a.code).lowercase())!!
        assertEquals(a.crewId, same.crewId)
        assertNull(CrewKey.fromCode("too-short"))
        val bare = MeshPacket(MeshType.TEXT, 3, 0, 1, 0, a.crewId, 1, 2, 3, ByteArray(0))
        val sealed = MeshPacket(MeshType.TEXT, 3, 0, 1, 0, a.crewId, 1, 2, 3, a.seal(bare, "hi".toByteArray()))
        assertEquals("hi", String(same.open(sealed)!!))
        assertNull(CrewKey.create().open(sealed))
        // Link fields may change at each hop without breaking authentication...
        assertNotNull(same.open(sealed.relayed(55, 9)))
        // ...but end-to-end fields may not.
        assertNull(same.open(MeshPacket(MeshType.TEXT, 3, 0, 1, 0, a.crewId, 1, 999, 3, sealed.body)))
    }

    @Test fun messageHopsAlongAChainThroughStrangersPhones() {
        val crew = CrewKey.create()
        // Only the two ends are in the crew; the three phones in the middle relay without being able to read.
        val c = Chain(5, listOf(crew, null, CrewKey.create(), null, crew))
        c.routers[0].originate(MeshMessage.Text("meet at gate 3"), t0)
        c.run(t0, 10_000)
        assertEquals(MeshMessage.Text("meet at gate 3"), c.inboxes[4].got.single().first)
        assertEquals(3, c.inboxes[4].got.single().second) // relayed by 3 phones
        assertTrue(c.inboxes[1].got.isEmpty() && c.inboxes[2].got.isEmpty())
        assertTrue(c.routers[2].stats.foreignRelayed >= 1)
    }

    @Test fun bridgeSeesOwnAndFirstHeardPacketsOnce() {
        val crew = CrewKey.create()
        val c = Chain(3, List(3) { crew })
        val bridged = mutableListOf<Int>()
        c.routers[1].bridge = { _, p -> bridged += p.packetId }
        val mine = c.routers[1].originate(MeshMessage.Text("mine"), t0)
        val theirs = c.routers[0].originate(MeshMessage.Text("theirs"), t0)
        c.run(t0, 5_000)
        assertEquals(listOf(mine, theirs), bridged) // each once, despite repeats and relays heard back
    }

    @Test fun ttlLimitsHops() {
        val crew = CrewKey.create()
        val c = Chain(5, List(5) { crew })
        c.routers[0].originate(MeshMessage.Text("x"), t0, ttl = 2)
        c.run(t0, 10_000)
        assertEquals(1, c.inboxes[2].got.size)
        assertTrue(c.inboxes[4].got.isEmpty())
    }

    @Test fun sosIsRepeatedUntilAcknowledged() {
        val crew = CrewKey.create()
        val c = Chain(3, List(3) { crew })
        val id = c.routers[0].originate(MeshMessage.Sos(25.0, 82.0, "help"), t0)
        c.run(t0, 5_000)
        assertTrue(id in c.inboxes[0].acks)
        assertEquals(0, c.routers[0].pendingUrgent)
        // Everyone gets it exactly once despite duplicates.
        assertEquals(1, c.inboxes[2].got.count { it.first is MeshMessage.Sos })
    }

    @Test fun suppressionCancelsRedundantRelays() {
        val crew = CrewKey.create()
        // Star: one sender heard by 6 phones that all hear each other. Most relays are pointless.
        fun flood(suppression: Boolean): Int {
            val cfg = MeshConfig(suppression = suppression, suppressAfter = 2)
            val routers = List(7) { MeshRouter(200 + it, crew, cfg, Random(it), object : MeshListener {}) }
            routers[0].originate(MeshMessage.Text("x"), t0)
            var tx = 0; var t = t0
            while (t < t0 + 5_000) {
                for (i in routers.indices) {
                    val f = routers[i].nextFrame(t, allowFiller = false) ?: continue
                    tx++
                    for (j in routers.indices) if (j != i) routers[j].onFrame(f, -60 - 5 * j, false, t)
                }
                t += 250
            }
            return tx
        }
        assertTrue(flood(true) < flood(false))
    }

    @Test fun forgedCrewPacketIsNeitherDeliveredNorRelayed() {
        val crew = CrewKey.create()
        val tag = crew.tagForTime(t0 / 1000)
        val inbox = Inbox()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), inbox)
        val forged = MeshPacket(MeshType.TEXT, 3, 0, 9, 1, tag, 0, 9, 1, t0 / 1000, ByteArray(20))
        r.onFrame(MeshPacket.encode(forged), -60, false, t0)
        assertEquals(1, r.stats.authFailed)
        assertNull(r.nextFrame(t0 + 5_000, allowFiller = false))
        assertTrue(inbox.got.isEmpty())
    }

    @Test fun trackerMeasuresDeliveryRatio() {
        val t = LinkTracker()
        var now = t0
        for (seq in 1..10) { if (seq % 2 == 0) t.onFrame(5, seq, -70, false, now); now += 1_000 }
        assertEquals(0.5, t.pdr(5, 10_000, now - 1_000), 0.05)
        assertTrue(!t.onFrame(5, 10, -70, false, now - 1_000)) // repeated advertisement of the same frame
        assertEquals(LinkFeatures.ALL.size, t.features(5, now, 0, false).size)
    }

    @Test fun dwellIsRespectedForRoutineFrames() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(dwellMs = 1_000L), Random(1), object : MeshListener {})
        r.originate(MeshMessage.Text("routine message"), t0)

        // Routine frame is not urgent
        assertTrue(!r.hasUrgentDue(t0))

        // Before dwellMs has elapsed, shouldTransmit is false
        val lastSent = t0
        val lastPreempt = t0
        assertTrue(!r.shouldTransmit(lastSent, lastPreempt, t0 + 250))
        assertTrue(!r.shouldTransmit(lastSent, lastPreempt, t0 + 500))
        assertTrue(!r.shouldTransmit(lastSent, lastPreempt, t0 + 999))

        // Once dwellMs has elapsed, shouldTransmit is true
        assertTrue(r.shouldTransmit(lastSent, lastPreempt, t0 + 1_000))
        assertTrue(r.shouldTransmit(lastSent, lastPreempt, t0 + 1_250))
    }

    @Test fun urgentFramePreemptsDwellWith250msRateLimit() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(dwellMs = 1_000L), Random(1), object : MeshListener {})
        r.originate(MeshMessage.Sos(25.0, 82.0, "help"), t0)

        // SOS is priority-0 urgent
        assertTrue(r.hasUrgentDue(t0))

        val lastSent = t0
        val lastPreempt = t0

        // If less than 250 ms since last frame or last preempt, cannot pre-empt yet
        assertTrue(!r.shouldTransmit(lastSent, lastPreempt, t0 + 100))
        assertTrue(!r.shouldTransmit(lastSent, lastPreempt, t0 + 249))

        // Once >= 250 ms, urgent frame pre-empts early even though routine dwell (1000 ms) has not expired
        assertTrue(r.shouldTransmit(lastSent, lastPreempt, t0 + 250))
        assertTrue(r.shouldTransmit(lastSent, lastPreempt, t0 + 500))

        // If an urgent frame pre-empted at t0 + 250, next pre-emption cannot happen before t0 + 500
        val newLastSent = t0 + 250
        val newLastPreempt = t0 + 250
        assertTrue(!r.shouldTransmit(newLastSent, newLastPreempt, t0 + 350))
        assertTrue(!r.shouldTransmit(newLastSent, newLastPreempt, t0 + 499))
        assertTrue(r.shouldTransmit(newLastSent, newLastPreempt, t0 + 500))
    }

    @Test fun fillerNeverSentWhileRealFrameIsDue() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})

        // With empty queue, nextFrame with allowFiller = true returns NEIGHBOR filler
        val filler = r.nextFrame(t0, allowFiller = true)
        assertNotNull(filler)
        val decodedFiller = MeshPacket.decode(filler!!)!!
        assertEquals(MeshType.NEIGHBOR, decodedFiller.type)

        // With empty queue, allowFiller = false returns null
        assertNull(r.nextFrame(t0, allowFiller = false))

        // Now queue a real text message
        r.originate(MeshMessage.Text("urgent notice"), t0)

        // While real frame is due, allowFiller = true MUST return the real frame, never a filler!
        val frame = r.nextFrame(t0, allowFiller = true)
        assertNotNull(frame)
        val decoded = MeshPacket.decode(frame!!)!!
        assertEquals(MeshType.TEXT, decoded.type)
    }

    @Test fun poor1mHealthDetection() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})

        // With no neighbors heard in last 10s, 1M health is poor
        assertTrue(r.is1mHealthPoor(t0))

        // If a neighbor is heard consistently with 100% PDR, 1M health is NOT poor
        var now = t0
        for (seq in 1..5) {
            r.links.onFrame(99, seq, -60, false, now)
            now += 1_000
        }
        assertTrue(!r.is1mHealthPoor(now))

        // If neighbor drops below 50% PDR, 1M health is poor
        now += 10_000 // neighbor aged out
        r.links.onFrame(99, 100, -90, false, now)
        // 2100 ms later: neighbor was heard 2.1s ago (active within 10s), but 0 frames in last 2s window (pdr = 0.0 < 0.5)
        assertTrue(r.is1mHealthPoor(now + 2_100))
    }

    @Test fun predictorLearnsAndRoundTrips() {
        val data = LinkDataset.build(40, 3)
        val m = LossPredictor.train(data.map { it.features }, data.map { it.lossNextWindow }, LinkFeatures.SETS.getValue(LinkFeatures.DEPLOYED), iterations = 150)
        val p = data.map { m.predict(it.features) }
        val y = data.map { it.lossNextWindow }
        assertTrue("model should beat the base rate", Metrics.prAuc(p, y) > y.average() + 0.05)
        val back = LossPredictor.parse(m.write())!!
        assertEquals(m.predict(data[0].features), back.predict(data[0].features), 1e-12)
        assertNull(LossPredictor.parse("garbage"))
    }

    @Test fun metrics() {
        assertEquals(1.0, Metrics.prAuc(listOf(0.9, 0.8, 0.1), listOf(1, 1, 0)), 1e-9)
        assertEquals(0.0, Metrics.brier(listOf(1.0, 0.0), listOf(1, 0)), 1e-9)
    }

    @Test fun densityTierPolicySelection() {
        val cfg = MeshConfig()
        val p0 = cfg.densityPolicy(0)
        assertEquals(4, p0.routineTtl)
        assertEquals(6, p0.urgentTtl)
        assertEquals(1.0, p0.relayProbability, 1e-9)
        assertEquals(2, p0.suppressAfter)
        assertEquals(3_000L, p0.beaconPeriodMs)

        val p9 = cfg.densityPolicy(9)
        assertEquals(4, p9.routineTtl)
        assertEquals(2, p9.suppressAfter)

        val p10 = cfg.densityPolicy(10)
        assertEquals(3, p10.routineTtl)
        assertEquals(5, p10.urgentTtl)
        assertEquals(0.7, p10.relayProbability, 1e-9)
        assertEquals(3, p10.suppressAfter)
        assertEquals(6_000L, p10.beaconPeriodMs)

        val p49 = cfg.densityPolicy(49)
        assertEquals(3, p49.routineTtl)
        assertEquals(3, p49.suppressAfter)

        val p50 = cfg.densityPolicy(50)
        assertEquals(2, p50.routineTtl)
        assertEquals(4, p50.urgentTtl)
        assertEquals(8.0 / 50.0, p50.relayProbability, 1e-9)
        assertEquals(4, p50.suppressAfter)
        assertEquals(12_000L, p50.beaconPeriodMs)

        val p200 = cfg.densityPolicy(200)
        assertEquals(2, p200.routineTtl)
        assertEquals(3, p200.urgentTtl)
        assertEquals(8.0 / 200.0, p200.relayProbability, 1e-9)
        assertEquals(5, p200.suppressAfter)
        assertEquals(20_000L, p200.beaconPeriodMs)
    }

    @Test fun urgentBypassesProbabilityGate() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(42), object : MeshListener {})
        for (i in 1..200) {
            r.links.onFrame(100 + i, 1, -70, false, t0)
        }
        assertEquals(200, r.links.density(t0))
        val policy = r.config.densityPolicy(r.links.density(t0))
        assertEquals(0.04, policy.relayProbability, 1e-9)

        val tag = crew.tagForTime(t0 / 1000)
        for (i in 1..100) {
            val bare = MeshPacket(MeshType.SOS, 3, 0, 1000 + i, 1, tag, 0, 2000 + i, 1, t0 / 1000, ByteArray(0))
            val sealed = MeshPacket(MeshType.SOS, 3, 0, 1000 + i, 1, tag, 0, 2000 + i, 1, t0 / 1000, crew.seal(bare, MeshMessage.encode(MeshMessage.Sos(25.0, 82.0, "help")).second))
            r.onFrame(MeshPacket.encode(sealed), -70, false, t0)
        }
        assertEquals(100, r.stats.relayScheduled)

        r.stats.relayScheduled = 0
        for (i in 1..100) {
            val bare = MeshPacket(MeshType.TEXT, 3, 0, 2000 + i, 1, tag, 0, 3000 + i, 1, t0 / 1000, ByteArray(0))
            val sealed = MeshPacket(MeshType.TEXT, 3, 0, 2000 + i, 1, tag, 0, 3000 + i, 1, t0 / 1000, crew.seal(bare, MeshMessage.encode(MeshMessage.Text("chat")).second))
            r.onFrame(MeshPacket.encode(sealed), -70, false, t0)
        }
        assertTrue("non-urgent packets must be gated by relay probability", r.stats.relayScheduled < 20)
    }

    @Test fun suppressionThresholdAdaptsWithCrowdDensity() {
        val crew = CrewKey.create()
        val tag = crew.tagForTime(t0 / 1000)
        val r = MeshRouter(1, crew, MeshConfig(suppression = true), Random(1), object : MeshListener {})

        val bare = MeshPacket(MeshType.TEXT, 3, 0, 99, 1, tag, 0, 500, 1, t0 / 1000, ByteArray(0))
        val sealed = MeshPacket(MeshType.TEXT, 3, 0, 99, 1, tag, 0, 500, 1, t0 / 1000, crew.seal(bare, MeshMessage.encode(MeshMessage.Text("test")).second))
        val encoded = MeshPacket.encode(sealed)
        r.onFrame(encoded, -70, false, t0)
        assertEquals(1, r.stats.relayScheduled)

        val enc1 = MeshPacket.encode(MeshPacket(MeshType.TEXT, 3, 0, 101, 1, tag, 0, 500, 1, t0 / 1000, sealed.body))
        r.onFrame(enc1, -70, false, t0 + 10)
        assertEquals(0, r.stats.suppressed)

        val enc2 = MeshPacket.encode(MeshPacket(MeshType.TEXT, 3, 0, 102, 1, tag, 0, 500, 1, t0 / 1000, sealed.body))
        r.onFrame(enc2, -70, false, t0 + 20)

        val frame = r.nextFrame(t0 + 2000, allowFiller = false)
        assertNull("frame should be suppressed after hearing 2 other relays in low density tier", frame)
        assertEquals(1, r.stats.suppressed)
    }

    @Test fun urgentExponentialRetryScheduleAndJitterBounds() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(urgentMaxSends = 6), Random(123), object : MeshListener {})

        val packetId = r.originate(MeshMessage.Sos(25.0, 82.0, "help"), t0)
        assertEquals(1, r.pendingUrgent)

        r.tick(t0 + 700)
        assertEquals(0, r.stats.urgentResends)

        r.tick(t0 + 1300)
        assertEquals(1, r.stats.urgentResends)

        r.tick(t0 + 1300 + 1500)
        assertEquals(1, r.stats.urgentResends)
        r.tick(t0 + 1300 + 2600)
        assertEquals(2, r.stats.urgentResends)

        r.tick(t0 + 3900 + 5200)
        assertEquals(3, r.stats.urgentResends)

        val tag = crew.tagForTime(t0 / 1000)
        val ackBare = MeshPacket(MeshType.ACK, 3, 0, 2, 1, tag, 0, 2, 999, t0 / 1000, ByteArray(0))
        val ackEncoded = MeshPacket.encode(MeshPacket(MeshType.ACK, 3, 0, 2, 1, tag, 0, 2, 999, t0 / 1000, crew.seal(ackBare, MeshMessage.encode(MeshMessage.Ack(1, packetId)).second)))
        r.onFrame(ackEncoded, -70, false, t0 + 10_000)

        assertEquals(0, r.pendingUrgent)
        r.tick(t0 + 60_000)
        assertEquals(3, r.stats.urgentResends)
    }

    @Test fun randomSlotRelayRuleWithRssiTieBreaker() {
        val crew = CrewKey.create()
        val tag = crew.tagForTime(t0 / 1000)
        val rRandom = MeshRouter(1, crew, MeshConfig(relayRule = app.linkfill.core.mesh.RelayRule.RANDOM_SLOT), Random(1), object : MeshListener {})
        val rFarFirst = MeshRouter(2, crew, MeshConfig(relayRule = app.linkfill.core.mesh.RelayRule.RSSI_FAR_FIRST), Random(1), object : MeshListener {})

        val bare = MeshPacket(MeshType.SOS, 3, 0, 99, 1, tag, 0, 50, 1, t0 / 1000, ByteArray(0))
        val enc = MeshPacket.encode(MeshPacket(MeshType.SOS, 3, 0, 99, 1, tag, 0, 50, 1, t0 / 1000, crew.seal(bare, MeshMessage.encode(MeshMessage.Sos(25.0, 82.0, "help")).second)))

        rRandom.onFrame(enc, -90, false, t0)
        assertTrue(rRandom.stats.relayScheduled == 1)

        rFarFirst.onFrame(enc, -90, false, t0)
        assertTrue(rFarFirst.stats.relayScheduled == 1)
    }

    @Test fun fillerSuppressedWhileUrgentPending() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})

        assertNotNull(r.nextFrame(t0, allowFiller = true))

        r.originate(MeshMessage.Sos(25.0, 82.0, "help"), t0)
        assertTrue(r.hasPendingUrgent())

        val sosFrame = r.nextFrame(t0, allowFiller = true)
        assertNotNull(sosFrame)
        assertEquals(MeshType.SOS, MeshPacket.decode(sosFrame!!)!!.type)

        assertNull("filler must be suppressed while urgent message is pending retry", r.nextFrame(t0 + 100, allowFiller = true))
    }

    @Test fun cacheSummaryBloomAndExactEncodingRoundTrip() {
        val keys = (1L..32L).map { 1000L * it }
        val bloomSummary = app.linkfill.core.mesh.CacheSummary(
            epoch = 42,
            count = keys.size,
            isExact = false,
            bloom = app.linkfill.core.mesh.CacheSummary.buildBloom(keys),
            digest = app.linkfill.core.mesh.CacheSummary.computeDigest(keys),
            oldestTimeS = 100,
            newestTimeS = 200,
        )
        val encodedBloom = app.linkfill.core.mesh.CacheSummary.encode(bloomSummary)
        val decodedBloom = app.linkfill.core.mesh.CacheSummary.decode(encodedBloom)
        assertEquals(bloomSummary, decodedBloom)
        assertTrue(app.linkfill.core.mesh.CacheSummary.bloomContains(decodedBloom!!.bloom, 1000L))
        assertFalse(app.linkfill.core.mesh.CacheSummary.bloomContains(decodedBloom.bloom, 999999L))

        val exactSummary = app.linkfill.core.mesh.CacheSummary(
            epoch = 43,
            count = keys.size,
            isExact = true,
            bloom = ByteArray(0),
            digest = app.linkfill.core.mesh.CacheSummary.computeDigest(keys),
            oldestTimeS = 100,
            newestTimeS = 200,
            exactKeys = keys.map { app.linkfill.core.mesh.CacheSummary.truncateKey(it) }.toIntArray()
        )
        val encodedExact = app.linkfill.core.mesh.CacheSummary.encode(exactSummary)
        val decodedExact = app.linkfill.core.mesh.CacheSummary.decode(encodedExact)
        assertEquals(exactSummary, decodedExact)
    }

    @Test fun antiEntropyReconciliationFindsMissingItemsWithLowerBytes() {
        val crew = CrewKey.create()
        for (diff in listOf(0, 1, 8, 16)) {
            val inboxA = Inbox()
            val inboxB = Inbox()
            val rA = MeshRouter(1, crew, MeshConfig(), Random(1), inboxA)
            val rB = MeshRouter(2, crew, MeshConfig(), Random(2), inboxB)

            var totalBytesSent = 0
            for (i in 1..32) {
                val (st, sb) = PublicMessage.encode(PublicMessage.PublicSos(SosKind.MANUAL, 25.0, 82.0, "alert $i"))
                val packet = MeshPacket(st, 3, 0, 1000 + i, 1, 0, 1000 + i, 1, t0 / 1000, sb)
                rA.onFrame(MeshPacket.encode(packet), -70, false, t0)
                if (i <= 32 - diff) {
                    rB.onFrame(MeshPacket.encode(packet), -70, false, t0)
                }
            }
            while (rA.nextFrame(t0 + 2000, allowFiller = false) != null) {}
            while (rB.nextFrame(t0 + 2000, allowFiller = false) != null) {}
            inboxA.publicGot.clear()
            inboxB.publicGot.clear()

            var t = t0 + 70_000
            val bFrame = rB.nextFrame(t, allowFiller = true)
            assertNotNull(bFrame)
            totalBytesSent += bFrame!!.size
            rA.onFrame(bFrame, -70, false, t)

            for (step in 1..30) {
                t += 250
                val fA = rA.nextFrame(t, allowFiller = false)
                if (fA != null) {
                    totalBytesSent += fA.size
                    rB.onFrame(fA, -70, false, t)
                }
                val fB = rB.nextFrame(t, allowFiller = false)
                if (fB != null) {
                    totalBytesSent += fB.size
                    rA.onFrame(fB, -70, false, t)
                }
                if (inboxB.publicGot.size == diff && rA.queueSize == 0 && rB.queueSize == 0) break
            }

            assertEquals("All $diff missing items must be delivered to B", diff, inboxB.publicGot.size)
            if (diff == 0) {
                assertTrue("Reconciliation for 0 diff ($totalBytesSent B) should only be summary frames", totalBytesSent <= 300)
            } else if (diff == 1) {
                assertTrue("Reconciliation for 1 diff ($totalBytesSent B) should be under 800 B (vs blind multi-packet offers >= 1280 B)", totalBytesSent <= 800)
            }
        }
    }

    @Test fun forcedBloomFalsePositiveTriggersExactSummaryReconciliation() {
        val crew = CrewKey.create()
        val inboxB = Inbox()
        val rA = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})
        val rB = MeshRouter(2, crew, MeshConfig(), Random(2), inboxB)

        val id = rA.originate(MeshMessage.Sos(25.0, 82.0, "sos"), t0)
        val initialSos = rA.nextFrame(t0, allowFiller = false)
        assertNotNull(initialSos)

        val allOnesBloom = ByteArray(app.linkfill.core.mesh.CacheSummary.BLOOM_BYTES) { 0xFF.toByte() }
        val fakeSummary = app.linkfill.core.mesh.CacheSummary(
            epoch = 1,
            count = 1,
            isExact = false,
            bloom = allOnesBloom,
            digest = ByteArray(app.linkfill.core.mesh.CacheSummary.DIGEST_BYTES),
            oldestTimeS = t0 / 1000,
            newestTimeS = t0 / 1000,
        )
        val fakePacket = MeshPacket(
            MeshType.SUMMARY, 1, 0, 2, 1, 0, 2, 50, t0 / 1000,
            app.linkfill.core.mesh.CacheSummary.encode(fakeSummary)
        )

        rA.onFrame(MeshPacket.encode(fakePacket), -70, false, t0 + 2000)

        val exactFrame = rA.nextFrame(t0 + 2000, allowFiller = false)
        assertNotNull(exactFrame)
        val exactPacket = MeshPacket.decode(exactFrame!!)!!
        assertEquals(MeshType.SUMMARY, exactPacket.type)
        val decodedExact = app.linkfill.core.mesh.CacheSummary.decode(exactPacket.body)!!
        assertTrue(decodedExact.isExact)

        rB.onFrame(exactFrame, -70, false, t0 + 2100)
        val bReply = rB.nextFrame(t0 + 2200, allowFiller = false)
        assertNotNull(bReply)
        rA.onFrame(bReply!!, -70, false, t0 + 2300)

        var offer: ByteArray? = null
        for (step in 1..6) {
            val f = rA.nextFrame(t0 + 2300 + step * 250, allowFiller = false)
            if (f != null && MeshPacket.decode(f)?.type == MeshType.SOS) {
                offer = f
                break
            }
        }
        assertNotNull(offer)
        rB.onFrame(offer!!, -70, false, t0 + 4000)

        assertEquals("B must receive the missing SOS despite initial Bloom false positive", 1, inboxB.got.size)
    }

    @Test fun resurrectionPreventedAfterAck() {
        val crew = CrewKey.create()
        val inboxA = Inbox()
        val inboxB = Inbox()
        val rA = MeshRouter(1, crew, MeshConfig(), Random(1), inboxA)
        val rB = MeshRouter(2, crew, MeshConfig(), Random(2), inboxB)

        val id = rA.originate(MeshMessage.Sos(25.0, 82.0, "help"), t0)
        val sosFrame = rA.nextFrame(t0, allowFiller = false)!!

        rB.onFrame(sosFrame, -70, false, t0 + 50)
        assertEquals(1, inboxB.got.size)

        val ackFrame = rB.nextFrame(t0 + 50, allowFiller = false)!!
        rA.onFrame(ackFrame, -70, false, t0 + 100)
        assertTrue(id in inboxA.acks)

        // Drain any pending relays from initial exchange
        while (rB.nextFrame(t0 + 2000, allowFiller = false) != null) {}
        while (rA.nextFrame(t0 + 2000, allowFiller = false) != null) {}

        val tReturn = t0 + 180_000
        rA.onFrame(sosFrame, -70, false, tReturn)
        rB.onFrame(sosFrame, -70, false, tReturn)

        assertEquals("Node B must not re-deliver old SOS", 1, inboxB.got.size)
        assertEquals("Node A must not deliver its own acknowledged SOS", 0, inboxA.got.size)
        val nextFrameAfterReturn = rB.nextFrame(tReturn + 500, allowFiller = false)
        if (nextFrameAfterReturn != null) {
            val decoded = MeshPacket.decode(nextFrameAfterReturn)!!
            org.junit.Assert.assertNotEquals("Node B must not relay the old acknowledged SOS", MeshType.SOS, decoded.type)
            assertEquals("Any frame sent on new contact must be SUMMARY", MeshType.SUMMARY, decoded.type)
        }
    }

    @Test fun custodyByOverhearingStretchesUrgentRetrySchedule() {
        val crew = CrewKey.create()
        val rA = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})

        val packetId = rA.originate(MeshMessage.Sos(25.0, 82.0, "help"), t0)
        assertEquals(1, rA.pendingUrgent)

        val tag = crew.tagForTime(t0 / 1000)
        val bare = MeshPacket(MeshType.SOS, 3, 0, 1, packetId, tag, 0, 1, packetId, t0 / 1000, ByteArray(0))
        val relayedBy2 = MeshPacket(MeshType.SOS, 2, 1, 2, 10, tag, 0, 1, packetId, t0 / 1000, crew.seal(bare, MeshMessage.encode(MeshMessage.Sos(25.0, 82.0, "help")).second))
        rA.onFrame(MeshPacket.encode(relayedBy2), -70, false, t0 + 20)

        assertEquals(1, rA.stats.custodiesObserved)

        rA.tick(t0 + 1_500)
        assertEquals("Must not retry within 1.5s once custody is overheard", 0, rA.stats.urgentResends)
    }

    @Test fun nonceUniquenessAcrossRestartsAndReinstalls() {
        val seenNonces = HashSet<Long>(1_000_000, 0.75f)
        val rng = Random(42)
        val fixedOriginId = 0x12345678
        for (restart in 1..1000) {
            val runEpoch = rng.nextInt()
            for (packetId in 1..1000) {
                val key = (runEpoch.toLong() shl 32) or (packetId.toLong() and 0xFFFFFFFFL)
                val added = seenNonces.add(key)
                assertTrue("Nonce (runEpoch=$runEpoch, pid=$packetId) must be unique", added)
            }
        }
        assertEquals(1_000_000, seenNonces.size)
    }

    @Test fun crewTagRotationMatchesAcrossSlotBoundariesAndRejectsTwoSlotsAway() {
        val crew = CrewKey.create()
        val nowS = 1_000_000L
        val currentSlot = crew.slotForTime(nowS)

        val currentTag = crew.tagForSlot(currentSlot)
        val prevTag = crew.tagForSlot(currentSlot - 1)
        val nextTag = crew.tagForSlot(currentSlot + 1)
        val twoSlotsBehindTag = crew.tagForSlot(currentSlot - 2)
        val twoSlotsAheadTag = crew.tagForSlot(currentSlot + 2)

        assertTrue(crew.matchesTag(currentTag, nowS))
        assertTrue(crew.matchesTag(prevTag, nowS))
        assertTrue(crew.matchesTag(nextTag, nowS))
        assertFalse(crew.matchesTag(twoSlotsBehindTag, nowS))
        assertFalse(crew.matchesTag(twoSlotsAheadTag, nowS))
    }

    @Test fun v1FramesCleanlyRejected() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})
        val v1Header = java.nio.ByteBuffer.allocate(29).apply {
            put(MeshPacket.MAGIC.toByte())
            put(1.toByte()) // VERSION 1
            put(MeshType.TEXT.code.toByte())
            put(0.toByte()) // flags
            put(3.toByte()) // ttl
            put(0.toByte()) // hops
            putShort(1.toShort()) // linkSeq
            putInt(99) // relayId
            putInt(crew.crewId) // crewId
            putInt(500) // originId
            putInt(1) // packetId
            putInt((t0 / 1000).toInt()) // originTimeS
            put(0.toByte()) // len
        }.array()

        assertNull("Decoder must reject v1 frames", MeshPacket.decode(v1Header))
        r.onFrame(v1Header, -70, false, t0)
        assertEquals(1, r.stats.malformed)
        assertEquals(0, r.stats.delivered)
        assertEquals(0, r.stats.relayScheduled)
    }

    @Test fun rateLimiterDropsFloodingSourceBeforeDecryption() {
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})
        val tag = crew.tagForTime(t0 / 1000)

        for (i in 1..50) {
            val bare = MeshPacket(MeshType.TEXT, 3, 0, 99, i, tag, 0, 500, i, t0 / 1000, ByteArray(0))
            val sealed = MeshPacket(MeshType.TEXT, 3, 0, 99, i, tag, 0, 500, i, t0 / 1000, crew.seal(bare, "msg $i".toByteArray()))
            r.onFrame(MeshPacket.encode(sealed), -70, false, t0 + i * 2)
        }

        assertTrue("Rate limiter must drop excess frames (dropped=${r.stats.rateLimited})", r.stats.rateLimited >= 25)
    }

    @Test fun repeatedCopiesOfOneFrameAreNotRateLimitedAndStillFeedFind() {
        // With a 1 s dwell every advertising event repeats the same frame; 30 copies in 1.5 s is normal, not a flood.
        val crew = CrewKey.create()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), object : MeshListener {})
        var heard = 0
        r.rssiObserver = { _, _, _, _ -> heard++ }
        val filler = MeshPacket.encode(MeshPacket(MeshType.NEIGHBOR, 0, 0, 77, 5, 0L, 0, 77, 0, t0 / 1000, ByteArray(0)))
        for (i in 0 until 30) r.onFrame(filler, -60, false, t0 + i * 50L)
        assertEquals(0, r.stats.rateLimited)
        assertEquals(30, heard)
    }

    @Test fun crewIdKeepsTheV1DerivationUsedByPrintedWristbands() {
        assertEquals(2019978398, CrewKey.fromCode("ABCDEFGHJKLMNPQR")!!.crewId) // value from the v1 interop vectors
    }

    @Test fun unauthenticatedForeignUrgentPacketsCappedAndRoutinePriority() {
        val myCrew = CrewKey.create()
        val otherCrew = CrewKey.create()
        val r = MeshRouter(1, myCrew, MeshConfig(), Random(1), object : MeshListener {})

        val otherTag = otherCrew.tagForTime(t0 / 1000)
        val bare = MeshPacket(MeshType.SOS, 3, 0, 88, 1, otherTag, 0, 999, 1, t0 / 1000, ByteArray(0))
        val sealed = MeshPacket(MeshType.SOS, 3, 0, 88, 1, otherTag, 0, 999, 1, t0 / 1000, otherCrew.seal(bare, "help".toByteArray()))

        r.onFrame(MeshPacket.encode(sealed), -70, false, t0)
        assertEquals(1, r.stats.foreignRelayed)
        assertEquals(0, r.stats.delivered)
        val queued = r.nextFrame(t0 + 2000, allowFiller = false)
        assertNotNull(queued)
    }
}
