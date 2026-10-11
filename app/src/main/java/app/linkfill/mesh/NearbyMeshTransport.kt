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
    /** Shown only if Android keeps refusing location (error 8034) for 30 s; the app then asks for "all the time". */
    private val permissionHint: String = "",
    /** Called when a start that had failed succeeds, so the app can clear a stale error. */
    private val onRecovered: () -> Unit = {},
) {
    val health = TransportHealth(TransportKind.NEARBY)
    private val client = Nearby.getConnectionsClient(context)
    private val connected = LinkedHashSet<String>()
    private val pending = HashSet<String>()
    private val outgoing = HashSet<Long>()
    /** endpointId -> the other phone's advertised name, "khoj-<hex node id>", learned when the connection is initiated. */
    private val names = HashMap<String, String>()
    /** Phones heard while discovering (endpointId to their advertised name), kept so an isolated phone can dial one of them. */
    private val seen = LinkedHashMap<String, String>()
    private var discoveringSinceMs = 0L
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
            discover()
            onLinkChanged()
        }

        override fun onDisconnected(endpointId: String) { connected -= endpointId; names.remove(endpointId); health.peers = connected.size; discover(); onLinkChanged() }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            seen[endpointId] = info.endpointName
            // Only the phone with the smaller name dials, so two phones never open two connections to each other.
            if (!running || nodeName >= info.endpointName || endpointId in connected || connected.size + pending.size >= MAX_LINKS) return
            if (!pending.add(endpointId)) return
            client.requestConnection(nodeName, endpointId, lifecycle).addOnFailureListener { pending.remove(endpointId) }
        }

        override fun onEndpointLost(endpointId: String) { pending.remove(endpointId); seen.remove(endpointId) }
    }

    fun start() {
        if (running) return
        running = true
        health.up = true
        ensure()
    }

    private var advertising = false
    private var discovering = false
    private var failures = 0
    private var retryAfterMs = 0L

    /**
     * Starts whatever should be running and is not. Android refuses Nearby's location use whenever the app does not count as
     * "in use" (screen off, another app on top) and reports error 8034; that is temporary, so a refusal is retried every
     * 10 s instead of failing for good, and only a refusal that lasts 30 s is reported. Call it every few seconds.
     */
    fun ensure() {
        if (!running || android.os.SystemClock.elapsedRealtime() < retryAfterMs) return
        if (!advertising) {
            advertising = true
            client.startAdvertising(nodeName, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { ok() }.addOnFailureListener { advertising = false; fail("advertising", it) }
        }
        discover()
        dialIfAlone()
    }

    /**
     * The "smaller name dials" rule stops two phones opening two links to each other, but it leaves a phone out when every
     * phone it can see has a smaller name and is already connected (and so no longer discovering). A phone that has been
     * discovering for 20 s with no link at all therefore dials whoever it has seen, whatever their name.
     */
    private fun dialIfAlone() {
        if (!running || !discovering || connected.isNotEmpty() || pending.isNotEmpty()) return
        if (android.os.SystemClock.elapsedRealtime() - discoveringSinceMs < ALONE_AFTER_MS) return
        val ep = seen.keys.firstOrNull { it !in connected } ?: return
        pending += ep
        client.requestConnection(nodeName, ep, lifecycle).addOnFailureListener { pending.remove(ep) }
    }

    private fun ok() { if (failures > 0) { failures = 0; onRecovered() } }

    private fun fail(what: String, e: Exception) {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        android.util.Log.w("KHOJ", "Nearby $what failed: ${e.message}")
        retryAfterMs = android.os.SystemClock.elapsedRealtime() + 10_000
        val missingPermission = code != null && code in 8030..8040
        if (!missingPermission) onError("Nearby $what failed: ${e.message}")
        else if (++failures == 3 && permissionHint.isNotEmpty()) onError(permissionHint)
    }

    /**
     * Nearby discovery includes Bluetooth Classic inquiry, which blocks BLE reception on the same chip: with it running,
     * the advertising mesh delivered 22-64% of frames at 1 m (measured on two iQOOs). So discover only while not
     * connected to anyone; incoming connections still arrive through advertising.
     */
    private fun discover() {
        val want = running && connected.isEmpty()
        if (want == discovering) return
        discovering = want
        if (want) { discoveringSinceMs = android.os.SystemClock.elapsedRealtime(); seen.clear() }
        if (want) client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener { ok() }.addOnFailureListener { discovering = false; fail("discovery", it) }
        else client.stopDiscovery()
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
        client.stopAdvertising(); advertising = false
        client.stopDiscovery(); discovering = false
        connected.forEach(client::disconnectFromEndpoint)
        connected.clear(); pending.clear()
        health.up = false; health.peers = 0
    }

    val isRunning get() = running

    companion object {
        fun nameFor(nodeId: Int) = "khoj-" + Integer.toHexString(nodeId)
        const val SERVICE = "app.linkfill.mesh.v1"
        const val MAX_LINKS = 4
        const val ALONE_AFTER_MS = 20_000L
    }
}
