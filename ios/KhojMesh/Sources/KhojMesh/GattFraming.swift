import Foundation

/// Splits frames into GATT writes or notifications that fit the link's ATT MTU, and joins them again.
/// Fragment = frame id (1 byte), (index << 4 | count) (1 byte), then a slice of the frame.
/// See docs/GATT_BEARER.md section 4.
public enum GattFraming {
    public static let headerSize = 2
    public static let maxFragments = 15

    /// Frame bytes per fragment at this ATT MTU (the write or notification carries MTU - 3 bytes).
    public static func chunk(forMTU mtu: Int) -> Int { max(mtu - 3 - headerSize, 1) }

    /// Nil if the frame is empty or would need more than 15 fragments.
    public static func split(frameId: UInt8, frame: [UInt8], mtu: Int) -> [[UInt8]]? {
        let size = chunk(forMTU: mtu)
        let count = (frame.count + size - 1) / size
        guard (1...maxFragments).contains(count) else { return nil }
        return (0..<count).map { i -> [UInt8] in
            [frameId, UInt8(i << 4 | count)] + frame[i * size ..< min(frame.count, (i + 1) * size)]
        }
    }

    /// One per link. Fragments of a frame must arrive in order (true for one GATT link); a broken frame is dropped.
    public struct Joiner {
        private var id = -1
        private var next = 0
        private var parts: [UInt8] = []

        public init() {}

        /// Returns a whole frame when its last fragment arrives, else nil. Never traps on hostile input.
        public mutating func add(_ fragment: [UInt8]) -> [UInt8]? {
            guard fragment.count > GattFraming.headerSize else { return nil }
            let fid = Int(fragment[0]), idx = Int(fragment[1] >> 4), count = Int(fragment[1] & 0x0F)
            guard count > 0, idx < count else { return nil }
            if idx == 0 { id = fid; next = 0; parts = [] }
            guard fid == id, idx == next else { id = -1; return nil }
            parts += fragment[GattFraming.headerSize...]
            next += 1
            if next < count { return nil }
            id = -1
            defer { parts = [] }
            return parts.count <= MeshPacket.maxSize ? parts : nil
        }
    }
}
