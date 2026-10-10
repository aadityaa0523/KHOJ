# KHOJ cross-platform research: GATT bearer, iOS, budget Android, low-power members

Researched 10 Oct 2026. Every non-obvious claim has a link. "Unconfirmed" means I could not
find a primary source in this pass. Several Apple pages returned only their title to my
fetcher, so some Apple claims rest on Apple forum posts or DTS replies quoted by third parties.

## Bottom line

- The GATT bearer is the right way to reach old Android phones and iOS. Extended advertising
  and LE Coded PHY (our current mesh) cannot be received by many cheap Androids and are not
  usable on iOS at all.
- On iOS, the phone must be the one that **connects out** (central). A backgrounded iPhone cannot
  be found by an Android scanner, but a backgrounded iPhone can find and connect to an Android
  phone that advertises the KHOJ service UUID.
- Google Nearby Connections on iOS is Wi-Fi LAN only, so it is not a usable offline bridge to Android.
- Budget numbers: ask for MTU 247, keep fragments to 2 bytes of header, cap links at about 4,
  and expect iPhones to settle near ATT MTU 185.

## 1. iOS CoreBluetooth

### Peripheral role (iPhone advertises, Android scans)

| Situation | What happens | Source |
|---|---|---|
| Foreground | Only two advertisement keys are accepted: local name and service UUIDs. About 28 bytes in the primary packet plus 10 bytes of scan response (name only). | [Apple best practices (archived)](https://developer.apple.com/library/content/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/BestPracticesForSettingUpYourIOSDeviceAsAPeripheral/BestPracticesForSettingUpYourIOSDeviceAsAPeripheral.html), [Apple forum](https://developer.apple.com/forums/thread/751600) |
| Background | Local name is dropped. Service UUIDs move to a special "overflow area" that "can be discovered only by an iOS device that is explicitly scanning for them". Advertising rate may drop. | [Apple background processing guide](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html) |
| Background, non-Apple scanner | The overflow format is undocumented, so Android cannot decode it. A Nordic user hit this exactly. | [Nordic DevZone](https://devzone.nordicsemi.com/f/nordic-q-a/57986/nrf52832-as-central-to-connect-an-iphone-in-background/235349) |

Needs `UIBackgroundModes` value `bluetooth-peripheral`. There is no API to set the advertising
PHY or send a longer payload; the Apple forum poster who tried got only about 31 bytes
([thread](https://developer.apple.com/forums/thread/751600)). So an iPhone cannot send extended advertising.

### Central role (iPhone scans and connects)

- Needs `bluetooth-central`. In the background, duplicate-report option is ignored and repeat
  sightings are coalesced; scan interval grows when every scanning app is backgrounded, so
  discovery "may take longer" ([Apple guide](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)).
- Explicit service UUID filter in `scanForPeripherals(withServices:)` and the UUID in the primary advert
  packet (not the scan response) are the conditions developers cite
  ([forum](https://developer.apple.com/forums/thread/709901)). The "UUID required" rule is
  widely stated but I did not find it spelled out on Apple's page: treat as **community-confirmed**.
- Callbacks run in the background; a terminated app is relaunched only by discovery, connection, or a
  notification from a subscribed characteristic ([Punch Through](https://punchthrough.com/leveraging-background-bluetooth-for-a-great-user-experience/)).
- A woken app has about 10 seconds to finish work ([Apple guide](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)).

### State restoration

Opt in with a restore identifier. iOS saves scanned services, pending connections and
subscribed characteristics, and relaunches the app for them
([Apple guide](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html),
[Punch Through](https://punchthrough.com/leveraging-background-bluetooth-for-a-great-user-experience/)).
It does **not** work after the user force-quits the app, after reboot until first unlock, or with Bluetooth off
([Punch Through](https://punchthrough.com/leveraging-background-bluetooth-for-a-great-user-experience/));
DTS says force quit blocks relaunch except for AccessorySetupKit apps on iOS 26+
([forum summary via search](https://developer.apple.com/forums/thread/818370), Apple TN3115 page body did not load: **partly unconfirmed**).

### ATT MTU

| Claim | Evidence |
|---|---|
| iOS has historically capped at 185 (ATT payload 182). Older: 135 (iOS 8), 158 (iOS 9). | [Nordic thread](https://devzone.nordicsemi.com/f/nordic-q-a/44825/ios-mtu-size-why-only-185-bytes/176060), [Apple forum](https://developer.apple.com/forums/thread/93007) |
| Some reports show iOS requesting 527 up to iOS 15, and after iOS 16.1 not initiating the exchange so the peripheral's value is used. One developer's report only. | [Nordic thread](https://devzone.nordicsemi.com/f/nordic-q-a/103673/unable-to-update-att-mtu), [Apple forum](https://developer.apple.com/forums/thread/721541): **unconfirmed** |
| `maximumWriteValueLength` may return 512 regardless of the real MTU, because longer writes become queued writes. | [Nordic thread](https://devzone.nordicsemi.com/f/nordic-q-a/56886/ios-queued-writes) |
| iOS has no API to request an MTU; it only reports a write length. | same sources |

Planning value: assume 185 (182 payload) on iPhone, 23 as the floor. Do not rely on a 247 or 527 result.

### Connection limits

Apple states no number: it varies by device, model, OS and other apps; detect `connectionLimitReached` at runtime
([Apple forum](https://developer.apple.com/forums/thread/766537)). A developer saw 6 on an iPhone 7 under iOS 11
([search result](https://developer.apple.com/forums/thread/78857)). Plan for 3 or fewer from an iPhone.

### Extended advertising and LE Coded on iOS

- `CBCentralManager.supports(.extendedScanAndConnect)` exists from iOS 13. It is a link-layer capability, varies
  by device and chipset, and Apple publishes no list. Reports show it returning false on an iPhone 14 and
  an iPad mini 5 ([forum](https://developer.apple.com/forums/thread/740773), [forum](https://developer.apple.com/forums/thread/690000)).
- Whether extended adverts are delivered while the app is backgrounded: unanswered
  ([forum](https://developer.apple.com/forums/thread/709901)). **Unconfirmed.**
- No Core Bluetooth API selects the Coded PHY for scanning. **Unconfirmed**; assume the iPhone cannot hear our
  Coded-PHY extended frames.

### Which direction works with the iOS app backgrounded

| Direction | Verdict |
|---|---|
| iOS central connects to Android GATT server (legacy connectable advert with the 128-bit UUID in the primary packet) | Works. Background scan with that UUID, then a pending `connect`, then notifications can wake or relaunch the app. |
| Android central connects to backgrounded iPhone peripheral | Does not work reliably. The UUID is in the overflow area Android cannot read. Works only while the iOS app is in the foreground. |

Design rule: **iOS always initiates. Android phones always advertise the connectable service.**
A legacy advert holds 31 bytes; flags (3) plus a 128-bit UUID (18) leaves about 10 bytes spare.

## 2. Nearby Connections, Multipeer and Nearby Interaction

| Technology | Status for KHOJ | Source |
|---|---|---|
| Google Nearby Connections on iOS (google/nearby Swift package) | Google says the API works on Android and iOS and fully offline, but the repo's own iOS/macOS table lists **only Wi-Fi LAN** as implemented. Bluetooth Classic, BLE GATT, BLE extended and Wi-Fi Hotspot are "not yet implemented". No Android interop is documented. The repo says "not an officially supported Google product". The Swift package registry shows only a 2021 iOS 0.0.1 release. So no Bluetooth path to Android; Wi-Fi LAN needs both phones on one network (no internet needed, but a hotspot or router is). | [connections README](https://raw.githubusercontent.com/google/nearby/main/connections/README.md), [overview](https://developers.google.com/nearby/connections/overview), [registry](https://swiftpackageregistry.com/google/nearby) |
| Apple Multipeer Connectivity | Runs on proprietary AWDL. Does not interoperate with Android. | [Ditto](https://www.ditto.com/blog/cross-platform-p2p-wi-fi-how-the-eu-killed-awdl), [Apple forum](https://developer.apple.com/forums/thread/785308) |
| Wi-Fi Aware (future bridge) | EU order reportedly requires Apple to support Wi-Fi Aware 4.0 by "iOS 19" (the post predates Apple's switch to year-based version numbers; whether it shipped is unconfirmed). Android has had Wi-Fi Aware since 8.0. Source is a vendor blog: **unconfirmed** that iOS apps can use it today. | [Ditto](https://www.ditto.com/blog/cross-platform-p2p-wi-fi-how-the-eu-killed-awdl) |
| Nearby Interaction (UWB) | Apple framework for U1/U2 Apple devices and MFi/FiRa-compatible accessories. No source found for Android phones as peers; Android has its own UWB API. **Unconfirmed** for Android-to-iPhone; not useful for a mesh anyway. | [Apple Japan page](https://developer.apple.com/jp/nearby-interaction/), [forum](https://developer.apple.com/forums/thread/652754) |

Conclusion: BLE GATT is the only offline channel that Android and iOS share today.

## 3. Budget Android phones in India

### Version spread

StatCounter, India, mobile plus tablet, September 2026 ([page](https://gs.statcounter.com/android-version-market-share/mobile-tablet/india)):
Android 16 38.8%, 15 19.9%, 13 10.1%, 14 10.0%, 12 7.2%, 11 6.8%. These six sum to 92.8%, so Android 10 and older is
about 7% by subtraction (the page did not list them). StatCounter counts web traffic, not installed phones, so
treat budget-phone share as understated. Earlier monthly snapshots in 2026 showed Android 12 and below near 20%.
I found no clean 2025 figure. Our minSdk 29 already covers Android 10-12.

### BLE features on cheap phones

- Android exposes `isLeExtendedAdvertisingSupported`, `isLeCodedPhySupported`, `isLe2MPhySupported` and
  `isMultipleAdvertisementSupported` separately, and support depends on the chip vendor
  ([AOSP](https://source.android.com/docs/core/connect/bluetooth/ble_advertising)).
- That Helio, Unisoc or specific Redmi/Realme/itel/Lava models report extended advertising false: **unconfirmed**.
  I found no per-model list. Do not assume; build the GATT bearer as the guaranteed baseline and probe the flags at runtime.
- Legacy connectable advertising works on all BLE phones. Treat `isMultipleAdvertisementSupported` false as "one advert at a time":
  then alternate between the mesh frame advert and the connectable advert, or drop to the connectable one.

### GATT pitfalls ([Punch Through Android BLE guide](https://punchthrough.com/android-ble-guide/))

- One outstanding operation per `BluetoothGatt`; Android has no internal queue, so we must serialize writes, MTU, discovery.
- Status 133 (`GATT_ERROR`) is common: close the `BluetoothGatt`, drop the reference, retry a few times with backoff.
- `requestMtu(517)` after connect; assume 23 if `onMtuChanged` never arrives. From Android 14 the OS negotiates 517 on its own
  and later requests are ignored (same guide; [Nordic thread](https://devzone.nordicsemi.com/f/nordic-q-a/115384/android-14-enforcing-mtu-size-517-causes-ota-issues-with-ble-5-0-device-firmware/506418)).
- `autoConnect=true` does not mean reconnect-for-free; the guide recommends `false` with your own retries.
- Scan starts: 5 per 30 s per app, silent failure otherwise.
- Unfiltered scans stop when the screen is off, so a `ScanFilter` on our service UUID is mandatory for background
  operation ([BluetoothLeScanner reference via search](https://developer.android.com/reference/kotlin/android/bluetooth/le/BluetoothLeScanner)).
  Some Samsung phones still stop scanning with a filter ([Samsung forum](https://forum.developer.samsung.com/t/bluetooth-le-stop-scanning-when-screen-is-off/1492)).
- Max concurrent connections: commonly 7 from the Bluedroid constant, lower on some phones
  ([Nordic](https://devzone.nordicsemi.com/f/nordic-q-a/79481/can-not-connect-multiple-three-four-two-nrf52810-with-android-10/329147)).
  Anecdotal. The stack reportedly hangs when an 8th connect uses autoConnect ([Espruino forum](https://forum.espruino.com/comments/13433136/)). Server-side
  inbound links probably share the same controller link budget: **unconfirmed**.
- Connection priority intervals (HIGH about 11-15 ms, BALANCED 30-50, LOW_POWER 100-125) and advertise mode intervals
  (LOW_LATENCY 100 ms, BALANCED 250 ms, LOW_POWER 1000 ms) are from my recall of the Android API reference; the page body
  did not load in my fetch. **Verify.**

### OEM background killing

| OEM | Guidance (user must do it) | Source |
|---|---|---|
| Xiaomi / Redmi | Lock app in Recents; enable Autostart; Battery saver "No restrictions". Hardest skin. | [dontkillmyapp](https://dontkillmyapp.com/xiaomi) |
| Oppo / Realme | Pin in Recents, allow auto-start, disable battery optimisation, run a persistent notification. Page is for an Oppo F1S and says "No known solution on the dev end"; Realme-specific text **unconfirmed**. | [dontkillmyapp](https://dontkillmyapp.com/oppo) |
| Vivo | Autostart on, battery "Not optimized"/unrestricted, allow high background power use, lock in Recents. | [dontkillmyapp](https://dontkillmyapp.com/vivo) |
| Samsung | Remove app from Sleeping and Deep sleeping apps, add to Never sleeping, turn off "Put unused apps to sleep"; Adaptive battery is aggressive. Firmware updates can reset it. | [dontkillmyapp](https://dontkillmyapp.com/samsung) |

Use a foreground service of type `connectedDevice` (needs a Bluetooth runtime permission; required type from Android 14)
([Android docs](https://developer.android.com/develop/background-work/services/fgs/service-types)). Add an in-app onboarding
screen that deep-links to each OEM's setting. This is the largest real-world risk to the whole design.

### Cost of lowering minSdk below 29

Permissions already split at API 31 ([Android docs](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)):
Android 10-11 need `ACCESS_FINE_LOCATION` and, for a background service, `ACCESS_BACKGROUND_LOCATION`;
Android 12+ use `BLUETOOTH_SCAN/ADVERTISE/CONNECT`. Going below 29 mostly costs testing, not code: API 26 is where extended advertising
and `startForegroundService` appear (from general platform knowledge, not fetched here); below 26 there is no extended-advert API at all and
no notification channels. Share below Android 10 is about 7% (section 3). Recommendation: keep minSdk 29; reconsider 26 only if field
testing shows demand. Whether the Nearby Connections library still supports below 29: **unconfirmed**.

## 4. Bluetooth Mesh friend and low-power-node parameters

Vendor docs for the Mesh Profile: the LPN states PollTimeout and ReceiveDelay in its Friend Request; a Friend answers with
ReceiveWindow, queue size, subscription list size and RSSI; if no Poll arrives within PollTimeout the friendship ends.

| Parameter | Meaning | Range |
|---|---|---|
| PollTimeout | Longest sleep between polls; the key battery lever | 1 s to 96 h in 100 ms steps |
| ReceiveDelay | Wait after LPN request before listening | 10 to 255 ms |
| ReceiveWindow | How long LPN listens for the reply (Friend sets it) | 1 to 255 ms; 10-20 ms typical; 255 ms can raise LPN drain up to 25x when messages are missed |
| Friend queue | Messages held for the LPN | 2 to 128, power of two; overflow is dropped |
| Clock accuracy | Sizes the window; poor clocks stretch it up to about 2.5x | ppm |

Sources: [Silicon Labs LPN API](https://docs.silabs.com/btmesh/9.1.0/bluetooth-mesh-api/sl-btmesh-lpn),
[Silicon Labs power note](https://docs.silabs.com/btmesh/11.0.1/btmesh-power-consumption-measurements/01-bluetooth-mesh-node-power-consumption).
The spec text itself was not fetched, so the exact hex encodings are **unconfirmed**.

### How KHOJ adapts it

| Mesh concept | KHOJ low-power member |
|---|---|
| LPN sleeps, polls friend | Stops relaying, beacons every 15 s, scans with `SCAN_MODE_LOW_POWER`, advertises at the slow interval |
| Friendship negotiation | None. Any normal phone is a friend by convention, so no state to lose |
| Friend queue | A ring buffer of recent urgent packets (SOS, found, ack, official alert, rally) |
| Poll | The member's 15 s beacon is the poll. A friend that has not heard the member for 60 s re-offers the queue |
| PollTimeout | Implicit 60 s |

Honest gaps: the SIG model is ordered and acknowledged; ours is best-effort re-offer, so an unlucky member can miss a packet if
every friend left. Cap each re-offer burst and dedupe by message id. Use AES-GCM and crew key as today; friends need no extra trust.

## 5. Recommended design and limits

### Numbers

| Item | Value | Why |
|---|---|---|
| Service UUID in the connectable advert | Primary packet, 128-bit, nothing else required | iOS background scan needs it there; Android overflow is unreadable |
| Connectable advert | Legacy ADV_IND, no local name; optional 6-byte manufacturer-data crew tag (~10 bytes spare) | 31-byte limit |
| Advertise interval | Normal: BALANCED (about 250 ms). Low-power member: LOW_POWER (about 1000 ms), connectable only while a link slot is free | Android intervals from recall, verify |
| MTU request | Android: ask for 247; if Android 14+ gives 517 accept it. iPhone: expect 185. Floor 23 | sections 1, 3 |
| Fragment header | 2 bytes: byte 0 = frame id (0-255 per link); byte 1 = fragment index (high 4 bits) and fragment count (low 4 bits) | 200-byte frame needs 2 fragments at MTU 185 (180 payload each) and 12 at MTU 23 (18 payload), all within 16 |
| Fragment payload | `min(MTU - 3, 247) - 2`, using the real `onMtuChanged` or `maximumWriteValueLength` value | |
| Reassembly | Per-link buffer, drop on frame id change, 2 s timeout, then discard; the AES-GCM tag rejects corrupt frames | |
| Max GATT links per phone | 4 total (inbound plus outbound) on unknown or budget phones, 6 on a phone that passes a self-test; iPhone 3 | 7 is the community ceiling, controllers and other apps use some |
| Role | One link per neighbour, direction chosen by lower random id connecting out; iOS always connects out | Avoid double links |
| Connection priority | LOW_POWER (100-125 ms) idle; BALANCED while sending SOS or acks | Power |
| Scan | `ScanFilter` on service UUID, at most 4 starts per 30 s, `SCAN_MODE_LOW_POWER` for low-power members | 5 per 30 s limit |
| Operation queue | One GATT op at a time per link, 133 handling: close and retry with 1, 2, 4 s backoff, give up after 3 | |
| Write type | Write without response inbound; notify outbound. On iOS, honour `canSendWriteWithoutResponse`; on Android, pace to the connection interval | (iOS flow control API is from general knowledge, not fetched) |

### What an iOS KHOJ app could do

- Join as a normal crew member over GATT: receive and send the same 200-byte sealed frames through Android bridge phones,
  with the same crew key.
- Keep running in the background after the user opens the app once, using `bluetooth-central`, UUID-filtered scan, a pending
  connect, and state restoration, as long as the user does not force-quit.
- Show SOS, found reports, official alerts and rally in the app, and send SOS while the app is open.

### What an iOS KHOJ app could not do

- Hear or send extended advertising or LE Coded frames. All iOS traffic needs an Android phone within GATT range to bridge it.
- Be discovered by Android while backgrounded, so Android cannot connect to it first.
- Run after a force quit or reboot until the user opens the app again, and no guaranteed delivery during the 10-second wakes.
- Hold many links (plan 3) or rely on an MTU above 185.
- Use Nearby Connections over Bluetooth, Multipeer, or UWB to reach Android.
- Be a reliable relay node: an always-on relay is not realistic for a backgrounded iPhone. Treat it as a leaf, not a router.
- Be proven yet: all iOS behaviour here comes from docs, forums and developer reports; we have not tested on a real iPhone.

### Test before shipping

1. A Redmi 9A or Realme C-series phone (Android 10/11): log the four `BluetoothAdapter` flags and the real max GATT links.
2. An iPhone, backgrounded and screen locked, discovering an Android advertiser; record time to discover and the MTU.
3. Four-hour screen-off run on Xiaomi, Realme and Vivo with the OEM settings applied and not applied.
