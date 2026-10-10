package app.linkfill.nearby

import android.content.Context
import app.linkfill.core.sim.GilbertElliott
import com.google.android.gms.common.api.ApiException
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
 * Thin wrapper over Google Nearby Connections. Nearby does discovery, the radio transports and its own
 * encrypted link; this class only reports events. All callbacks arrive on the main thread.
 * It does not provide multi-hop routing: one phone talks to one phone (P2P_POINT_TO_POINT).
 */
class NearbyConnectionManager(context: Context, private val events: Events) {

    interface Events {
        fun onEndpointFound(endpointId: String, name: String)
        fun onEndpointLost(endpointId: String)
        fun onConnectionInitiated(endpointId: String, name: String, authDigits: String, incoming: Boolean)
        fun onConnected(endpointId: String)
        fun onConnectionFailed(endpointId: String, reason: String)
        fun onDisconnected(endpointId: String)
        fun onBytes(endpointId: String, bytes: ByteArray)
        fun onError(message: String)
    }

    private val client = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val requesting = mutableSetOf<String>()

    /** Test-only application-layer loss (labelled in the UI). Null means no simulated loss. */
    var simulatedLoss: GilbertElliott? = null

    // Transport-level delivery states, kept separate from application-level acknowledgements.
    var handedToTransport = 0
        private set
    var transportFailures = 0
        private set
    var droppedBySimulation = 0
        private set

    var advertising = false
        private set
    var discovering = false
        private set

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (simulatedLoss?.lost() == true) { droppedBySimulation++; return }
            events.onBytes(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) transportFailures++
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            events.onConnectionInitiated(endpointId, info.endpointName, info.authenticationDigits, info.isIncomingConnection)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            requesting.remove(endpointId)
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> events.onConnected(endpointId)
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> events.onConnectionFailed(endpointId, "The other phone declined the connection.")
                else -> events.onConnectionFailed(endpointId, describe(result.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) = events.onDisconnected(endpointId)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE_ID) events.onEndpointFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) = events.onEndpointLost(endpointId)
    }

    fun startAdvertising(name: String) {
        if (advertising) return
        advertising = true
        client.startAdvertising(name, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { advertising = false; events.onError("Could not start sharing: ${describe(it)}") }
    }

    fun stopAdvertising() { client.stopAdvertising(); advertising = false }

    fun startDiscovery() {
        if (discovering) return
        discovering = true
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { discovering = false; events.onError("Could not search for nearby phones: ${describe(it)}") }
    }

    fun stopDiscovery() { client.stopDiscovery(); discovering = false }

    /** Ignores repeated taps while a request to the same endpoint is already in flight. */
    fun requestConnection(localName: String, endpointId: String): Boolean {
        if (!requesting.add(endpointId)) return false
        client.requestConnection(localName, endpointId, lifecycle)
            .addOnFailureListener {
                requesting.remove(endpointId)
                events.onConnectionFailed(endpointId, describe(it))
            }
        return true
    }

    fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { events.onConnectionFailed(endpointId, describe(it)) }
    }

    fun reject(endpointId: String) { client.rejectConnection(endpointId) }

    fun send(endpointId: String, bytes: ByteArray) {
        if (simulatedLoss?.lost() == true) { droppedBySimulation++; return }
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnSuccessListener { handedToTransport++ }
            .addOnFailureListener { transportFailures++ }
    }

    fun disconnect(endpointId: String) { client.disconnectFromEndpoint(endpointId) }

    fun stopAll() {
        client.stopAllEndpoints()
        advertising = false
        discovering = false
        requesting.clear()
    }

    companion object {
        const val SERVICE_ID = "app.linkfill.share.v1"

        fun describe(e: Exception): String = if (e is ApiException) describe(e.statusCode) else (e.message ?: e.javaClass.simpleName)

        fun describe(code: Int): String = when (code) {
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth problem. Check that Bluetooth is on."
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES -> "A required permission is missing."
            ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "Already connected to this phone."
            ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR -> "The connection broke while sending."
            ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "A radio error occurred. Try turning Bluetooth off and on."
            else -> ConnectionsStatusCodes.getStatusCodeString(code)
        }
    }
}
