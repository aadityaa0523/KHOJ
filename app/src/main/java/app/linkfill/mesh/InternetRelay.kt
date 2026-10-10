package app.linkfill.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import app.linkfill.core.mesh.TransportHealth
import app.linkfill.core.mesh.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mobile-data bridge to the KHOJ relay server (relay/server.js). Uploads packets this phone originates or
 * first hears, and long-polls for the crew's packets so they can be re-broadcast into the local mesh.
 * The server only ever sees crew-encrypted packets. Works only while this phone has a validated internet
 * connection; with none, it simply stays down.
 */
class InternetRelay(private val context: Context, private val scope: CoroutineScope, private val onFrame: (ByteArray) -> Unit) {
    val health = TransportHealth(TransportKind.INTERNET)
    private var poll: Job? = null
    private var baseUrl = ""
    private var crewHex = ""
    var lastError: String? = null
        private set

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun start(url: String, crewId: Int) {
        stop()
        baseUrl = url.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return
        crewHex = hex(crewId)
        poll = scope.launch(Dispatchers.IO) {
            var after = 0L
            var backoff = 2_000L
            while (isActive) {
                health.up = online()
                if (!health.up) { delay(5_000); continue }
                try {
                    val c = URL("$baseUrl/v1/crews/$crewHex/packets?after=$after&wait=20").openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 30_000
                    if (c.responseCode != 200) error("relay answered ${c.responseCode}")
                    val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    after = json.getLong("next") - 1
                    val packets = json.getJSONArray("packets")
                    val frames = List(packets.length()) { Base64.decode(packets.getString(it), Base64.DEFAULT) }
                    withContext(Dispatchers.Main) {
                        for (f in frames) { health.received++; health.lastRxMs = System.currentTimeMillis(); onFrame(f) }
                    }
                    lastError = null
                    backoff = 2_000
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    /** Uploads one packet under the crew tag in its own header. Fire and forget; the result feeds link quality. */
    fun upload(frame: ByteArray, crewId: Int) {
        if (baseUrl.isEmpty() || !health.up) return
        val url = "$baseUrl/v1/crews/${hex(crewId)}/packets"
        health.sent++
        scope.launch(Dispatchers.IO) {
            val ok = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("content-type", "application/octet-stream")
                c.outputStream.use { it.write(frame) }
                c.responseCode == 204
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                false
            }
            withContext(Dispatchers.Main) { health.recordSend(ok) }
        }
    }

    fun stop() { poll?.cancel(); poll = null; health.up = false }

    private fun hex(id: Int) = "%08x".format(id)
}
