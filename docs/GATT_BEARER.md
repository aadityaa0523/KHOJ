# KHOJ GATT bearer: wire protocol

This document is the byte-level contract for joining the KHOJ phone mesh over Bluetooth LE GATT connections. It is written so that an iOS app (or any other platform) can interoperate with the Android app without reading the Kotlin code. The Kotlin code stays the source of truth; where this document and the code disagree, the code wins and this document has a bug.

Source files this document was written from:

| Topic | File |
|---|---|
| Frame header, crew message bodies | `core/src/main/kotlin/app/linkfill/core/mesh/MeshPacket.kt` |
| Key derivation, AES-GCM | `core/src/main/kotlin/app/linkfill/core/mesh/CrewKey.kt` |
| Public messages (public SOS and others) | `core/src/main/kotlin/app/linkfill/core/mesh/PublicMessages.kt` |
| GATT fragment header | `core/src/main/kotlin/app/linkfill/core/mesh/GattFraming.kt` |
| Door advert, connection rules, link handling | `app/src/main/java/app/linkfill/mesh/GattBearer.kt` |
| Flooding, duplicates, carry-forward, low-power flag | `core/src/main/kotlin/app/linkfill/core/mesh/MeshRouter.kt` |
| Exact bytes | `docs/interop/khoj_test_vectors.json` |
| iOS limits that shaped this design | `docs/CROSS_PLATFORM_RESEARCH.md` |

All multi-byte integers are big-endian unless stated. "Int32" means signed 32-bit, "u8/u16/u32" means unsigned.

## 1. Purpose

The normal KHOJ mesh sends 200-byte frames as Bluetooth 5 extended advertisements. Two kinds of phone cannot use it:

- older Android phones whose chip has no extended advertising;
- iPhones, because iOS apps cannot send extended or custom advertisements and cannot reliably receive them.

The GATT bearer carries the same frames over ordinary BLE connections, like the "proxy" idea in Bluetooth Mesh. Nothing about the frame, the crew key or the routing changes. A phone that uses the bearer is a normal mesh member as far as the crew is concerned, with the limits in section 8.

## 2. Discovery and who connects to whom

### 2.1 The door advertisement

Every Android phone that runs the bearer advertises a "door": a legacy, connectable advertisement (ADV_IND). It is 30 of the 31 available bytes.

| AD structure | Bytes on air | Content |
|---|---|---|
| Flags | `02 01 06` | added by Android |
| Complete list of 128-bit service UUIDs | `11 07` + 16 bytes | `6b686f6a-0002-4b48-8f4a-6c696e6b6669` (UUID bytes little-endian on air, as for any BLE UUID) |
| Manufacturer specific data | `08 FF` + 8 bytes | company id `0xFFFF` (little-endian on air: `FF FF`), then the 5-byte payload below |

Manufacturer payload (5 bytes):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | capability byte. Bit 0 (`0x01`) = this phone has the extended-advertising mesh. Other bits are zero and must be ignored |
| 1 | 4 | node id, Int32 big-endian (the same id the phone uses as `originId` / `relayId`) |

`0xFFFF` is the Bluetooth SIG test company id. It is a placeholder until a company id is registered.

The service UUID is in the primary advertisement packet, not the scan response. That matters on iOS: a backgrounded iOS scan only reports advertisers that have the UUID in the primary packet.

CoreBluetooth hands the manufacturer data to an iOS app as `Data` that starts with the two company-id bytes `FF FF`, then the 5 payload bytes.

An iPhone cannot add manufacturer data. A foreground iPhone acting as a peripheral advertises the service UUID only (no capability byte, no node id).

### 2.2 Connection rule

Exactly one side opens a link, so a pair of phones never holds two links. A link is opened only when at least one side needs the bearer, so two modern phones never spend a connection on each other.

An Android phone that sees a connectable door applies these rules in order. "Needs the bearer" means the phone has no extended-advertising mesh (capability bit 0 is 0).

| Step | Condition | Result |
|---|---|---|
| 1 | Advert not connectable, all 4 link slots used, or this peer dropped in the last 15 s | do not connect |
| 2 | Door's node id equals own id, or a link to that node id already exists | do not connect |
| 3 | Door has **no** manufacturer data (a foreground iPhone) | **connect** (any Android phone with a free slot) |
| 4 | Door has manufacturer data and this phone **has** the extended-advertising mesh | do not connect. Mesh-capable phones never connect to a door that has manufacturer data; they only accept connections |
| 5 | Door has manufacturer data, this phone needs the bearer, and the door's capability bit 0 is **1** | **connect** (the other phone does not need the bearer, so it will not connect to us) |
| 6 | Door has manufacturer data, both phones need the bearer, both node ids known | the phone with the **lower** node id connects. Node ids are compared as **signed** Int32. The higher one only accepts |
| 7 | Door has manufacturer data with fewer than 5 payload bytes (no node id) and the door's bit 0 is 0 | **connect** (no id to compare) |

Consequences for an iOS app:

- An iPhone needs the bearer, so it **connects out** to every Android door it sees (central role). It does not wait to be found.
- A backgrounded iPhone is invisible to Android: its UUID moves to an Apple-only overflow area that Android scanners cannot read. So a backgrounded iPhone must act as central with a scan filtered on the service UUID (`scanForPeripherals(withServices:)`), with `bluetooth-central` background mode and state restoration. Android phones advertise the door continuously, which is what makes this work.
- A foreground iPhone may also run a GATT server and advertise the UUID (optional, step 3). Any Android phone with a free slot may then connect to it. If the same iPhone also connected out to that Android phone, the pair would hold two links. This is harmless (the duplicate cache removes repeats) but wastes a slot. An iOS app that runs both roles should accept this, or skip the peripheral role while it already has outbound links.
- iPhone outbound links: plan for 3 at most. Android allows 4 links in total (client plus server roles together) and cancels an extra incoming connection.
- The same Android phone can show up under different addresses (random address rotation). Use the node id in the manufacturer data, when present, to avoid linking to the same phone twice.

## 3. GATT service and characteristic

| Item | Value |
|---|---|
| Service (primary) | `6b686f6a-0002-4b48-8f4a-6c696e6b6669` |
| Frames characteristic | `6b686f6a-0003-4b48-8f4a-6c696e6b6669` |
| Characteristic properties | write without response, write (with response), notify |
| Characteristic permission | write |
| Client Characteristic Configuration Descriptor | standard `0x2902`, readable and writable |

Both directions use the one characteristic:

| Direction | Mechanism |
|---|---|
| Central to peripheral (inbound to the GATT server) | write **without response** to the characteristic. Each write is one fragment |
| Peripheral to central (outbound from the GATT server) | notification on the characteristic. Each notification is one fragment |

Link set-up, as the central:

1. Connect.
2. Android only: request MTU 247 (an iPhone cannot request an MTU; see section 4).
3. Discover the service and the characteristic.
4. Write `0x0001` to the CCCD (call `setNotifyValue(true)` on iOS). The Android server treats the link as ready for sending only after this write. Do not expect frames before it, and send only after it.
5. Both sides now send fragments freely.

Either side may send at any time once the link is ready. Both ends run the same relay logic.

## 4. MTU and fragmentation

The ATT MTU is negotiated once per link. Android requests 247. An iPhone typically ends at 185. An unnegotiated link is 23. Android assumes 23 until it is told otherwise.

Payload bytes in one write or notification: `MTU - 3`. Fragment body bytes: `MTU - 3 - 2`.

| MTU | Bytes per write or notification | Frame bytes per fragment | Fragments for a 200-byte frame |
|---|---|---|---|
| 23 | 20 | 18 | 12 |
| 185 (typical iPhone) | 182 | 180 | 2 |
| 247 (Android request) | 244 | 242 | 1 |

On iOS, `peripheral.maximumWriteValueLength(for: .withoutResponse)` already returns `MTU - 3`; for notifications from a `CBPeripheralManager`, `central.maximumUpdateValueLength` does the same.

Fragment layout (2-byte header, then a slice of the frame):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | frame id, 0 to 255. The sender increments it for every frame and lets it wrap |
| 1 | 1 | high 4 bits: fragment index (starting at 0). Low 4 bits: fragment count (1 to 15) |
| 2 | 1 or more | the next slice of the frame |

Rules:

- A frame must split into 1 to 15 fragments. An empty frame is not sent.
- Fragments of one frame are sent in order and arrive in order (one GATT link preserves order).
- Receiver, one state per link: a fragment with index 0 starts a new frame and discards any partial one. A fragment whose frame id differs from the current one, or whose index is not the expected next index, discards the partial frame. A fragment of 2 bytes or fewer, a count of 0, or an index not below the count is ignored. When the last fragment arrives, the joined frame is passed on if it is at most 200 bytes, otherwise dropped.
- A broken frame is simply lost. The AES-GCM tag rejects any frame that was joined wrongly.
- Android keeps at most 60 queued fragments per link and drops the oldest when a slow link falls behind. An iOS sender should do the same, and must honour `canSendWriteWithoutResponse` and `peripheralIsReady(toSendWriteWithoutResponse:)`.

## 5. Frame format

A frame is what the reassembler outputs. It is the same byte string the extended-advertising mesh sends. Size: 37-byte header plus 0 to 163 body bytes, so 37 to 200 bytes.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | magic | `0x4B` (`K`) |
| 1 | 1 | version | `2` |
| 2 | 1 | type | see type table |
| 3 | 1 | flags | bit 0 (`0x01`) = low-power sender. Other bits must be sent as 0 and ignored |
| 4 | 1 | ttl | remaining hops, 0 to 8. Default for new packets is 6 |
| 5 | 1 | hops | hops travelled so far, 0 to 8. 0 at the origin |
| 6 | 2 | linkSeq | u16, per-transmitter counter, wraps at 65536 |
| 8 | 4 | relayId | Int32, node id of the phone that put this frame on this link |
| 12 | 8 | crewTag | u64 big-endian, rotating ephemeral crew tag (derived, section 6). 0 for public messages |
| 20 | 4 | runEpoch | Int32 big-endian, random epoch generated at process boot |
| 24 | 4 | originId | Int32, node id of the phone that created the packet |
| 28 | 4 | packetId | Int32, per-origin packet counter |
| 32 | 4 | originTimeS | u32, Unix time in seconds when created |
| 36 | 1 | bodyLen | u8, must equal the number of bytes that follow |
| 37 | bodyLen | body | sealed message (crew types) or plain message (public types) |

A receiver drops a frame when: the length is below 37 or above 200; magic or version differ; the type is unknown; ttl or hops is above 8; or bodyLen does not equal the remaining bytes. Mesh input comes from anyone in range, so decoding never trusts it.

Link fields versus end-to-end fields:

| Group | Fields | Changes at each hop? | Covered by the crew key? |
|---|---|---|---|
| Link | flags, ttl, hops, linkSeq, relayId | yes | no |
| End-to-end | type, crewTag, runEpoch, originId, packetId, originTimeS, body | never | yes (version, type, crewTag, runEpoch, originId, packetId, originTimeS are the 26-byte AAD; the body is the ciphertext) |

Type codes:

| Code | Name | Sealed with crew key? | Carried for late arrivals? |
|---|---|---|---|
| 1 | BEACON | yes | no |
| 2 | SOS | yes | yes |
| 3 | RALLY | yes | yes |
| 4 | TEXT | yes | no |
| 5 | ACK | yes | yes |
| 6 | NEIGHBOR | no, empty filler | no |
| 7 | FOUND | no (sealed to a wristband key) | yes |
| 8 | FOUND_ACK | no | yes |
| 9 | PUBLIC_SOS | no | yes |
| 10 | RESPOND | no | yes |
| 11 | OFFICIAL | no (signed by the authority key) | yes |
| 12 | FOUND_NOTICE | no | no |

Types 7 to 12 are the "public" types. NEIGHBOR frames carry nothing and Android does not send them over GATT; ignore them if received.

## 6. Crypto

The crew shares one 16-character code, exchanged in person. It uses the alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 symbols, no `0 O 1 I`). Input is accepted in any case, with spaces or dashes removed, and must be exactly 16 symbols from that alphabet.

### 6.1 Key derivation (v2)

1. Crew secret: 16 bytes, byte *i* is the index (0 to 31) of code character *i* in the alphabet.
2. HKDF with SHA-256 (RFC 5869): salt = the UTF-8 bytes of `KHOJ crew v2`, input key material = the crew secret.
3. Three separate subkeys derived via HKDF-Expand with distinct context labels:
   - `content` (32 bytes): packet encryption key for AES-256-GCM.
   - `tag` (32 bytes): tag key for HMAC-SHA256 ephemeral crew tag generation.
   - `tunnel` (32 bytes): tunnel key for point-to-point payloads.
4. Internal `crewId`: first 4 bytes of `tag` key read as Int32 big-endian (internal roster use only, never transmitted over the air).

### 6.2 Ephemeral Rotating Crew Tag

To prevent cross-site physical tracking via static broadcast tags, `crewTag` rotates every 15 minutes (900 seconds):
- Slot: `slot = floor(unixSeconds / 900)`.
- Ephemeral tag: first 8 bytes of `HMAC-SHA256(tagKey, slot.toBigEndianBytes())` as UInt64 big-endian.
- Verification: A node accepts frames if `crewTag` matches slot $t$, $t-1$, or $t+1$ to tolerate clock skew up to 15 minutes.

### 6.3 Sealing a packet

- Algorithm: AES-256-GCM, 16-byte tag.
- Nonce (12 bytes / 96 bits): `runEpoch (4) | originId (4) | packetId (4)`, big-endian. Guarantees nonce uniqueness across app restarts and counter resets.
- AAD (26 bytes): `version (1) | type (1) | crewTag (8) | runEpoch (4) | originId (4) | packetId (4) | originTimeS (4)`, big-endian. Binds protocol version and all end-to-end metadata. Link fields are excluded so relays can forward without invalidating the tag.
- The body is `ciphertext | tag`. It is 16 bytes longer than the plaintext, so the plaintext is at most 147 bytes.

Nonce safety: the nonce is partitioned into `(runEpoch, originId, packetId)`. Because `runEpoch` is randomly generated on every router startup and `originId` is random, nonces never collide even if packet counter resets to 1 after an app crash or restart. Repeats of an SOS or RALLY resend the original sealed packet byte for byte; they do not reseal it with a new time.

Opening: find the frame's crewId; if it equals your crew's crewId, open with the same nonce and AAD built from the frame. A failed tag check means forged or corrupted: neither deliver nor relay it.

## 7. Message bodies

Coordinates are Int32 in units of 1e-7 degrees: `lat * 1e7`. Latitude must be within -90 to 90 and longitude within -180 to 180, otherwise the message is rejected. Text is UTF-8, runs to the end of the body, and is at most 100 bytes (30 for the public SOS label).

### 7.1 Crew messages (sealed; the table shows the plaintext)

BEACON (type 1), 12 fixed bytes plus name:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | latitude, Int32 |
| 4 | 4 | longitude, Int32 |
| 8 | 2 | accuracy in metres, u16 |
| 10 | 1 | battery percent, 0 to 100 (higher is rejected) |
| 11 | 1 | bit 0 = findable (crew may start a precise-finding session) |
| 12 | 0 to 100 | name, UTF-8 |

SOS (type 2): latitude Int32, longitude Int32, then the note (UTF-8, to the end).

RALLY (type 3): latitude Int32, longitude Int32, meetAtS u32 (Unix seconds), then the label (UTF-8, to the end).

TEXT (type 4): the text, UTF-8, the whole plaintext.

ACK (type 5), 8 bytes: originId Int32, packetId Int32. These name the SOS or RALLY being acknowledged (the original sender's ids, not the ACK packet's own ids). The ACK packet itself is a normal sealed packet from the acknowledging phone.

### 7.2 Public messages (not sealed; crewId is 0)

PUBLIC_SOS (type 9): for volunteers and police, sent alongside the crew SOS.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | kind: 0 SOS, 1 fall detected, 2 panic (shake), 3 separated from family. Unknown values mean 0 |
| 1 | 4 | latitude, Int32 |
| 5 | 4 | longitude, Int32 |
| 9 | 0 to 30 | label, UTF-8 |

The other public types (FOUND, FOUND_ACK, RESPOND, OFFICIAL, FOUND_NOTICE) are relayed like any other packet. A leaf app that does not show them can still relay or carry them without decoding. Their layouts are in `PublicMessages.kt`.

## 8. What an iOS node must do

### 8.1 Receiving and relaying

For every complete frame that arrives on any link:

1. Decode it (section 5). Drop it if malformed.
2. Ignore NEIGHBOR frames and frames whose `originId` is your own.
3. **Duplicate check.** The packet key is `(originId, packetId)` taken as a 64-bit value. If you have seen the key, drop the frame. Otherwise remember it. Android remembers keys for 120 seconds, and keeps carried urgent packets "seen" for their whole life (600 seconds plus 60). Remember for at least 120 seconds.
4. **Age.** Drop the packet if `now - originTimeS` is above 600 seconds or below -60 seconds (clock ahead of ours).
5. **Deliver.** If the type is public, rate-limit to 5 packets per 10 seconds per originId, then decode and show it. If the crewId equals yours, open it; on a tag failure drop it and do not relay. If the crewId is someone else's, do not read it, but still relay it.
6. **Acknowledge.** After delivering an SOS or RALLY from your crew, originate an ACK for it (section 7.1). When you receive an ACK whose originId is yours, stop repeating that SOS or RALLY.
7. **Relay.** If `ttl > 0`: build the same packet with `ttl - 1`, `hops + 1`, `relayId` = your node id, a fresh `linkSeq`, and send it on every link. Do not echo it back needlessly; if you do, the other side drops it as a duplicate. Android waits a random 40 to 290 ms before relaying (plus a signal-strength term) so the farthest phones go first; a short random delay is enough for a bearer link.
8. If `ttl` is 0, deliver but do not relay.

Rules that never change:

- **Never re-encrypt.** Relays copy the sealed body unchanged. Only the link fields change.
- **Never change** `type`, `crewId`, `originId`, `packetId`, `originTimeS` or the body. Any change fails the tag check at the crew's phones.
- Keep `linkSeq` counting up by 1 for every frame you transmit.
- When you originate: `ttl` 6, `hops` 0, `relayId` = `originId` = your node id, `originTimeS` = current Unix seconds, and a `packetId` that never repeats for you (start from a random value and increment; persist the counter if you can). SOS and RALLY are repeated by resending the stored packet unchanged, up to 6 sends 10 seconds apart, until an ACK arrives.

### 8.2 Backgrounded iOS: leaf and low-power member

A backgrounded iPhone is woken for about 10 seconds at a time and cannot be a dependable router. When the app is in the background it should:

- act as a leaf: deliver what it receives, but **not relay and not carry** other people's packets;
- set `flags` bit 0 (`0x01`) on every frame it transmits;
- send its own beacon at a slow rate (Android's low-power members use 15 seconds).

The flag is a link-level hint, not authenticated. A normal Android phone that hears a flagged frame treats the sender as a sleeping member: it re-offers the urgent packets it has stored since it last heard that sender, at most once every 5 seconds, 8 packets per offer, newest first. Those offers arrive as normal frames and go through the duplicate check. A foreground iPhone that relays should clear the flag. The flag describes the phone that transmits this frame, not the originator, so a relay sets or clears it for itself.

Android sends every non-NEIGHBOR frame it puts on the air to every ready GATT link, including relays, repeats and carried packets. An iPhone therefore sees the same traffic a nearby Android phone sees, with many duplicates.

## 9. Test vectors

`docs/interop/khoj_test_vectors.json` holds exact bytes, generated by `core/src/test/kotlin/app/linkfill/core/InteropVectorsTest.kt`. All hex is lowercase with no separators. AES-GCM here is deterministic (the nonce comes from the packet ids), so the file changes only when the protocol changes.

| Field | Meaning |
|---|---|
| `crew_code` | the crew code used: `ABCDEFGHJKLMNPQR` |
| `crew_secret_hex` | the 16 alphabet indices (`00 01 02 ... 0f`) |
| `hkdf` | the HKDF parameters: SHA-256, salt text, info text, 68 bytes |
| `okm_hex` | the full 68-byte HKDF output |
| `packet_key_hex` | bytes 0 to 31 of the output |
| `crew_id_int32_be` | bytes 32 to 35 as a signed integer |
| `tunnel_key_hex` | bytes 36 to 67 |
| `beacon.fields` | the header values of the example BEACON (type 1, ttl 6, hops 0, relayId and originId `0x11223344`, linkSeq 1, packetId 7, originTimeS 1790000000) |
| `beacon.plaintext_hex` | the BEACON body before sealing: lat 25.3176, lon 82.9739, accuracy 12 m, battery 80, not findable, name `Asha` |
| `beacon.aad_hex` | the 17 AAD bytes |
| `beacon.nonce_hex` | the 12 nonce bytes |
| `beacon.gcm_tag_bytes` | 16 |
| `beacon.frame_hex` | the complete 61-byte frame: 29 header bytes, 16 ciphertext bytes, 16 tag bytes |
| `public_sos_frame_hex` | a complete unsealed PUBLIC_SOS frame (crewId 0, linkSeq 2, packetId 8, kind SOS, same position, label `Asha`) |
| `gatt_fragments_mtu185_hex` | the beacon frame split at MTU 185 with frame id 1: one fragment, header `01 01` |
| `gatt_fragments_mtu23_hex` | the beacon frame split at MTU 23 with frame id 2: four fragments, headers `02 04`, `02 14`, `02 24`, `02 34`, carrying 18, 18, 18 and 7 frame bytes |

An implementation passes when it reproduces: the keys and crewId from the code; the AAD and nonce from the fields; the decrypted plaintext from `frame_hex`; `frame_hex` itself from the fields and plaintext; the public SOS frame; and both fragment lists from `frame_hex`, and the reverse (joining the fragments gives `frame_hex`).

## 10. What is verified and what is not

Verified:

- Kotlin unit tests cover the frame codec, the crew message codecs, key derivation, AES-GCM sealing, fragment splitting and joining, and the router (duplicates, TTL, flooding, carry-forward, the low-power flag). `InteropVectorsTest` writes the vector file.
- An independent Python implementation (not derived from the Kotlin code) reproduces the vector file's keys, crewId, nonce, AAD, ciphertext and frame.

Not verified:

- No iPhone has run any of this. The Swift package under `ios/KhojMesh` has never been compiled.
- The Android GATT bearer code has not been tested against a CoreBluetooth peer. Everything said about iOS behaviour (background scanning of the UUID, state restoration, the MTU an iPhone settles on, connection limits) comes from Apple documentation and developer reports, see `docs/CROSS_PLATFORM_RESEARCH.md`, and is not confirmed on hardware.
- Only the BEACON and PUBLIC_SOS frames are in the vector file. The layouts of SOS, RALLY, TEXT and ACK bodies in section 7 are read from the Kotlin code, not from vectors.

## 11. Open points and edge cases

- **Android server MTU.** When an iPhone is the central, Android (the server) learns the MTU only if the iPhone starts the MTU exchange. An Android server cannot request one. If it never hears one, it assumes 23 and sends notifications as 4 fragments of 18 bytes. That still works, only slower. Whether iOS starts the exchange on current versions is unconfirmed.
- **Double link to a foreground iPhone** that both connects out and advertises (section 2.2).
- **Text truncation.** Android cuts text at 100 bytes (30 for the public label) by bytes, which can split a multi-byte UTF-8 character. Receivers decode with replacement characters. A sender should cut at a character boundary.
- **Coordinate rounding.** Android converts degrees to Int32 by truncating toward zero. Rounding to the nearest unit gives the same decoded value within 1e-7 degrees; either is accepted by receivers.
- **Joiner has no timeout** and does not check that the fragment count stays the same within one frame. A hostile peer can waste at most 15 fragments of memory per link.
- **Time.** `originTimeS` is a 32-bit Unix second count. A phone with a wrong clock sees other phones' packets as too old or too new and drops them.
- **Company id.** `0xFFFF` is a test value. Changing it later is a protocol change for Android scanners.
