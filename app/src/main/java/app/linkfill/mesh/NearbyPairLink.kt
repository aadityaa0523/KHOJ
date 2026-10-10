package app.linkfill.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
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
 * A private link between the two people of a Find. P2P_POINT_TO_POINT is the Nearby strategy that upgrades a connection
 * from Bluetooth to Wi-Fi Direct or a Wi-Fi hotspot when it can (the mesh's P2P_CLUSTER never does), so once connected
 * the pair keeps a faster link that reaches further. One Connections client cannot run two strategies at once, so the
 * caller pauses the mesh's cluster link while this one runs; the Bluetooth advertising mesh is unaffected.
 *
 * The person being found advertises; the finder discovers and dials. Payloads are already crew-encrypted mesh frames
 * or tunnel frames, so the connection itself is accepted without a token.
 */
class NearbyPairLink(
    context: Context,
    private val myName: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onTunnel: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
) {
    enum class Role { ADVERTISE, DISCOVER }

    private val client = Nearby.getConnectionsClient(context)
    private var role: Role? = null
    private var peerName: String? = null
    private var endpoint: String? = null
    private var pending = false
    /** BandwidthInfo.Quality of the connection: HIGH means Nearby moved it onto Wi-Fi (Direct or hotspot). */
    var quality: Int = BandwidthInfo.Quality.UNKNOWN
        private set
    val running get() = role != null
    val connected get() = endpoint != null

    /** Short label for the screen: which radio the pair link is on now. */
    val medium: String? get() = if (!connected) null else when (quality) {
        BandwidthInfo.Quality.HIGH -> "Wi-Fi Direct / hotspot"
        BandwidthInfo.Quality.MEDIUM -> "Bluetooth (upgrading)"
        else -> "Bluetooth"
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (app.linkfill.core.find.Tunnel.isTunnel(bytes)) onTunnel(bytes) else onFrame(bytes)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Advertising side accepts only the person it expects; the dialer accepts its own request.
            if (role == Role.ADVERTISE && peerName != null && info.endpointName != peerName) { client.rejectConnection(endpointId); return }
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending = false
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                endpoint = endpointId
                log("pair link up with $peerName")
                // Point-to-point allows one connection; stop looking so the radio is free for Wi-Fi and the mesh.
                client.stopDiscovery(); client.stopAdvertising()
            }
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            quality = info.quality
            log("pair link bandwidth: $medium")
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != endpoint) return
            endpoint = null; quality = BandwidthInfo.Quality.UNKNOWN
            log("pair link lost; looking again")
            role?.let { r -> peerName?.let { begin(r, it) } }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName != peerName || connected || pending) return
            pending = true
            client.requestConnection(myName, endpointId, lifecycle).addOnFailureListener { pending = false }
        }
        override fun onEndpointLost(endpointId: String) = Unit
    }

    /** Starts (or keeps) the link with [peer] in [newRole]. Changing peer or role restarts it. */
    fun start(newRole: Role, peer: String) {
        if (role == newRole && peerName == peer) return
        stop()
        role = newRole; peerName = peer
        begin(newRole, peer)
    }

    private fun begin(r: Role, peer: String) {
        if (r == Role.ADVERTISE) client.startAdvertising(myName, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnFailureListener { log("pair advertising failed: ${it.message}") }
        else client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnFailureListener { log("pair discovery failed: ${it.message}") }
        log("pair link ${r.name.lowercase()} for $peer")
    }

    fun stop() {
        if (role == null) return
        client.stopAdvertising(); client.stopDiscovery()
        endpoint?.let(client::disconnectFromEndpoint)
        role = null; peerName = null; endpoint = null; pending = false; quality = BandwidthInfo.Quality.UNKNOWN
    }

    fun hasLinkTo(nodeId: Int) = connected && peerName == NearbyMeshTransport.nameFor(nodeId)

    fun sendTunnel(nodeId: Int, frame: ByteArray): Boolean {
        val ep = endpoint ?: return false
        if (!hasLinkTo(nodeId)) return false
        client.sendPayload(ep, Payload.fromBytes(frame))
        return true
    }

    fun send(frame: ByteArray) { endpoint?.let { client.sendPayload(it, Payload.fromBytes(frame)) } }

    companion object { const val SERVICE = "app.linkfill.pair.v1" }
}
