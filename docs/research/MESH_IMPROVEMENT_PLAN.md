# KHOJ mesh improvement plan

How to turn the two research reports in this folder into a faster, stronger, more accurate mesh, one phase at a time.

Written 2026-10-10. Nothing in this plan has been implemented yet.

---

## 0. How to use this document

You can start with zero context. Read sections 1 to 6 once (about 20 minutes), then work through the phases in order. Each phase includes:
- the goal and the research that supports it;
- the files to read first;
- the steps;
- the tests to write;
- the phone test;
- the exit criteria that must pass before the next phase starts.

Rules for working through it:
1. **One phase at a time.** Do not start a phase until the previous phase's exit criteria pass, unless the phase says it can run in parallel.
2. **Measure before and after every change, on real phones.** Record every number in [BENCH_RESULTS.md](BENCH_RESULTS.md) (create it in Phase 0). A change without a measured before/after number is not done.
3. **Stop at decision gates.** Phases 2, 3 and 7 end in a decision. Write the decision and its evidence in [DECISIONS.md](DECISIONS.md) and show it to the project owner before continuing.
4. **Never invent a number.** If something was not measured, write "not measured". If a claim comes from a report and was not checked, write "UNCONFIRMED (report A/B)".
5. **Keep every new behaviour switchable** (a `MeshConfig` field or a debug flag) until its phase's phone test passes, so old and new can be compared on the same phones.

---

## 1. What KHOJ is

KHOJ (engineering name LinkFill) is an Android app that keeps families and groups connected in crowds (fairs, stations, stadiums, protests, pilgrimages) with **no mobile network and no extra hardware**. Phones talk to each other directly and relay for each other.

- **Repo:** `C:\Users\Aadityaa\linkfill`, remote https://github.com/aadityaa0523/KHOJ (branch `master`).
- **Modules:**
  - `core/` is pure Kotlin/JVM: the mesh protocol, routing, crypto, Find maths and the simulator. It is unit-tested and has no Android dependencies.
  - `app/` is the Android app (Jetpack Compose).
  - `ios/KhojMesh/` is a Swift port of the wire format. It has not been compiled on a Mac yet.
  - `training/` holds the Link AI training script and data.
  - `docs/` holds the documentation.
- **Radios, in order of importance:**
  1. **BLE extended advertising** (connectionless broadcast; every phone in range hears every frame). This is the backbone.
  2. **GATT bearer.** Older phones without extended advertising, and iPhones, connect to a phone's GATT "door" service.
  3. **Google Nearby Connections.** P2P_CLUSTER for the mesh; a point-to-point pair link, which can upgrade to Wi-Fi Direct, for Find.
  4. **Internet bridge**, when any phone has data.
- **Main features that use the mesh:**
  - Group beacons: positions every 3 s, and alerts when someone leaves the circle.
  - SOS: siren, torch blinking Morse SOS, and a message with location, repeated until acknowledged.
  - Find Member: an arrow that points at a crew member, AirTag-style.
  - Public messages: found child, public SOS, signed official alerts.
  - Store-carry-forward healing.
  - Low-power members.
  - Link AI: a logistic regression that predicts link loss and runs on the Qualcomm NPU via LiteRT + QNN.

Background reading, only if needed: [docs/MESH.md](../MESH.md), [docs/ARCHITECTURE.md](../ARCHITECTURE.md), [docs/DEVICE_PROFILE_IQOO.md](../DEVICE_PROFILE_IQOO.md), [docs/GATT_BEARER.md](../GATT_BEARER.md), [README.md](../../README.md).

---

## 2. The problem, in numbers

- Two iQOO I2501 phones (Snapdragon SM8850, Android 16, Bluetooth 6.0), 1 m apart, catch only about **30-60% of each other's changing frames** at -40 to -65 dBm. Signal strength is not the problem.
- Turning Wi-Fi off, turning a third phone off, and scanning on 1M only did **not** change it.
- Nearby discovery makes it worse.
- Reception is asymmetric (A hears B better than B hears A).
- This "30-60%" is **delivery per changing logical frame** as seen by the app. It is not the same as per-advertising-event packet reception (report A, "Scope and findings").

Everything else (crowd scale, healing, Find accuracy) sits on top of this. **Fix and understand the single-hop link first.**

---

## 3. What the research says

The two reports are [MESH_RESEARCH_A_hci_diagnosis.md](MESH_RESEARCH_A_hci_diagnosis.md) (A) and [MESH_RESEARCH_B_crowd_scale_roadmap.md](MESH_RESEARCH_B_crowd_scale_roadmap.md) (B). Neither has been verified. A is more careful and marks its uncertain claims; B states several guesses as "confirmed". **Where they disagree, this plan follows A by default and tests B's variant when that is cheap.**

### 3.1 Where both agree (high confidence these are worth doing)

| # | Change | A ref | B ref |
|---|---|---|---|
| 1 | Stop replacing the advertising data every 250 ms; hold each logical frame still for about 1 s | Ranked 1 | Ranked 1 |
| 2 | Stop running LE 1M and LE Coded sets all the time; use 1M, with Coded only as a short urgent fallback | Ranked 3, B "Dual-set verdict" | Ranked 2 |
| 3 | One long-lived scan; never restart more than 5 times in 30 s | I "Android scanning" | Ranked 7 |
| 4 | Counter-based relay suppression with density-adaptive TTL and relay rate | Ranked 4, C | Ranked 3, C |
| 5 | Replace blind "re-offer 8 packets" with a Bloom-filter summary exchange | Ranked 6, D | Ranked 6, D |
| 6 | Explicit unique AES-GCM nonce, rotating public crew tag instead of the static `crewId` | Ranked 8, G | Ranked 5, G |
| 7 | Link AI: evaluate with grouped splits (by session, device, venue), PR-AUC and calibration; more device and venue data | Ranked 9, E | Ranked 4, E |
| 8 | Position: age-aware uncertainty, short PDR coasts; Channel Sounding only for 1-to-1 Find | Ranked 10-11, F | Ranked 8, 12, F |
| 9 | Foreground service plus OEM battery guidance; screen-off qualification tests | I | Ranked 11, I |
| 10 | A small custom event simulator calibrated on real traces, not stock ns-3 | Ranked 12, H | H |

### 3.2 Where they disagree, and what we do

| Topic | Report A | Report B | Decision for this plan |
|---|---|---|---|
| Cause of the loss | "Most likely" the data updates; must be proven with an experiment | "Confirmed": Qualcomm controller flushes buffers for 15-40 ms per update | **Hypothesis only.** B's Qualcomm details have no checkable source. Phase 2 decides. |
| Relay delay rule | Random slots; RSSI only as a weak tie-breaker | RSSI-weighted, far nodes relay first (similar to what we have now) | A's rule is the default; the current rule stays as a switchable variant; the 3-phone test in Phase 4 decides. |
| NPU Link AI | Measure; CPU may be cheaper for a tiny model | Remove it; claims 5-15 ms and 150-450 mW per inference | B's latency claim contradicts our measurement (0.904 ms per batch of 16 on the HTP). **Measure power in Phase 7, keep the NPU path, and ask the owner before removing anything.** The NPU feature was an explicit owner request. |
| Bloom summary size | 512 bits, 7 hashes, plus count, epoch and digest (about 0.07% false positives for 32 items) | 16 bytes (128 bits) | A's size. 128 bits for 32 items gives about 15% false positives. |
| Duplicate cache | Separate payload, duplicate-ID and tombstone lifetimes; duplicate ID = payload life + 2 min | 2,048-slot ring buffer plus counting Bloom, adaptive 60-300 s | A's lifetimes (correctness). B's structure only if memory is measured as a problem (it will not be at our scale). |
| ACKs | End-to-end ACK, custody indication and tombstones | Passive hop ACK by overhearing, and aggregated end-to-end ACK for SOS | Both treat overhearing as custody. Implement custody-by-overhearing plus tombstones. Defer ACK aggregation. |
| Density tiers | Distinct neighbours in 5 s; tiers 0-9 / 10-49 / 50-199 / 200+ | 10 s window; different numbers | A's table as starting values; tune later with the simulator or field data. |
| Dead-reckoning coast | 20-30 s maximum | 45 s | 30 s maximum. |
| Key ratchet | Epoch keys, MLS later | Daily hash ratchet | Optional in Phase 6; MLS out of scope. |

### 3.3 What we deliberately do NOT do (and why)

| Not doing | Why |
|---|---|
| Reinforcement learning or bandits for retransmission | Report A: confounded data and too few devices. |
| MLS group key agreement | Too heavy for offline crews. |
| Cooperative RSSI localisation shown to users | Both reports: research only. |
| A raw GNSS solver | Too large a project. |
| Wi-Fi RTT | The I2501 has no Wi-Fi RTT ([DEVICE_PROFILE_IQOO.md](../DEVICE_PROFILE_IQOO.md)). |
| A BLE sniffer or any extra hardware | Project rule: phones only. A third phone acts as the independent observer instead. |
| ns-3 or OMNeT++ | Report A: neither models Android extended advertising. |
| Promising iPhone parity | Report A: background iOS relaying is unproven. |

---

## 4. Ground rules (read before touching anything)

These come from the project owner and earlier work. They override anything in the reports.

1. **Only phones.** No extra hardware, no map downloads, no servers required for the mesh to work.
2. **Never fabricate.** Do not invent directions, data, measurements or citations. Label anything unconfirmed.
3. **Do not change phone system settings or grant permissions yourself.** Examples: Developer options, HCI snoop log, battery optimisation, location "all the time". Ask the owner to do it and wait.
4. **Never uninstall the app from a phone** without asking; it wipes the crew keys and data. Always use `adb install -r`.
5. **`secrets/` never goes into git** (it holds the police authority private key). It is already in `.gitignore`; keep it there.
6. **Keep decisions the owner already made:**
   - Find is **arrow-first**: no compass words when a compass exists, no percentage, no "last known location" text.
   - The arrow points by GPS bearing relative to the phone's heading, with an uncertainty wedge; BLE turn and trend modes refine it.
   - SOS = siren + torch blinking + message with location, and works without GPS.
   - Precise finding turns on automatically in range (no toggle).
   - Volunteer and police share one responder role.
   - Wi-Fi Direct is used for Find only.
   - Link AI runs on the NPU via QNN.
7. **Wire format changes must stay in sync with iOS.** If you change `MeshPacket`, `MeshMessage`, `CrewKey` or `GattFraming`, you must also:
   - regenerate `docs/interop/khoj_test_vectors.json` (run `InteropVectorsTest`);
   - update the Swift port in `ios/KhojMesh/`;
   - update [docs/MAC_SETUP_IOS.md](../MAC_SETUP_IOS.md);
   - bump `MeshPacket.VERSION` when old phones would misread new frames.
8. **All three test phones must run the same build** during any multi-phone test. Mixed versions make results meaningless.
9. **`./gradlew :core:test` must stay green** after every change (116 tests at the time of writing).
10. **Commits:** small, one per logical step, on a feature branch, with messages that say what changed and what was measured. Ask the owner before pushing to GitHub.

---

## 5. Environment setup (Windows PC)

```bash
# Git Bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/Aadityaa/linkfill
./gradlew :core:test                 # JVM tests, about 1 min
./gradlew :app:assembleDebug         # APK at app/build/outputs/apk/debug/app-debug.apk (~110 MB)
adb devices -l                       # list phones
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> logcat -s KHOJ       # app log (capability line printed at mesh start)
MSYS_NO_PATHCONV=1 adb -s <serial> pull /sdcard/Android/data/app.linkfill/files/link_log.csv .
```

Notes:
- In Git Bash, prefix commands that pass phone paths with `MSYS_NO_PATHCONV=1`. Otherwise the path is rewritten into a Windows path.
- USB connections drop often. Re-run `adb devices` before each step.
- **Phones:**
  - Phone A is `10BFAX1BU80010U` (iQOO I2501).
  - Phone B is a second I2501 (the owner's friend's phone).
  - Phone C is a third phone in family mode.
  - Check `adb devices -l` for the current serials and ask the owner to connect and approve any phone that shows `unauthorized`.
- The debug build has a test activity, `app/src/debug/java/app/linkfill/FindPreviewActivity.kt`, that takes `--ez`/`--es` intent extras. Use the same pattern for the bench mode in Phase 1.
- Python 3 and TensorFlow are installed (used by `training/train_link_ai.py`).

---

## 6. Map of the code you will touch, with today's values

| File | Role | Current behaviour the research targets |
|---|---|---|
| `app/src/main/java/app/linkfill/mesh/BleMeshRadio.kt` | BLE advertise + scan | **Two** extended non-connectable sets (LE 1M and LE Coded) when Coded is supported, both at `INTERVAL_LOW` (100 ms) and `TX_POWER_MAX` (+1 dBm). `send()` calls `setAdvertisingData()` on **every** set for **every** frame. One scan with service-data filter + GATT door filter, `ALL_MATCHES`, report delay not set explicitly (default 0). `setLowLatencyScan()` restarts the scan, at most once per 30 s. |
| `app/src/main/java/app/linkfill/mesh/MeshController.kt` (loop at about line 470) | Drives everything | A 250 ms loop. Each tick calls `r.nextFrame(now, allowFiller = n % 2 == 0)`, so real packets go out every 250 ms and idle NEIGHBOR filler every 500 ms. That is **up to 8 `setAdvertisingData` calls per second** across the two sets. Beacons every 3 s (15 s for low-power members). `transmit()` sends on BLE always, Nearby and others when chosen. `applyTransports()` runs every 5 s. |
| `core/src/main/kotlin/app/linkfill/core/mesh/MeshRouter.kt` | Managed flooding, carry-forward, link mode | Defaults in `MeshConfig`: TTL 6, SOS/RALLY resend every **10 s** up to 6 times, relay delay = 40 ms + RSSI × 400 ms (**strong signal waits longer, so far phones relay first**) + 0-250 ms jitter, suppress after **2** other relays, duplicate memory **120 s**, packet max age 600 s, queue 64, carry 32 urgent packets, offer up to 8 to a phone unheard for 60 s. `LinkMode` NORMAL / MEDIUM (2 copies) / HIGH (3 copies + Coded) from median neighbour risk. |
| `core/src/main/kotlin/app/linkfill/core/mesh/MeshPacket.kt` | Wire format | 29-byte header: magic, version 1, type, flags, ttl, hops, linkSeq(2), relayId(4), **crewId(4, in clear)**, originId(4), packetId(4), originTime(4), len. Max 200 bytes. AAD = type, crewId, originId, packetId, originTime. |
| `core/src/main/kotlin/app/linkfill/core/mesh/CrewKey.kt` | Crew crypto | One static AES-256-GCM key per crew, derived by HKDF from an 80-bit 16-character code. Nonce = originId \| packetId \| crewId. The node id is random per mesh start and packetSeq starts random, so nonces are *probably* unique, but nothing guarantees it. 16-byte tag. Separate tunnel key with random nonces. |
| `core/src/main/kotlin/app/linkfill/core/mesh/LinkTracker.kt` | Per-neighbour link stats | PDR from `linkSeq` gaps. Features `LinkFeatures.ALL` (13 columns) feed the Link AI. **PDR assumes one linkSeq per 250/500 ms frame**, so changing the frame dwell (Phase 3) changes what these features mean. |
| `app/src/main/java/app/linkfill/mesh/LinkAi.kt`, `core/.../mesh/LossPredictor.kt`, `training/train_link_ai.py` | Link AI | Logistic regression; label `lost_5s`. Trained on 35k rows from 2 phones in 1 room; held-out-by-session ROC-AUC 0.75. LiteRT 1.4.2 + QNN delegate on HTP, CPU fallback. Assets: `app/src/main/assets/link_ai_v1.tflite`, `.json`; CPU model `core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt`. |
| `app/src/main/java/app/linkfill/mesh/GattBearer.kt`, `core/.../mesh/GattFraming.kt` | GATT bearer for old phones and iPhones | Every real (non-filler) frame passed to `BleMeshRadio.send` also goes to GATT links. |
| `app/src/main/java/app/linkfill/mesh/NearbyMeshTransport.kt`, `NearbyPairLink.kt` | Nearby | Discovery runs only when not connected. 8034 permission errors are retried every 10 s. The pair link is used for Find. |
| `core/src/main/kotlin/app/linkfill/core/find/*` and `app/.../find/FindMemberSession.kt` | Find Member | `MeshRouter.rssiObserver` receives **every** BLE frame (including repeats) before duplicate filtering, so Find's RSSI sample rate depends on how many frames arrive. `Predict.kt`: velocity capped at 1.8 m/s, coasts 30 s, uncertainty +1 m/s. `SpinFinder`: turn-to-find. |
| `app/.../mesh/MeshController.kt` `checkLostContact` + `core/.../find/Circle.kt` | Group safety | "Left the circle" and lost-contact alerts depend on **beacon freshness**. If Phase 4 slows beacons, these timeouts must scale with the beacon period. |
| `core/src/main/kotlin/app/linkfill/core/sim/*` | Simulator | `RadioModel`, `MeshRangeExperiment` (`./gradlew :core:runMeshRange`), `LinkSimulator`. Not calibrated against real traces. |
| `core/src/test/kotlin/app/linkfill/core/*` | Tests | `MeshTest`, `GroupSafetyTest`, `FindTest`, `SpinFinderTest`, `InteropVectorsTest` and others. |
| `app/src/main/AndroidManifest.xml` | Permissions and service | `MeshService` is a foreground service of type `location\|connectedDevice\|camera`. `ACCESS_BACKGROUND_LOCATION` is declared. |

Official alerts are **already** signed with an authority key (`PublicMessages.kt`, `Official.verify`). Keep that.

---

## 7. Phases

Overview and dependencies:

```
P0 Baseline ─► P1 Instrument ─► P2 Diagnose ─► P3 Radio fix ─► P4 Crowd routing ─► P5 Healing ─► P6 Security
                                    │                                                              │
                                    └──────────► P10 Simulator (parallel, optional) ◄──────────────┘
                                                 P7 Link AI   (after P3; needs field data)
                                                 P8 Find accuracy (after P3; independent of P4-P6)
                                                 P9 Background survival (after P3; independent)
```

Rough effort: P0 0.5 day · P1 1-2 days · P2 2-3 days · P3 2-3 days · P4 3-4 days · P5 3 days · P6 3-4 days · P7 3-5 days · P8 3-4 days · P9 2 days + overnight runs · P10 3-5 days.

---

### Phase 0: Baseline and safety net

**Goal:** a clean starting point that can be rolled back, plus today's numbers.

**Steps**
1. Run `git status`. If there are uncommitted changes, **ask the owner** whether to commit them as a baseline commit on `master` first. Do not discard anything.
2. Create the branch `mesh-improvements` from that point.
3. Build, run `./gradlew :core:test` and write down the pass count.
4. Install the build on all three phones with `adb install -r`.
5. Create the two working files (keep them up to date in every phase):
   - `docs/research/BENCH_RESULTS.md`: one table per experiment, with columns date, build (git hash), phones (serial, role), setup, run length, metric, value, notes.
   - `docs/research/DECISIONS.md`: one entry per decision: question, options, evidence, decision, who approved.
6. **Baseline measurement with the current app:** two phones 1 m apart on non-metal stands, same height, screen on, mesh running, 4 minutes. Record:
   - per-neighbour PDR from the app's diagnostics screen and `link_log.csv` (`pdr_2s`, `pdr_10s`);
   - SOS delivery time (send SOS on A, note when B shows it);
   - Find arrow behaviour (does it point and update).

   Swap roles and repeat.

**Exit criteria:** branch exists; tests green; baseline numbers in BENCH_RESULTS.md.

---

### Phase 1: Instrumentation and bench mode

**Goal:** turn "it loses frames" into numbers that separate the possible causes. No behaviour changes for normal users.

**Research:** A "Android scan semantics", A "Instrumentation fields", A ranked 2; B "Instrumentation and Telemetry Capture".

**Read first:** `BleMeshRadio.kt` (whole), `MeshController.kt` loop and `transmit()`, `FindPreviewActivity.kt` (the debug-extras pattern).

**Steps**
1. **Radio log** (`app/src/main/java/app/linkfill/mesh/RadioLog.kt`). Write one CSV row per event to `files/radio_log.csv`, buffered and flushed every second (no file write inside Bluetooth callbacks). Events:
   - `TX_UPDATE`: elapsedRealtimeNanos, set PHY, payload seq, payload length, payload hash (first 8 hex of SHA-256).
   - `TX_DONE`: `onAdvertisingDataSet` time and status (match it to its `TX_UPDATE`).
   - `RX`: `ScanResult.timestampNanos`, callback-arrival elapsedRealtimeNanos, RSSI, primary/secondary PHY, advertising SID, `dataStatus`, TX power, payload hash, relayId, linkSeq.
   - `SCAN_START`, `SCAN_STOP`, `SCAN_FAIL` (with error code).
   - `STATE` every 5 s: Nearby state (off / advertising / discovering / connected), GATT link count, screen on/off, battery saver, thermal status, battery %, number of active advertising sets.
   - Header row: phone model, Android build, app git hash, run id.

   In normal use the log is **off**. Turn it on via the bench mode below or a diagnostics switch.
2. **Make the radio configurable** without changing defaults. Extend `BleMeshRadio.start(...)` with optional parameters: which PHYs to advertise on (`1M`, `1M+Coded`, `Coded`), advertise on/off, scan on/off. Set `setReportDelay(0)` explicitly in `scanSettings()`.
3. **Bench mode (debug build only)**, in `app/src/debug/`, like `FindPreviewActivity`:
   - `BenchActivity` drives `BleMeshRadio` **directly, without the router**, so only the radio is measured.
   - Extras:
     - `role=tx|rx|both`;
     - `sets=1m|1m_coded|coded`;
     - `updateMs=0|2000|1000|500|250|100` (0 = fixed payload);
     - `bytes=200|31` (payload size);
     - `seconds=240`;
     - `legacy=true|false` (a legacy 31-byte advertisement for comparison, where supported);
     - `nearby=off|discover|connected`;
     - `gatt=off|idle`.
   - Payload = 4-byte sequence number + padding, inside the normal KHOJ service data, so the existing scan filter matches it.
   - Example: `adb shell am start -n app.linkfill/.BenchActivity --es role tx --es sets 1m --ei updateMs 0 --ei seconds 240`.
4. **Analysis script** (`tools/bench/analyze.py`):
   - Input: the TX phone's and the RX phone's `radio_log.csv`. Clocks differ between phones, so match by payload seq and hash, never by wall clock.
   - Output:
     - **logical-frame delivery** = distinct seqs received / seqs sent;
     - **delivery within 1 s** (offset estimated as the minimum over seqs of RX-first minus TX-update, which treats the fastest one-way delay as about zero; state this assumption in the output);
     - **events heard per second** for fixed payloads, compared with about 9.5 expected at a 100 ms interval;
     - inter-arrival gap distribution (median, p95, max) and gaps over 2 s;
     - TX update-to-done latency (median, p95, max);
     - payload versions that were never received;
     - callback delay (arrival minus `timestampNanos`);
     - share of incomplete or truncated `dataStatus`.
   - Include a `--selftest` that builds a synthetic pair of logs with known loss and asserts the metrics.
5. **HCI snoop log (optional, owner action):**
   - Ask the owner to turn on *Developer options → Enable Bluetooth HCI snoop log* on both phones and toggle Bluetooth. Do not do it yourself.
   - After a run: `adb bugreport bench.zip`, then extract `FS/data/misc/bluetooth/logs/btsnoop_hci.log`.
   - If Wireshark/tshark is installed, filter:
     - `HCI_LE_Set_Extended_Advertising_Data` (opcode 0x2037);
     - `HCI_LE_Set_Extended_Advertising_Enable` (0x2039);
     - `HCI_LE_Set_Extended_Scan_Enable` (0x2042);
     - LE Extended Advertising Report (LE Meta subevent 0x0D);
     - Command Complete/Status errors.
   - If no Wireshark, skip HCI and rely on the app logs. Note that in BENCH_RESULTS.md.

**Tests:** `analyze.py --selftest` passes; `./gradlew :core:test` still green; the normal app behaves exactly as before with the log off.

**Exit criteria:** one 4-minute TX-only/RX-only run produces matched logs; `analyze.py` prints every metric; at least 99% of `TX_UPDATE` rows have a matching `TX_DONE`.

---

### Phase 2: Find the cause (decision gate)

**Goal:** know *why* frames are lost before changing the protocol.

**Research:** A "The separating experiment" and "First-week experiments" (days 1-5); B "Root-Cause Diagnostic Experiment Plan".

**Physical setup (every run):**
- Phones on non-metal stands, 1 m apart, same height, fixed orientation, marked positions, not hand-held.
- Screen on, not charging.
- Each run is 4 minutes: 30 s warm-up, 3 min measured, 30 s cool-down.
- Repeat every critical run with roles swapped, without moving the stands.
- Reboot and let the phones cool before the final repeat.

**Runs** (bench mode; phone C is an extra RX-only observer where marked):

| Run | A | B | Sets | Updates | Isolates |
|---|---|---|---|---|---|
| R1 | TX only | RX only | 1M | fixed | Clean baseline |
| R2 | RX only | TX only | 1M | fixed | Handset asymmetry (compare with R1) |
| R3 | both | both | 1M each | fixed | Own advertising blinding own scan |
| R4 | both | both | 1M + Coded | fixed | Second set / Coded airtime |
| R5-R9 | TX only | RX only | 1M | 2000, 1000, 500, 250, 100 ms | **Update cadence (main suspect)** |
| R10 | both | both | 1M + Coded | 250 ms | Today's app configuration, for reference |
| R11 | TX only | RX only | 1M | fixed, 31-byte extended vs 200-byte extended vs legacy | AUX-follow / payload size |
| R12 | both | both + Nearby discovering | 1M | fixed | Nearby inquiry contention |
| R13 | both | both + Nearby connected, not discovering | 1M | fixed | Whether stopping discovery helps |
| R14 | both | both + 1 idle GATT link | 1M | fixed | Connection-event scheduling |
| R15 | TX only | RX only, screen locked | 1M | fixed | Screen-off throttling |
| R16 | as R3 and R10 | as R3 and R10 | | | **Phone C, RX only, 1 m from both**: if C hears frames that B missed, B's receiver missed them; if C also missed them, the transmitter did not send them. This replaces the sniffer report A asks for. |

**How to read the results** (from report A):

| If you see | It means |
|---|---|
| Gaps start right after update commands; delivery falls as updates speed up | The data updates are the problem |
| R1/R2 good, R3 bad | Own advertising and scanning share the radio (time-sharing) |
| R3 good, R4 bad | The second set or Coded airtime hurts |
| HCI reports present but app callbacks missing | Android filtering or batching |
| More incomplete `dataStatus` with 200 bytes or the second set | AUX-follow failures |
| Asymmetry follows the handset when roles swap | Antenna or controller difference (accept, do not chase) |
| Asymmetry follows the role | A configuration difference (fix it) |
| R12 bad, R13 fine | Keep the current "stop discovery once connected" policy |

**Deliverable:** a "Phase 2" section in BENCH_RESULTS.md with every run's numbers, plus a DECISIONS.md entry naming the confirmed causes and the chosen radio configuration (sets, dwell time, payload size).

**Decision gate. Stop and report to the owner if either is true:**
- **R1 (fixed payload, one 1M set, TX-only/RX-only) is still about 30-60%.** Then the loss is not caused by our update pattern. Changing routing would not help. Next options to discuss: a non-iQOO phone as RX, a legacy advertising path, or a different interval. Do not continue to Phase 3 without the owner's decision.
- The results contradict both reports in a way this plan did not foresee.

**Exit criteria:** every run done in both directions; causes ranked with evidence; owner has seen the summary.

---

### Phase 3: Radio fix

**Goal:** single-hop logical-frame delivery above 90% at 1 m in both directions, with the real app (not just bench mode).

**Research:** A ranked 1, 3; B ranked 1, 2, 7. **The exact values (dwell, sets) come from the Phase 2 decision.** The defaults below assume Phase 2 confirms the update-cadence and dual-set hypotheses.

**Read first:** `BleMeshRadio.kt`, the `MeshController.kt` loop, `MeshRouter.nextFrame`, `LinkTracker.kt`, `FindMemberSession.kt` (how it consumes RSSI).

**Steps**
1. **One 1M set by default.** Do not start the Coded set at mesh start.
   - Coded becomes a **burst**: start the Coded set only when all three hold: an urgent packet is pending, 1M health is poor (for example `pdr_2s` below 0.5 for every neighbour, or no neighbour heard for 10 s), and local density is low (fewer than 10 neighbours).
   - Stop the burst after at most 10 s.
   - Wire this to `LinkMode.HIGH.coded` in place of the always-on set.
   - Measure whether starting or stopping the set itself causes a gap (report A warns it might).
2. **Frame dwell.**
   - The controller asks the router for a new frame only when the current frame has been on air for `dwellMs`. Default 1000 ms, or the Phase 2 value; make it a `MeshConfig` field.
   - **Exception:** a priority-0 frame (SOS, ACK, PUBLIC_SOS, FOUND, OFFICIAL) may replace the current frame early, but never more often than once per 250 ms.
   - Add a small pure method to `MeshRouter` (for example `hasUrgentDue(now)`) so the decision stays testable in `core`.
3. **No idle churn.** The NEIGHBOR filler is only sent once the current frame's dwell has ended and nothing else is due. Each new filler is one data update, so at most 1 update/s when idle.
4. **Low-power members** (`setSlowAdvertising`, about 1 s interval) need a longer dwell (3-5 s), so each frame still gets several advertising events.
5. **LinkTracker semantics.** With dwell, one `linkSeq` lasts about 1 s and repeats are dropped as `repeatedFrames`.
   - Re-define PDR windows so they count expected frames for the new dwell (for example `pdr_0_5s` becomes meaningless; use 2 s, 5 s and 10 s windows).
   - **This invalidates the current Link AI model.** Until Phase 7 retrains it, set `riskModel` and `predictor` aside: use the built-in fallback `1 - pdr(2 s)`. Keep the NPU code; just do not feed it out-of-distribution features.
   - Write this in DECISIONS.md.
6. **Find must not regress.** `rssiObserver` sees every received copy, including repeats, so the RSSI sample rate should stay the same or rise. Verify on phones:
   - the arrow appears and updates;
   - turn-to-find works;
   - precise range activates within about 40 m;
   - the "is looking for you" banner still works.
7. **Scanning:** keep one long-lived scan. Keep the existing ≤1 restart per 30 s rule for low-latency/balanced switching. Log every `onScanFailed`.
8. **GATT:** confirm each logical frame is sent over GATT links once, not once per dwell tick.

**Tests**
- New `MeshTest` cases: a dwell is respected; an urgent frame pre-empts a dwell but at most once per 250 ms; filler is never sent while a real frame is due.
- `LinkTracker` PDR is correct for the new dwell.
- All existing tests are updated, not deleted.

**Phone test** (real app, all three phones on the same build, radio log on):
- R3-equivalent with the full app: logical delivery of BEACON and NEIGHBOR frames in both directions.
- SOS at 1 m: time from press to the other phone showing it, 10 tries.
- Find walk test: 30 m away, then walk in.
- Repeat with screen locked (5 min).

**Exit criteria (report A, "Day 7 decision gate"):**
- median single-hop logical-frame delivery above 90%, and above 80% in the worse direction;
- no unexplained gaps over 2 s;
- SOS shows on the other phone within 2 s in at least 9 of 10 tries at 1 m;
- Find arrow works as before.

Record before/after in BENCH_RESULTS.md. Owner sign-off before Phase 4.

---

### Phase 4: Crowd routing

**Goal:** the mesh stays fast when 50-500 phones are around, instead of every phone relaying every packet.

**Research:** A section C (protocol comparison, Trickle placement, why far-first RSSI is weak, density estimator, starting density policy), A ranked 4, 5, 7; B section C and ranked 3.

**Read first:** `MeshRouter.kt` (whole), `MeshPacket.kt`, `MeshTest.kt`, `GroupSafetyTest.kt`, `MeshController.checkLostContact`, `core/.../sim/MeshRangeExperiment.kt`.

Most of this phase is pure `core` code and can be unit-tested and simulated before any phone test.

**Steps**
1. **Density estimate.** Add `LinkTracker.density(now)` = distinct relayIds heard in the last 5 s. Start with this raw count; do not claim an absolute crowd size.
2. **Density policy** (starting values from report A; they are test parameters, not proven optima). Put them in one function of `MeshConfig`:

   | Neighbours (5 s) | Routine TTL | Urgent TTL | Relay probability (non-urgent) | Suppress after | Beacon period |
   |---|---|---|---|---|---|
   | 0-9 | 4 | 6 | 1.0 | 2 | 3 s |
   | 10-49 | 3 | 5 | 0.7 | 3 | 6 s |
   | 50-199 | 2 | 4 | min(0.4, 8/N) | 4 | 12 s |
   | 200+ | 2 | 3 | min(0.1, 8/N) | 5 | 20 s |

3. **Relay delay rule.** Add `relayRule` to `MeshConfig`:
   - `RANDOM_SLOT` (new default, report A): delay = base + random slot, with RSSI as a small tie-breaker (at most ±20% of the slot).
   - `RSSI_FAR_FIRST`: today's rule, which report B prefers.

   Keep both; the phone test picks one.
4. **Urgent traffic is never dropped by the probability gate.** It always schedules a relay; counter suppression still applies. Non-urgent relays pass the relay-probability gate first.
5. **Traffic classes**, in priority order:
   1. SOS, found, cancellation;
   2. ACKs and tombstones;
   3. official alerts;
   4. positions;
   5. link filler and diagnostics.

   While any urgent packet is pending: no filler, and the beacon period doubles.
6. **Trickle-like beacons.**
   - The beacon period grows from 3 s up to the density cap while the position has not moved more than its accuracy.
   - It resets to 3 s on movement, on a new neighbour, or when someone starts finding this person (the LOOKING message).
   - **Coupling:** group-safety timeouts (lost contact, left the circle) must scale with the current beacon period of the *sender*. Carry the period in the beacon, or make timeouts at least 3× the density-tier cap. Update `GroupSafetyTest`.
7. **Faster urgent retries.** Replace "every 10 s, 6 times" with jittered exponential resends at 1, 2, 4, 8, 16 and 32 s (±20% jitter), stopping on ACK. In dense tiers, once custody is seen (Phase 5), stretch to 2, 5, 10, 20 and 40 s.
8. **Duplicate memory.** Urgent packet keys must be remembered for their whole life plus 2 minutes (`maxAgeS` + 120 s), not 120 s. Check `longSeen` already covers all `CARRY_TYPES`; extend it if not.

**Tests (JVM)**
- Unit tests for each rule: the density tier picks the right values; urgent bypasses the probability gate; suppression thresholds; retry timings with jitter bounds; beacons slow down and reset; lost-contact alerts do not fire falsely when beacons slow down.
- **Simulation comparison:** extend `MeshRangeExperiment` (or add `CrowdRoutingExperiment`) to compare old and new rules at 5, 20, 100 and 500 phones. Measure delivery, median and p95 latency, transmissions per delivered packet, and duplicates.
- The simulator is not calibrated until Phase 10. **Label these results "uncalibrated simulation, directional only"** in BENCH_RESULTS.md.

**Phone test (three phones in a line, A-B-C):**
- Place C where it cannot hear A directly: separate rooms, a long corridor or distance. Confirm with the radio log that C hears A's frames under 10% of the time. Never drop frames in software to fake this.
- Send 50 SOS from A under `RSSI_FAR_FIRST` and under `RANDOM_SLOT`.
- Measure: A→C delivery, median and p95 latency, transmissions per delivered packet, and duplicates at C.

**Exit criteria:**
- delivery is equal or better than Phase 3;
- fewer transmissions per delivered packet;
- SOS p95 under 2 s over two hops, if the line topology was achievable (if not, write that down);
- group-safety alerts are still correct on phones;
- all tests green.

---

### Phase 5: Healing and delivery guarantees

**Goal:** a person who walks out of range and back gets missed urgent messages exactly once, without bursts of re-sends, and acknowledged emergencies never come back.

**Research:** A section D (store-carry-forward design, cache and duplicate lifetime, ACK design), A ranked 6, 7; B section D and ranked 6, 10.

**Read first:** `MeshRouter.offerCarried`, `carry`, `tick`, `deliver`; `MeshPacket`; `InteropVectorsTest`; `ios/KhojMesh/Sources`.

**Steps**
1. **New `MeshType.SUMMARY` (14), unencrypted** (packet keys are already visible in headers). Body:
   - cache epoch (2 B);
   - count (1 B);
   - 512-bit Bloom filter (64 B, k = 7, over the 8-byte packet keys);
   - 16-byte digest (SHA-256 of the sorted keys, truncated);
   - oldest and newest origin time.

   About 90 bytes.
2. **When to send it:** in place of the blind 8-packet offer, when a phone unheard for 60 s appears; and Trickle-style every 30-120 s while carrying anything. Low-power friend polls keep working (summary first, then missing items).
3. **On receiving a SUMMARY:**
   - Offer every carried packet whose key is *not* in the Bloom filter, using the existing relay path, so other carriers still suppress each other.
   - If the digest differs but the Bloom filter shows nothing missing (a false positive), send an `exact` SUMMARY variant listing the 4-byte truncated keys of every carried packet (32 × 4 = 128 B, fits in one frame). The other side then requests or offers the difference.
4. **Tombstones.**
   - When an ACK for a packet is seen, or a cancellation, keep a tombstone for that key for `maxAgeS` + 5 min.
   - Stop carrying and offering that packet, and never accept or relay it again.
   - ACKs are already carried; keep them carried.
5. **Custody by overhearing** (both reports): if a phone overhears a different relay re-transmitting an urgent packet it originated or relayed, it marks custody and switches to the stretched retry schedule from Phase 4. Custody never deletes the payload; only an ACK does.
6. **Defer** aggregated ACK bitmaps (not needed at our group sizes). Write that in DECISIONS.md.
7. **Interop:** the new type is ignored by old decoders (`MeshType.of` returns null), so no version bump is needed. Still regenerate the test vectors and update the Swift enum and decoder plus `MAC_SETUP_IOS.md`.

**Tests (JVM)**
- Report A's test: preload 32 items with 0, 1, 8 and 16 differences. Every missing item is found, and the bytes sent are fewer than a blind offer in every case except near-total divergence.
- Forced Bloom false positive: the digest path finds it.
- Resurrection: after an ACK, a returning phone carrying the old SOS never re-delivers it.
- A low-power friend poll still works.

**Phone test:**
- Phone C walks out of range (confirm with the log) while A sends an SOS and B acknowledges it.
- C returns after 2 minutes. C must show the SOS once, or not at all if the owner prefers "already handled" (ask), and must not re-offer it after the ACK.
- Count frames sent during reconnection, before and after this change.

**Exit criteria:** all tests green; the phone test passes; reconnection bytes are lower than Phase 4; iOS port and vectors updated.

---

### Phase 6: Security and privacy

**Goal:** no possible AES-GCM nonce reuse, and strangers cannot track a crew by a fixed ID in the air.

**Research:** A section G (immediate cryptographic changes, tracking identifiers, replay and flooding), A ranked 8; B section G and ranked 5.

**This is a wire-format change (version 2).** Every phone and the iOS port must update together.

**Read first:** `CrewKey.kt`, `MeshPacket.kt`, `MeshRouter.onFrame`, `SessionCrypto.hkdf`, `InteropVectorsTest`, `ios/KhojMesh`.

**Recommended design** (you may improve it, but the tests below must pass):
1. **Keys:** derive three keys from the crew secret with HKDF and distinct labels:
   - content key (AES-256-GCM);
   - tag key `K_id` (HMAC-SHA256);
   - tunnel key (as today).
2. **Run epoch:** a 32-bit value from `SecureRandom`, generated at every mesh start and carried in the header.
3. **Nonce** (exactly 96 bits) = run epoch (4 B) | originId (4 B) | packetId (4 B). Since `packetId` already starts random and the epoch is fresh per run, a repeat needs both 32-bit values to collide in the same run.
4. **Rotating crew tag** (8 B) replaces the clear `crewId` in the header: `tag = first 8 bytes of HMAC-SHA256(K_id, slot)`, where `slot = floor(unixSeconds / 900)` (15 minutes).
   - Receivers accept slot-1, slot and slot+1, to tolerate clock error.
   - Public messages keep their current crew field rules (0, or a wristband family id); check `PublicMessages`.
5. **AAD** = version, type, crew tag, run epoch, originId, packetId, originTime.
6. **Rate limits before decryption:**
   - a token bucket per relayId (start with 10 frames/s, burst 20) and a global cap;
   - a cap on foreign (other-crew) urgent relays per source per minute;
   - **no priority just because a clear header says SOS** for frames we cannot authenticate (foreign relays get urgent priority only within their cap).
7. **Header v2:** bump `MeshPacket.VERSION` to 2; the decoder rejects v1 frames cleanly. Recompute `HEADER` and check `MAX_SIZE` still fits the longest message.
8. **Known limit to write down (do not fix now):** node ids are random per run but stable during a run, so a long run is linkable by relayId. Rotating node ids would break Find targeting and roster mapping. Leave it for later.
9. **Optional:** a daily key ratchet (`K_day = HKDF(K, date)`). Only if time allows, and only with a written plan for phones that miss a day.

**Tests (JVM)**
- Simulate 10⁶ packets across 1,000 simulated restarts, counter resets and a reinstall with the same crew: zero nonce duplicates.
- Tag matches across slot boundaries (−1, 0, +1) and is rejected two slots away.
- v1 frames are rejected without crashing.
- Forged frame: authentication fails; it is not delivered or relayed.
- The rate limiter drops a flooding source before decryption.
- Regenerate the interop vectors; update the Swift code to match them.

**Phone test:**
- Install on all three phones together.
- Use a debug setting to shorten the slot to 60 s; leave crew messages flowing across 3 slot changes; nothing is lost.
- The radio log shows the tag changing each slot and no `crewId` in clear.

**Exit criteria:** tests green; phone test passes; `ios/KhojMesh` and `MAC_SETUP_IOS.md` updated; DECISIONS.md records the final header layout.

---

### Phase 7: Link AI made honest (decision gate)

**Goal:** a link-loss model whose numbers we can defend for other phones and places, or an honest decision to use the simple rule.

**Research:** A section E (evaluation protocol, label definition, added features, field collection, bandit/RL), A ranked 9; B section E and ranked 4.

**Can start after Phase 3** (the radio behaviour must be final, because features depend on it).

**Read first:**
- `LinkTracker.kt` (`features`, `LinkFeatures`);
- `MeshController.sampleLinks`;
- `training/train_link_ai.py`;
- `LinkAi.kt`, `LossPredictor.kt`;
- [docs/LINK_PREDICTOR_RESULTS.md](../LINK_PREDICTOR_RESULTS.md).

**Steps**
1. **Name the label honestly.** `lost_5s` means "no frame from this neighbour in the next 5 s", which is *availability failure*, not radio failure. Rename it in code comments, docs and the UI text where shown.
2. **Add cheap, high-value features:**
   - age since our last data update;
   - number of active advertising sets;
   - Nearby state (0 off / 1 discovering / 2 connected);
   - GATT link count;
   - screen on;
   - battery saver;
   - thermal status;
   - local density (Phase 4);
   - the neighbour's beacon period, if known.

   Drop or justify `rssi_min_2s` (outlier-sensitive).

   Update together: `LinkFeatures.ALL`, `LinkTracker.features`, `sampleLinks`, `train_link_ai.py`, the tflite export (input width), `LinkAi` input shape, and the `LossPredictor` file format.
3. **Collect data:**
   - All three phones, at least 3 places (a room, a corridor or outdoors, and somewhere with people around), at least 3 sessions per place.
   - Vary how the phone is carried (hand, pocket, bag).
   - Save each pulled file as `training/data/<serial>_<place>_<date>.csv`.
   - **Privacy:** no names, crew ids, message text or raw coordinates in these logs. Check the columns before committing data.
4. **Evaluate properly** (in `train_link_ai.py`):
   - Splits: outer = leave one place out (and separately leave one device out); middle = leave whole sessions out; inner = `GroupKFold` for regularisation.
   - Calibrate on data not used for fitting. Freeze the threshold before looking at the outer test.
   - Report PR-AUC, ROC-AUC, recall at the operating false-alarm rate, precision, Brier score, log loss, a calibration plot, and bootstrap confidence intervals by session.
   - **Baseline to beat:** the rule `risk = 1 - pdr(2 s)`.
5. **NPU vs CPU, measured:**
   - Latency: `LinkAi` already logs it.
   - Power: 30 min screen-off runs, NPU vs CPU, same phone, same place, two runs each. Compare battery % drop and, if possible, `dumpsys batterystats`.
   - Report both. **Do not remove the NPU path without asking the owner**; if CPU is cheaper, offer "CPU by default, NPU optional" as the decision.
6. **No reinforcement learning or bandits** (report A).

**Decision gate:** ship the model only if it beats the rule on the held-out place and device in both PR-AUC and Brier score. Otherwise ship the rule, keep the model code, and record why. Write the results in `docs/LINK_PREDICTOR_RESULTS.md` (a new "real-data, grouped evaluation" section) and DECISIONS.md, and show the owner.

---

### Phase 8: Find accuracy (keep the arrow, make it truer)

**Goal:** the Find arrow's wedge honestly reflects how sure we are, the predicted position does not drift, and close-range finding gets sharper where the hardware allows.

**Research:** A section F (age-aware uncertainty, PDR, BLE RSSI constraints, Channel Sounding, position verdict), A ranked 10, 11; B section F and ranked 8, 12.

**Independent of Phases 4-6; can start after Phase 3.**

**Read first:**
- `core/.../find/FindResolver.kt`, `Predict.kt`, `SpinFinder.kt`, `RssiTracker.kt`;
- `app/.../find/FindMemberSession.kt`, `MotionTracker.kt`, `AndroidRangingProvider.kt`;
- `app/.../ui/FindMember.kt`;
- `FindTest.kt`.

**UI stays as is:** arrow-first, no percentage, no "last known" text.

**Steps**
1. **Age-aware uncertainty:**
   - `Predict.position` returns an uncertainty radius that grows as σ²(t) = σ²_GNSS + t²σ²_v + (vt)²σ²_θ + q·t (report A).
   - The arrow wedge (`arrowUncertaintyDeg`) uses it.
   - When the radius passes 30 m, stop using the predicted point; the arrow falls back to the BLE turn and trend modes or the sweeping "searching" dial.
2. **Motion classes instead of the fixed 1.8 m/s cap.** Use step cadence from `MotionTracker`: still / walking / running, with a velocity limit for each.
3. **Own-phone PDR for short GPS gaps** (30 s maximum): steps × stride from the Weinberg formula or a fixed 0.7 m default, plus heading. Use it only to move *my* position between fixes.
   - Sending the target's steps would need a beacon field (a wire change). Optional, and only after Phase 6.
4. **BLE RSSI stays coarse.** Very near / near / uncertain / far. Never convert it to metres on a map.
5. **Channel Sounding retry:**
   - Both I2501 phones support it, but ranging failed with "peer not found".
   - Investigate whether `RangingManager` CS needs a bonded pair.
   - If it does, offer an explicit, user-consented pairing step inside Find, only when within about 25 m.
   - If it still fails, document the exact error and stop.
   - Wi-Fi RTT is not supported on the I2501; skip it.

**Tests (JVM):**
- The wedge grows with fix age.
- Prediction stops beyond 30 m uncertainty.
- Motion-class limits apply.
- The PDR coast stops at 30 s.
- Existing `FindTest` and `SpinFinderTest` pass.

**Phone test:**
- Walk a measured 100 m route outdoors with phone B as the target.
- Mark true positions at known points and compare them with the displayed arrow and wedge.
- Target: at least 90% of true positions fall inside the displayed wedge.
- If Channel Sounding works: measure at 1, 3, 5, 10 and 20 m, in 4 orientations.

**Exit criteria:** tests green; the walk test meets the 90% target (or the miss is documented with numbers); Channel Sounding result documented either way.

---

### Phase 9: Survive the screen being off

**Goal:** the mesh keeps working in a pocket for hours on Vivo/iQOO and other common Indian phones.

**Research:** A section I (Android scanning, background survival, OEM verdict); B section I and ranked 11. B's per-OEM claims, for example "Vivo disables extended advertising in background", are **UNCONFIRMED**; test them, do not assume them.

**Independent; can start after Phase 3.**

**Steps**
1. Confirm the mesh runs inside `MeshService` as a foreground service (type `connectedDevice|location`) whenever it is on. Confirm scanning keeps its filter (required for screen-off scanning).
2. **Battery guidance screen** (in the Call UI style, `app/.../ui/CallPages.kt`):
   - Detect `batteryUnrestricted()` (it already exists in `MeshController`).
   - If restricted, explain in plain words what to change on Vivo/iQOO (background power consumption, auto-start) and what it costs in battery.
   - Deep-link to the right settings page where an intent exists.
   - **Never request battery-optimisation exemption silently.**
   - New strings go in `values/strings.xml` (English; translations can follow).
3. **Qualification runs** (phone C as the monitor, logging frames heard from the test phone):

   | Condition | Duration |
   |---|---|
   | Screen off, stationary | 1 h |
   | Screen off, walking | 30 min |
   | Battery saver on | 30 min |
   | App swiped from recents | 30 min |
   | Bluetooth toggled off/on | 5 min |
   | Reboot (check auto-start behaviour) | 5 min |
   | Overnight, screen off | 8 h |

   Record the longest gap between beacons heard by the monitor, and battery % used.

**Exit criteria:** an overnight screen-off run on an iQOO phone with no gap longer than 3× the beacon period (or the failure documented with its cause); guidance screen shipped; results table in BENCH_RESULTS.md per phone model.

---

### Phase 10: Calibrated simulator (optional, parallel after Phase 2)

**Goal:** predict how the mesh behaves with 100-5,000 phones, using a model fitted to real phone traces rather than guesses.

**Research:** A section H (event-level PHY model, two simulation levels, validation ladder), A ranked 12; B section H.

**Read first:** `core/src/main/kotlin/app/linkfill/core/sim/RadioModel.kt`, `MeshRangeExperiment.kt`, `LinkSimulator.kt`; `core/build.gradle.kts` (the `runMeshRange` task).

**Steps**
1. **Event-level radio model** in `core/sim`, reusing the existing classes where possible. Model:
   - 3 primary and 37 secondary channels;
   - ADV_EXT_IND → AUX_ADV_IND with AuxPtr delay;
   - airtime per PHY (1M about 1.7 ms, S=8 about 13.5 ms for 200 bytes; report A, approximate);
   - advertising interval plus 0-10 ms random delay;
   - scanner blackout while the phone transmits or applies a data update (**fitted from Phase 2 logs**);
   - capture effect (3-6 dB);
   - body-shadowing variance;
   - per-device asymmetry;
   - Nearby inquiry duty.

   No carrier sense (BLE advertising has none).
2. **Fit** only on Phase 2 training runs. **Validate** on held-out runs, for example R1 + R7 for fitting and R2 + R8 for testing. Simulated delivery and gap distributions must be within 10 percentage points of the held-out measurements (report A's metric). If not, say so and do not use the simulator for decisions.
3. **Abstract level** for 100-5,000 phones: per-link delivery probability looked up from the detailed model by density, RSSI bin, frame airtime and concurrency state.
4. Use it to tune the Phase 4 density table, and write `docs/MESH_SIM_RESULTS.md`, labelled with the validation result.

**Exit criteria:** validation numbers reported honestly; the density table updated only if the simulator passed validation.

---

## 8. Definition of done (whole plan)

| Area | Target | Phase |
|---|---|---|
| Single hop, 1 m | Median logical-frame delivery > 90%, worse direction > 80%, no gap > 2 s | 3 |
| Two hops | ≥ 99% urgent delivery within 5 s on the A-B-C line; SOS p95 < 2 s | 4 |
| Efficiency | Fewer transmissions per delivered urgent packet than today | 4 |
| Healing | Returning phone gets each missed SOS once; no resurrection after ACK | 5 |
| Security | Zero nonce repeats in the restart simulation; no static crew ID on air | 6 |
| Link AI | Grouped-evaluation results published; model shipped only if it beats the rule | 7 |
| Find | ≥ 90% of true positions inside the shown wedge on the walk test | 8 |
| Background | Overnight screen-off run passes on iQOO | 9 |
| Honesty | Every number in BENCH_RESULTS.md has a date, build hash, phones and setup | all |

---

## 9. Glossary

| Term | Meaning |
|---|---|
| Extended advertising | Bluetooth 5 broadcast. A short pointer goes out on channels 37/38/39, and the payload (up to 200+ bytes) follows on one of 37 other channels (the AUX packet). |
| Advertising set | One running broadcast with its own PHY and interval. We run two today (1M and Coded). |
| LE 1M / LE Coded (S=8) | Normal Bluetooth LE speed / slower long-range mode that takes about 8× the airtime. |
| `setAdvertisingData` | The Android call that replaces what a running set broadcasts. |
| Logical-frame delivery | Share of distinct frames (by sequence number) the other phone heard at least once. |
| Dwell | How long one frame stays on air before it is replaced. |
| Managed flooding | Every phone relays each packet once after a short random delay. |
| Counter suppression | Cancel your own relay if you already heard k others relay it. |
| TTL | Hops a packet may still travel. |
| Trickle | Send less often while everyone agrees; speed up when something changes (RFC 6206). |
| Store-carry-forward | Phones keep urgent packets and hand them to phones that arrive later. |
| Bloom filter | A compact bit array that says "definitely not in the set" or "probably in the set". |
| Tombstone | A remembered "this packet is finished, never revive it". |
| Custody | Overhearing someone else relay your packet, so you can slow your own retries. |
| AES-GCM nonce | A 12-byte value that must never repeat under the same key. Repeating it breaks the encryption. |
| HCI snoop log | A phone-side recording of every command between Android and the Bluetooth chip. |
| PR-AUC, Brier score | Model quality measures that work well when failures are rare / that check probabilities are honest. |
| PDR (positioning) | Pedestrian dead reckoning: estimating movement from steps and heading. Not the same as PDR (packet delivery ratio) in the mesh code. |
| Channel Sounding | Bluetooth 6 phase/time ranging, about ±0.5 m typical. Needs supporting hardware and a connection. |
