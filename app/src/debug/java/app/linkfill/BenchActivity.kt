package app.linkfill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.linkfill.mesh.BleMeshRadio
import app.linkfill.mesh.NearbyMeshTransport
import app.linkfill.mesh.RadioLog
import app.linkfill.ui.LinkFillTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DEBUG ONLY: Isolated radio benchmark harness for Phase 1 and Phase 2 experiments.
 * Drives BleMeshRadio directly without MeshRouter to measure physical and link layer performance.
 *
 * Example adb command:
 *   adb shell am start -n app.linkfill/.BenchActivity --es role tx --es sets 1m --ei updateMs 0 --ei seconds 240
 */
class BenchActivity : ComponentActivity() {

    private var radio: BleMeshRadio? = null
    private var nearbyTransport: NearbyMeshTransport? = null
    private var txJob: Job? = null
    private var timerJob: Job? = null

    private var status by mutableStateOf("INITIALIZING")
    private var secondsRemaining by mutableIntStateOf(240)
    private var txCount by mutableLongStateOf(0L)
    private var rxCount by mutableLongStateOf(0L)
    private var lastRssi by mutableIntStateOf(0)
    private var errorMessage by mutableStateOf<String?>(null)

    private var roleStr = "both"
    private var setsStr = "1m"
    private var updateMsVal = 0
    private var bytesVal = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Parse intent extras with plan-specified defaults
        roleStr = intent.getStringExtra("role") ?: "both"
        setsStr = intent.getStringExtra("sets") ?: "1m"
        updateMsVal = intent.getIntExtra("updateMs", 0)
        bytesVal = intent.getIntExtra("bytes", 200)
        val totalSeconds = intent.getIntExtra("seconds", 240)
        secondsRemaining = totalSeconds
        val legacy = intent.getBooleanExtra("legacy", false)
        val nearby = intent.getStringExtra("nearby") ?: "off"
        val gatt = intent.getStringExtra("gatt") ?: "off"
        val runId = intent.getStringExtra("runId") ?: "bench_${roleStr}_${setsStr}_u${updateMsVal}_${System.currentTimeMillis()}"

        // Start RadioLog
        RadioLog.start(this, runId)

        // Configure radio
        val advSets = when (setsStr.lowercase()) {
            "1m_coded" -> BleMeshRadio.AdvSets.M1_AND_CODED
            "coded" -> BleMeshRadio.AdvSets.CODED
            else -> BleMeshRadio.AdvSets.M1
        }
        val isTx = roleStr.equals("tx", true) || roleStr.equals("both", true)
        val isRx = roleStr.equals("rx", true) || roleStr.equals("both", true)

        val r = BleMeshRadio(
            context = this,
            onFrame = { bytes, rssi, _ ->
                rxCount++
                lastRssi = rssi
            },
            onError = { err ->
                errorMessage = err
            },
            onGattFrame = { _, _ -> },
        )
        radio = r

        val err = r.start(
            nodeId = 0xBE4C,
            advertise = isTx,
            scan = isRx,
            advSets = advSets,
            legacy = legacy,
        )

        if (err != null) {
            status = "ERROR: $err"
            errorMessage = err
        } else {
            status = "RUNNING"
        }

        // Configure GATT idle or off
        if (!gatt.equals("idle", true)) {
            r.gatt.stop()
        }

        // Configure Nearby contention testing
        if (nearby.equals("discover", true)) {
            val nb = NearbyMeshTransport(
                context = this,
                nodeName = "Bench_${roleStr}",
                onFrame = { _ -> },
                onError = { e -> errorMessage = e },
            )
            nearbyTransport = nb
            nb.start()
        }

        // TX transmission loop
        if (isTx && err == null) {
            txJob = lifecycleScope.launch {
                var currentSeq = 0L
                if (updateMsVal <= 0) {
                    // Fixed payload: send once and hold still
                    currentSeq = 1L
                    val payload = buildPayload(currentSeq, bytesVal)
                    r.send(payload, seq = currentSeq)
                    txCount = 1L
                } else {
                    // Periodic data updates
                    while (isActive && secondsRemaining > 0) {
                        currentSeq++
                        val payload = buildPayload(currentSeq, bytesVal)
                        r.send(payload, seq = currentSeq)
                        txCount = currentSeq
                        delay(updateMsVal.toLong())
                    }
                }
            }
        }

        // Countdown timer
        timerJob = lifecycleScope.launch {
            while (isActive && secondsRemaining > 0) {
                delay(1000)
                secondsRemaining--
            }
            if (secondsRemaining <= 0) {
                stopBench("COMPLETED")
            }
        }

        setContent {
            LinkFillTheme {
                BenchScreen(
                    status = status,
                    secondsRemaining = secondsRemaining,
                    role = roleStr,
                    sets = setsStr,
                    updateMs = updateMsVal,
                    bytes = bytesVal,
                    txCount = txCount,
                    rxCount = rxCount,
                    lastRssi = lastRssi,
                    errorMessage = errorMessage,
                    onStop = { stopBench("STOPPED BY USER") },
                )
            }
        }
    }

    private fun buildPayload(seq: Long, size: Int): ByteArray {
        val buf = ByteArray(size.coerceAtLeast(4))
        buf[0] = ((seq ushr 24) and 0xFF).toByte()
        buf[1] = ((seq ushr 16) and 0xFF).toByte()
        buf[2] = ((seq ushr 8) and 0xFF).toByte()
        buf[3] = (seq and 0xFF).toByte()
        for (i in 4 until buf.size) {
            buf[i] = (i and 0xFF).toByte()
        }
        return buf
    }

    private fun stopBench(newStatus: String) {
        status = newStatus
        txJob?.cancel()
        timerJob?.cancel()
        nearbyTransport?.stop()
        nearbyTransport = null
        radio?.stop()
        RadioLog.stop()
    }

    override fun onDestroy() {
        stopBench("DESTROYED")
        super.onDestroy()
    }
}

@Composable
fun BenchScreen(
    status: String,
    secondsRemaining: Int,
    role: String,
    sets: String,
    updateMs: Int,
    bytes: Int,
    txCount: Long,
    rxCount: Long,
    lastRssi: Int,
    errorMessage: String?,
    onStop: () -> Unit,
) {
    val isRunning = status == "RUNNING"
    val statusColor = when (status) {
        "RUNNING" -> Color(0xFF2E7D32)
        "COMPLETED" -> Color(0xFF1565C0)
        else -> Color(0xFFC62828)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "KHOJ BENCH MODE",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Status: $status (${secondsRemaining}s remaining)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                }
            }

            // Configuration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFAAAAAA),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Role: ${role.uppercase()}", color = Color.White)
                        Text("PHY Sets: $sets", color = Color.White)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (updateMs == 0) "Updates: FIXED" else "Updates: ${updateMs}ms",
                            color = Color.White,
                        )
                        Text("Size: ${bytes}B", color = Color.White)
                    }
                }
            }

            // Real-time Metrics Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Real-Time Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFAAAAAA),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TX Sent", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                            Text(
                                text = "$txCount",
                                color = Color(0xFF64B5F6),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RX Heard", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                            Text(
                                text = "$rxCount",
                                color = Color(0xFF81C784),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Last RSSI", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                            Text(
                                text = if (lastRssi != 0) "$lastRssi dBm" else "--",
                                color = Color(0xFFFFB74D),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            // Error Message (if any)
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3E1414)),
                ) {
                    Text(
                        text = "Error: $errorMessage",
                        color = Color(0xFFFF8A80),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action Button
            if (isRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                ) {
                    Text("STOP BENCH", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
