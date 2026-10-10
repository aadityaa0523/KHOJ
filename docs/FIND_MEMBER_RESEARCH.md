# Find Member: research notes

Research done 2026-10-10. Every claim says where it came from. **Published or vendor claims are separated from what we measured ourselves.** Where a source could not be read, or a fact could not be confirmed, that is stated instead of filled in.

Our own measurements so far are limited to one iQOO I2501 (Snapdragon SM8850, Android 16), queried through the platform's own APIs. No two-phone ranging has been run yet (see [FIND_MEMBER_TEST_PLAN.md](FIND_MEMBER_TEST_PLAN.md)).

---

## 1. What we measured on the target phone (not from a paper)

Logged by the app at start-up (`adb logcat -s KHOJ`), from `RangingManager.registerCapabilitiesCallback`:

| Technology | Reported by the platform on the iQOO |
|---|---|
| Ultra-wideband (UWB) | **not supported** |
| Bluetooth Channel Sounding (BLE CS) | **enabled** |
| Bluetooth RSSI ranging | enabled |
| Wi-Fi NAN RTT | **not supported** |
| UWB azimuth / elevation / background | not applicable (no UWB) |

Also from `pm list features` and the controller dump: `android.hardware.bluetooth_le.channel_sounding` present, Wi-Fi Aware absent, Wi-Fi Direct present, Bluetooth 6.0 controller, LE Coded supported.

**Consequence:** on these phones the best hardware tier is **Channel Sounding, which gives distance only.** UWB and its direction measurement are not available, so a hardware-supplied arrow is not possible on this hardware. Nothing in this repository can change that.

---

## 2. Apple AirTag Precision Finding: what it is, and what we can learn

Sources: Apple developer pages on Nearby Interaction (`developer.apple.com/documentation/nearbyinteraction`, `isCameraAssistanceEnabled`) and press coverage of the 2021 launch. Fetched 2026-10-10; the sources are mostly from 2021 and some Apple pages could only be summarised from search results, so treat details as secondary.

- **Discovery and the wide network:** an AirTag uses Bluetooth Low Energy for discovery and for the crowd-sourced Find My network (other people's Apple devices relay its location). This is proprietary and closed.
- **Precision Finding:** when the finder's phone has a UWB chip (U1 or later) it measures distance and direction to the tag's UWB chip and shows an on-screen arrow and distance. Apple says the experience **fuses UWB with the camera (ARKit), accelerometer and gyroscope**, and guides with sound, haptics and visuals.
- **Camera assist** (iOS 16+ for developers) combines ARKit's 6-degree-of-freedom tracking with UWB readings; one developer forum reports it is affected by low light and requires the app in the foreground (one developer's observation, not Apple guidance).
- **How direction is derived from UWB** is not explained in the sources we could read.

**What informs our design:**
1. Direction needs a *radio direction measurement* (UWB angle) plus orientation sensors. Distance alone is not enough.
2. Sensor fusion (motion sensors) keeps the arrow stable between measurements; we keep the arrow right while the phone turns (`FindResolver`).
3. Distinguish "precision finding" (nearby, hardware-assisted) from the wide crowd network. Ours is the mesh, which carries *position updates*, never bearing.

**What depends on Apple-only technology and is out of reach:** the Find My network and AirTag firmware, the U1 chip's ranging, and Apple's private protocols. We do not use or imitate them, and an arbitrary BLE beacon is not an AirTag.

---

## 3. Bluetooth direction finding, RSSI, and Channel Sounding

### 3.1 Direction finding (AoA / AoD)
Source: Bluetooth SIG, *Direction Finding* (`bluetooth.com/learn-about-bluetooth/feature-enhancements/direction-finding/`).
- **AoA:** the transmitter has one antenna, the receiver has a multi-antenna array and computes the angle from the signal across the array. **AoD:** the transmitter has the array; the receiver has one antenna and computes direction. The page names a smartphone as the single-antenna receiver in the AoD case.
- The page states "centimeter-level location accuracy" and gives **no numbers or test conditions** (a marketing claim).
- It does not say which phones support it.

**What a third-party Android app can obtain:** Android exposes no AoA/AoD or raw phase/antenna-array data to apps, and ordinary phones do not carry the antenna arrays. We therefore treat Bluetooth Direction Finding as **unavailable**.

### 3.2 RSSI
- Android's `BluetoothLeScanner` gives per-advertisement RSSI. Android's Nearby Connections does **not** expose RSSI for its endpoints. We take RSSI from our own advertising frames (the mesh scanner), so there is **no second scanner** and no conflict with Nearby.
- RSSI is a poor distance estimator: body shadowing, antenna orientation, multipath and non-line-of-sight all change it by several dB without any change in distance. A **review of smartphone indoor localisation** reports BLE accuracy of **1-5 m** with "poor signal stability" (Liu et al., *Sensors* 25(6):1806, 2025, doi 10.3390/s25061806; this is a literature synthesis whose figures come from different setups and are not directly comparable).
- A concrete caution from the BLE literature: RSSI exists only for packets that were decoded, so near the edge of range it is biased upward (survivorship). Our design therefore also tracks the packet-delivery ratio (see MESH.md).

Design consequence: from RSSI we show **proximity bands and a trend only**, never a bearing and never a calibrated metre figure.

### 3.3 Channel Sounding (CS)
Sources: Bluetooth SIG, *Channel Sounding: Technical Overview* (`bluetooth.com/bluetooth-resources/channel-sounding/`) and vendor pages (u-blox, Minew, Ezurio).
- Added in **Bluetooth Core 6.0**. The SIG page describes it as "enabling distance estimation between two devices with a high level of accuracy" and mentions **distance only**, not direction or angle.
- Vendor descriptions: it combines **phase-based ranging** across many frequencies with **round-trip time** as a secure distance-bounding check. Accuracy figures **conflict**: u-blox cites 0.3-1 m, Minew +/-10-50 cm, Ezurio says "potentially centimeter-level". These are vendor-reported, not certified, and real accuracy depends on hardware, antennas and environment.
- A 2025 ICASSP paper (Tsemko, Santra, Kapshii, Pandey, doi 10.1109/ICASSP49660.2025.10887852) applies a parametric neural network to improve CS distance estimates. **We could not retrieve its abstract or results**, so we cite only its existence and subject.

**Android support:** present on the iQOO as a feature flag and reported *enabled* by the Ranging module (§1). Both phones in a session must support it.

---

## 4. Android Ranging module (the first API to try)

Sources: `developer.android.com/develop/connectivity/ranging` (fetched 2026-10-10) and the **real API surface read from the SDK 36 `android.jar`** with `javap`, which is more reliable than the web reference (the reference pages are very large and could not be fully fetched).

**Requirements and structure**
- **Android 16 (API 36) or higher on all devices.** Permission `android.permission.RANGING` (in the Nearby devices permission group). UWB additionally has `UWB_RANGING`.
- `RangingManager` (via `getSystemService`): constants `UWB`, `BLE_CS`, `BLE_RSSI`, `WIFI_NAN_RTT`; `createRangingSession(executor, callback)`; `registerCapabilitiesCallback`.
- `RangingCapabilities.getTechnologyAvailability()` returns a map technology → `ENABLED`, `NOT_SUPPORTED`, `DISABLED_USER`, `DISABLED_USER_RESTRICTIONS`, `DISABLED_REGULATORY`. `getUwbCapabilities()` exposes `isAzimuthalAngleSupported()`, `isElevationAngleSupported()`, `isBackgroundRangingSupported()`. `getCsCapabilities()` exposes supported CS security levels (1 and 4).
- Roles: `DEVICE_ROLE_INITIATOR` (starts, can range several responders) and `DEVICE_ROLE_RESPONDER` (one initiator at a time).
- **Out-of-band (OOB):** *default OOB* (`OobInitiatorRangingConfig` / `OobResponderRangingConfig`) lets the module negotiate parameters from both phones' capabilities, but **the app must supply the transport** (`TransportHandle`: `sendData(byte[])` and a receive callback) and the peer discovery. *Raw* sessions (`RangingConfig.RANGING_SESSION_RAW`) leave negotiation to the app.
- Config options include `RANGING_MODE_AUTO / FUSED / HIGH_ACCURACY / HIGH_ACCURACY_PREFERRED`, `SECURITY_LEVEL_BASIC / SECURE`, ranging intervals, and `SessionConfig.setAngleOfArrivalNeeded(boolean)`.
- Results: `RangingData` with `getDistance()`, `getAzimuth()` (documented as null if not available), `getElevation()`, `getRangingTechnology()`, `getRssi()`, `getTimestampMillis()`. `RangingMeasurement` has a `double` value and a confidence of `LOW / MEDIUM / HIGH`.
- The online reference (API 37) also lists `getDistanceStandardDeviationMeters()`; **that method is not in the API 36 jar**, so on Android 16 we only have the coarse confidence level and we never print a metre uncertainty for hardware ranging.

**Restrictions stated by Android**
- Third-party apps can range **in the background only with UWB, and only on supported devices.** Other technologies need the app in the foreground. **This matters for us:** the phone being found must also have KHOJ in the foreground to answer a CS session. Whether our foreground service counts is untested.
- Ranging can be refused when the device reaches its maximum concurrent sessions, and the system may restrict ranging for battery, performance or memory reasons.
- For non-UWB technologies, data goes only to the initiator. The initiator (the finder) is the one that sees the measurements, which suits us.

**What we could not confirm:** the **units and sign convention of `getAzimuth()` / `getElevation()`** are not stated in any source we could read. The UI therefore refuses to draw a hardware arrow until a validated flag is set (§7). No phone we own can test this, since none has UWB.

---

## 5. Nearby Connections and the existing mesh

Sources: `developers.google.com/nearby/connections/overview`, the *get-started* and *manage connections* guides (fetched earlier in the project).
- Nearby gives discovery, an encrypted link, and byte payloads between **named endpoints**. It returns neither signal strength nor direction. It does **not** provide application-level multi-hop routing.
- We run it in cluster mode (`P2P_CLUSTER`) as an *on-demand* transport (see DEVICE_PROFILE_IQOO.md for its measured CPU cost) and use the advertised endpoint name `khoj-<node id>` to identify which connection belongs to which crew member.
- **Decision:** the ranging out-of-band negotiation travels over a **direct Nearby connection to the target**, as an authenticated tunnel (`core/find/Tunnel.kt`), not over the BLE advertising mesh. The mesh packets are limited to 200 bytes and are broadcast; OOB messages can be larger and must be reliable and unicast.
- **Coexistence:** the Find feature takes RSSI from the existing mesh scanner (one scan filter, hardware-offloaded). There is no extra `BluetoothLeScanner`, so nothing competes with Nearby for scanning.

---

## 6. Wi-Fi Direct

Source: `developer.android.com/develop/connectivity/wifi/wifip2p` (fetched 2026-10-10).
- Wi-Fi Direct lets devices connect without an access point and exchange data over sockets at Wi-Fi speeds. It needs `NEARBY_WIFI_DEVICES` (Android 13+) or location permission, and uses `WifiP2pManager` (discover peers, connect, group, `ServerSocket`/`Socket`).
- The page says nothing about ranging; Wi-Fi Aware and RTT are separate guides. **Wi-Fi Direct is a transport, not a way to find direction, and it is not Wi-Fi NAN RTT.** (The iQOO supports Wi-Fi Direct but reports Wi-Fi NAN RTT as not supported.)
- **Decision:** we do not add a Wi-Fi Direct stack. Nearby already uses Wi-Fi Direct or Bluetooth underneath, and we have no measurement showing a gap that a second stack would fill.

A relay phone can carry the target's position to the finder, but the *signal strength from the relay* says nothing about the target's bearing. The relay path only ever delivers a timestamped position, which the finder uses with its own GPS and heading.

---

## 7. Orientation and sensor fusion

Source: Android sensor documentation (`SensorManager`, `TYPE_ROTATION_VECTOR`, `GeomagneticField`, `Surface.ROTATION_*`); implemented and unit-tested in `core/find/Geometry.kt`.
- The **rotation vector** is a fused orientation (accelerometer + gyroscope + magnetometer) referenced to **magnetic** north. `values[4]` is the platform's estimated heading accuracy in radians where available.
- Convert to true north with the declination from `GeomagneticField` for the current position. Compare a *true* GPS bearing only against a *true* heading; mixing magnetic and true is wrong wherever declination is not zero.
- **Posture matters.** `SensorManager.getOrientation()`'s azimuth is wrong when the phone is held upright, which is how people hold a phone to walk towards something. `Orientation.heading()` therefore uses the screen's top edge when the phone is flat, and the **back-camera direction** when upright, with display-rotation handling. Tested with synthetic quaternions for flat, upright, tilted and landscape postures.
- The compass reports where the *phone* points, never where the *other person* is. The relative arrow is always `bearing to target - heading`, where the bearing comes from two position fixes or from a hardware angle.
- The platform's accuracy estimate does not capture magnetic interference near steel structures; the UI drops the arrow if the uncertainty exceeds 40 degrees.

---

## 8. Papers and real-world evidence, compared critically

| Source | Relevance | What it supports | Caveat |
|---|---|---|---|
| Liu et al., *Indoor Localization Methods for Smartphones with Multi-Source Sensors Fusion*, Sensors 25(6):1806, 2025 (PMC11945668) | Review of smartphone positioning | BLE RSSI 1-5 m; Wi-Fi RTT ~1 m (one study 1.08 m); fusion reduces PDR drift; no single technology works everywhere | Literature synthesis; figures from different setups; not Android-specific |
| Kandula, Chidurala, Cao, Li, *Localization of a BLE Device Based on Single-Device RSSI and DOA Measurements*, Network 4(2):196-216, 2024 (doi 10.3390/network4020010) | RSSI + direction | Iterative measurements guide the user toward the beacon; reported direction within a 30 degree range | Needs a direction-of-arrival receiver (antenna array). We could not open the full text (HTTP 403), so this relies on the search summary. **The brief cited this as `mdpi.com/2673-8732/4/2/10`; that is the *Network* journal, not *Telecom*.** |
| Yang, Yang, Meng, *A Bluetooth Indoor Positioning System Based on Deep Learning with RSSI and AoA*, Sensors 25(9):2834, 2025 (PMC12074306) | Learned positioning | Reports **0.29 m** mean error using CNN-MHA with Kalman filtering of AoA and median plus moving-average smoothing of RSSI | Needs AoA hardware; test-bed results; full text not opened (HTTP 403 from MDPI) so only the abstract-level summary is used |
| A 2025 IEEE benchmark noted in our search: KNN, random forest and a plain DNN beat sequence and attention models on BLE RSSI, RMSE as low as 1.297 m | ML on RSSI | Simple models can win on noisy RSSI | Seen only as a search summary; not read in full |
| Tsemko et al., ICASSP 2025 | ML on CS distance | A neural network can improve CS distance | Abstract not retrievable |
| Bluetooth SIG pages | Specs | Section 3 | No numbers for direction finding; marketing language |

**Comparison:** the strongest published results use direction hardware (arrays) or fingerprinting, neither available to a normal phone app. For RSSI alone the credible range of accuracy is metres with poor stability, which is why we expose **bands and trend only**. The conclusions we drew are about what to *withhold*: no bearing from RSSI, no metre figure from RSSI, no hardware arrow without validated units.

---

## 9. Filtering choices (RSSI)

Following the papers' use of a median then a moving average, `RssiTracker` applies a **5-sample median** (spike removal) then an **exponential moving average** (alpha 0.3), with a **regression slope and its standard error** (widened 2x for correlation introduced by the EMA) so that a trend is only called "improving" or "worsening" when it is statistically distinguishable from noise and above 0.4 dB/s. Band thresholds have hysteresis. These parameters are **defaults, not calibrated**; the test plan measures them. Alternatives to compare experimentally: a Kalman filter, a longer median window, and per-orientation offsets.

---

## 10. Open research questions

1. Units and sign of `RangingMeasurement.getMeasurement()` for azimuth and elevation, and whether `setAngleOfArrivalNeeded(true)` can make a CS-only session fail.
2. Whether a session with a phone that is not foreground (but has a foreground service) is allowed for CS.
3. Real CS distance accuracy between two iQOO phones, with and without bodies in the way.
4. Whether `SECURITY_LEVEL_BASIC` vs `SECURE` changes which technology is chosen.
5. How accurate the RSSI proximity bands are across phones, orientations and pockets.

## 11. Source list

- Android Ranging guide and `android.ranging` API (SDK 36 jar via `javap`; online reference): <https://developer.android.com/develop/connectivity/ranging>, <https://developer.android.com/reference/android/ranging/RangingManager>
- Bluetooth SIG Direction Finding: <https://www.bluetooth.com/learn-about-bluetooth/feature-enhancements/direction-finding/>
- Bluetooth SIG Channel Sounding overview: <https://www.bluetooth.com/bluetooth-resources/channel-sounding/>
- Vendor CS overviews: u-blox, Minew, Ezurio (accuracy figures conflict)
- Apple Nearby Interaction: <https://developer.apple.com/documentation/nearbyinteraction/finding-devices-with-precision>
- Nearby Connections: <https://developers.google.com/nearby/connections/overview>
- Wi-Fi Direct: <https://developer.android.com/develop/connectivity/wifi/wifip2p>
- Liu et al. 2025, <https://pmc.ncbi.nlm.nih.gov/articles/PMC11945668/>
- Kandula et al. 2024, doi 10.3390/network4020010
- Yang et al. 2025, <https://pmc.ncbi.nlm.nih.gov/articles/PMC12074306>
- Tsemko et al. 2025, doi 10.1109/ICASSP49660.2025.10887852
