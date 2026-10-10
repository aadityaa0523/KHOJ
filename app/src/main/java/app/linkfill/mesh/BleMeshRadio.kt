package app.linkfill.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * The phone as a mesh radio: non-connectable BLE extended advertising to send, continuous scanning to
 * receive. No pairing and no connections, so any number of phones in range hear each frame.
 * Phones without extended advertising (older models) and iPhones join through [GattBearer] instead.
 *
 * On phones whose controller supports LE Coded (long range), every frame goes out on TWO advertising sets
 * at once: LE 1M, which every Bluetooth 5 phone hears, and LE Coded, which reaches further but only phones
 * with Coded support hear. Running both continuously avoids the gap of tearing down and restarting a set to
 * switch PHY. The scanner listens on every PHY the chip supports.
 * Callers must hold BLUETOOTH_ADVERTISE and BLUETOOTH_SCAN.
 */
@SuppressLint("MissingPermission")
class BleMeshRadio(
    context: Context,
    private val onFrame: (bytes: ByteArray, rssi: Int, coded: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    onGattFrame: (bytes: ByteArray, rssi: Int) -> Unit,
) {
    val gatt = GattBearer(context, onGattFrame, ::log)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    enum class AdvSets { M1, M1_AND_CODED, CODED }

    val bluetoothOn get() = adapter?.isEnabled == true
    val extendedSupported get() = adapter?.isLeExtendedAdvertisingSupported == true
    val codedSupported get() = adapter?.isLeCodedPhySupported == true
    val maxAdvertisingBytes get() = adapter?.leMaximumAdvertisingDataLength ?: 31

    /** One running advertising set per PHY. */
    private inner class Advertiser(val phy: Int, val legacy: Boolean = false) : AdvertisingSetCallback() {
        var set: AdvertisingSet? = null
        var txPowerDbm: Int? = null
        var currentSeq: Long = 0L

        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) { set = advertisingSet; txPowerDbm = txPower }
            else { advertiseErrors++; onError("Could not start ${phyName(phy)} advertising (code $status).") }
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            RadioLog.logTxDone(android.os.SystemClock.elapsedRealtimeNanos(), phy, status, currentSeq)
            if (status != ADVERTISE_SUCCESS) advertiseErrors++
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) { if (advertisingSet == set) set = null }

        fun start() {
            val adv = adapter?.bluetoothLeAdvertiser ?: return onError("Bluetooth advertiser unavailable.")
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(legacy)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(if (slow) AdvertisingSetParameters.INTERVAL_HIGH else AdvertisingSetParameters.INTERVAL_LOW) // about every 1 s or 100 ms
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
                .setPrimaryPhy(if (legacy) BluetoothDevice.PHY_LE_1M else phy)
                .setSecondaryPhy(if (legacy) BluetoothDevice.PHY_LE_1M else phy)
                .build()
            adv.startAdvertisingSet(params, data(ByteArray(0)), null, null, null, this)
        }

        fun stop() {
            try { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(this) } catch (_: IllegalStateException) { }
            set = null
        }
    }

    private val advertisers = ArrayList<Advertiser>()
    private var slow = false
    private var legacyMode = false

    /** Low-power member: advertise about once a second instead of ten times (restarts the running sets). */
    fun setSlowAdvertising(on: Boolean) {
        if (on == slow) return
        slow = on
        advertisers.forEach { it.stop(); it.start() }
    }
    var framesSent = 0
        private set
    var advertiseErrors = 0
        private set

    /** True when at least one advertising set has started and is active. */
    val isAdvertising get() = advertisers.any { it.set != null }

    /** True when frames are also going out on LE Coded. */
    val isCoded get() = advertisers.any { it.phy == BluetoothDevice.PHY_LE_CODED && it.set != null }

    /** Human-readable summary of the running sets, for diagnostics. */
    val activeSets get() = advertisers.filter { it.set != null }.joinToString { "${phyName(it.phy)} at ${it.txPowerDbm ?: "?"} dBm" }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val arrivalNs = android.os.SystemClock.elapsedRealtimeNanos()
            val record = result.scanRecord ?: return
            record.getServiceData(SERVICE)?.let {
                RadioLog.logRx(result, arrivalNs, it)
                return onFrame(it, result.rssi, result.primaryPhy == BluetoothDevice.PHY_LE_CODED)
            }
            if (record.serviceUuids?.contains(GattBearer.SERVICE) == true)
                gatt.onDoorSeen(result.device, result.rssi, record.getManufacturerSpecificData(GattBearer.MANUFACTURER), result.isConnectable)
        }

        override fun onScanFailed(errorCode: Int) {
            RadioLog.logScanFail(android.os.SystemClock.elapsedRealtimeNanos(), errorCode)
            onError("Bluetooth scanning failed (code $errorCode).")
        }
    }

    var lowLatencyScan = true
        private set
    private var lastScanSwitchMs = 0L

    /**
     * Low-latency scanning hears the most frames but costs the most power. Android blocks apps that restart
     * scans more than 5 times in 30 s, so switches are limited to one per 30 s.
     */
    fun setLowLatencyScan(want: Boolean, now: Long) {
        if (want == lowLatencyScan || now - lastScanSwitchMs < 30_000 || !running) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        try { scanner.stopScan(scanCallback) } catch (_: IllegalStateException) { return }
        lowLatencyScan = want
        lastScanSwitchMs = now
        RadioLog.logScanStart(android.os.SystemClock.elapsedRealtimeNanos(), if (want) "LOW_LATENCY" else "BALANCED")
        scanner.startScan(scanFilters(), scanSettings(legacyMode), scanCallback)
    }

    private fun scanSettings(legacy: Boolean = false) = ScanSettings.Builder()
        .setScanMode(if (lowLatencyScan) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
        .setLegacy(legacy || !extendedSupported) // asking for extended reports on a phone without them makes the scan fail
        .setPhy(if (extendedSupported && !legacy) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setReportDelay(0)
        .build()

    // A filter is required for scanning to continue with the screen off; these are offloaded to the controller.
    private fun scanFilters() = listOf(
        ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0), ByteArray(0)).build(),
        ScanFilter.Builder().setServiceUuid(GattBearer.SERVICE).build(),
    )

    /**
     * Starts the radio. Can be configured for tx-only, rx-only, specific PHY sets, or legacy advertising for bench testing.
     * Defaults to 1M only; Coded PHY is started on-demand as an emergency burst.
     */
    fun start(
        nodeId: Int,
        advertise: Boolean = true,
        scan: Boolean = true,
        advSets: AdvSets = AdvSets.M1,
        legacy: Boolean = false,
    ): String? {
        val a = adapter ?: return "This phone has no Bluetooth."
        if (!a.isEnabled) return "Bluetooth is off. Turn it on to join the mesh."
        advertisers.clear()
        legacyMode = legacy
        if (advertise && (extendedSupported || legacy)) {
            val want1m = advSets == AdvSets.M1 || advSets == AdvSets.M1_AND_CODED
            val wantCoded = (advSets == AdvSets.CODED || advSets == AdvSets.M1_AND_CODED) && codedSupported && !legacy
            if (want1m) advertisers += Advertiser(BluetoothDevice.PHY_LE_1M, legacy)
            if (wantCoded) advertisers += Advertiser(BluetoothDevice.PHY_LE_CODED, false)
            advertisers.forEach { it.start() }
        }
        gatt.start(nodeId, meshCapable = extendedSupported)?.let { if (!extendedSupported) return it else log(it) }
        lowLatencyScan = true
        running = true
        RadioLog.activeAdvSetsProvider = { advertisers.count { it.set != null } }
        if (scan) {
            RadioLog.logScanStart(android.os.SystemClock.elapsedRealtimeNanos(), if (lowLatencyScan) "LOW_LATENCY" else "BALANCED")
            a.bluetoothLeScanner?.startScan(scanFilters(), scanSettings(legacy), scanCallback) ?: return "Bluetooth scanner unavailable."
        }
        return null
    }

    /**
     * Dynamically turns the LE Coded advertising set on or off.
     * In Phase 3, LE Coded runs as an emergency burst rather than an always-on set.
     */
    fun setCodedAdvertising(on: Boolean) {
        if (!codedSupported || legacyMode || !running) return
        val existing = advertisers.firstOrNull { it.phy == BluetoothDevice.PHY_LE_CODED }
        if (on) {
            if (existing == null) {
                val adv = Advertiser(BluetoothDevice.PHY_LE_CODED, false)
                advertisers.add(adv)
                adv.start()
                log("Started LE Coded advertising set (emergency burst).")
            }
        } else {
            if (existing != null) {
                existing.stop()
                advertisers.remove(existing)
                log("Stopped LE Coded advertising set.")
            }
        }
    }

    private var running = false

    /** Puts [frame] on the air on every running PHY until the next call. */
    fun send(frame: ByteArray, seq: Long? = null) {
        val payloadSeq = seq ?: RadioLog.parseSeq(frame)
        val hash = RadioLog.hash8(frame)
        val d = data(frame)
        var sent = false
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        for (a in advertisers) {
            a.set?.let {
                a.currentSeq = payloadSeq
                RadioLog.logTxUpdate(nowNs, a.phy, payloadSeq, frame.size, hash)
                it.setAdvertisingData(d)
                sent = true
            }
        }
        if (sent) framesSent++
        // Link-measurement filler only makes sense on the broadcast radio; GATT links carry real packets.
        if (frame.size > 2 && frame[2].toInt() != app.linkfill.core.mesh.MeshType.NEIGHBOR.code) gatt.send(frame)
    }

    fun stop() {
        running = false
        gatt.stop()
        advertisers.forEach { it.stop() }
        advertisers.clear()
        try {
            RadioLog.logScanStop(android.os.SystemClock.elapsedRealtimeNanos())
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: IllegalStateException) { }
    }

    /** One-line capability report, logged at start so every test phone documents what it can do. */
    fun capabilities(): String {
        val a = adapter ?: return "no Bluetooth adapter"
        return "extendedAdv=${a.isLeExtendedAdvertisingSupported} coded=${a.isLeCodedPhySupported} 2M=${a.isLe2MPhySupported} " +
            "periodicAdv=${a.isLePeriodicAdvertisingSupported} maxAdvBytes=${a.leMaximumAdvertisingDataLength} " +
            "offloadedFiltering=${a.isOffloadedFilteringSupported} offloadedBatching=${a.isOffloadedScanBatchingSupported} " +
            "multiAdv=${a.isMultipleAdvertisementSupported} gattOnly=${!a.isLeExtendedAdvertisingSupported}"
    }

    private fun data(frame: ByteArray) = AdvertiseData.Builder().addServiceData(SERVICE, frame).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()

    companion object {
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("6b686f6a-0001-4b48-8f4a-6c696e6b6669")) // "khoj"..."linkfi"

        fun phyName(phy: Int) = when (phy) { BluetoothDevice.PHY_LE_CODED -> "LE Coded"; BluetoothDevice.PHY_LE_2M -> "LE 2M"; else -> "LE 1M" }

        fun log(msg: String) = Log.i("KHOJ", msg)
    }
}
