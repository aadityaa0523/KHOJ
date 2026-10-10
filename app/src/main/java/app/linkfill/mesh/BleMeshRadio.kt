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

    val bluetoothOn get() = adapter?.isEnabled == true
    val extendedSupported get() = adapter?.isLeExtendedAdvertisingSupported == true
    val codedSupported get() = adapter?.isLeCodedPhySupported == true
    val maxAdvertisingBytes get() = adapter?.leMaximumAdvertisingDataLength ?: 31

    /** One running advertising set per PHY. */
    private inner class Advertiser(val phy: Int) : AdvertisingSetCallback() {
        var set: AdvertisingSet? = null
        var txPowerDbm: Int? = null

        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) { set = advertisingSet; txPowerDbm = txPower }
            else { advertiseErrors++; onError("Could not start ${phyName(phy)} advertising (code $status).") }
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) { if (status != ADVERTISE_SUCCESS) advertiseErrors++ }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) { if (advertisingSet == set) set = null }

        fun start() {
            val adv = adapter?.bluetoothLeAdvertiser ?: return onError("Bluetooth advertiser unavailable.")
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(if (slow) AdvertisingSetParameters.INTERVAL_HIGH else AdvertisingSetParameters.INTERVAL_LOW) // about every 1 s or 100 ms
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
                .setPrimaryPhy(phy)
                .setSecondaryPhy(phy)
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

    /** True when frames are also going out on LE Coded. */
    val isCoded get() = advertisers.any { it.phy == BluetoothDevice.PHY_LE_CODED && it.set != null }

    /** Human-readable summary of the running sets, for diagnostics. */
    val activeSets get() = advertisers.filter { it.set != null }.joinToString { "${phyName(it.phy)} at ${it.txPowerDbm ?: "?"} dBm" }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            record.getServiceData(SERVICE)?.let { return onFrame(it, result.rssi, result.primaryPhy == BluetoothDevice.PHY_LE_CODED) }
            if (record.serviceUuids?.contains(GattBearer.SERVICE) == true)
                gatt.onDoorSeen(result.device, result.rssi, record.getManufacturerSpecificData(GattBearer.MANUFACTURER), result.isConnectable)
        }

        override fun onScanFailed(errorCode: Int) = onError("Bluetooth scanning failed (code $errorCode).")
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
        scanner.startScan(scanFilters(), scanSettings(), scanCallback)
    }

    private fun scanSettings() = ScanSettings.Builder()
        .setScanMode(if (lowLatencyScan) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
        .setLegacy(!extendedSupported) // asking for extended reports on a phone without them makes the scan fail
        .setPhy(if (extendedSupported) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    // A filter is required for scanning to continue with the screen off; these are offloaded to the controller.
    private fun scanFilters() = listOf(
        ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0), ByteArray(0)).build(),
        ScanFilter.Builder().setServiceUuid(GattBearer.SERVICE).build(),
    )

    /**
     * Returns an error message instead of starting when this phone cannot run the mesh. Without extended advertising
     * (older phones) it still joins: frames go over GATT links only.
     */
    fun start(nodeId: Int): String? {
        val a = adapter ?: return "This phone has no Bluetooth."
        if (!a.isEnabled) return "Bluetooth is off. Turn it on to join the mesh."
        advertisers.clear()
        if (extendedSupported) {
            advertisers += Advertiser(BluetoothDevice.PHY_LE_1M)
            if (codedSupported) advertisers += Advertiser(BluetoothDevice.PHY_LE_CODED)
            advertisers.forEach { it.start() }
        }
        gatt.start(nodeId, meshCapable = extendedSupported)?.let { if (!extendedSupported) return it else log(it) }
        lowLatencyScan = true
        running = true
        a.bluetoothLeScanner?.startScan(scanFilters(), scanSettings(), scanCallback) ?: return "Bluetooth scanner unavailable."
        return null
    }

    private var running = false

    /** Puts [frame] on the air on every running PHY until the next call. */
    fun send(frame: ByteArray) {
        val d = data(frame)
        var sent = false
        for (a in advertisers) a.set?.let { it.setAdvertisingData(d); sent = true }
        if (sent) framesSent++
        // Link-measurement filler only makes sense on the broadcast radio; GATT links carry real packets.
        if (frame.size > 2 && frame[2].toInt() != app.linkfill.core.mesh.MeshType.NEIGHBOR.code) gatt.send(frame)
    }

    fun stop() {
        running = false
        gatt.stop()
        advertisers.forEach { it.stop() }
        advertisers.clear()
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: IllegalStateException) { }
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
