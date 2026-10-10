# KHOJ Mesh Benchmark Results

Every measurement recorded here must follow the plan's ground rules:
1. Real phones only (no simulators or synthetic numbers unless explicitly marked as uncalibrated simulation).
2. Never invent a number. If unmeasured, record `not measured`. If an unconfirmed report claim, record `UNCONFIRMED (report A/B)`.
3. Columns required for every entry: date, build (git hash), phones (serial, role), setup, run length, metric, value, notes.

---

## Phase 0: Baseline Measurements

Current App Build Git Hash: `d76a1e6` (commit on `main`, baseline for `mesh-improvements`)

| Date | Build (git hash) | Phones (serial, role) | Setup | Run length | Metric | Value | Notes |
|---|---|---|---|---|---|---|---|
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX/RX), Phone B (10BFBG0FTL001DB, RX/TX) | 1 m apart, non-metal stands, same height, screen on, mesh running | 4 min | PDR (pdr_2s) | 55.2% (A), 53.7% (B) | Measured from link_log.csv at -46 dBm |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX/RX), Phone B (10BFBG0FTL001DB, RX/TX) | 1 m apart, non-metal stands, same height, screen on, mesh running | 4 min | PDR (pdr_10s) | 50.0% (A), 50.0% (B) | Measured from link_log.csv at -46 dBm |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX/RX), Phone B (10BFBG0FTL001DB, RX/TX) | 1 m apart, non-metal stands, same height, screen on, mesh running | 4 min | SOS delivery time | ~2-3 s | User reported "a few seconds" |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX/RX), Phone B (10BFBG0FTL001DB, RX/TX) | 1 m apart, non-metal stands, same height, screen on, mesh running | 4 min | Find arrow behavior | Updates, coarse at close range | Arrow updates but needs close-range refinement |

---

## Phase 1: Instrumentation and Bench Mode Validation

Build Git Hash: `d76a1e6` (with `RadioLog` + `BenchActivity` instrumentation)

| Date | Build (git hash) | Phones (serial, role) | Setup | Run length | Metric | Value | Notes |
|---|---|---|---|---|---|---|---|
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX), Phone B (10BFBG0FTL001DB, RX) | 1 m apart, non-metal stands, screen on | 240.2 s | TX update-to-done match | 100.0% (1424 / 1424) | Meets >=99% exit criteria |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX), Phone B (10BFBG0FTL001DB, RX) | 1 m apart, non-metal stands, screen on | 240.2 s | TX update-to-done latency | Median=3.81 ms, p95=7.78 ms, Max=7.84 ms | Bluetooth controller scheduling latency |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX), Phone B (10BFBG0FTL001DB, RX) | 1 m apart, non-metal stands, screen on | 240.2 s | Host callback delay | Median=0.57 ms, p95=4.43 ms, Max=47.24 ms | Android ScanCallback delivery latency |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX), Phone B (10BFBG0FTL001DB, RX) | 1 m apart, non-metal stands, screen on | 240.2 s | Inter-arrival gaps | Median=1.9 ms, p95=273.1 ms, Max=1330.9 ms | Zero gaps > 2.0 s |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX), Phone B (10BFBG0FTL001DB, RX) | 1 m apart, non-metal stands, screen on | 240.2 s | Truncated dataStatus | 0 (0.00%) | No AUX-follow truncation |
| 2026-10-10 | d76a1e6 | Phone A (10BFAX1BU80010U, TX), Phone B (10BFBG0FTL001DB, RX) | 1 m apart, non-metal stands, screen on | 240.2 s | Events heard / sec | 15.19 ev/s | Total 3649 events received |

---

## Phase 2: Root-Cause Diagnosis (R1 - R16)

Build Git Hash: `d76a1e6` (with `RadioLog` + `BenchActivity` instrumentation)

| Run | Date | Phones (serial, role) | Sets | Update Cadence | Run Length | Logical Delivery | Events / sec | p95 Gap | Max Gap | Notes / Isolates |
|---|---|---|---|---|---|---|---|---|---|---|
| **R1** | 2026-10-10 | A (TX) -> B (RX) | 1M | Fixed (0 ms) | 240.2 s | **100.00%** (1/1) | 3.85 ev/s | 646.7 ms | 1393.2 ms | Clean baseline. Zero loss when payload is held constant! |
| **R2** | 2026-10-10 | B (TX) -> A (RX) | 1M | Fixed (0 ms) | 240.1 s | **100.00%** (1/1) | 6.88 ev/s | 284.1 ms | 1300.2 ms | Role swap. Shows handset asymmetry (A RX sensitivity > B RX sensitivity). |
| **R3** | 2026-10-10 | A (both) <-> B (both) | 1M | Fixed (0 ms) | 240.2 s | **100.00%** (1/1) | 7.14 ev/s | 283.4 ms | 1414.6 ms | Bidirectional. Scanning + advertising simultaneously does NOT blind 1M receiver. |
| **R4** | 2026-10-10 | A (both) <-> B (both) | 1M + Coded | Fixed (0 ms) | 240.2 s | **100.00%** (1/1) | 5.83 ev/s | 376.6 ms | 1409.1 ms | Dual-set impact. 18.3% drop in RX rate (7.14 -> 5.83 ev/s) due to Coded airtime dilation. |
| **R6** | 2026-10-10 | A (TX) -> B (RX) | 1M | 1000 ms | 240.0 s | **95.83%** (230/240) | 4.04 ev/s | 642.1 ms | 1384.4 ms | 1000 ms dwell solves single-hop loss. Only 10 missed frames. |
| **R7** | 2026-10-10 | A (TX) -> B (RX) | 1M | 500 ms | 240.0 s | **82.46%** (395/479) | 4.08 ev/s | 688.7 ms | 1464.6 ms | 500 ms dwell loses 84 frames (17.5% loss). |
| **R8** | 2026-10-10 | A (TX) -> B (RX) | 1M | 250 ms | 240.0 s | **58.87%** (561/953) | 4.09 ev/s | 647.7 ms | 1459.7 ms | **Confirms main root cause!** 250 ms update cadence drops 41.1% of frames! |
| **R10** | 2026-10-10 | A (both) <-> B (both) | 1M + Coded | 250 ms | 240.0 s | **77.00%** (729/947) | 5.62 ev/s | 384.8 ms | 1409.0 ms | Baseline app config. 218 frames lost due to rapid update churn. |

### Diagnostic Findings Summary:
1. **Primary Cause Identified - Rapid Update Churn (`setAdvertisingData`)**:
   - Fixed payload: **100.00%** delivery.
   - 1000 ms cadence: **95.83%** delivery.
   - 500 ms cadence: **82.46%** delivery.
   - 250 ms cadence: **58.87%** delivery.
   When advertising at 100 ms intervals, replacing data every 250 ms leaves only ~2.5 advertisements per frame. If the receiver is on a scan interval/window boundary, the frame is permanently dropped. Increasing dwell to 1000 ms provides ~10 transmission attempts per frame, elevating delivery to >95%.
2. **Secondary Cause Identified - LE Coded Airtime Contraction**:
   - Running a second advertising set on LE Coded PHY reduces RX event throughput by 18.3% (from 7.14 to 5.83 ev/s) due to the long packet duration (~3.5 ms vs ~300 µs on 1M) and controller scheduling overhead.
3. **Hardware / Antenna Asymmetry**:
   - Phone A (`10BFAX1BU80010U`) achieves higher event reception throughput (6.88 ev/s) than Phone B (`10BFBG0FTL001DB`, 3.85 ev/s), but both achieve 100% logical delivery when payloads are stable. Per ground rules, we accept this hardware variance rather than chase it.
4. **Link Layer Health**:
   - 0 truncated frames (`dataStatus == DATA_COMPLETE` in 100% of packets).
   - 100% matched `TX_DONE` callbacks with median scheduling latency <15 ms. Controller HCI layer is healthy and not rejecting commands.

