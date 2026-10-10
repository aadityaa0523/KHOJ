# KhojMesh (Swift reference package)

> **WARNING: this code has never been compiled or run.** It was written on a Windows machine with no Mac and no Swift compiler, against my reading of the current CryptoKit and CoreBluetooth APIs. Treat it as a careful draft. **Run `swift test` on a Mac first**, fix whatever the compiler finds, and only then build on it. The Bluetooth classes (`KhojBluetooth.swift`) are not covered by any test and have not touched an iPhone.

## What it is

A small reference for a future KHOJ iOS app that joins the offline phone mesh over the GATT bearer. The wire format is in [`docs/GATT_BEARER.md`](../../docs/GATT_BEARER.md); this package implements it.

| File | Purpose |
|---|---|
| `CrewKey.swift` | 16-character crew code to keys (HKDF-SHA256) and crewId; AES-256-GCM seal and open with the KHOJ nonce and AAD (CryptoKit) |
| `MeshPacket.swift` | the 29-byte frame header: encode, decode, relay copy, AAD, duplicate key |
| `MeshMessage.swift` | message bodies: beacon, SOS, rally, text, ack, public SOS |
| `GattFraming.swift` | 2-byte fragment header: split a frame for an MTU, rejoin fragments |
| `KhojBluetooth.swift` | `KhojCentral` (scan, connect out, notify, write without response) and optional `KhojPeripheral` (foreground GATT server) |

Not included, on purpose: a router. Duplicate suppression, TTL handling, relay delay, SOS repeats and ACKs are described in section 8 of the protocol document and are left to the app. `MeshPacket.key` and `MeshPacket.relayed(by:linkSeq:lowPower:)` are the pieces you need.

## Test it on a Mac

```
cd ios/KhojMesh
swift test
```

Needs Xcode 15 or newer (Swift 5.9 tools). `Tests/KhojMeshTests/Resources/khoj_test_vectors.json` is a copy of `docs/interop/khoj_test_vectors.json`; copy it again whenever the Kotlin `InteropVectorsTest` regenerates the original.

`VectorTests` checks:

- key derivation: packet key, crewId and tunnel key from the crew code;
- the beacon frame decodes, its header fields, AAD and nonce match, and it decrypts to the expected plaintext (position, battery, name `Asha`);
- encoding the same beacon and sealing it reproduces the frame byte for byte;
- the public SOS frame decodes and re-encodes byte for byte;
- the GATT fragments at MTU 185 and MTU 23 are produced exactly and rejoin to the frame;
- tampering and malformed input are rejected.

Everything the tests run is pure computation. They do not use Bluetooth, so they run on a Mac without a Bluetooth device.

## Using it in an app

Info.plist:

| Key | Value |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | a sentence saying why the app uses Bluetooth, for example "KHOJ uses Bluetooth to pass emergency messages between nearby phones without internet." |
| `UIBackgroundModes` | `bluetooth-central` (needed for background scanning and connections). Add `bluetooth-peripheral` only if you use `KhojPeripheral` and want it to keep advertising in the background (see the limits below) |

Minimal use:

```swift
let central = KhojCentral()          // starts scanning when Bluetooth is on
central.onFrame = { frame in /* decode, de-duplicate, deliver, relay: protocol section 8 */ }
central.send(myFrame)                // goes to every connected link

let server = KhojPeripheral()        // optional, foreground only
server.onFrame = central.onFrame
```

Create `KhojCentral` once, early (for example in `application(_:didFinishLaunchingWithOptions:)`), because state restoration relaunches the app and hands the same restore identifier back to a new instance.

Notes on behaviour:

- An iPhone always connects out. It does not look at the door's capability byte; it counts as a phone that needs the bearer. Android phones with the extended-advertising mesh only accept connections.
- Up to 3 outbound links. A link that does not become ready in 20 seconds is dropped. After any drop, scanning is restarted after 3 seconds so the door is found again.
- With the app backgrounded, set `MeshPacket.flagLowPower` on every frame you send and do not relay (protocol section 8.2).
- State restoration works while the app is suspended or was killed by iOS. It does not work after the user force-quits the app, or after a reboot until the first unlock.
- `KhojPeripheral` only helps while the app is on screen. A backgrounded iPhone's service UUID is not readable by Android scanners, so Android cannot find it; `KhojCentral` is the link that works in the background.
- The Android phone chooses the MTU only if the iPhone starts an MTU exchange. If it never does, Android sends notifications as 4 small fragments. That still works.

## Known gaps

- Never compiled or run (see the warning at the top).
- No router, no persistence of the packet counter, no handling of FOUND, RESPOND, OFFICIAL or FOUND_NOTICE bodies (they can be relayed without decoding).
- Connection limits, background wake times and MTU on real iPhones are from Apple documentation and developer reports (`docs/CROSS_PLATFORM_RESEARCH.md`), not measured.
