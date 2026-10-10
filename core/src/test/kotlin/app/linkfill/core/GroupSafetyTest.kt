package app.linkfill.core

import app.linkfill.core.find.Circle
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.MeshConfig
import app.linkfill.core.mesh.MeshListener
import app.linkfill.core.mesh.MeshMessage
import app.linkfill.core.mesh.MeshPacket
import app.linkfill.core.mesh.MeshRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GroupSafetyTest {
    private val t0 = 2_000_000_000L

    private class Inbox : MeshListener {
        val got = mutableListOf<MeshMessage>()
        override fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) { if (message !is MeshMessage.Ack) got += message }
        val sos get() = got.count { it is MeshMessage.Sos }
    }

    /** Runs [routers] for [ms]; [hears] says who hears whom at time t (a moving crowd). */
    private fun run(routers: List<MeshRouter>, from: Long, ms: Long, hears: (Int, Int, Long) -> Boolean) {
        var t = from
        while (t <= from + ms) {
            for (i in routers.indices) {
                val f = routers[i].nextFrame(t, allowFiller = true) ?: continue
                for (j in routers.indices) if (j != i && hears(i, j, t)) routers[j].onFrame(f, -70, false, t)
            }
            routers.forEach { it.tick(t) }
            t += 250
        }
    }

    @Test fun circleIgnoresGpsNoiseAndHasHysteresis() {
        // 60 m apart with 10 m accuracy each: could be 40 m, so not outside a 50 m circle.
        assertFalse(Circle.outside(60.0, 10.0, 10.0, 50.0, wasOutside = false))
        assertTrue(Circle.outside(75.0, 10.0, 10.0, 50.0, wasOutside = false))
        // Back to 45 m: inside the radius but not below 80 %, so the alarm state holds.
        assertTrue(Circle.outside(45.0, 5.0, 5.0, 50.0, wasOutside = true))
        assertFalse(Circle.outside(39.0, 5.0, 5.0, 50.0, wasOutside = true))
    }

    @Test fun sosReachesSomeoneWhoArrivesAfterItWasSent() {
        val crew = CrewKey.create()
        val inbox = List(3) { Inbox() }
        // 0 sends SOS; 1 hears it; 2 is far away and walks into 1's range after 90 s (0 is long out of range by then:
        // and has stopped repeating, so only carrying can deliver it).
        val r = List(3) { MeshRouter(100 + it, crew, MeshConfig(urgentMaxSends = 2), Random(it), inbox[it]) }
        r[0].originate(MeshMessage.Sos(25.3, 82.9, "help"), t0)
        val arrive = t0 + 90_000
        run(r, t0, 150_000) { i, j, t -> val pair = setOf(i, j); pair == setOf(0, 1) && t < t0 + 30_000 || pair == setOf(1, 2) && t >= arrive }
        assertEquals(1, inbox[1].sos)
        assertEquals("carried SOS delivered to the late arrival exactly once", 1, inbox[2].sos)
        assertTrue(r[1].stats.carriedOffered > 0)
    }

    @Test fun reOffersAreNeverDeliveredTwice() {
        val crew = CrewKey.create()
        val inbox = List(2) { Inbox() }
        val r = List(2) { MeshRouter(100 + it, crew, MeshConfig(), Random(it), inbox[it]) }
        r[0].originate(MeshMessage.Sos(25.3, 82.9, "help"), t0)
        // In range, then apart for 2 minutes (past the 120 s duplicate memory), then together again: 0 re-offers.
        run(r, t0, 400_000) { _, _, t -> t < t0 + 20_000 || t > t0 + 200_000 }
        assertEquals(1, inbox[1].sos)
    }

    @Test fun lowPowerMemberDoesNotRelayAndItsFriendFillsItIn() {
        val crew = CrewKey.create()
        val inbox = List(3) { Inbox() }
        val r = List(3) { MeshRouter(100 + it, crew, MeshConfig(), Random(it), inbox[it]) }
        val lpn = r[2]
        lpn.lowPower = true
        assertNull("a low-power member sends no filler frames", lpn.nextFrame(t0))
        // The low-power member is asleep (hears nothing) while 0's SOS reaches its friend 1.
        r[0].originate(MeshMessage.Sos(25.3, 82.9, "help"), t0)
        val wake = t0 + 20_000
        run(r, t0, 40_000) { i, j, t -> when {
            setOf(i, j) == setOf(0, 1) -> true
            j == 2 -> t >= wake // asleep until it wakes
            else -> true // its own transmissions are heard
        } }
        // It wakes and sends a beacon; the friend answers with what it missed.
        lpn.originate(MeshMessage.Beacon(25.3, 82.9, 5, 50, "Dadi"), wake + 1)
        run(r, wake + 1, 10_000) { i, j, _ -> setOf(i, j) != setOf(0, 2) }
        assertEquals(1, inbox[2].sos)
        assertTrue(r[1].stats.friendPolls > 0)
        assertEquals("low-power member relayed nothing", 0, lpn.stats.relayed)
    }

    @Test fun gattFramingRoundTripsAtEveryMtuAndDropsBrokenFrames() {
        val frame = ByteArray(200) { it.toByte() }
        for (mtu in listOf(23, 185, 247, 517)) {
            val parts = app.linkfill.core.mesh.GattFraming.split(7, frame, mtu)
            assertTrue(parts.all { it.size <= mtu - 3 })
            val j = app.linkfill.core.mesh.GattFraming.Joiner()
            val out = parts.map { j.add(it) }
            assertTrue(out.dropLast(1).all { it == null })
            assertTrue(frame.contentEquals(out.last()))
        }
        // A lost middle fragment drops the frame, and the next frame still comes through.
        val j = app.linkfill.core.mesh.GattFraming.Joiner()
        val a = app.linkfill.core.mesh.GattFraming.split(1, frame, 23)
        (a.take(3) + a.drop(4)).forEach { assertNull(j.add(it)) }
        val b = app.linkfill.core.mesh.GattFraming.split(2, frame, 185)
        assertNull(j.add(b[0])); assertTrue(frame.contentEquals(j.add(b[1])))
        // A fragment claiming a different count mid-frame breaks the frame.
        val c = app.linkfill.core.mesh.GattFraming.split(3, frame, 23)
        assertNull(j.add(c[0])); assertNull(j.add(c[1].copyOf().also { it[1] = ((1 shl 4) or 3).toByte() }))
        // Garbage never throws.
        for (bad in listOf(ByteArray(0), byteArrayOf(1), byteArrayOf(1, 0, 5), byteArrayOf(1, 0x21, 5))) assertNull(j.add(bad))
    }

    @Test fun lowPowerFlagIsLinkLevelOnly() {
        val p = MeshPacket(app.linkfill.core.mesh.MeshType.TEXT, 5, 0, 1, 1, 2, 3, 4, 5, byteArrayOf(1))
        val marked = MeshPacket.decode(MeshPacket.encode(p.onLink(9, 2, lowPower = true)))!!
        assertTrue(marked.fromLowPower)
        assertFalse(marked.onLink(10, 3).fromLowPower)
        assertTrue(p.aad().contentEquals(marked.aad()))
    }
}
