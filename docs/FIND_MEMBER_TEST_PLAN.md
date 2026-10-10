# Find Member: test plan

Nothing in the "Results" tables below has been measured yet. They are blank on purpose. Unit tests use synthetic inputs and say so; they are **not** field results.

## 1. What is already checked, and how

| Check | How | Status |
|---|---|---|
| Mode policy (what may be claimed) | `FindTest`: GPS arrow only with fresh fixes, usable heading, bearing uncertainty ≤ 45°; never an arrow when too close, stale, or heading unreliable; hardware angle needs trust; distance-only hardware gives no angle; RSSI "ranging" never counts as precise; Bluetooth never gives an arrow; heading turn compensation | **Passing** (synthetic inputs) |
| Orientation maths | `FindTest`: flat, rotated, landscape, upright, tilted-upright quaternions give the expected heading; declination conversion | **Passing** (synthetic) |
| Arrow smoothing | Across ±180° the arrow takes the short way; turn compensation | **Passing** |
| RSSI filtering | Median removes spikes; trend needs a real slope over noise; band hysteresis; invalid values rejected; stale gives no guidance; haptic cue is rate limited | **Passing** (synthetic) |
| Tunnel security | Round trip; wrong recipient, wrong crew, replay after 30 s, tampering and redirect all rejected | **Passing** |
| Beacon `findable` flag | Encode/decode; crew tag unchanged by the new key derivation | **Passing** |
| Automatic precise-ranging start (within about 40 m, phase `WAITING_FOR_RANGE`) | Written; no unit test yet | **Not yet run** |
| Capabilities on the iQOO | App log at start-up | **Observed**: UWB no, Channel Sounding yes, BLE RSSI yes, Wi-Fi RTT no |
| Find screen layouts | Debug-only `FindPreviewActivity` with simulated data, stamped as such | **Observed on an iQOO** for GPS and Bluetooth modes |
| Real ranging sessions, RSSI behaviour, GPS bearing, background behaviour, battery | **Needs two or more phones** | **Not yet run** |

Run the automated checks with `./gradlew :core:test` (106 core tests, 0 failures, at the time of writing; these include the walking-direction geometry on synthetic walks).

## 2. Setup for field tests

**Devices:** two iQOO I2501 phones (Android 16, Channel Sounding). Add a third for relay tests and, if available, any phone with UWB for the angle checks.

1. Install the debug build on all phones.
2. Phone A: create a crew in setup. Phone B: join by scanning A's family QR.
3. **Both:** there is no precise-finding switch, because being in the same crew is consent. Ranging starts by itself when the phones are within about 40 m. Both: allow the ranging permission when asked (or `adb shell pm grant app.linkfill android.permission.RANGING`).
4. Turn mobile data and Wi-Fi internet **off**. Keep Bluetooth, Wi-Fi radio and Location on. Keep **both screens on with KHOJ in the foreground** (non-UWB ranging is foreground-only).
5. Check the log: `adb logcat -s KHOJ` shows the *ranging capabilities* line on each phone.

**Measurement logs:** `find_log.csv` in `Android/data/app.linkfill/files/` on the finder phone (pull with `adb pull`). Columns: time, a random session id, mode, source, distance, confidence, arrow source and angle, RSSI raw / filtered / std, trend, proximity, ground truth, link phase. **No coordinates or names are logged.** In the Find screen's *Test tools*, type the tape-measured distance into *Ground-truth distance*; every following log row carries it.

## 3. Scenarios

Run each for at least 60 s per distance, repeat 3 times, and record phone orientation (hand, chest pendant, pocket, bag).

| # | Scenario | What to record | Pass criterion |
|---|---|---|---|
| S1 | Both phones in line of sight, outdoors, distances 1, 2, 5, 10, 20, 30 m, on tape | Mode, CS distance error, RSSI band | Mode reaches `PRECISION_DISTANCE` when CS runs; distance and ground truth logged |
| S2 | Same, but a person standing between the phones | Same | Degradation is visible as lower confidence or a different mode; no arrow appears from Bluetooth |
| S3 | Phones in pockets or bags | RSSI filtered level, band, proximity stability | No crash; bands change by at most one step at fixed distance in most samples (to be measured, not assumed) |
| S4 | Phone held flat, upright, and tilted at 45°, rotating 360° slowly | Heading versus a trusted compass, arrow angle against a marked bearing | Arrow stays within the displayed uncertainty of the marked direction (outdoors, GPS target 50 m+) |
| S5 | Indoors versus outdoors | GPS accuracy, mode chosen | No GPS arrow indoors if fixes are poor; reason note shown |
| S6 | Compass interference (next to a steel door, a speaker, a laptop) | Heading uncertainty, arrow dropped | Arrow disappears or the wedge widens when uncertainty exceeds 40° |
| S7 | UWB or Channel Sounding unavailable on one phone (disable Bluetooth on one, or use a phone without CS) | Link phase, notes | Session ends in an explained state; GPS or Bluetooth tier continues |
| S8 | BLE-only walk: finder walks toward the stationary target from 30 m | Trend, proximity band, haptic cue | Trend is *improving* for most of the approach; cue fires at most every 4 s; no arrow shown |
| S9 | Stale or missing GPS (cover the sky) | Notes, mode | `LAST_KNOWN` or Bluetooth; no arrow |
| S10 | Direct link lost, then relay: three phones, finder out of Bluetooth range of the target but both in range of a relay | Position age and source on the finder | Position arrives with its **original timestamp**; the screen shows the target's position age, never the relay's position |
| S11 | Permission denied or revoked during a session | Notes, session state | Clear message; no crash; session ends |
| S12 | App backgrounded on the finder or the target | Whether ranging results continue | Documented (Android allows background ranging only with UWB) |
| S13 | Bluetooth or Wi-Fi toggled off and on | Recovery time | Session recovers or ends with an explanation |
| S14 | Sustained use for 30 minutes | Battery percent per hour, CPU of the Bluetooth process (`top`) | Recorded; compare with the earlier 3% Bluetooth CPU baseline |
| S16 | Direction from walking: finder stands 15-20 m from a still target, walks an L (6 m east, 6 m north), then continues toward the arrow | Mode `RANGE_WALK`, arrow versus the true bearing, time to first arrow, step-count error (walk 20 m, compare steps × 0.7 with tape) | Arrow within its displayed wedge of the true bearing in most samples; straight walk gives the "walk sideways" prompt, not an arrow |
| S15 | Phone with UWB (if available) | Azimuth sign and units against a known bearing | Determines `trustRangingAngle`; until then the arrow stays off |
| S17 | Automatic start and pause: the finder starts 60 m from a still target and walks in; then the target taps *Pause for 30 minutes*; then the finder walks back within 40 m | Link phase over time, time from 40 m to the first ranging result, whether a new session starts during the pause | Phase is `WAITING_FOR_RANGE` above about 40 m; ranging starts inside about 40 m; no new session starts during the 30-minute pause |

## 4. Metrics

Compute from `find_log.csv` joined with the tape-measured ground truth:

| Metric | Definition |
|---|---|
| Ranging session setup time | `REQUEST` sent to first ranging result |
| Range error | measured distance − ground truth; report median and 90th percentile per distance |
| Angular error | (hardware or GPS arrow) − known direction; median and 90th percentile; **only where an angle exists** |
| Invalid / unavailable rate | share of seconds in `UNAVAILABLE` or with stale ranging |
| Time to reacquire | link loss to the next valid measurement |
| RSSI variability | standard deviation of raw RSSI at a fixed distance and orientation |
| Trend correctness | share of approach time with trend *improving* versus ground-truth distance decreasing |
| Delivery latency over a relay | origin timestamp in the beacon to arrival on the finder |
| Battery / scanning overhead | % battery per hour and Bluetooth process CPU |

## 5. Result tables (to be filled from real runs)

| Scenario | Devices | n | Median | p90 | Notes |
|---|---|---|---|---|---|
| S1 CS range error, 1 m | | | | | |
| S1 CS range error, 5 m | | | | | |
| S1 CS range error, 10 m | | | | | |
| S1 CS range error, 20 m | | | | | |
| S2 with a body in between | | | | | |
| S4 heading error | | | | | |
| S8 trend correctness | | | | | |
| S10 relay delivery latency | | | | | |
| S14 battery per hour | | | | | |

## 6. Baseline before any learned method

Record the deterministic baseline (median + EMA + regression trend, fixed band thresholds) on all scenarios first. Only then evaluate a model, and split training and test data **by phone pair and session**, never by random row. Report baseline and model side by side with the same metrics. A model may not be used to produce an angle, bypass the confidence gates, or relax privacy controls.

## 7. Known gaps this plan must close

1. **Does a Channel Sounding session between two iQOO phones start through our tunnel?** (S1) This is the biggest unknown: the code path is implemented against the SDK 36 API but has never run with two phones.
2. Does the target need KHOJ in the foreground? (S12)
3. Azimuth units and sign (S15, needs a UWB phone).
4. Real RSSI band thresholds per phone and posture (S3, S8).
5. Whether `setAngleOfArrivalNeeded` interacts badly with CS-only sessions (we request it only when UWB azimuth is reported).
