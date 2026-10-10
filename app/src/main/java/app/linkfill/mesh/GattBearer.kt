package app.linkfill.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import app.linkfill.core.mesh.GattFraming
import java.nio.ByteBuffer
import java.util.UUID

/**
 * GATT bearer, Bluetooth Mesh's "proxy" idea applied to phones: the same mesh frames, carried over BLE connections
 * instead of extended advertising. It lets phones that cannot use the advertising mesh join it:
 * older phones without Bluetooth 5 extended advertising, and iPhones (iOS apps cannot send extended or custom
 * advertisements, but can connect). See docs/GATT_BEARER.md for the wire format.
 *
 * Every phone runs a GATT server and a small legacy, connectable "door" advertisement (service UUID plus capability
 * byte and node id). A link is opened only when at least one side cannot use the advertising mesh, so two modern
 * phones never spend a connection on each other. All callbacks are moved to the main thread, like the scanner's.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // the pre-Android-13 GATT calls are needed down to minSdk 29
class GattBearer(
    private val context: Context,
    private val onFrame: (bytes: ByteArray, rssi: Int) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var nodeId = 0
    private var meshCapable = false
    private var doorOn = false
    private var frameId = 0

    private class Link(val key: String, val device: BluetoothDevice, val gatt: BluetoothGatt?, val peerId: Int?) {
        var mtu = 23
        var ready = false
        var char: BluetoothGattCharacteristic? = null
        val joiner = GattFraming.Joiner()
        val out = ArrayDeque<ByteArray>()
        var busySince = 0L
        var rssi = -70
    }

    private val links = HashMap<String, Link>()
    private val backoff = HashMap<String, Long>()
    val linkCount get() = links.values.count { it.ready }
    var framesIn = 0
        private set

    fun start(nodeId: Int, meshCapable: Boolean): String? {
        val m = manager ?: return "no Bluetooth"
        this.nodeId = nodeId; this.meshCapable = meshCapable
        val ch = BluetoothGattCharacteristic(FRAMES,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        ch.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val svc = BluetoothGattService(SERVICE.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(ch) }
        val s = try { m.openGattServer(context, serverCb) } catch (e: SecurityException) { return "GATT needs the nearby-devices permission" } ?: return "GATT server unavailable"
        s.addService(svc)
        server = s; serverChar = ch
        startDoor()
        return null
    }

    fun stop() {
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(doorCb) } catch (_: IllegalStateException) { }
        doorOn = false
        links.values.forEach { l -> l.gatt?.let { it.disconnect(); it.close() } ?: server?.cancelConnection(l.device) }
        links.clear()
        server?.close(); server = null; serverChar = null
    }

    // ---------------------------------------------------------------- door advertisement

    private val doorCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { doorOn = true }
        override fun onStartFailure(errorCode: Int) { log("GATT door advertising failed ($errorCode)") }
    }

    private fun startDoor() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return log("no advertiser for the GATT door")
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        // 3 (flags) + 18 (128-bit UUID) + 9 (manufacturer: id, capability, node id) = 30 of 31 bytes.
        val info = ByteBuffer.allocate(5).put(if (meshCapable) CAP_ADV_MESH else 0).putInt(nodeId).array()
        val data = AdvertiseData.Builder().addServiceUuid(SERVICE).addManufacturerData(MANUFACTURER, info).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        adv.startAdvertising(settings, data, doorCb)
    }

    // ---------------------------------------------------------------- client side

    /**
     * A door was heard in a scan. [info] is the manufacturer data (absent for an iPhone, which cannot add it).
     * Exactly one side connects, so a pair never holds two links: the phone that needs the bearer (no advertising
     * mesh) connects out and mesh-capable phones only accept; between two older phones the lower node id connects.
     * The one exception is a foreground iPhone (no manufacturer data): anyone with a free slot connects to it.
     */
    fun onDoorSeen(device: BluetoothDevice, rssi: Int, info: ByteArray?, connectable: Boolean) {
        val key = "c:" + device.address
        links[key]?.let { it.rssi = rssi; return }
        if (!connectable || links.size >= MAX_LINKS || SystemClock.elapsedRealtime() < (backoff[key] ?: 0L)) return
        val peerId = info?.takeIf { it.size >= 5 }?.let { ByteBuffer.wrap(it, 1, 4).int }
        val peerMesh = info != null && info.isNotEmpty() && info[0].toInt() and CAP_ADV_MESH.toInt() != 0
        if (peerId == nodeId || peerId != null && links.values.any { it.peerId == peerId }) return
        if (info != null && (meshCapable || !peerMesh && peerId != null && nodeId > peerId)) return
        val g = try { device.connectGatt(context, false, clientCb, BluetoothDevice.TRANSPORT_LE) } catch (_: SecurityException) { null } ?: return
        links[key] = Link(key, device, g, peerId).also { it.rssi = rssi }
    }

    private fun clientLink(g: BluetoothGatt) = links["c:" + g.device.address]

    private val clientCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { main.post {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) { if (!g.requestMtu(MTU)) g.discoverServices() }
            else drop("c:" + g.device.address, g, status)
        } }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { main.post {
            if (status == BluetoothGatt.GATT_SUCCESS) clientLink(g)?.mtu = mtu
            g.discoverServices()
        } }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { main.post {
            val l = clientLink(g) ?: return@post
            val ch = g.getService(SERVICE.uuid)?.getCharacteristic(FRAMES) ?: return@post drop(l.key, g, status)
            l.char = ch
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return@post drop(l.key, g, status)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        } }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { main.post {
            val l = clientLink(g) ?: return@post
            if (status != BluetoothGatt.GATT_SUCCESS) return@post drop(l.key, g, status)
            l.ready = true
            log("GATT link up (client) to ${l.peerId?.let { Integer.toHexString(it) } ?: "an iPhone or unknown"}, MTU ${l.mtu}")
            pump(l)
        } }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { main.post {
            clientLink(g)?.let { it.busySince = 0; pump(it) }
        } }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) { main.post {
            clientLink(g)?.let { receive(it, value) }
        } }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = ch.value?.copyOf() ?: return
            main.post { clientLink(g)?.let { receive(it, v) } }
        }
    }

    // ---------------------------------------------------------------- server side

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { main.post {
            val key = "s:" + device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (links.containsKey("c:" + device.address)) return@post // Android reports our own outgoing connections here too
                if (links.size >= MAX_LINKS) { server?.cancelConnection(device); return@post }
                links[key] = Link(key, device, null, null)
            } else links.remove(key)
        } }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { main.post { links["s:" + device.address]?.mtu = mtu } }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor, prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val on = value != null && value.isNotEmpty() && value[0].toInt() and 1 != 0
            main.post { links["s:" + device.address]?.let { it.ready = on; if (on) { log("GATT link up (server), MTU ${it.mtu}"); pump(it) } } }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic, prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val v = value?.copyOf() ?: return
            main.post { links["s:" + device.address]?.let { receive(it, v) } }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) { main.post {
            links["s:" + device.address]?.let { it.busySince = 0; pump(it) }
        } }
    }

    // ---------------------------------------------------------------- data

    private fun receive(l: Link, fragment: ByteArray) {
        val frame = l.joiner.add(fragment) ?: return
        framesIn++
        onFrame(frame, l.rssi)
    }

    /** Sends one mesh frame on every ready link; a slow link drops its oldest queued fragments rather than lag. */
    fun send(frame: ByteArray) {
        if (links.isEmpty()) return
        frameId = (frameId + 1) and 0xFF
        val now = SystemClock.elapsedRealtime()
        for (l in links.values) {
            if (!l.ready) continue
            if (l.busySince != 0L && now - l.busySince > 2_000) l.busySince = 0 // a lost callback must not wedge the link
            l.out.addAll(GattFraming.split(frameId, frame, l.mtu))
            while (l.out.size > MAX_QUEUED) l.out.removeFirst()
            pump(l)
        }
    }

    /** Android allows one outstanding GATT operation per link; the next fragment goes when the previous one is done. */
    private fun pump(l: Link) {
        if (!l.ready || l.busySince != 0L) return
        val frag = l.out.removeFirstOrNull() ?: return
        l.busySince = SystemClock.elapsedRealtime()
        val ok = try {
            val g = l.gatt
            val ch = l.char
            if (g != null && ch != null) {
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, frag, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                else { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = frag; g.writeCharacteristic(ch) }
            } else {
                val sc = serverChar ?: return
                val s = server ?: return
                if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(l.device, sc, false, frag) == BluetoothGatt.GATT_SUCCESS
                else { sc.value = frag; s.notifyCharacteristicChanged(l.device, sc, false) }
            }
        } catch (_: SecurityException) { false }
        if (!ok) l.busySince = 0
    }

    private fun drop(key: String, g: BluetoothGatt?, status: Int) {
        links.remove(key)
        g?.close()
        // Status 133 and friends are common on budget phones; try that phone again later, not in a tight loop.
        backoff[key] = SystemClock.elapsedRealtime() + 15_000
        if (status != BluetoothGatt.GATT_SUCCESS) log("GATT link dropped (status $status)")
    }

    companion object {
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("6b686f6a-0002-4b48-8f4a-6c696e6b6669"))
        val FRAMES: UUID = UUID.fromString("6b686f6a-0003-4b48-8f4a-6c696e6b6669")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** 0xFFFF is the Bluetooth SIG id reserved for testing; shortcut: needs a registered company id for a public release. */
        const val MANUFACTURER = 0xFFFF
        const val CAP_ADV_MESH: Byte = 0x01
        const val MTU = 247
        /** Budget phones handle about 4 links reliably (docs/CROSS_PLATFORM_RESEARCH.md). */
        const val MAX_LINKS = 4
        const val MAX_QUEUED = 60
    }
}
