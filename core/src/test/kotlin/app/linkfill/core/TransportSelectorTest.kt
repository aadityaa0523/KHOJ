package app.linkfill.core

import app.linkfill.core.mesh.CombineMode
import app.linkfill.core.mesh.MeshType
import app.linkfill.core.mesh.TransportHealth
import app.linkfill.core.mesh.TransportKind
import app.linkfill.core.mesh.TransportSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSelectorTest {
    private fun health(ble: Double?, awarePeers: Int, awareOk: Int, awareFail: Int, online: Boolean) = listOf(
        TransportHealth(TransportKind.BLE).apply { up = true; externalQuality = ble },
        TransportHealth(TransportKind.WIFI_AWARE).apply { up = true; peers = awarePeers; repeat(awareOk) { recordSend(true) }; repeat(awareFail) { recordSend(false) } },
        TransportHealth(TransportKind.NEARBY).apply { up = false },
        TransportHealth(TransportKind.INTERNET).apply { up = online },
    )

    @Test fun calmGoodBluetoothUsesBluetoothOnly() {
        assertEquals(listOf(TransportKind.BLE), TransportSelector.choose(MeshType.BEACON, 0.1, health(0.95, 3, 10, 5, true), CombineMode.AUTO))
    }

    @Test fun weakBluetoothAddsTheBestOtherLink() {
        assertEquals(listOf(TransportKind.BLE, TransportKind.WIFI_AWARE), TransportSelector.choose(MeshType.BEACON, 0.2, health(0.5, 3, 9, 1, false), CombineMode.AUTO))
    }

    @Test fun urgentOrRiskyCombinesEveryUsableLocalLink() {
        val h = health(0.95, 2, 5, 0, true) // mobile data is a bridge, never a per-frame choice
        assertEquals(setOf(TransportKind.BLE, TransportKind.WIFI_AWARE), TransportSelector.choose(MeshType.SOS, 0.0, h, CombineMode.AUTO).toSet())
        assertEquals(2, TransportSelector.choose(MeshType.BEACON, 0.9, h, CombineMode.AUTO).size)
    }

    @Test fun downOrPeerlessLinksAreSkipped() {
        val h = health(0.5, 0, 0, 0, false) // Wi-Fi Aware has no peers, Nearby is down, no data
        assertEquals(listOf(TransportKind.BLE), TransportSelector.choose(MeshType.SOS, 0.0, h, CombineMode.ALL))
        assertEquals(listOf(TransportKind.BLE), TransportSelector.choose(MeshType.NEIGHBOR, 0.9, health(0.5, 3, 5, 0, true), CombineMode.ALL))
    }

    @Test fun bestOnlyPicksQualityPerCost() {
        assertEquals(listOf(TransportKind.BLE), TransportSelector.choose(MeshType.TEXT, 0.0, health(0.7, 3, 9, 1, false), CombineMode.BEST_ONLY))
        assertEquals(listOf(TransportKind.WIFI_AWARE), TransportSelector.choose(MeshType.TEXT, 0.0, health(0.2, 3, 30, 0, false), CombineMode.BEST_ONLY))
    }

    @Test fun combinedDeliveryAssumesIndependentLinks() {
        val h = health(0.5, 3, 0, 0, false) // Wi-Fi Aware quality 0.5 by smoothing
        assertEquals(0.75, TransportSelector.combinedDelivery(listOf(TransportKind.BLE, TransportKind.WIFI_AWARE), h), 1e-9)
        assertTrue(TransportSelector.typeOf(byteArrayOf(0x4B, 1, 2)) == MeshType.SOS)
    }
}
