import CryptoKit
import Foundation

/// A crew's shared secret, exchanged in person as a 16-character code. See docs/GATT_BEARER.md section 6.
public struct CrewKey {
    public static let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
    public static let tagBytes = 16

    /// Stable crew id for wristbands, found reports and the internet relay; not sent in crew packet headers.
    public let crewId: Int32
    public let tagKey: SymmetricKey
    let packetKey: SymmetricKey
    let tunnelKey: SymmetricKey
    public var slotDurationS: UInt64 = 900

    /// Accepts any case, with spaces or dashes. Nil unless exactly 16 symbols from `alphabet`.
    public init?(code input: String) {
        let chars = input.uppercased().filter { $0.isLetter || $0.isNumber }
        let secret = chars.compactMap { Self.alphabet.firstIndex(of: $0) }.map { UInt8($0) }
        guard chars.count == 16, secret.count == 16 else { return nil }
        let ikm = SymmetricKey(data: secret)
        let salt = Array("KHOJ crew v2".utf8)
        let contentOkm = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Array("content".utf8), outputByteCount: 32)
        let tagOkm = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Array("tag".utf8), outputByteCount: 32)
        let tunnelOkm = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Array("tunnel".utf8), outputByteCount: 32)
        packetKey = contentOkm
        tagKey = tagOkm
        tunnelKey = tunnelOkm
        // Stable id printed into family wristband QR codes: derived exactly as in v1 so existing wristbands keep working.
        let v1 = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: Array("KHOJ crew v1".utf8), info: Array("crew".utf8), outputByteCount: 36)
        let v1Bytes = v1.withUnsafeBytes { [UInt8]($0) }
        crewId = Int32(bitPattern: v1Bytes[32..<36].reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
    }

    public func slotForTime(_ unixSeconds: UInt64) -> UInt64 {
        unixSeconds / slotDurationS
    }

    public func tagForSlot(_ slot: UInt64) -> UInt64 {
        var slotBytes: [UInt8] = []
        slotBytes.appendBE(slot)
        let hmac = HMAC<SHA256>.authenticationCode(for: slotBytes, using: tagKey)
        let b = Array(hmac)
        return b[0..<8].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
    }

    public func tagForTime(_ unixSeconds: UInt64) -> UInt64 {
        tagForSlot(slotForTime(unixSeconds))
    }

    public func matchesTag(_ tag: UInt64, unixSeconds: UInt64) -> Bool {
        let slot = slotForTime(unixSeconds)
        return tag == tagForSlot(slot) ||
               (slot > 0 && tag == tagForSlot(slot - 1)) ||
               tag == tagForSlot(slot + 1)
    }

    private func nonce(_ p: MeshPacket) throws -> AES.GCM.Nonce {
        var n: [UInt8] = []
        n.appendBE(p.runEpoch); n.appendBE(p.originId); n.appendBE(p.packetId)
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
