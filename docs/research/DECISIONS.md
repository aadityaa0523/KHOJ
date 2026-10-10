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
- **Approved by:** Project Owner (Presenting at Phase 2 Decision Gate)

---

### DEC-002: Phase 3 Single-Hop Delivery & Dwell Settings

*(Pending Phase 3)*

---

### DEC-003: Phase 7 Link AI vs Rule Selection

*(Pending Phase 7)*
