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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshTest {
    private val t0 = 2_000_000_000L

    private class Inbox : MeshListener {
        val got = mutableListOf<Pair<MeshMessage, Int>>()
        val acks = mutableListOf<Int>()
        override fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) { got += message to hops }
        override fun onAcked(packetId: Int, byOriginId: Int) { acks += packetId }
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
        val inbox = Inbox()
        val r = MeshRouter(1, crew, MeshConfig(), Random(1), inbox)
        val forged = MeshPacket(MeshType.TEXT, 3, 0, 9, 1, crew.crewId, 9, 1, t0 / 1000, ByteArray(20))
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
}
