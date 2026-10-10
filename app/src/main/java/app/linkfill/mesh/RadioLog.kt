package app.linkfill.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * High-performance, non-blocking telemetry logger for BLE mesh benchmark and diagnostics.
 * In normal use, logging is off (all log calls return immediately with zero allocations).
 * When enabled (via BenchActivity or diagnostics switch), events are enqueued to a lock-free queue
 * and flushed to `files/radio_log.csv` once per second on a background thread.
 */
object RadioLog {
    private const val TAG = "KHOJ"

    @Volatile
    var enabled: Boolean = false
        private set

    private val queue = ConcurrentLinkedQueue<String>()
    private var executor: ScheduledExecutorService? = null
    private var logFile: File? = null

    // Callbacks to query live system state every 5 s
    var nearbyStateProvider: (() -> String)? = null
    var gattCountProvider: (() -> Int)? = null
    var activeAdvSetsProvider: (() -> Int)? = null

    private val sha256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    /** Computes the first 8 hex characters of the SHA-256 digest. */
    fun hash8(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "00000000"
        val md = sha256.get() ?: MessageDigest.getInstance("SHA-256")
        md.reset()
        val digest = md.digest(bytes)
        return buildString(8) {
            for (i in 0 until 4) {
                val b = digest[i].toInt() and 0xFF
                append(Character.forDigit((b ushr 4) and 0xF, 16))
                append(Character.forDigit(b and 0xF, 16))
            }
        }
    }

    /** Extracts payload sequence number from standard Bench or KHOJ packets. */
    fun parseSeq(bytes: ByteArray): Long {
        if (bytes.size < 4) return 0L
        return ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
    }

    fun phyLabel(phy: Int) = when (phy) {
        BluetoothDevice.PHY_LE_CODED -> "LE Coded"
        BluetoothDevice.PHY_LE_2M -> "LE 2M"
        else -> "LE 1M"
    }

    /**
     * Starts radio logging for a benchmark run.
     * Writes header row: `# HEADER,model,build,gitHash,runId`
     */
    @Synchronized
    fun start(
        context: Context,
        runId: String,
        gitHash: String = "d76a1e6",
        append: Boolean = false,
    ) {
        if (enabled) stop()

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "radio_log.csv")
        logFile = file

        val isNew = !file.exists() || !append || file.length() == 0L
        if (isNew) {
            val model = "${Build.MANUFACTURER} ${Build.MODEL}".replace(",", " ")
            val build = "${Build.ID} (Android ${Build.VERSION.RELEASE})".replace(",", " ")
            queue.add("# HEADER,$model,$build,$gitHash,$runId")
        }

        enabled = true
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "RadioLogFlusher").apply { isDaemon = true }
        }
        executor = exec

        // Flush queued rows to file every 1000 ms
        exec.scheduleWithFixedDelay({
            flushQueue()
        }, 1000, 1000, TimeUnit.MILLISECONDS)

        // Log STATE every 5 s
        exec.scheduleWithFixedDelay({
            if (enabled) logSystemState(context)
        }, 2000, 5000, TimeUnit.MILLISECONDS)

        Log.i(TAG, "RadioLog started: logging to ${file.absolutePath} (runId=$runId)")
    }

    /**
     * Flushes any pending events and stops logging.
     */
    @Synchronized
    fun stop() {
        if (!enabled) return
        enabled = false
        executor?.let { exec ->
            try {
                exec.shutdown()
                exec.awaitTermination(1500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        executor = null
        flushQueue()
        Log.i(TAG, "RadioLog stopped.")
    }

    private fun flushQueue() {
        val file = logFile ?: return
        if (queue.isEmpty()) return

        try {
            BufferedWriter(FileWriter(file, true)).use { writer ->
                while (true) {
                    val line = queue.poll() ?: break
                    writer.write(line)
                    writer.newLine()
                }
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RadioLog flush error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- Logging Events

    fun logTxUpdate(timestampNs: Long, phy: Int, seq: Long, len: Int, hash: String) {
        if (!enabled) return
        queue.add("TX_UPDATE,$timestampNs,${phyLabel(phy)},$seq,$len,$hash")
    }

    fun logTxDone(timestampNs: Long, phy: Int, status: Int, seq: Long) {
        if (!enabled) return
        queue.add("TX_DONE,$timestampNs,${phyLabel(phy)},$status,$seq")
    }

    fun logRx(result: ScanResult, arrivalNs: Long, payload: ByteArray) {
        if (!enabled) return
        val scanNs = result.timestampNanos
        val rssi = result.rssi
        val primaryPhy = phyLabel(result.primaryPhy)
        val secondaryPhy = phyLabel(result.secondaryPhy)
        val sid = result.advertisingSid
        val dataStatus = result.dataStatus
        val txPower = result.txPower
        val hash = hash8(payload)
        val seq = parseSeq(payload)

        // Parse optional relayId and linkSeq if this is a standard KHOJ mesh frame (>=29 bytes, magic 0x4B)
        val (relayId, linkSeq) = if (payload.size >= 29 && payload[0] == 0x4B.toByte()) {
            val rId = ((payload[8].toInt() and 0xFF) shl 24) or
                ((payload[9].toInt() and 0xFF) shl 16) or
                ((payload[10].toInt() and 0xFF) shl 8) or
                (payload[11].toInt() and 0xFF)
            val lSeq = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
            rId to lSeq
        } else {
            0 to (seq and 0xFFFF).toInt()
        }

        queue.add("RX,$scanNs,$arrivalNs,$rssi,$primaryPhy,$secondaryPhy,$sid,$dataStatus,$txPower,$hash,$seq,$relayId,$linkSeq")
    }

    fun logScanStart(timestampNs: Long, mode: String) {
        if (!enabled) return
        queue.add("SCAN_START,$timestampNs,$mode")
    }

    fun logScanStop(timestampNs: Long) {
        if (!enabled) return
        queue.add("SCAN_STOP,$timestampNs")
    }

    fun logScanFail(timestampNs: Long, errorCode: Int) {
        if (!enabled) return
        queue.add("SCAN_FAIL,$timestampNs,$errorCode")
    }

    private fun logSystemState(context: Context) {
        if (!enabled) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val pm = context.getSystemService(PowerManager::class.java)
        val bm = context.getSystemService(BatteryManager::class.java)

        val screenOn = pm?.isInteractive == true
        val batterySaver = pm?.isPowerSaveMode == true
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm?.currentThermalStatus ?: -1
        } else -1
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val nearbyState = nearbyStateProvider?.invoke() ?: "off"
        val gattCount = gattCountProvider?.invoke() ?: 0
        val activeSets = activeAdvSetsProvider?.invoke() ?: 0

        queue.add("STATE,$nowNs,$nearbyState,$gattCount,$screenOn,$batterySaver,$thermalStatus,$batteryPct,$activeSets")
    }
}
