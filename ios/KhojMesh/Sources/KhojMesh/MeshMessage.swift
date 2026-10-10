import Foundation

/// Decoded message bodies. Coordinates are Int32 in 1e-7 degrees on the wire. See docs/GATT_BEARER.md section 7.
/// Crew cases are sealed by `CrewKey` before sending; `.publicSos` is sent as is (crewId 0).
/// Other public types (found, respond, official...) are relayed without decoding.
public enum MeshMessage: Equatable {
    case beacon(lat: Double, lon: Double, accuracyM: Int, battery: Int, name: String, findable: Bool)
    case sos(lat: Double, lon: Double, note: String)
    case rally(lat: Double, lon: Double, meetAtS: UInt32, label: String)
    case text(String)
    case ack(originId: Int32, packetId: Int32)
    /// kind: 0 SOS, 1 fall, 2 panic, 3 separated from family.
    case publicSos(kind: UInt8, lat: Double, lon: Double, label: String)

    public var type: MeshType {
        switch self {
        case .beacon: return .beacon
        case .sos: return .sos
        case .rally: return .rally
        case .text: return .text
        case .ack: return .ack
        case .publicSos: return .publicSos
        }
    }

    static let maxText = 100, maxPublicLabel = 30

    /// The plaintext body (for crew types, what you pass to `CrewKey.seal`).
    public func encode() -> [UInt8] {
        var o: [UInt8] = []
        switch self {
        case let .beacon(lat, lon, acc, bat, name, findable):
            o.putLatLon(lat, lon); o.appendBE(UInt16(min(max(acc, 0), 65_535)))
            o.append(UInt8(min(max(bat, 0), 100))); o.append(findable ? 1 : 0)
            o += Self.utf8(name, Self.maxText)
        case let .sos(lat, lon, note):
            o.putLatLon(lat, lon); o += Self.utf8(note, Self.maxText)
        case let .rally(lat, lon, meetAtS, label):
            o.putLatLon(lat, lon); o.appendBE(meetAtS); o += Self.utf8(label, Self.maxText)
        case let .text(t):
            o = Self.utf8(t, Self.maxText)
        case let .ack(origin, pid):
            o.appendBE(origin); o.appendBE(pid)
        case let .publicSos(kind, lat, lon, label):
            o.append(kind); o.putLatLon(lat, lon); o += Self.utf8(label, Self.maxPublicLabel)
        }
        return o
    }

    /// Nil when the body is too short or a coordinate or battery value is out of range.
    public static func decode(type: MeshType, body: [UInt8]) -> MeshMessage? {
        var r = ByteReader(bytes: body)
        do {
            switch type {
            case .beacon:
                let (la, lo) = try r.latLon(), acc = Int(try r.u16()), bat = Int(try r.u8()), f = try r.u8()
                guard bat <= 100 else { return nil }
                return ok(la, lo, .beacon(lat: la, lon: lo, accuracyM: acc, battery: bat, name: r.string(), findable: f & 1 == 1))
            case .sos:
                let (la, lo) = try r.latLon()
                return ok(la, lo, .sos(lat: la, lon: lo, note: r.string()))
            case .rally:
                let (la, lo) = try r.latLon(), t = try r.u32()
                return ok(la, lo, .rally(lat: la, lon: lo, meetAtS: t, label: r.string()))
            case .text:
                return .text(r.string())
            case .ack:
                return .ack(originId: try r.i32(), packetId: try r.i32())
            case .publicSos:
                let k = try r.u8(), (la, lo) = try r.latLon()
                return ok(la, lo, .publicSos(kind: k, lat: la, lon: lo, label: r.string()))
            default:
                return nil
            }
        } catch { return nil }
    }

    private static func ok(_ lat: Double, _ lon: Double, _ m: MeshMessage) -> MeshMessage? {
        (-90.0...90.0).contains(lat) && (-180.0...180.0).contains(lon) ? m : nil
    }

    /// UTF-8, cut at a character boundary so the receiver never sees a broken sequence.
    private static func utf8(_ s: String, _ max: Int) -> [UInt8] {
        var out: [UInt8] = []
        for scalar in s.unicodeScalars {
            let u = Array(String(scalar).utf8)
            if out.count + u.count > max { break }
            out += u
        }
        return out
    }
}

private extension Array where Element == UInt8 {
    mutating func putLatLon(_ lat: Double, _ lon: Double) {
        func fixed(_ d: Double, _ limit: Double) -> Int32 { d.isFinite ? Int32((min(max(d, -limit), limit) * 1e7).rounded()) : 0 }
        appendBE(fixed(lat, 90)); appendBE(fixed(lon, 180))
    }
}

extension ByteReader {
    mutating func latLon() throws -> (Double, Double) { (Double(try i32()) / 1e7, Double(try i32()) / 1e7) }
    mutating func string() -> String { String(decoding: rest(), as: UTF8.self) }
}
