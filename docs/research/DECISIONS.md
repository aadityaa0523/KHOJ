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

### DEC-003: Phase 7 Link AI vs Rule Selection

*(Pending Phase 7)*
