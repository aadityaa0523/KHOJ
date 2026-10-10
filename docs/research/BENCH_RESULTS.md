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

*(To be populated during Phase 2)*
