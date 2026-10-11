# Zero-Install & Bystander Mesh Networking: Feasibility, OS Constraints, and Technical Pathways

**Document Status:** Comprehensive Systems & OS Security Research  
**Target Platform:** Android (AOSP / GMS) & iOS (Apple Ecosystem)  
**Context:** KHOJ Offline Mobile Ad-Hoc Mesh Network  

---

## 1. Executive Summary & Problem Formulation

The central challenge in disaster and large-crowd emergency communications (e.g., Kumbh Mela, stadium stampedes, earthquake zones) is **network sparsity**: only a small percentage of attendees (typically 1–5%) will have downloaded the safety application beforehand.

If packet routing requires every intermediate hop to have the KHOJ application installed, the network partitions whenever the distance between active KHOJ users exceeds the physical Bluetooth Low Energy (BLE) or Wi-Fi Direct range (30 m – 80 m).

This research investigates whether and how smartphones **without the KHOJ app installed** ("bystander phones") can participate in, relay for, or be utilized by the KHOJ mesh network.

---

## 2. Theoretical & OS Security Limits: Why Invisible Zero-Install Multi-Hop Relaying Is Impossible

### 2.1 The Computability & Hardware Reality
A mobile device's radio hardware (Broadcom, Qualcomm, MediaTek, Apple Silicon) consists of:
1. Physical Transceivers (RF front-end, antennas).
2. Baseband / Link Layer Controller (executing closed-source firmware on an embedded ARM/RISC-V core).
3. Host Controller Interface (HCI) over UART/PCIe communicating with the OS kernel.
4. OS Host Stack (Fluoride/Gki on Android; Bluetooth daemon on iOS).
5. User-Space Applications running inside strict security sandboxes.

The Bluetooth Low Energy (BLE) link-layer standard does **not** autonomously retransmit incoming advertising packets at the physical or link layer. Every packet received by the antenna is either delivered up the HCI stack or discarded by hardware address/UUID filters. For a packet to be re-transmitted on advertising channels (37, 38, 39):
- The OS host must parse the packet.
- An application or system service must make a policy decision to forward it.
- An HCI command (`HCI_LE_Set_Extended_Advertising_Data` or `HCI_LE_Set_Advertising_Data`) must be dispatched back down to the controller.

### 2.2 The OS Security & Anti-Malware Sandbox
Neither Android (Google) nor iOS (Apple) includes an open, autonomous packet forwarding daemon for unauthenticated third-party radio frames. Both operating systems deliberately forbid this behavior:
1. **Battery Depletion (Vampire Attacks):** Autonomous forwarding would allow malicious actors to flood the RF spectrum, forcing bystander phones in sleep mode to continuously wake their baseband and CPU, draining batteries in hours.
2. **Denial of Service & Spectral Congestion:** Flooding arbitrary mesh packets through innocent devices violates FCC/ETSI unlicensed spectrum etiquette and causes radio frequency saturation.
3. **Privacy, Eavesdropping & Criminal Liability:** Relaying encrypted, uninspected third-party traffic exposes innocent bystanders to forensic implication in malicious traffic distribution.
4. **Zero-Click Remote Vulnerability:** Any mechanism allowing an uninstalled phone to execute code or forward traffic without user consent is classified by Google Project Zero and Apple Security as a **Critical Zero-Click Remote Code Execution (RCE) vulnerability** (e.g., Ian Beer’s AWDL exploit CVE-2020-27932 or BlueBorne CVE-2017-0781), and is patched immediately.

> **Conclusion:** It is technically and physically impossible for a bystander phone with no app installed to act as a silent, invisible, multi-hop mesh repeater in its pocket without running code.

---

## 3. What CAN Be Done: The Five Practical Engineering Pathways

While invisible active multi-hop packet repeating is blocked by OS sandboxes, there are **five viable architectural pathways** that leverage bystander phones to extend the reach, utility, and survival of KHOJ:

```
+----------------------------------------------------------------------------------------------------+
|                                    BYSTANDER PHONE INTERACTION TAXONOMY                            |
+----------------------------------------------------------------------------------------------------+
| 1. ASYMMETRIC CLOUD UPLINK   | Piggyback on Apple Find My & Google Find My Device (Zero install)   |
| 2. ZERO-INSTALL LOCAL PORTAL  | Local Wi-Fi AP + Captive Portal + PWA / WebRTC (No App Store)      |
| 3. AMBIENT SSID BROADCASTING  | Dynamic Wi-Fi SSIDs ("Beacon Stuffing") for 1-way broadcast alerts  |
| 4. PASSIVE RF SENSING         | Sniff bystander Wi-Fi/BLE probe requests for crowd crush detection |
| 5. VIRAL PEER-TO-PEER BEAM    | Instant 2-second offline APK sideload via Hotspot / Quick Share    |
+----------------------------------------------------------------------------------------------------+
```

---

### Pathway 1: Asymmetric Cloud Uplink via Apple "Find My" & Google "Find My Device"

#### Mechanism
Virtually every modern smartphone on Earth has a built-in background scanner maintained by the operating system manufacturer:
- **Apple Devices (iOS):** The `searchparty` daemon continuously scans for BLE advertisements matching Apple Company Identifier `0x004C`, Type `0x12` (Find My / AirTag beacon).
- **Android Devices (GMS):** Google Play Services continuously scans for Fast Pair / Find My Device Network (FMDN) beacons (`0xFE2C` / `0xFEED`).

When a bystander walks past an emitter:
1. The bystander phone (screen off, in pocket, zero third-party apps installed) detects the beacon frame.
2. The OS encrypts its own GPS fix with the beacon's broadcast public key.
3. **When the bystander enters cellular or Wi-Fi range**, the OS quietly uploads this encrypted payload to Apple’s iCloud or Google’s Find My Device servers.

#### Application in KHOJ
- When a user triggers **SOS** or a member is flagged **LOST**, and the phone is partitioned from any local KHOJ mesh nodes:
  - KHOJ switches its BLE peripheral to broadcast a standard **Apple Find My Offline Finding beacon** (using OpenHaystack / SendMy specifications) and a **Google FMDN beacon**.
  - Thousands of stranger iPhones and Android phones passing through the crowd pick up the signal silently.
  - As soon as any bystander walks to the edge of the crowd where cellular towers work, the lost person's GPS location is uploaded to the cloud.
  - A family member or rescue authority outside the dead zone queries the Apple/Google reporting endpoint and pinpoints the victim.

#### Advantages & Limitations
- **Advantage:** Works with 100% of bystander phones with zero app installation.
- **Limitation:** Asymmetric uplink only (does not forward packets locally between two offline phones; requires bystander to eventually reach an internet connection).

---

### Pathway 2: Zero-Install Local Web Portal (Captive Portal + WebRTC / PWA)

#### Mechanism
In disaster zones or cellular dead spots, people actively search for Wi-Fi. KHOJ can leverage the native **Captive Network Assistant (CNA)** built into Android and iOS:
1. A KHOJ phone (or portable emergency node / backpack bridge) enables a Wi-Fi Access Point (Hotspot):
   - SSID: `KHOJ - Free Emergency Safety Mesh`
2. When a bystander connects:
   - Android ("Sign in to network") and iOS ("Captive Portal Assistant") automatically launch a full-screen browser window without the user needing to type a URL.
3. The embedded HTTP server on the KHOJ phone serves an ultra-lightweight, high-performance HTML/JS Progressive Web App (PWA):
   - **Size:** Under 100 KB (cached instantly).
   - **Features:** 
     - Live emergency feed (lost children, SOS alerts, medical tents).
     - "I Need Help" / "I Found Someone" forms that directly inject packets into the KHOJ mesh via local WebSockets or HTTP REST.
     - WebRTC DataChannel connection bridging the browser into the mesh while the window is active.
     - 1-tap "Download Complete Offline App" button (serving the 4 MB APK directly over local Wi-Fi in ~2 seconds without cellular data).

#### Advantages & Limitations
- **Advantage:** Full bidirectional communication and reporting without needing Google Play Store or Apple App Store access.
- **Limitation:** Requires the bystander to tap and connect to the Wi-Fi network.

---

### Pathway 3: Ambient SSID Emergency Broadcasting ("Wi-Fi Beacon Stuffing")

#### Mechanism
Wi-Fi Access Points periodically broadcast Beacon Frames (typically every 102.4 ms) announcing their SSID (Service Set Identifier). 
- An SSID is up to 32 UTF-8 bytes long.
- Every smartphone scanning for Wi-Fi decodes and displays SSIDs on the user's screen in the Wi-Fi settings or Quick Settings panel, **without requiring connection or authentication**.

#### Application in KHOJ
A KHOJ gateway node or phone in SOS/Anchor mode can dynamically rotate or broadcast multiple SSIDs:
- `!SOS: Lost 6yo Boy Blue TShirt`
- `!KHOJ: Safe Zone at Gate 4`
- `!ALERT: High Crush at North Exit`

Anyone in range who unlocks their phone and glances at their Wi-Fi picker sees the urgent message in plain text.

#### Advantages & Limitations
- **Advantage:** Zero install, zero connection, reaches every bystander looking for signal.
- **Limitation:** Unidirectional broadcast only; limited to 32 characters per SSID; relies on bystanders checking Wi-Fi.

---

### Pathway 4: Passive RF Sensing of Bystanders (Crowd Crush & Proximity Detection)

#### Mechanism
Even when locked in pockets with screens off, every bystander smartphone continuously emits:
1. **Wi-Fi Probe Requests:** Scanning for previously associated home/office networks (emitted at 10–60 second intervals).
2. **BLE Discovery Advertisements:** Background beacons for Quick Share (Nearby Share), AirDrop, Apple Continuity, and smartwatches.

#### Application in KHOJ
KHOJ nodes do not need bystanders to run software to extract critical life-safety data from them:
1. **Crowd Density & Stampede Prevention:**
   - By calculating the count and RSSI distribution of unique randomized MAC addresses per 10-second window, KHOJ nodes estimate real-time local pedestrian density (persons/m²).
   - If pedestrian density exceeds safety thresholds (> 4-5 persons/m²), KHOJ triggers an automated proactive **Crowd Crush Warning** on the mesh.
2. **Device Proximity Detection:**
   - If a family member loses a phone that does not have KHOJ installed, but its Bluetooth device name or paired BLE MAC signature is known, nearby KHOJ phones can passively detect its presence and distance using signal strength (RSSI).

---

### Pathway 5: Viral Peer-to-Peer Sideloading (Offline Zero-Data Mesh Growth)

#### Mechanism
When cellular data is dead, app distribution must occur peer-to-peer ("sneakernet"):
1. **Android Quick Share / Nearby Share:** KHOJ can package its 4 MB APK for instant beam to bystander devices via system Quick Share.
2. **Local Hotspot APK Distribution:** KHOJ's captive portal hosts the APK file directly at `http://192.168.49.1/khoj.apk`.
3. **QR Code Install:** Scanning a KHOJ user's QR code connects the bystander to the hotspot and initiates the direct download in seconds.

Once installed, the bystander's phone immediately converts into a full BLE mesh relay node.

---

## 4. Architectural Comparison Matrix

| Feature / Pathway | Zero Install Required? | Works 100% Offline? | Multi-Hop Mesh Relaying? | User Action Needed? | Latency |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Pure Invisible BLE Relay** | ❌ Impossible (OS Blocked) | N/A | N/A | None | N/A |
| **Pathway 1: Apple/Google Find My** | ✅ Yes | ❌ Requires Cloud Uplink | ❌ Asymmetric (1-hop to cloud) | None | Minutes to Hours |
| **Pathway 2: Captive Web Portal (PWA)** | ✅ Yes | ✅ Yes (100% Offline) | ✅ Yes (While browser open) | Connect to Wi-Fi | Real-time (< 100 ms) |
| **Pathway 3: SSID Beacon Broadcast** | ✅ Yes | ✅ Yes (100% Offline) | ❌ Unidirectional Broadcast | Open Wi-Fi Menu | Real-time |
| **Pathway 4: Passive RF Crowd Sensing** | ✅ Yes | ✅ Yes (100% Offline) | N/A (Sensing Only) | None | Real-time |
| **Pathway 5: Viral Local APK Share** | ❌ Needs Install | ✅ Yes (100% Offline) | ✅ Full Multi-hop BLE Mesh | Tap Install | ~30 seconds setup |

---

## 5. Recommended Engineering Roadmap for KHOJ

To give KHOJ the maximum possible capability with uninstalled bystander phones, the following multi-layer architecture should be integrated:

1. **Layer 1: Find My Emergency Beacon (Escape the Dead Zone)**
   - When SOS is activated or a node is separated from the mesh, emit an Apple Find My compatible advertising frame (`0x004C`) and Google Fast Pair frame. Bystanders carry the SOS signal out of the dead zone to the cloud.
2. **Layer 2: Local Wi-Fi Captive Portal & WebRTC Bridge (Zero-Install Interaction)**
   - Add a lightweight embedded HTTP/WebSocket server in `app` (or on dedicated ESP32 mesh anchors) that hosts the KHOJ Web Portal and APK download page.
3. **Layer 3: Dynamic Emergency SSID Broadcaster (Visual Public Alert)**
   - When a critical SOS or Stampede alert is active, configure local Wi-Fi AP SSID to display the alert text for bystanders scanning networks.
4. **Layer 4: Passive Bystander Probe Counter (Crowd Density Intelligence)**
   - Integrate an ambient BLE/Wi-Fi packet density estimator to feed the Link Predictor and Stampede Early Warning algorithms.
