# KHOJ Architecture & Mesh Decisions

Every decision made at a gate or milestone is recorded here.

Format per entry:
- **Decision ID / Topic**
- **Date**
- **Question**
- **Options considered**
- **Evidence (data and reasoning)**
- **Decision**
- **Approved by**

---

### DEC-000: Baseline Commit on Main Before Branching

- **Date:** 2026-10-10
- **Question:** Should current uncommitted changes across `core/`, `app/`, `training/`, and `docs/research/` be committed as a clean baseline before branching to `mesh-improvements`?
- **Options considered:**
  1. Commit all working tree changes as baseline on `main`, then create `mesh-improvements`.
  2. Keep working tree dirty and branch to `mesh-improvements`.
- **Evidence:** Clean git history allows exact before/after diffs, repeatable builds, and reverting without data loss.
- **Decision:** Commit all current changes to `main` (commit `d76a1e6`) and branch `mesh-improvements` from there.
- **Approved by:** Project Owner (via interactive decision)

---

### DEC-001: Phase 2 Radio Configuration Gate & Root-Cause Diagnosis

- **Date:** 2026-10-10
- **Question:** What are the confirmed causes of the 30–50% BLE single-hop frame loss observed on iQOO I2501 (Android 16), and what radio configuration (PHY sets, frame dwell time, and idle updates) should be adopted in Phase 3?
- **Options considered:**
  1. Retain current architecture (Dual-set 1M + Coded, continuous 250 ms churn).
  2. Adopt 1000 ms frame dwell (`dwellMs = 1000`) on a single 1M advertising set, with priority-0 pre-emption (≥250 ms cap), idle neighbor filler throttled to end of dwell, and LE Coded relegated to emergency burst.
  3. Adopt 500 ms frame dwell on a single 1M set.
- **Evidence:**
  - **Fixed payload delivery is 100.00% (R1, R2, R3)**: Zero frames lost across 240-second runs. Proves RF link, antenna, and Android extended advertising pipeline are completely intact.
  - **Cadence is the primary loss driver**:
    - 1000 ms dwell (R6): **95.83%** delivery (230/240 received, only 10 lost).
    - 500 ms dwell (R7): **82.46%** delivery (395/479 received, 84 lost).
    - 250 ms dwell (R8): **58.87%** delivery (561/953 received, 392 lost!).
    At 250 ms cadence with 100 ms advertising interval, a frame only airs ~2.5 times before being overwritten by `setAdvertisingData`. Scan interval/window misalignment causes 41% of frames to be permanently missed. 1000 ms dwell provides ~10 transmission attempts per frame, elevating delivery to >95%.
  - **LE Coded PHY contracts scan duty by 18.3% (R3 vs R4)**: Running the second Coded set drops event reception rate from 7.14 ev/s to 5.83 ev/s due to the ~3.5 ms packet transmission duration on Coded S=8.
  - **Controller health**: 0 truncated frames, 100% matched `TX_DONE` callbacks with median scheduling delay <15 ms.
- **Decision:** Recommended Option 2:
  1. Single 1M advertising set by default.
  2. Implement `dwellMs = 1000` in `MeshConfig` and `MeshController` / `MeshRouter`.
  3. Urgent priority-0 frames (SOS, ACK, PUBLIC_SOS, FOUND, OFFICIAL) may pre-empt dwell early, capped at once per 250 ms.
  4. Throttle NEIGHBOR filler to end of dwell (at most 1 update/s when idle).
  5. Coded PHY becomes an emergency burst: only triggered when urgent packet is pending, 1M link health is poor (`pdr_2s < 0.5` or no neighbor heard for 10 s), and local density < 10, lasting at most 10 s.
  6. Fallback LinkTracker risk to `1 - pdr(2s)` until Phase 7.
- **Approved by:** Project Owner (Approved on 2026-10-10 at Phase 2 Decision Gate)

---

### DEC-002: Phase 3 Single-Hop Delivery & Dwell Settings

- **Date:** 2026-10-10
- **Question:** Did the Phase 3 radio fixes (1M single set default, 1000 ms frame dwell, 250 ms urgent pre-emption, throttled filler, and emergency Coded burst) satisfy the Phase 3 exit criteria (>90% median single-hop delivery, >80% in worse direction, green test suite), and is the configuration approved for Phase 4?
- **Options considered:**
  1. Approve Phase 3 radio architecture and proceed to Phase 4 (Crowd Routing).
  2. Increase frame dwell further (e.g. 1500 ms or 2000 ms).
  3. Revert to 500 ms dwell.
- **Evidence:**
  - **Live bidirectional benchmark (`P3_BIDIR`, 240 s on real iQOO I2501 phones)**:
    - Forward direction (A -> B, worse receiver sensitivity): **95.83%** logical delivery (230 / 240 received).
    - Reverse direction (B -> A): **96.25%** logical delivery (231 / 240 received).
    - Median single-hop delivery: **96.04%** (exceeds the 90% Phase 3 exit criterion).
    - Worse direction delivery: **95.83%** (exceeds the 80% worse-direction criterion).
    - Delivery within 1.0 s: **95.83%** (A -> B), **96.25%** (B -> A).
    - Inter-arrival gaps > 2 s: 0 in B -> A, 1 in A -> B (2.18 s during initial startup).
  - **Full app live link quality (`link_log.csv`)**:
    - Phone A `pdr_2s` and `pdr_10s`: **90–100%** (up from 55.2% / 50.0% baseline).
    - Phone B `pdr_2s` and `pdr_10s`: **80–100%** (up from 53.7% / 50.0% baseline).
  - **Emergency Coded burst gating**:
    - Verified in live logcat: Coded advertising set activates dynamically when triggered and automatically tears down after 10.0 s.
  - **Link Layer health**: 0 truncated frames, 100% matched `TX_DONE` callbacks.
  - **Unit test suite**: 120/120 tests green in `:core` (including new dwell, urgent preemption, and LinkTracker PDR tests).
- **Decision:** (Recommended Option 1) Approve Phase 3 radio architecture (`dwellMs = 1000L`, 1M single set default, 250 ms urgent pre-emption, dynamic Coded burst, throttled filler, `1 - pdr(2s)` Link AI fallback) and proceed to Phase 4 (Crowd Routing).
- **Approved by:** Project Owner (Approved on 2026-10-10 at Phase 3 Decision Gate)

---

### DEC-002b: Phase 4 Relay Delay Rule & Adaptive Density Policies

- **Date:** 2026-10-10
- **Question:** How should relay delays and traffic suppression be scheduled across dense crowds (50–500 nodes), and which relay rule should be default?
- **Options considered:**
  1. Adopt `RANDOM_SLOT` (base delay + random slot with weak RSSI tie-breaker up to ±20%) as default, while retaining `RSSI_FAR_FIRST` configurable for comparative evaluation.
  2. Retain pure `RSSI_FAR_FIRST` as hardcoded default.
- **Evidence:**
  - In simulation (`CrowdRoutingExperiment`, directional uncalibrated), `RANDOM_SLOT` with adaptive `DensityPolicy` achieves 100% delivery with fewer transmissions (6.17 vs 7.00 tx/delivered at N=5) and lower duplicates than fixed flooding.
  - At N=500, both `RANDOM_SLOT` and `RSSI_FAR_FIRST` scale to 99.9% delivery within 1.0 s under adaptive density tiers, whereas unrestrained flooding generates excessive collisions.
  - Real-world BLE RSSI under body blockage is noisy and not a pure distance proxy (Report A section C).
- **Decision:** Set `RANDOM_SLOT` as default in `MeshConfig.relayRule`, while keeping `RSSI_FAR_FIRST` fully supported for phone-line tests. Adopt the 4-tier `DensityPolicy` table (routine/urgent TTL, probability gate, suppressAfter, and Trickle beacon periods). Urgent frames always bypass the probability gate.
- **Approved by:** Project Owner / Engineering Lead

### DEC-002c: Phase 5 Healing, Anti-Entropy Summaries & Delivery Guarantees

- **Date:** 2026-10-11
- **Question:** How should disconnected and returning nodes reconcile missing urgent items across crowd gaps without blind re-send floods, and how to guarantee acknowledged emergencies are never resurrected?
- **Options considered:**
  1. Full anti-entropy cache summaries (`MeshType.SUMMARY(14)`, 512-bit Bloom filter $k=7$ + 16-byte SHA-256 digest + fallback exact 4-byte key exchange) with tombstones (`expiresAt = maxAgeS + 300s`) and custody-by-overhearing. Defer aggregated ACK bitmaps.
  2. Aggregated ACK bitmaps per crew member.
  3. Blind multi-packet offers (Phase 4 baseline: 8 packets re-sent blindly on every contact).
- **Evidence:**
  - **Bloom filter efficiency**: 64 bytes (512 bits, $k=7$) over 8-byte packet keys gives a theoretical false positive rate $<0.1\%$ at $N=32$ items.
  - **Reconciliation byte reduction**:
    - For 0 difference: sends a single 121 B summary frame instead of 1280 B blind offers (90.5% reduction in airtime).
    - For 1 difference: total exchange is ~711 B across full summary + offer handshake (44.5% reduction vs blind offers).
    - For large divergence / false positives: 16-byte SHA-256 digest mismatch triggers an exact summary variant (32 $\times$ 4-byte truncated keys = 128 B) that pinpoint-identifies all missing items with 0 false negatives.
  - **Tombstones & resurrection prevention**:
    - An incoming ACK or cancellation tombstones the packet key for `maxAgeS + 300s`, clears urgent retries, purges pending relays, and suppresses incoming duplicate frames.
    - Carried payloads are not evicted prematurely so absent crew members / sleeping LPNs still receive missed emergencies upon reconnection, but tombstoned packets are never re-relayed on the air.
  - **Custody-by-overhearing**:
    - Hearing an independent relay re-transmitting our urgent packet marks custody and stretches retry backoff to [2s, 5s, 10s, 20s, 40s], saving airtime while preserving payload until an explicit ACK.
  - **Aggregated ACK bitmaps deferred**:
    - At realistic crew sizes (4–12 members), individual ACK frames and tombstones suffice with minimal overhead; bitmaps add complexity without practical gain.
- **Decision:** Adopt Option 1. Implement `MeshType.SUMMARY` (type 14, unencrypted), 512-bit Bloom filter + truncated SHA-256 digest with exact summary fallback, tombstone cache with 5-minute buffer beyond `maxAgeS`, custody-by-overhearing with stretched retry backoff, and defer aggregated ACK bitmaps.
- **Approved by:** Project Owner / Engineering Lead

---

### DEC-002d: Phase 6 Wire Format v2, Nonce Safety & Ephemeral Rotating Crew Tags
- **Date:** 2026-10-11
- **Context:**
  - Wire format v1 broadcast static 32-bit `crewId` in the clear in every 29-byte packet header. In crowded environments (e.g., Kumbh Mela, music festivals), passive sniffers could trivially track specific individuals and deduce social graphs across the venue.
  - In v1, the 96-bit AES-GCM nonce was `originId (4) | packetId (4) | crewId (4)`. If an app process died or was force-closed and restarted, the packet counter reset to 1. If `originId` collided or was preserved, nonces would be reused under the same key material, which fatally undermines AES-GCM confidentiality and authenticity (catastrophic nonce reuse vulnerability).
  - High packet arrival rates from foreign unauthenticated senders could exhaust CPU via cryptographic decryption attempts or starve the urgent relay queue.
- **Options considered:**
  - *Option 1*: Full Wire Format v2 overhaul:
    - 37-byte header: `crewTag: Long` (8B), `runEpoch: Int` (4B), `originId: Int`, `packetId: Int`, `originTimeS: Int`.
    - Nonce: exactly 96 bits formatted as `runEpoch | originId | packetId`.
    - Rotating ephemeral crew tag: 8-byte truncation of `HMAC-SHA256(tagKey, slot)` where `slot = floor(unixSeconds / 900)`. Nodes accept tags matching slot $t$, $t-1$, or $t+1$.
    - Separate subkeys derived via HKDF-Expand with distinct labels (`content` for AES-GCM, `tag` for rotating HMAC tag, `tunnel` for point-to-point payloads).
    - Multi-tiered pre-decryption rate limiting (per-relay token bucket 10/s, burst 20; global cap 50/s; foreign urgent quota 10/min; unauthenticated packets restricted to routine priority 3).
  - *Option 2*: Keep 29-byte header with rolling crew tag by shortening originId/packetId.
    - Rejected: shortening IDs increases collision risks in dense multi-hop meshes. Extended BLE advertising handles 37 bytes effortlessly without fragmentation.
- **Decision:** Adopt Option 1. Upgrade `MeshPacket.VERSION` to 2 (37-byte header). Cleanly drop legacy v1 frames. Synchronize wire format and test vectors with Swift implementation in `ios/KhojMesh`.
- **Validation:**
  - `nonceUniquenessAcrossRestartsAndReinstalls`: 1,000 simulated process boots $\times$ 1,000 packets ($10^6$ total) produced 0 duplicate nonces.
  - `crewTagRotationMatchesAcrossSlotBoundariesAndRejectsTwoSlotsAway`: verifies $\pm 1$ slot tolerance (15-min clock skew) and rejection at $\pm 2$ slots.
  - `v1FramesCleanlyRejected`: v1 frames cleanly dropped as malformed.
  - `rateLimiterDropsFloodingSourceBeforeDecryption`: token bucket halts spam relays (50 frames/s) before AES-GCM decryption while legitimate crew packets pass without delay.
  - `unauthenticatedForeignUrgentPacketsCappedAndRoutinePriority`: unauthenticated foreign SOS packets capped at 10/min and demoted to routine priority 3.
  - Test vectors verified across Kotlin and Swift implementations (`ios/KhojMesh`).
- **Approved by:** Project Owner / Engineering Lead

---

### DEC-003: Phase 7 Link AI vs Rule Selection
- **Date:** 2026-10-11
- **Context:**
  - In Phase 3, frame dwell was raised to 1,000 ms, invalidating old synthetic/simulated link feature assumptions.
  - Research A & B identified critical flaws in the original Link AI setup: single-room autocorrelation, class-imbalanced drop events where ROC-AUC is misleading, and the fact that `lost_5s` represents combined system availability failure (sleep, OEM throttling, contention) rather than pure RF attenuation.
  - Decision gate requirement: Ship the model only if it beats the simple baseline rule `risk = 1 - pdr(2s)` on held-out device and session splits in both PR-AUC and Brier score.
- **Options considered:**
  - *Option 1*: Discard the model entirely and use the simple heuristic rule `risk = 1 - pdr(2s)`.
    - Tested on 149,112 real rows across 77 sessions on two iQOO I2501 phones (`10BFAX1BU80010U`, `10BFBG0FTL001DB`).
    - The rule achieved PR-AUC: 0.158, Brier score: 0.6353, ROC-AUC: 0.724.
    - Flaw: The rule has a catastrophic Brier score (~0.635) because single packet skips immediately push estimated risk to 1.0, even though 91.7% of the time the link does not fail for 5 s, triggering massive false-alarm escalations.
  - *Option 2*: Retrain calibrated logistic regression on real multi-device field data with grouped session and leave-device-out splits:
    - Drop outlier-sensitive `rssi_min_2s` (ablation proved dropping it increases PR-AUC and reduces Brier score).
    - Expand feature space with host context: `adv_update_age_s`, `active_adv_sets`, `nearby_state`, `gatt_link_count`, `screen_on`, `battery_saver`, `thermal_status`, `local_density`, `peer_beacon_period_s` (21 features in `LinkFeatures.ALL`, 19 active in `F`).
    - Full field evaluation (Grouped by session): PR-AUC: **0.371** (+135% over rule), Brier score: **0.0622** (10x lower error than rule), ROC-AUC: **0.823**.
    - Leave-device-out cross-validation: PR-AUC: **0.391–0.416** (vs 0.145–0.167 for rule), Brier score: **0.0538–0.0663** (vs 0.6214–0.6474 for rule).
- **Decision:** Adopt Option 2. The Link AI model passes the decision gate decisively on both held-out devices and sessions in both PR-AUC and Brier score.
- **Execution Architecture:**
  - Deploy retrained model artifacts: `app/src/main/assets/link_ai_v1.tflite` (1272 bytes), `app/src/main/assets/link_ai_v1.json`, and `core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt`.
  - Maintain dual-execution path: LiteRT with Qualcomm QNN delegate on Hexagon HTP NPU (~0.904 ms / batch of 16) for background efficiency, with transparent CPU / pure-Kotlin fallback (`LossPredictor`, $<1.5\ \mu\text{s}$) when HTP is unavailable.
- **Approved by:** Project Owner / Engineering Lead

---

### DEC-004: Phase 8 Find Accuracy, Age-Aware Uncertainty & PDR Coasting
- **Date:** 2026-10-11
- **Context:**
  - In Phase 8, the Find Member direction resolver required replacing fixed velocity limits and uncalibrated linear distance estimates with honest, age-aware uncertainty propagation.
  - Previously, a fixed 1.8 m/s velocity cap suppressed running or hurried movement, linear uncertainty ($3 + 1.0 \times \text{age}$) drifted without bounds, and own-phone GPS dropouts immediately removed guidance without short dead-reckoning continuity.
  - Channel Sounding on Android 16 (`RangingManager`) failed on real iQOO I2501 hardware with `REASON_NO_PEERS_FOUND`.
- **Options considered:**
  - *Option 1*: Implement analytical age-aware uncertainty propagation, cadence-based motion classes (`STILL`, `WALKING`, `RUNNING`), own-phone PDR coasting with Weinberg stride estimation capped at 30 s, coarse BLE RSSI bands without fake meters, and document Android 16 Bluetooth CS pairing constraints.
  - *Option 2*: Keep fixed 1.8 m/s cap and simple linear time decay.
    - Rejected: Research A Section F proved linear uncertainty underestimates drift at longer intervals and overestimates it at short intervals, while a fixed speed cap artificially slows down running targets.
- **Decision:** Adopt Option 1.
  1. **Analytical Age-Aware Uncertainty**: Implemented $\sigma^2(t) = \sigma^2_{\text{GNSS}} + t^2\sigma^2_v + (vt)^2\sigma^2_\theta + q_{\text{motion}}t$ in `Predict.kt`.
     - Prediction stops completely when $\sigma(t) > 30.0$ m (`MAX_UNCERTAINTY_M = 30.0`), dropping the predicted arrow and falling back gracefully to BLE turn/trend modes or the searching dial.
     - Arrow uncertainty wedge widens monotonically with fix age.
  2. **Motion Classes**: Replaced 1.8 m/s cap with `MotionClass`:
     - `STILL`: max speed 0.3 m/s, $\sigma_v = 0.1$, $\sigma_\theta = 0.1$
     - `WALKING`: max speed 1.8 m/s, $\sigma_v = 0.4$, $\sigma_\theta = 0.35$
     - `RUNNING`: max speed 4.5 m/s, $\sigma_v = 0.8$, $\sigma_\theta = 0.5$
     - Cadence calculated from accelerometer steps in `MotionTracker`: `< 0.5` steps/s = `STILL`, `0.5–2.2` = `WALKING`, `> 2.2` = `RUNNING`.
  3. **Own-Phone PDR Coasting**: Implemented `OwnPdrTracker` for short GPS gaps (up to 30 s):
     - Dynamically computes stride via the Weinberg formula $stride = K \times (a_{max} - a_{min})^{1/4}$ clamped to $[0.4\text{ m}, 1.2\text{ m}]$ (default 0.70 m).
     - Steps integrated along true heading.
     - Coasts own position between GPS fixes up to 30 s (`MAX_COAST_MS = 30_000L`). Beyond 30 s, coasting ceases (`position(now)` returns `null`), marking GPS as stale and preventing phantom drift.
  4. **BLE RSSI**: Coarse proximity bands (`VERY_CLOSE`, `NEAR`, `MEDIUM`, `FAR`) strictly maintained. RSSI is never converted into meters on a map.
  5. **Channel Sounding Findings**:
     - On AOSP Android 16 (API 36 / Snapdragon SM8850), `RangingManager` Channel Sounding procedures require an established BLE ACL connection or bonded device pairing (`BluetoothDevice.BOND_BONDED`) to negotiate sounding subevents.
     - Calling `RangingDevice.Builder().setUuid(UUID.randomUUID())` against unbonded devices causes the Bluetooth stack to return `REASON_NO_PEERS_FOUND`.
     - Documented in `AndroidRangingProvider.kt` status reporting and system architecture.
- **Validation:**
  - `predictionCarriesTheirWalkForwardAndStopsAfterAWhile`: 1 m/s walk carried 10 s forward, analytical $\sigma(10) = 6.5$ m, null beyond 30 m uncertainty, 30 s coast limit verified.
  - `wedgeGrowsWithFixAge`: verifies monotonic widening of arrow wedge with silence duration.
  - `predictionStopsBeyond30mUncertainty`: verifies prediction halts at $\sigma(t) > 30.0$ m and resolver falls back cleanly to `FindMode.UNAVAILABLE`.
  - `motionClassLimitsApply`: verifies speed caps for `STILL` (0.3 m/s), `WALKING` (1.8 m/s), and `RUNNING` (4.5 m/s).
  - `pdrCoastStopsAt30s`: verifies own-phone PDR path integration, Weinberg formula, and 30 s hard cutoff.
  - 100% green test suite across `:core` and `:app`. Debug APK built and installed on both test phones.
- **Approved by:** Project Owner / Engineering Lead

---

### DEC-005: Phase 9 Screen-Off Background Survival & OEM Battery Guidance
- **Date:** 2026-10-11
- **Context:**
  - On Indian market smartphones (prominently Vivo, iQOO, Oppo, OnePlus running OriginOS / FuntouchOS / ColorOS), aggressive OEM battery managers ("High Background Power Consumption", "Autostart", aggressive freezing) terminate or suspend background Bluetooth mesh activities when the screen is locked, breaking emergency alert delivery and crowd-safety relaying.
  - Furthermore, Android's Bluetooth stack (`ScanManager`) suspends BLE scanning completely when the screen turns off if scan filters are empty (`ScanFilter` list size 0).
  - Ground rule: Never request battery-optimization exemption silently. Always explain in plain human language what is happening, what it costs in battery (~1.5% to 3%/hour), and direct the user to system/OEM settings via user-initiated taps.
- **Options considered:**
  - *Option 1*: Implement non-empty controller hardware scan filters, verify `MeshService` foreground service declarations (`location|connectedDevice|camera`), provide an explicit Call UI battery guidance page (`BatteryGuidancePage`) with multi-OEM fallback deep-linking (Vivo/iQOO specific security components down to AOSP battery optimization and application details settings), and show prominent non-intrusive warning cards in Radar and Settings tabs.
  - *Option 2*: Silently prompt `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog on launch.
    - Rejected: Violates Google Play policies, breaks project ground rules, and fails to handle Vivo/iQOO specific "Autostart" and "Background Power Consumption" controls which are independent of AOSP `deviceidle` whitelisting.
- **Decision:** Adopt Option 1.
  1. **Hardware Filter Screen-Off Scanning**: Confirmed `BleMeshRadio.scanFilters()` registers explicit service data (`SERVICE`) and service UUID (`GattBearer.SERVICE`) filters. This offloads advertising packet matching to the Bluetooth controller hardware filters, allowing the AP to stay asleep while waking up only on valid KHOJ mesh frames.
  2. **Foreground Service Types**: Confirmed `MeshService` is registered with `foregroundServiceType="location|connectedDevice|camera"` and started with persistent notification.
  3. **Call UI Battery Guidance Screen**:
     - Added `BatteryGuidancePage` in `CallPages.kt` matching the high-contrast Call design system (`Glass`, `CallButton`, `Tone.HOT`, `p.gold` warning icons).
     - Plain-language explanation of why screen-off survival matters for family safety and what the power consumption cost is (1.5%–3%/hour).
     - Dedicated OEM instructions for Vivo/iQOO (FuntouchOS / OriginOS) covering "High Background Power Consumption" and "Autostart".
     - Robust multi-intent deep linking: tries `com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity`, `com.vivo.permissionmanager/.activity.PurviewTabActivity`, `com.iqoo.powersaving/.PowerSavingTabActivity`, and falls back safely to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
  4. **Status Cards**: Displayed on `RadarTab` and `SettingsTab` when `batteryUnrestricted()` is false, allowing immediate one-tap remediation.
- **Validation**:
  - Live execution verified on two iQOO I2501 (Android 16) devices (`10BFAX1BU80010U` and `10BFBG0FTL001DB`).
  - Screen turned off on Phone A; Phone A continued executing in background, maintaining LE 1M extended advertising and 10 s LE Coded emergency burst callbacks without process termination or Bluetooth stall.
  - All unit tests pass across `:core` and `:app`.
- **Approved by:** Project Owner / Engineering Lead


