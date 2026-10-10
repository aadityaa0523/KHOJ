import Foundation

/// Packet types. Codes 7 to 12 are public (not sealed with a crew key). See docs/GATT_BEARER.md section 5.
public enum MeshType: UInt8 {
    case beacon = 1, sos, rally, text, ack, neighbor
    case found, foundAck, publicSos, respond, official, foundNotice

    public var isPublic: Bool { rawValue >= MeshType.found.rawValue }
}

/// One frame on the air or on a GATT link: 29-byte header plus body (at most 200 bytes in all).
public struct MeshPacket: Equatable {
    public static let magic: UInt8 = 0x4B
    public static let version: UInt8 = 1
    public static let headerSize = 29
    public static let maxSize = 200
    public static let maxTTL: UInt8 = 8
    /// Link-level flag, not authenticated: the transmitter (not the originator) is a low-power member.
    public static let flagLowPower: UInt8 = 0x01

    public var type: MeshType
    public var flags: UInt8
    public var ttl: UInt8
    public var hops: UInt8
    public var linkSeq: UInt16
    public var relayId: Int32
    public var crewId: Int32
    public var originId: Int32
    public var packetId: Int32
    public var originTimeS: UInt32
    public var body: [UInt8]

    public init(type: MeshType, flags: UInt8 = 0, ttl: UInt8 = 6, hops: UInt8 = 0, linkSeq: UInt16 = 0,
                relayId: Int32, crewId: Int32, originId: Int32, packetId: Int32, originTimeS: UInt32, body: [UInt8]) {
        self.type = type; self.flags = flags; self.ttl = ttl; self.hops = hops; self.linkSeq = linkSeq
        self.relayId = relayId; self.crewId = crewId; self.originId = originId; self.packetId = packetId
        self.originTimeS = originTimeS; self.body = body
    }

    /// Duplicate-cache key: (originId, packetId) as one 64-bit value.
    public var key: UInt64 { UInt64(UInt32(bitPattern: originId)) << 32 | UInt64(UInt32(bitPattern: packetId)) }

    public var fromLowPower: Bool { flags & Self.flagLowPower != 0 }

    /// The copy to forward: ttl - 1, hops + 1, our id and link counter. The body is never touched.
    public func relayed(by id: Int32, linkSeq seq: UInt16, lowPower: Bool = false) -> MeshPacket {
        var p = self
        p.ttl = ttl > 0 ? ttl - 1 : 0; p.hops = hops &+ 1; p.relayId = id; p.linkSeq = seq
        p.flags = lowPower ? flags | Self.flagLowPower : flags & ~Self.flagLowPower
        return p
    }

    /// The bytes the crew key authenticates: end-to-end header fields only.
    public func aad() -> [UInt8] {
        var a: [UInt8] = [type.rawValue]
        a.appendBE(crewId); a.appendBE(originId); a.appendBE(packetId); a.appendBE(originTimeS)
        return a
    }

    /// Nil if the body does not fit (171 bytes at most).
    public func encoded() -> [UInt8]? {
        guard body.count <= Self.maxSize - Self.headerSize else { return nil }
        var o: [UInt8] = [Self.magic, Self.version, type.rawValue, flags, ttl, hops]
        o.appendBE(linkSeq); o.appendBE(relayId); o.appendBE(crewId); o.appendBE(originId)
        o.appendBE(packetId); o.appendBE(originTimeS)
        o.append(UInt8(body.count))
        return o + body
    }

    /// Nil for anything malformed; mesh input comes from anyone in radio range.
    public static func decode(_ bytes: [UInt8]) -> MeshPacket? {
        guard (headerSize...maxSize).contains(bytes.count) else { return nil }
        var r = ByteReader(bytes: bytes)
        do {
            let m = try r.u8(), v = try r.u8(), t = try r.u8()
            guard m == magic, v == version, let type = MeshType(rawValue: t) else { return nil }
            let flags = try r.u8(), ttl = try r.u8(), hops = try r.u8(), seq = try r.u16()
            let relay = try r.i32(), crew = try r.i32(), origin = try r.i32(), pid = try r.i32(), time = try r.u32()
            let len = Int(try r.u8())
            guard ttl <= maxTTL, hops <= maxTTL, len == r.remaining else { return nil }
            return MeshPacket(type: type, flags: flags, ttl: ttl, hops: hops, linkSeq: seq, relayId: relay,
                              crewId: crew, originId: origin, packetId: pid, originTimeS: time, body: r.rest())
        } catch { return nil }
    }
}

extension Array where Element == UInt8 {
    mutating func appendBE<T: FixedWidthInteger>(_ v: T) {
        withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) }
    }
}

/// Bounds-checked big-endian reader.
struct ByteReader {
    struct Underflow: Error {}
    let bytes: [UInt8]
    var pos = 0
    init(bytes: [UInt8]) { self.bytes = bytes }

    var remaining: Int { bytes.count - pos }

    mutating func uint(_ n: Int) throws -> UInt64 {
        guard n <= remaining else { throw Underflow() }
        defer { pos += n }
        return bytes[pos..<pos + n].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
    }
    mutating func u8() throws -> UInt8 { UInt8(try uint(1)) }
    mutating func u16() throws -> UInt16 { UInt16(try uint(2)) }
    mutating func u32() throws -> UInt32 { UInt32(try uint(4)) }
    mutating func i32() throws -> Int32 { Int32(bitPattern: try u32()) }
    mutating func rest() -> [UInt8] { defer { pos = bytes.count }; return Array(bytes[pos...]) }
}
