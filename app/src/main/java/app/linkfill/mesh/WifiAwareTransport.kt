package app.linkfill.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import app.linkfill.core.mesh.TransportHealth
import app.linkfill.core.mesh.TransportKind

/**
 * Wi-Fi Aware (Neighbor Awareness Networking): phones discover each other over Wi-Fi without a router or
 * internet, then exchange short acknowledged messages. Every phone both publishes and subscribes the KHOJ
 * service; frames go out as messages to every discovered peer. The message-sent callbacks are Wi-Fi's own
 * acknowledgements, so this link's quality is measured directly.
 * Needs a phone with Wi-Fi Aware hardware and Wi-Fi switched on (no network connection needed).
 */
@SuppressLint("MissingPermission")
class WifiAwareTransport(context: Context, private val onFrame: (ByteArray) -> Unit, private val onError: (String) -> Unit) {
    val health = TransportHealth(TransportKind.WIFI_AWARE)
    private val manager: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) && manager != null
    private val handler = Handler(Looper.getMainLooper())
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val peers = LinkedHashMap<PeerHandle, Int>() // peer -> consecutive send failures
    private val inFlight = HashMap<Int, PeerHandle>()
    private var messageId = 0

    private val maxBytes get() = manager?.characteristics?.maxServiceSpecificInfoLength ?: 255

    fun start(): String? {
        val m = manager ?: return "Wi-Fi Aware is not available on this phone."
        if (!supported) return "This phone has no Wi-Fi Aware."
        if (!m.isAvailable) return "Wi-Fi Aware is off. Turn Wi-Fi on (no network needed)."
        m.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                s.publish(PublishConfig.Builder().setServiceName(SERVICE).build(), callback, handler)
                s.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE).build(), callback, handler)
                health.up = true
            }

            override fun onAttachFailed() { health.up = false; onError("Could not start Wi-Fi Aware.") }
        }, handler)
        return null
    }

    private val callback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) { publish = s }
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) { subscribe = s }

        override fun onServiceDiscovered(peer: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
            peers[peer] = 0
            health.peers = peers.size
        }

        override fun onServiceLost(peer: PeerHandle, reason: Int) { peers.remove(peer); health.peers = peers.size }

        override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
            health.received++
            health.lastRxMs = System.currentTimeMillis()
            onFrame(message)
        }

        override fun onMessageSendSucceeded(id: Int) {
            health.recordSend(true)
            inFlight.remove(id)?.let { peers[it] = 0 }
        }

        override fun onMessageSendFailed(id: Int) {
            health.recordSend(false)
            val peer = inFlight.remove(id) ?: return
            val fails = (peers[peer] ?: return) + 1
            // A peer that keeps failing has walked away; forget it until it is discovered again.
            if (fails >= 5) peers.remove(peer) else peers[peer] = fails
            health.peers = peers.size
        }

        override fun onSessionTerminated() { health.up = false; peers.clear(); health.peers = 0 }
    }

    /** Sends to every discovered peer. Messages are sent from the subscribe session, which discovered them. */
    fun send(frame: ByteArray) {
        val s = subscribe ?: return
        if (frame.size > maxBytes) return
        for (peer in peers.keys.toList()) {
            val id = messageId++
            inFlight[id] = peer
            s.sendMessage(peer, id, frame)
            health.sent++
        }
        while (inFlight.size > 256) inFlight.remove(inFlight.keys.first())
    }

    fun stop() {
        publish?.close(); subscribe?.close(); session?.close()
        publish = null; subscribe = null; session = null
        peers.clear(); inFlight.clear()
        health.up = false; health.peers = 0
    }

    companion object { const val SERVICE = "khoj-mesh" }
}
