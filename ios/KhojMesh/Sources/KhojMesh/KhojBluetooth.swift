import CoreBluetooth
import Foundation

/// UUIDs and limits of the KHOJ GATT bearer. See docs/GATT_BEARER.md sections 2 and 3.
public enum KhojGatt {
    public static let service = CBUUID(string: "6b686f6a-0002-4b48-8f4a-6c696e6b6669")
    public static let characteristic = CBUUID(string: "6b686f6a-0003-4b48-8f4a-6c696e6b6669")
    /// Outbound links from an iPhone (Android allows 4 in total, both roles together).
    public static let maxCentralLinks = 3
    static let maxQueued = 60
    static let setupTimeout: TimeInterval = 20
}

/// The iPhone as BLE central: scans for Android "doors", connects out, subscribes, and carries frames.
///
/// An iPhone has no extended-advertising mesh, so it always needs the bearer and always connects out to every
/// Android door it sees (Android's mesh-capable phones only accept; see section 2.2). This is also the only role that
/// works with the app in the background: needs UIBackgroundModes `bluetooth-central`. The restore identifier lets iOS
/// relaunch the app for the scan and the connections (not after a force quit).
///
/// All callbacks, including `onFrame`, run on `queue`. A frame is a whole 29 to 200 byte mesh frame; decoding,
/// duplicate checks and relaying are the caller's job (docs/GATT_BEARER.md section 8).
public final class KhojCentral: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    public var onFrame: (([UInt8]) -> Void)?
    public var onLog: ((String) -> Void)?
    public let queue = DispatchQueue(label: "app.khoj.central")

    private final class Link {
        let peripheral: CBPeripheral
        let peerId: Int32?
        var characteristic: CBCharacteristic?
        var ready = false
        var joiner = GattFraming.Joiner()
        var out: [[UInt8]] = []
        init(_ p: CBPeripheral, peerId: Int32?) { peripheral = p; self.peerId = peerId }
    }

    private var central: CBCentralManager!
    private var links: [UUID: Link] = [:]
    private var restored: [CBPeripheral] = []
    private var frameId: UInt8 = 0
    private var rescanPending = false

    public init(restoreIdentifier: String = "app.khoj.central") {
        super.init()
        central = CBCentralManager(delegate: self, queue: queue,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: restoreIdentifier])
    }

    // MARK: scanning and connecting

    public func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {
        restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
    }

    public func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard c.state == .poweredOn else { return }
        for p in restored.prefix(KhojGatt.maxCentralLinks) { adopt(p, peerId: nil) }
        restored = []
        startScan()
    }

    private func startScan() {
        // The explicit service filter is what makes scanning work in the background. Duplicates are coalesced there anyway.
        central.scanForPeripherals(withServices: [KhojGatt.service],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    /// A rescan lets a door that dropped (or was never connected) be reported again.
    private func scheduleRescan() {
        guard !rescanPending else { return }
        rescanPending = true
        queue.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self else { return }
            rescanPending = false
            if central.state == .poweredOn { central.stopScan(); startScan() }
        }
    }

    public func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                               advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard links[p.identifier] == nil, links.count < KhojGatt.maxCentralLinks else { return }
        let peer = Self.nodeId(advertisementData)
        if let peer, links.values.contains(where: { $0.peerId == peer }) { return } // same phone, rotated address
        adopt(p, peerId: peer)
    }

    /// Manufacturer data is `FF FF`, a capability byte, then the node id (Int32 big-endian). Absent on an iPhone door.
    static func nodeId(_ ad: [String: Any]) -> Int32? {
        guard let data = ad[CBAdvertisementDataManufacturerDataKey] as? Data else { return nil }
        let m = [UInt8](data)
        guard m.count >= 7, m[0] == 0xFF, m[1] == 0xFF else { return nil }
        return Int32(bitPattern: m[3..<7].reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
    }

    private func adopt(_ p: CBPeripheral, peerId: Int32?) {
        p.delegate = self
        links[p.identifier] = Link(p, peerId: peerId)
        if p.state == .connected { p.discoverServices([KhojGatt.service]) } else { central.connect(p) }
        let id = p.identifier
        // A pending connect never times out by itself; do not let a vanished phone hold a slot.
        queue.asyncAfter(deadline: .now() + KhojGatt.setupTimeout) { [weak self] in
            if let l = self?.links[id], !l.ready { self?.drop(l.peripheral) }
        }
    }

    private func drop(_ p: CBPeripheral) {
        links[p.identifier] = nil
        central.cancelPeripheralConnection(p)
        scheduleRescan()
    }

    public func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.delegate = self
        p.discoverServices([KhojGatt.service])
    }

    public func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        onLog?("connect failed: \(error?.localizedDescription ?? "unknown")")
        drop(p)
    }

    public func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        onLog?("link down")
        links[p.identifier] = nil
        scheduleRescan()
    }

    // MARK: service, characteristic, notifications

    public func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let s = p.services?.first(where: { $0.uuid == KhojGatt.service }) else { return drop(p) }
        p.discoverCharacteristics([KhojGatt.characteristic], for: s)
    }

    public func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard error == nil, let ch = s.characteristics?.first(where: { $0.uuid == KhojGatt.characteristic }),
              let l = links[p.identifier] else { return drop(p) }
        l.characteristic = ch
        p.setNotifyValue(true, for: ch) // Android sends nothing until this CCCD write; also send nothing before it
    }

    public func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        guard error == nil, ch.isNotifying, let l = links[p.identifier] else { return drop(p) }
        l.ready = true
        onLog?("link up, MTU \(p.maximumWriteValueLength(for: .withoutResponse) + 3)")
        pump(l)
    }

    public func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard error == nil, let v = ch.value, let l = links[p.identifier] else { return }
        if let frame = l.joiner.add([UInt8](v)) { onFrame?(frame) }
    }

    // MARK: sending

    /// Sends one mesh frame on every ready link. A slow link drops its oldest queued fragments rather than lag.
    public func send(_ frame: [UInt8]) {
        queue.async { [self] in
            frameId &+= 1
            for l in links.values where l.ready {
                // maximumWriteValueLength(.withoutResponse) is the ATT MTU minus 3.
                let mtu = l.peripheral.maximumWriteValueLength(for: .withoutResponse) + 3
                guard let fragments = GattFraming.split(frameId: frameId, frame: frame, mtu: mtu) else { continue }
                l.out += fragments
                if l.out.count > KhojGatt.maxQueued { l.out.removeFirst(l.out.count - KhojGatt.maxQueued) }
                pump(l)
            }
        }
    }

    /// Write without response has no completion callback: respect canSendWriteWithoutResponse and resume in peripheralIsReady.
    private func pump(_ l: Link) {
        guard let ch = l.characteristic else { return }
        while l.peripheral.canSendWriteWithoutResponse, !l.out.isEmpty {
            l.peripheral.writeValue(Data(l.out.removeFirst()), for: ch, type: .withoutResponse)
        }
    }

    public func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) {
        if let l = links[p.identifier] { pump(l) }
    }
}

/// Optional GATT server for the foreground: advertises the service so an Android phone (or another iPhone) can connect in.
///
/// Limits: an iOS app cannot add manufacturer data, so Android sees a door with no capability byte and no node id, and
/// any Android phone with a free slot may connect to it. In the background iOS moves the UUID to an overflow area that
/// Android cannot read, so this role is only useful while the app is on screen. Do not rely on it; `KhojCentral` is the
/// link that keeps working. If both roles run, a pair of phones can end up with two links, which is harmless.
public final class KhojPeripheral: NSObject, CBPeripheralManagerDelegate {
    public var onFrame: (([UInt8]) -> Void)?
    public var onLog: ((String) -> Void)?
    public let queue = DispatchQueue(label: "app.khoj.peripheral")

    private final class Sub {
        let central: CBCentral
        var joiner = GattFraming.Joiner()
        var out: [[UInt8]] = []
        var subscribed = false
        init(_ c: CBCentral) { central = c }
    }

    private var manager: CBPeripheralManager!
    private let characteristic = CBMutableCharacteristic(
        type: KhojGatt.characteristic, properties: [.writeWithoutResponse, .write, .notify], value: nil, permissions: [.writeable])
    private var subs: [UUID: Sub] = [:]
    private var frameId: UInt8 = 0

    public override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: queue)
    }

    public func stop() {
        queue.async { [self] in manager.stopAdvertising(); manager.removeAllServices(); subs = [:] }
    }

    public func peripheralManagerDidUpdateState(_ m: CBPeripheralManager) {
        guard m.state == .poweredOn else { return }
        let service = CBMutableService(type: KhojGatt.service, primary: true)
        service.characteristics = [characteristic]
        m.removeAllServices()
        m.add(service)
    }

    public func peripheralManager(_ m: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { onLog?("add service failed: \(error.localizedDescription)"); return }
        // Only the service UUID: the 31-byte advert has no room for more and iOS accepts no manufacturer data.
        m.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [KhojGatt.service]])
    }

    private func sub(_ c: CBCentral) -> Sub {
        if let s = subs[c.identifier] { return s }
        let s = Sub(c)
        subs[c.identifier] = s
        return s
    }

    public func peripheralManager(_ m: CBPeripheralManager, central: CBCentral, didSubscribeTo ch: CBCharacteristic) {
        sub(central).subscribed = true
        onLog?("link up (server), MTU \(central.maximumUpdateValueLength + 3)")
    }

    public func peripheralManager(_ m: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom ch: CBCharacteristic) {
        subs[central.identifier] = nil
    }

    public func peripheralManager(_ m: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests {
            guard let v = r.value else { continue }
            if let frame = sub(r.central).joiner.add([UInt8](v)) { onFrame?(frame) }
        }
        if let first = requests.first { m.respond(to: first, withResult: .success) }
    }

    /// Sends one mesh frame as notifications to every subscribed central.
    public func send(_ frame: [UInt8]) {
        queue.async { [self] in
            frameId &+= 1
            for s in subs.values where s.subscribed {
                guard let fragments = GattFraming.split(frameId: frameId, frame: frame, mtu: s.central.maximumUpdateValueLength + 3)
                else { continue }
                s.out += fragments
                if s.out.count > KhojGatt.maxQueued { s.out.removeFirst(s.out.count - KhojGatt.maxQueued) }
                pump(s)
            }
        }
    }

    /// updateValue returns false when the transmit queue is full; resume in peripheralManagerIsReady.
    private func pump(_ s: Sub) {
        while let f = s.out.first, manager.updateValue(Data(f), for: characteristic, onSubscribedCentrals: [s.central]) {
            s.out.removeFirst()
        }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers m: CBPeripheralManager) {
        subs.values.forEach { pump($0) }
    }
}
