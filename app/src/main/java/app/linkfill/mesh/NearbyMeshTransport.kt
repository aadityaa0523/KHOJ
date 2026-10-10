package app.linkfill.mesh

import android.content.Context
import app.linkfill.core.mesh.TransportHealth
import app.linkfill.core.mesh.TransportKind
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Google Nearby Connections in cluster mode: connects to up to [MAX_LINKS] nearby KHOJ phones and lets
 * Google pick Bluetooth or Wi-Fi underneath. Connections are accepted automatically: they are not trusted,
 * because every mesh packet is end-to-end encrypted with the crew key and validated by the router.
 * Shares the app's single Nearby client with direct sharing, so the two must not run at the same time.
 */
class NearbyMeshTransport(
    context: Context,
    private val nodeName: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    /** Point-to-point tunnel frames (Find Member), see core `Tunnel`. They never enter the mesh. */
    private val onTunnel: (ByteArray) -> Unit = {},
    private val onLinkChanged: () -> Unit = {},
) {
    val health = TransportHealth(TransportKind.NEARBY)
    private val client = Nearby.getConnectionsClient(context)
    private val connected = LinkedHashSet<String>()
    private val pending = HashSet<String>()
    private val outgoing = HashSet<Long>()
    /** endpointId -> the other phone's advertised name, "khoj-<hex node id>", learned when the connection is initiated. */
    private val names = HashMap<String, String>()
    private var running = false

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (app.linkfill.core.find.Tunnel.isTunnel(bytes)) { onTunnel(bytes); return }
            health.received++
            health.lastRxMs = System.currentTimeMillis()
            onFrame(bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.payloadId !in outgoing) return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> health.recordSend(true)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> health.recordSend(false)
                else -> return
            }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            names[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending.remove(endpointId)
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) connected += endpointId
            health.peers = connected.size
            onLinkChanged()
        }

        override fun onDisconnected(endpointId: String) { connected -= endpointId; names.remove(endpointId); health.peers = connected.size; onLinkChanged() }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Only the phone with the smaller name dials, so two phones never open two connections to each other.
            if (!running || nodeName >= info.endpointName || endpointId in connected || connected.size + pending.size >= MAX_LINKS) return
            if (!pending.add(endpointId)) return
            client.requestConnection(nodeName, endpointId, lifecycle).addOnFailureListener { pending.remove(endpointId) }
        }

        override fun onEndpointLost(endpointId: String) { pending.remove(endpointId) }
    }

    fun start() {
        if (running) return
        running = true
        health.up = true
        client.startAdvertising(nodeName, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { onError("Nearby advertising failed: ${it.message}") }
        client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { onError("Nearby discovery failed: ${it.message}") }
    }

    /** True when a Nearby connection to the phone with node id [nodeId] is up. */
    fun hasLinkTo(nodeId: Int) = endpointFor(nodeId) != null

    /** Sends a tunnel frame to one specific phone. Returns false when there is no direct Nearby link to it. */
    fun sendTunnel(nodeId: Int, frame: ByteArray): Boolean {
        val ep = endpointFor(nodeId) ?: return false
        client.sendPayload(ep, Payload.fromBytes(frame))
        return true
    }

    private fun endpointFor(nodeId: Int): String? {
        val want = nameFor(nodeId)
        return connected.firstOrNull { names[it] == want }
    }

    fun send(frame: ByteArray) {
        if (connected.isEmpty()) return
        val p = Payload.fromBytes(frame)
        outgoing += p.id
        if (outgoing.size > 512) outgoing.remove(outgoing.first())
        client.sendPayload(connected.toList(), p)
        health.sent++
    }

    fun stop() {
        if (!running) return
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        connected.forEach(client::disconnectFromEndpoint)
        connected.clear(); pending.clear()
        health.up = false; health.peers = 0
    }

    val isRunning get() = running

    companion object {
        fun nameFor(nodeId: Int) = "khoj-" + Integer.toHexString(nodeId)
        const val SERVICE = "app.linkfill.mesh.v1"
        const val MAX_LINKS = 4
    }
}
