import CryptoKit
import Foundation

/// A crew's shared secret, exchanged in person as a 16-character code. See docs/GATT_BEARER.md section 6.
public struct CrewKey {
    public static let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
    public static let tagBytes = 16

    /// Public crew tag carried in every packet header.
    public let crewId: Int32
    let packetKey: SymmetricKey
    let tunnelKey: SymmetricKey

    /// Accepts any case, with spaces or dashes. Nil unless exactly 16 symbols from `alphabet`.
    public init?(code input: String) {
        let chars = input.uppercased().filter { $0.isLetter || $0.isNumber }
        let secret = chars.compactMap { Self.alphabet.firstIndex(of: $0) }.map { UInt8($0) }
        guard chars.count == 16, secret.count == 16 else { return nil }
        let okm = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: secret),
            salt: Array("KHOJ crew v1".utf8), info: Array("crew".utf8), outputByteCount: 68)
        let b = okm.withUnsafeBytes { [UInt8]($0) }
        packetKey = SymmetricKey(data: b[0..<32])
        crewId = Int32(bitPattern: b[32..<36].reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
        tunnelKey = SymmetricKey(data: b[36..<68])
    }

    private func nonce(_ p: MeshPacket) throws -> AES.GCM.Nonce {
        var n: [UInt8] = []
        n.appendBE(p.originId); n.appendBE(p.packetId); n.appendBE(p.crewId)
        return try AES.GCM.Nonce(data: n)
    }

    /// Returns ciphertext followed by the 16-byte tag. Uses only the end-to-end header fields of `p`.
    /// Never seal two different plaintexts under the same (originId, packetId).
    public func seal(_ p: MeshPacket, plaintext: [UInt8]) throws -> [UInt8] {
        let box = try AES.GCM.seal(plaintext, using: packetKey, nonce: nonce(p), authenticating: p.aad())
        return [UInt8](box.ciphertext) + [UInt8](box.tag)
    }

    /// Nil when the tag check fails (forged or corrupted): do not deliver and do not relay.
    public func open(_ p: MeshPacket) -> [UInt8]? {
        guard p.body.count >= Self.tagBytes,
              let n = try? nonce(p),
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: p.body.dropLast(Self.tagBytes), tag: p.body.suffix(Self.tagBytes)),
              let plain = try? AES.GCM.open(box, using: packetKey, authenticating: p.aad())
        else { return nil }
        return [UInt8](plain)
    }
}
