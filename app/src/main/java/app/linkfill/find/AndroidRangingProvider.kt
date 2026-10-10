package app.linkfill.find

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.ranging.RangingCapabilities
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingMeasurement
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SessionConfig
import android.ranging.oob.DeviceHandle
import android.ranging.oob.OobInitiatorRangingConfig
import android.ranging.oob.OobResponderRangingConfig
import android.ranging.oob.TransportHandle
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.linkfill.core.find.Level
import app.linkfill.core.find.RangingFix
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executor

/** Asks the platform what ranging hardware exists and is currently enabled. Safe to call on any Android version. */
class RangingCapabilityProbe(private val context: Context, private val onCaps: (RangingCaps) -> Unit) {
    private var cb: Any? = null

    fun permissionGranted() = Build.VERSION.SDK_INT >= 36 && context.checkSelfPermission(Manifest.permission.RANGING) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (Build.VERSION.SDK_INT < 36) { onCaps(RangingCaps(false, Build.VERSION.SDK_INT, false, true, emptyMap(), false, false, false)); return }
        onCaps(RangingCaps(true, Build.VERSION.SDK_INT, permissionGranted(), false, emptyMap(), false, false, false))
        registerApi36()
    }

    @RequiresApi(36)
    @SuppressLint("MissingPermission")
    private fun registerApi36() {
        val mgr = context.getSystemService(RangingManager::class.java) ?: return onCaps(RangingCaps(false, Build.VERSION.SDK_INT, false, true, emptyMap(), false, false, false))
        val callback = RangingManager.RangingCapabilitiesCallback { c -> onCaps(describe(c)) }
        cb = callback
        try { mgr.registerCapabilitiesCallback(ContextCompat.getMainExecutor(context), callback) } catch (e: SecurityException) { /* permission missing: the UI shows it */ }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < 36) return
        val callback = cb as? RangingManager.RangingCapabilitiesCallback ?: return
        context.getSystemService(RangingManager::class.java)?.unregisterCapabilitiesCallback(callback)
        cb = null
    }

    @RequiresApi(36)
    private fun describe(c: RangingCapabilities): RangingCaps {
        fun avail(v: Int?) = when (v) {
            RangingCapabilities.ENABLED -> Availability.ENABLED
            RangingCapabilities.NOT_SUPPORTED -> Availability.NOT_SUPPORTED
            RangingCapabilities.DISABLED_USER, RangingCapabilities.DISABLED_USER_RESTRICTIONS -> Availability.DISABLED_USER
            RangingCapabilities.DISABLED_REGULATORY -> Availability.DISABLED_REGULATORY
            null -> Availability.NOT_SUPPORTED
            else -> Availability.UNKNOWN
        }
        val map = c.technologyAvailability
        val tech = mapOf(
            Tech.UWB to avail(map[RangingManager.UWB]),
            Tech.BLE_CS to avail(map[RangingManager.BLE_CS]),
            Tech.BLE_RSSI to avail(map[RangingManager.BLE_RSSI]),
            Tech.WIFI_RTT to avail(map[RangingManager.WIFI_NAN_RTT]),
        )
        val uwb = c.uwbCapabilities
        return RangingCaps(true, Build.VERSION.SDK_INT, permissionGranted(), true, tech, uwb?.isAzimuthalAngleSupported == true, uwb?.isElevationAngleSupported == true, uwb?.isBackgroundRangingSupported == true)
    }
}

/**
 * Carries the ranging module's out-of-band negotiation bytes over our authenticated tunnel (see core `Tunnel`).
 * Bytes that arrive before the ranging module registers its receive callback are queued, so the responder
 * does not lose the initiator's first message.
 */
@RequiresApi(36)
class TunnelTransportHandle(private val executor: Executor, private val send: (ByteArray) -> Boolean) : TransportHandle {
    private var callback: TransportHandle.ReceiveCallback? = null
    private val queued = ArrayDeque<ByteArray>()
    private var closed = false

    override fun registerReceiveCallback(executor: Executor, callback: TransportHandle.ReceiveCallback) {
        this.callback = callback
        while (queued.isNotEmpty()) { val b = queued.removeFirst(); executor.execute { callback.onReceiveData(b) } }
    }

    override fun sendData(data: ByteArray) {
        if (closed) return
        if (!send(data)) executor.execute { callback?.onSendFailed() }
    }

    /** Called when the peer's DATA frame arrives through the tunnel. */
    fun deliver(data: ByteArray) {
        val cb = callback
        if (cb == null) queued.addLast(data) else executor.execute { cb.onReceiveData(data) }
    }

    fun linkLost() { callback?.let { executor.execute { it.onDisconnect() } } }

    override fun close() { closed = true; callback?.let { executor.execute { it.onClose() } } }
}

/**
 * One out-of-band ranging session with one crew member, on Android 16+. The platform picks the technology (UWB, Bluetooth
 * Channel Sounding, Wi-Fi NAN RTT, or BLE RSSI) from what both phones support; this class only reports what it returns.
 * Distance and, when the hardware provides it, azimuth come from [RangingData]; nothing here is estimated or filled in.
 */
@RequiresApi(36)
@SuppressLint("MissingPermission")
class AndroidRangingSession(
    private val context: Context,
    private val initiator: Boolean,
    private val wantAngle: Boolean,
    private val transport: TunnelTransportHandle,
    private val onFix: (RangingFix, rawTimestamp: Long, rawAzimuth: Double?) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val executor = ContextCompat.getMainExecutor(context)
    private var session: RangingSession? = null
    private val peer: RangingDevice = RangingDevice.Builder().setUuid(UUID.randomUUID()).build()
    var headingProvider: () -> Double? = { null }

    private val callback = object : RangingSession.Callback {
        override fun onOpened() = onStatus("session opened")
        override fun onOpenFailed(reason: Int) = onStatus("session failed to open: ${reasonText(reason)}")
        override fun onStarted(device: RangingDevice, technology: Int) = onStatus("ranging started with ${techName(technology)}")
        override fun onStopped(device: RangingDevice, technology: Int) = onStatus("${techName(technology)} stopped")
        override fun onClosed(reason: Int) = onStatus("session closed: ${reasonText(reason)}")
        override fun onResults(device: RangingDevice, data: RangingData) {
            val tech = Tech.entries.firstOrNull { it.ordinalToApi() == data.rangingTechnology } ?: return
            val dist = data.distance ?: return
            val az = data.azimuth
            val fix = RangingFix(
                tech.source, dist.measurement, level(dist.confidence), az?.measurement, az?.let { level(it.confidence) },
                System.currentTimeMillis(), headingProvider(),
            )
            onFix(fix, data.timestampMillis, az?.measurement)
        }
    }

    fun start() {
        val mgr = context.getSystemService(RangingManager::class.java) ?: return onStatus("no RangingManager")
        try {
            val s = mgr.createRangingSession(executor, callback) ?: return onStatus("could not create a ranging session")
            session = s
            val handle = DeviceHandle.Builder(peer, transport).build()
            val preference = if (initiator) {
                val config = OobInitiatorRangingConfig.Builder()
                    .setFastestRangingInterval(Duration.ofMillis(200))
                    .setSlowestRangingInterval(Duration.ofMillis(2000))
                    .setRangingMode(OobInitiatorRangingConfig.RANGING_MODE_HIGH_ACCURACY_PREFERRED) // AUTO picked plain RSSI ranging on two iQOOs with Channel Sounding (measured)
                    // Basic: the tunnel carrying the negotiation is already authenticated and encrypted with the crew key.
                    .setSecurityLevel(OobInitiatorRangingConfig.SECURITY_LEVEL_BASIC)
                    .addDeviceHandle(handle)
                    .build()
                RangingPreference.Builder(RangingPreference.DEVICE_ROLE_INITIATOR, config)
                    .setSessionConfig(SessionConfig.Builder().setAngleOfArrivalNeeded(wantAngle).build())
                    .build()
            } else {
                RangingPreference.Builder(RangingPreference.DEVICE_ROLE_RESPONDER, OobResponderRangingConfig.Builder(handle).build()).build()
            }
            s.start(preference)
            onStatus(if (initiator) "negotiating as initiator" else "waiting as responder")
        } catch (e: SecurityException) {
            onStatus("ranging permission denied")
        } catch (e: RuntimeException) {
            onStatus("could not start: ${e.message}")
        }
    }

    fun stop() {
        try { session?.stop() } catch (_: RuntimeException) { }
        try { session?.close() } catch (_: RuntimeException) { }
        session = null
        transport.close()
    }

    private fun level(c: Int) = when (c) {
        RangingMeasurement.CONFIDENCE_HIGH -> Level.HIGH
        RangingMeasurement.CONFIDENCE_MEDIUM -> Level.MEDIUM
        else -> Level.LOW
    }

    private fun Tech.ordinalToApi() = when (this) { Tech.UWB -> RangingManager.UWB; Tech.BLE_CS -> RangingManager.BLE_CS; Tech.BLE_RSSI -> RangingManager.BLE_RSSI; Tech.WIFI_RTT -> RangingManager.WIFI_NAN_RTT }

    private fun techName(t: Int) = Tech.entries.firstOrNull { it.ordinalToApi() == t }?.label ?: "technology $t"

    private fun reasonText(r: Int) = when (r) {
        RangingSession.Callback.REASON_LOCAL_REQUEST -> "stopped here"
        RangingSession.Callback.REASON_REMOTE_REQUEST -> "stopped by the other phone"
        RangingSession.Callback.REASON_NO_PEERS_FOUND -> "peer not found"
        RangingSession.Callback.REASON_SYSTEM_POLICY -> "blocked by system policy"
        RangingSession.Callback.REASON_UNSUPPORTED -> "not supported by one of the phones"
        else -> "unknown reason ($r)"
    }
}
