The strongest diagnosis is **host/controller scheduling disturbance from frequent `setAdvertisingData()` calls, amplified by simultaneous scanning and two advertising sets**—not RF range. The fastest path is to instrument HCI behavior, hold each logical frame constant across multiple advertising events, temporarily reduce to one 1M set, and use a crossed two-phone experiment before changing routing or ML.

# KHOJ Wireless-Mesh Improvement Plan

## Contents

- [Scope and findings](#scope-and-findings)
- [A. BLE advertising and scanning](#a-ble-advertising-and-scanning)
- [B. Coded PHY and range](#b-coded-phy-and-range)
- [C. Dense-crowd mesh protocol](#c-dense-crowd-mesh-protocol)
- [D. Healing and guarantees](#d-healing-and-guarantees)
- [E. Link prediction](#e-link-prediction)
- [F. Position accuracy](#f-position-accuracy)
- [G. Security and privacy](#g-security-and-privacy)
- [H. Scale and simulation](#h-scale-and-simulation)
- [I. Platform limits](#i-platform-limits)
- [Ranked improvements](#ranked-improvements)
- [First-week experiments](#first-week-experiments)
- [Reading list](#reading-list)
- [Possibly wrong beliefs](#possibly-wrong-beliefs)

## Scope and findings

The repository documentation could not be independently retrieved, so the architecture and measurements below treat the facts in the request as authoritative. Public documentation also does not expose Qualcomm’s proprietary controller scheduling policy, meaning several Android-controller conclusions must be validated experimentally rather than asserted as guaranteed behavior.

The current 30–60% figure should be described as **application-observed delivery per changing logical frame**, not necessarily RF packet reception ratio. Android does not expose every physical advertising event, and one `setAdvertisingData()` completion does not prove that the new payload was transmitted before it was replaced again.

The leading explanations, in order, are:

1. Frequent advertising-data replacement causes skipped, delayed, coalesced, or ambiguously timed payload generations.
2. The controller cannot fully satisfy two advertising sets, extended-advertisement auxiliary receptions, continuous scanning and concurrent Bluetooth activity.
3. Extended advertising’s primary-to-secondary follow operation misses AUX packets during scheduling conflicts.
4. Handset-specific antenna/controller behavior causes the persistent asymmetry.
5. Android callback batching or filtering hides otherwise received reports.

Wi-Fi being disabled does not isolate Bluetooth-controller contention, while restricting scanning to 1M does not eliminate AUX-follow scheduling if the 1M extended set still puts its 200-byte payload on a secondary channel.

***

## A. BLE advertising and scanning

### Extended-event structure

An extended advertisement is not one 200-byte transmission on channels 37, 38 and 39. The primary `ADV_EXT_IND` contains an `AuxPtr`; the scanner must receive it, retune to one of the 37 secondary channels at the indicated time and receive `AUX_ADV_IND`, potentially followed by chained packets. [mcuxpresso.nxp](https://mcuxpresso.nxp.com/mcuxsdk/latest/html/middleware/wireless/bluetooth/doc/Bluetooth%20Low%20Energy%20Application%20Developers%20Guide/topics/extended_advertising.html)

Therefore, reception requires all of the following:

1. Scanner listening on the correct primary channel.
2. Successful primary-PDU reception.
3. Controller capacity to schedule the AUX reception.
4. Successful secondary-PDU reception.
5. Host/controller report construction and delivery to Android.
6. Application callback processing.

A strong RSSI only helps steps 2 and 4. It does not correct scheduling conflicts in steps 1, 3 or 5.

### Data-update semantics

The Core specification permits extended advertising data to be changed while advertising is enabled. Subsequent events must use the new data, but an event already in progress may use either the old or new data; the specification does not promise a maximum application-to-air delay or require an event for every host update .

This is important for KHOJ: a 250 ms data-update timer and 100 ms nominal advertising interval do **not** prove that each logical frame receives two or three complete advertising events. Updates may land close to in-progress events, and Android, the HCI transport, controller firmware or Bluetooth coexistence scheduler may delay them.

The specification controls event timing through advertising parameters but does not require controllers to preserve all nominal advertising opportunities when multiple radio procedures conflict. Public Qualcomm documentation confirming its precise arbitration rules was not found; any claim about SM8850 behavior should therefore be marked **UNCONFIRMED** until supported by HCI and RF traces. [bluetooth](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host-controller-interface/host-controller-interface-functional-specification.html)

### Discovery model

Let:

- \(T_a\) = advertising interval, excluding random delay.
- \(T_s\) = scan interval.
- \(W_s\) = scan window.
- \(T_p\) = time for the packet that must fit inside the scan window.
- \(q_{\text{RF}}\) = probability that a temporally covered packet survives interference and decoding.
- \(q_{\text{aux}}\) = probability that the controller follows and receives the AUX packet.

Under a simplifying random-phase model, the effective temporal coverage is:

\[
d_{\text{eff}}=\frac{\max(0,W_s-T_p)}{T_s}
\]

and an approximate per-event success probability is:

\[
p \approx d_{\text{eff}}q_{\text{RF}}q_{\text{aux}}
\]

If successive opportunities are independent, the probability of at least one delivery after \(n\) events is:

\[
P_{\text{delivery}}(n)=1-(1-p)^n
\]

This Bernoulli model is useful for engineering estimates but is not exact. BLE advertiser and scanner schedules can enter favourable or unfavourable phase relationships, producing latency “peaks” or resonances; Kindt et al. model the relative motion of advertising events and scan windows to derive mean and worst-case latency for arbitrary interval choices. Packet duration also shortens the usable part of a scan window because a packet beginning too close to its end cannot be received completely. [arxiv](https://arxiv.org/pdf/1509.04366.pdf)

With nominal continuous scanning, \(W_s=T_s\), temporal coverage approaches one for short primary packets. It still does not imply \(q_{\text{aux}}=1\), because the controller can be advertising, receiving another AUX packet, handling a connection or conducting Bluetooth Classic discovery at the required auxiliary time.

### Android scan semantics

`SCAN_MODE_LOW_LATENCY` means the highest scan duty requested from Android, not an application-level guarantee that the receiver listens without interruption. AOSP describes it as the highest-duty mode and recommends it principally while the application is foregrounded. [android.googlesource](https://android.googlesource.com/platform/frameworks/base/+/af74e66/core/java/android/bluetooth/le/ScanSettings.java)

AOSP revisions have used equal low-latency scan windows and intervals—for example, 100 ms/100 ms in one current diff—but OEM controller firmware still arbitrates actual RF procedures. That nominal 100% schedule therefore cannot be treated as measured 100% receiver availability. [chromium.googlesource](https://chromium.googlesource.com/aosp/platform/packages/modules/Bluetooth/+/e145a3ec0711471c0d9242b629697261c9dd7d80%5E2..e145a3ec0711471c0d9242b629697261c9dd7d80/)

KHOJ should explicitly configure and log:

- `CALLBACK_TYPE_ALL_MATCHES`.
- `reportDelay=0`; a positive delay causes queuing, with Android documenting delivery at the requested delay or at least 5 seconds, unless buffers fill earlier. [developer.android](https://developer.android.com/reference/kotlin/android/bluetooth/le/ScanSettings.Builder)
- `ScanResult.timestampNanos`.
- Advertising SID.
- Primary and secondary PHY.
- Data status: complete, truncated or incomplete.
- Raw frame hash and packet sequence.
- Callback-arrival time separately from controller timestamp.

The application uses a filter, which is beneficial for screen-off behavior. Vendor Android guidance reports that unfiltered scans may be paused while the screen is off and warns that more than five scan starts/stops in 30 seconds can trigger temporary suppression. This is vendor documentation rather than a stable cross-OEM API contract, so KHOJ should also test the exact Android 16 builds it supports. [docs.silabs](https://docs.silabs.com/btmesh/9.0.2/bluetooth-mesh-for-android-and-ios-adk/04-resources)

### Hypothesis ranking

| Hypothesis | Evidence | Verdict |
|---|---|---|
| Frequent `setAdvertisingData()` updates | Delivery changes with update cadence and idle-frame rate; the Core provides no event-per-update guarantee | **Most likely** |
| Controller time-sharing | Nearby inquiry worsens results; two sets, scan, advertising and connections share one controller | **Very likely contributor** |
| AUX-follow misses | Every large extended frame needs a precisely timed secondary reception | **Likely, but needs HCI/RF evidence** |
| Android duplicate filtering/batching | Possible, especially with repeated identical data, but `ALL_MATCHES` and `reportDelay=0` should reduce it | **Test explicitly** |
| Antenna/controller variation | Persistent directional asymmetry supports it | **Likely secondary factor** |

### The separating experiment

No two-phone application-only experiment can definitively distinguish “advertiser did not transmit” from “scanner missed the transmission.” That requires an independent sniffer or third receiver. A crossed two-phone test with Bluetooth HCI snoop logs can nevertheless separate most stack-level alternatives:

1. Run phone A as advertiser-only and phone B as scanner-only.
2. Use one 1M extended set and a fixed payload for four minutes.
3. Repeat with updates every 1,000, 500, 250 and 100 ms.
4. Repeat fixed and 250 ms modes while both phones scan and advertise.
5. Repeat with the second Coded set enabled.
6. Swap A and B without moving the stands.
7. Compare HCI `LE Set Extended Advertising Data` completions, extended advertising reports, Android callbacks and inter-arrival gaps.

Interpretation:

- Gaps begin immediately after update commands: update disturbance.
- Advertiser-only/scanner-only works but simultaneous operation fails: controller time-sharing.
- One set works but two sets fail: set scheduling or Coded airtime.
- HCI reports remain high but Android callbacks fall: framework/app filtering or batching.
- `dataStatus` becomes incomplete while primary reports persist: AUX-follow failure.
- Asymmetry follows the physical handset after role reversal: device antenna/controller difference.
- Asymmetry follows the role rather than handset: scan/advertise configuration difference.

**Verdict:** freeze the payload and reduce to one set before evaluating range. Current measurements conflate RF loss, host-update semantics and controller concurrency.

***

## B. Coded PHY and range

### Airtime cost

LE Coded S=8 carries coded payload information at 125 kbit/s, while S=2 carries it at 500 kbit/s; several controller SDKs default extended Coded advertising to S=8. [docs.silabs](https://docs.silabs.com/bluetooth/5.0/bluetooth-commnd-events-extended-advertiser/)

For a roughly 200-byte extended payload, approximate secondary-PDU airtime is:

| PHY | Approximate payload-rate airtime | Relative cost |
|---|---:|---:|
| LE 1M | About 1.7 ms | 1× |
| LE Coded S=2 | About 3.7 ms | About 2.2× |
| LE Coded S=8 | About 13.5 ms | About 8× |

These are protocol-level approximations including basic framing, not measured iQOO values. Exact duration depends on the extended header, chain structure and controller implementation.

At a 100 ms interval, one continuously active 200-byte S=8 set can occupy roughly 13% of one logical event timeline before primary transmissions and other procedures. Two phones in range are manageable; hundreds of phones make always-on S=8 expensive even though auxiliary traffic spreads across 37 channels.

### Range expectations

Coded PHY supplies coding gain, but phone-to-phone range depends on:

- Controller receive sensitivity.
- Antenna efficiency and orientation.
- Body blockage.
- Phone case and hand position.
- Whether both the primary and secondary PHY use Coded.
- Controller willingness to schedule the AUX reception.
- Ambient 2.4 GHz occupancy.

No credible primary benchmark was found that establishes a universal Android-phone range multiplier for 1M, S=2 and S=8. Claims such as “four times the range” should be marked **UNCONFIRMED marketing shorthand**, not used as a KHOJ planning number.

KHOJ should report range as the distance at which a fixed logical frame achieves:

- 90% delivery within 1 second.
- 99% delivery within 5 seconds.
- Median and fifth-percentile performance across orientations.
- Outdoor line-of-sight and body-blocked cases separately.

### Dual-set verdict

Always transmitting the same data on 1M and S=8:

- Increases discovery diversity in sparse environments.
- Doubles primary-event demand.
- Adds long S=8 AUX transmissions.
- Reduces scanner availability for other advertisements.
- Makes dense-crowd contention worse.
- May send redundant copies to receivers that already receive 1M well.

**Verdict:** do not run both sets continuously. Default to one 1M set; enable a Coded burst for urgent frames only when measured recent 1M delivery is poor and estimated local density is low. If Android does not expose S=2 selection reliably on the target phones, treat Coded as S=8 unless HCI logs prove otherwise.

***

## C. Dense-crowd mesh protocol

### Protocol comparison

| Method | Strength | Failure mode | KHOJ use |
|---|---|---|---|
| Managed flooding | Fast, topology-free, robust to mobility | Transmissions grow with neighbour count; collisions and battery cost | Sparse-mode urgent traffic |
| Counter-based suppression | Simple; uses copies already overheard | Hidden terminals can still relay simultaneously | Primary urgent relay rule |
| Probabilistic gossip | Direct control of expected relay count | Can partition sparse regions | Dense-mode modifier |
| RSSI/distance suppression | Tries to favour spatial progress | RSSI is not a reliable distance measurement in crowds | Do not use alone |
| Trickle | Excellent for suppressing repeated consistent state | Listen-only delay is undesirable for first SOS | Position/alert state, not first urgent injection |
| Bloom-filter anti-entropy | Efficient after contacts or partitions | False positives can temporarily hide missing packets | Healing/GATT/Nearby |
| Epidemic DTN | Highest replication opportunity | Unbounded traffic without strict lifetime/storage limits | Only 32-item urgent cache |
| Spray-and-Wait | Bounded number of copies | Fixed copy budget may fail under mobility | Good model for non-SOS reports |
| PRoPHET/Spray-and-Focus | Uses encounter history | Weak when all users are transient strangers | Crew-specific carry decisions |

Bluetooth Mesh permits up to eight total relay transmissions and 10–320 ms relay-retransmission intervals, but those are configuration ranges rather than optimal values. Experimental analysis has found that retransmitting without loss can create extra collisions even in small Bluetooth Mesh networks. [mathworks](https://www.mathworks.com/help/bluetooth/ref/bluetoothmeshprofileconfig.html)

The important lesson is not to copy Bluetooth Mesh parameters directly. Bluetooth Mesh was designed around small advertising PDUs and usually constrained relay populations, whereas KHOJ’s extended frames are much longer.

### Trickle placement

Trickle suppresses redundant transmissions by counting consistent messages during an interval and suppressing when the redundancy constant is reached. It expands intervals while state remains consistent and resets to a short interval when inconsistency appears; its canonical definition is [RFC 6206](https://www.rfc-editor.org/rfc/rfc6206.html).

Use Trickle for:

- Periodic position state.
- Official-alert version numbers.
- Cache-summary versions.
- “Child already found” or “SOS cancelled” state.

Do not make a new SOS wait through a long Trickle listen-only period. Inject it immediately, then use suppression for relays and Trickle-like interval growth for later refreshes.

### Why far-first RSSI is weak

BLE RSSI varies with body shielding, carrying position, orientation and multipath. Smartphone measurements found that 1–2 m ranging is not reliable without knowing carrying position and orientation, while another real-world study found RSSI-to-distance accuracy degrading sharply outside controlled conditions. [arxiv](https://arxiv.org/pdf/2101.09075v1.pdf)

A far-first rule based only on weak RSSI can select:

- A nearby phone blocked by a body.
- A phone behind a wall or metal barrier.
- A poor receiver with no useful onward neighbours.
- A device whose RSSI calibration differs from others.

Retain RSSI only as one weak tie-breaker. The stronger relay criteria are novelty and coverage: “How many distinct neighbours does this node hear that the previous relay probably does not?”

### Density estimator

Estimate local density from information KHOJ already collects:

\[
\hat N_{5s}=\frac{\text{distinct authenticated rotating relay IDs heard in 5 s}}{\hat p_{\text{hear}}}
\]

Since \(\hat p_{\text{hear}}\) is uncertain, initially use raw distinct-neighbour count rather than claiming an absolute population. Also track:

- Valid frames per second.
- Duplicates per packet.
- Incomplete/truncated extended reports.
- Median callback inter-arrival gap.
- Distinct SIDs and rotating sender IDs.
- Fraction of receiver time affected by Nearby/GATT activity.
- Unique relays heard per urgent packet within 500 ms.

### Starting density policy

These are **test parameters**, not proven universal optima:

| Distinct neighbours in 5 s | Routine TTL | Urgent TTL | Relay probability | Suppression threshold | Position beacon |
|---:|---:|---:|---:|---:|---:|
| 0–9 | 4 | 6 | 1.0 | 2 | 3–5 s |
| 10–49 | 3 | 5 | 0.5–0.8 | 2–3 | 5–10 s |
| 50–199 | 2 | 4 | \( \min(0.4, 8/N) \) | 3–4 | 10–20 s |
| 200+ | 1–2 | 3 | \( \min(0.1, 8/N) \) | 4–6 | 20–30 s |

Use separate queues:

1. SOS, found-child and cancellation.
2. End-to-end ACKs and delivery tombstones.
3. Official signed alerts.
4. Position updates.
5. Link measurement and diagnostics.

When urgent traffic exists, suspend idle link-measurement frames and sharply reduce position traffic. In dense mode, target approximately 5–10 relays per hop rather than allowing every neighbour to transmit.

A 200-device BLE advertising experiment measured collision probabilities around 22–33%, but still achieved over 99% data-record reception when advertisements repeated at a 250 ms advertising interval and the logical data interval exceeded 4 seconds. That result supports repeating a stable logical item across several events, but it does not prove that 200 Android phones running simultaneous scanning and extended advertising will behave similarly. [mdpi](https://www.mdpi.com/1424-8220/20/1/107)

**Verdict:** retain flooding for first urgent delivery, but make relay probability, TTL and beacon rate density-adaptive. Use Trickle for replicated state and anti-entropy for healing.

***

## D. Healing and guarantees

### Store-carry-forward design

The current “re-offer up to eight packets when a previously unseen phone appears” can create bursts precisely when topology changes. Replace it with a three-step exchange:

1. Advertise a short cache epoch, item count and digest.
2. Use GATT or Nearby to exchange a compact summary when divergence exists.
3. Request only missing urgent packet IDs, ordered by priority and expiry.

Recommended summary for 32 cached messages:

- 64-byte/512-bit Bloom filter.
- Seven hash positions.
- Cache epoch.
- Count.
- 128-bit digest over sorted packet IDs.
- Oldest and newest origin time.

For \(n=32\), \(m=512\) and \(k=7\), the approximate Bloom false-positive rate is:

\[
\left(1-e^{-kn/m}\right)^k \approx 0.07\%
\]

A digest mismatch with no Bloom-indicated missing items signals a possible false positive; in that case, exchange exact truncated packet IDs over GATT/Nearby.

### Cache and duplicate lifetime

Separate three concepts:

- **Payload cache:** urgent content retained for delivery.
- **Duplicate cache:** packet IDs already processed.
- **Tombstone cache:** ACKed, cancelled or expired packet IDs that must not be resurrected.

A 120-second duplicate cache is too short if an urgent payload can remain in carry storage for ten minutes. A phone returning after three minutes can reintroduce a packet that peers have forgotten.

Recommended defaults:

- Payload: 10 minutes, as now, subject to priority.
- Duplicate ID: payload lifetime plus 2 minutes.
- ACK/cancellation tombstone: at least payload lifetime plus 5 minutes.
- Exact urgent-ID cache: all active urgent packets and tombstones.
- Probabilistic duplicate structure only for low-priority position frames.

### ACK design

Per-hop ACKs do not provide end-to-end success and can generate ACK implosion for broadcasts. Retain end-to-end destination ACKs, but add hop-local custody state:

- A relay records that at least one downstream peer advertised possession.
- That state suppresses rapid re-offers but does not delete the payload.
- Only an end-to-end ACK or signed cancellation creates a deletion tombstone.
- ACKs themselves are store-carry-forward items.

Aggregate ACKs by origin and epoch:

- Highest contiguous acknowledged packet sequence.
- Small bitmap for the following 32 or 64 sequences.
- Optional exceptions.
- Destination timestamp and signature/MAC.

### Retry schedule

The current first retry occurs at 10 seconds, which is long for an SOS. Replace fixed 10-second retries with jittered exponential intervals:

- Immediate.
- 1 second.
- 2 seconds.
- 4 seconds.
- 8 seconds.
- 16 seconds.
- 32 seconds if still valid.

In dense mode, stretch this to 2, 5, 10, 20 and 40 seconds once multiple custody indications have been heard. Always add random jitter of at least ±20% to prevent synchronized retransmissions.

**Verdict:** use E2E ACKs for correctness, custody indications for local suppression, and tombstones to prevent old emergencies from returning after partitions heal.

***

## E. Link prediction

### Current model assessment

A held-out-by-session ROC-AUC of 0.75 versus 0.62 is encouraging, but two phones in one environment cannot establish device or venue generalization. Adjacent rows are highly autocorrelated, so random row splits would be invalid.

Keep logistic regression as the baseline because it is:

- Easy to calibrate.
- Interpretable.
- Small enough to run without an NPU.
- Less likely than a large model to memorize handset/session artifacts.

Running such a small model through an NPU may cost more in delegation and synchronization than CPU inference; this should be measured rather than assumed.

### Evaluation protocol

Use nested grouped evaluation:

1. **Outer test:** leave one device pair or venue out.
2. **Middle test:** leave complete sessions out.
3. **Inner validation:** grouped folds for regularization and feature selection.
4. Fit probability calibration only on data not used to fit the base classifier.
5. Freeze the threshold before evaluating the outer test group.

`GroupKFold` ensures groups do not occur in both train and test folds. Where event prevalence differs heavily, `StratifiedGroupKFold` tries to preserve class proportions while retaining non-overlapping groups. [scikit-learn](https://scikit-learn.org/dev/modules/generated/sklearn.model_selection.GroupKFold.html)

Report:

- PR-AUC.
- ROC-AUC.
- Recall at operational false-escalation rates.
- Precision at the chosen threshold.
- Brier score.
- Log loss.
- Calibration curve.
- Expected radio cost per delivered urgent message.
- Confidence intervals bootstrapped by session, not by row.

PR curves are more informative than ROC curves when positives are rare because ROC can look optimistic as the negative population grows. A calibrated predicted probability should match the observed event frequency, and calibration must use data independent from base-model fitting. [scikit-learn](https://scikit-learn.org/stable/modules/calibration.html)

### Label definition

“This neighbour sends nothing for five seconds” mixes several causes:

- RF failure.
- Peer had nothing scheduled.
- Its app was suspended.
- Advertising data was not committed.
- Controller contention.
- Intentional low-power mode.
- Nearby discovery or connection activity.

Use two labels:

1. **Availability failure:** no expected frame received in the next five seconds.
2. **Conditional link failure:** expected transmitted opportunities existed, but none were received.

The second label requires transmitter-side logs or an independent observer. Without them, the model predicts combined system availability—not radio-link failure—and should be named accordingly.

### Added features

High-value features:

- Advertising data-update age and completion latency.
- Number of active local advertising sets.
- Local scan-and-advertise concurrency.
- Nearby discovery and connection state.
- GATT/Classic connection count.
- Primary/secondary PHY and advertising SID.
- Complete/incomplete/truncated result status.
- Callback delay: callback time minus `timestampNanos`.
- Phone model, OS build and Bluetooth firmware identifier.
- Screen/foreground-service state.
- Thermal status and battery-saver state.
- Motion state and phone carrying orientation proxy.
- Local neighbour count and duplicate rate.
- Age of the last authenticated frame.
- Recent action: extra copy, Coded, Nearby, or no change.

Potentially low-value or misleading:

- Time of day, unless it proxies measured crowd density.
- Queue depth without separating urgent and routine traffic.
- Raw minimum RSSI, which is sensitive to one outlier.

An advertising-event counter would be valuable, but normal Android extended advertising does not expose a dependable per-event transmitter counter to applications. Treat it as **UNCONFIRMED** unless visible in target-device HCI traces.

### Field collection

Collect only pseudonymous engineering telemetry:

- Random per-install study ID rotated per event/day.
- Device model and build, not advertising MAC address.
- Quantized location context such as indoor/outdoor—not raw coordinates—unless explicit research consent is granted.
- No crew IDs, names, phone numbers or message plaintext.
- Message-type category and byte length only.
- Salted packet IDs that cannot be linked across studies.
- Local encryption and upload only when the participant opts in and internet later becomes available.
- A short retention period and deletion control.

### Bandit or RL

A learned retransmission policy is premature. The action changes the data distribution: selecting Coded or extra copies changes the probability of observing the failure label, creating confounding.

Use a deterministic, logged rule first. A contextual bandit becomes reasonable only after:

- Several phone families and venues are represented.
- Every action and action probability is logged.
- Safety constraints cap airtime and minimum SOS redundancy.
- Offline policy evaluation is stable.
- Exploration never reduces the minimum emergency policy.

**Verdict:** keep calibrated logistic regression or a monotonic generalized additive model. Compare with a small gradient-boosted tree only after leave-device and leave-venue testing. Do not deploy RL in the next iteration.

***

## F. Position accuracy

### GNSS baseline

Smartphone GNSS commonly achieves approximately 3–5 m under favourable open-sky conditions. A broader recent evaluation reported a median horizontal error of 4.53 m, with high-end phones around 4 m and low-end phones around 6–6.5 m. [journals.plos](https://journals.plos.org/plosone/article/file?type=printable&id=10.1371/journal.pone.0327896)

Dense crowds principally affect antenna visibility and body blockage, while nearby buildings introduce multipath and non-line-of-sight signals. Urban-canyon smartphone errors can reach tens of metres; one study reported 18.4 m RMS for ordinary NMEA positions and NLOS errors exceeding 100 m in severe cases. Android 10 and later require devices to expose raw GNSS measurements, but writing a robust raw-measurement solver is a major navigation project. [developer.android](https://developer.android.com/develop/sensors-and-location/sensors/gnss)

### Age-aware uncertainty

Every relayed position should include:

- GNSS fix timestamp using monotonic elapsed time.
- Reported horizontal accuracy.
- Speed and speed accuracy.
- Bearing and bearing accuracy, if available.
- Motion-state flag.
- Prediction duration.
- Whether the point is raw GNSS, dead-reckoned or peer-constrained.

Do not display a predicted marker without an expanding uncertainty radius. A practical model is:

\[
\sigma^2(t)=\sigma^2_{\text{GNSS}}+
t^2\sigma^2_v+
(vt)^2\sigma^2_\theta+
q_{\text{motion}}t
\]

where velocity and heading uncertainties grow the ellipse over time. Stop or visually de-emphasize prediction when uncertainty exceeds a product threshold such as 20–30 m.

### Pedestrian dead reckoning

PDR is worth implementing for short GNSS gaps, provided carrying mode and uncertainty are handled. Research systems report roughly 1% median displacement error in favourable pedestrian studies, but published errors span about 0.07–10% depending on placement and conditions. In a 30-second comparison, model-based PDR produced about 3 m error while a more sophisticated learned inertial approach produced 0.75 m; pure inertial integration drifted by 22.5 m. [ora.ox.ac](https://ora.ox.ac.uk/objects/uuid:73b860e5-6914-4f2f-9293-e14b10752a5d/files/s2514nk763)

Realistic KHOJ target:

- 1–3 m additional relative error over a 20–30 second walking coast in favourable carrying modes.
- Larger uncertainty when the phone is swinging, in a bag, stationary on a moving vehicle or near magnetic interference.
- No claim that PDR corrects the GNSS point’s global bias.

A fixed 1.8 m/s velocity cap can suppress running or hurried movement. Replace it with motion-class-dependent limits and measured acceleration/step cadence.

### BLE RSSI constraints

Do not convert BLE RSSI directly into metres for the map. Body shielding and carrying position make 1–2 m estimation unreliable without orientation information. [arxiv](https://arxiv.org/pdf/2101.09075v1.pdf)

BLE RSSI remains useful for coarse statements:

- Probably very near.
- Near.
- Uncertain.
- Weak/far or obstructed.

Cooperative localization using multiple RSSI constraints may reject impossible layouts, but correlated GNSS errors and biased RSSI can also pull several users in the wrong direction. Build it only as a robust experimental layer using broad range intervals and outlier-resistant loss, not as the displayed source of truth.

### Channel Sounding

Android 16 introduces `RangingManager`, supporting Bluetooth Channel Sounding, UWB, Wi-Fi NAN RTT and BLE RSSI ranging on compatible hardware. BLE Channel Sounding APIs were added at API level 36. [developer.android](https://developer.android.com/develop/connectivity/ranging)

Bluetooth SIG describes typical Channel Sounding performance of approximately ±0.5 m at distances up to 100 m, although implementation, antenna design and environment remain important. It is connection-oriented and hardware-dependent, so it is appropriate for the final “find this person nearby” mode—not crowd-wide continuous ranging. [bluetooth](https://www.bluetooth.com/blog/bluetooth-channel-sounding-how-it-works/)

### Wi-Fi RTT

Android documents typical Wi-Fi RTT accuracy of 1–2 m on supported hardware and peers. AOSP’s KPI table gives 90th-percentile targets for 802.11mc of 2 m at 80 MHz, 4 m at 40 MHz and 8 m at 20 MHz; 802.11az improves those targets on compatible devices. [source.android](https://source.android.com/docs/core/connect/wifi-rtt)

Wi-Fi RTT needs compatible devices and foreground-visible or foreground-service operation. It fits the existing point-to-point finding mode but should not be required for basic mesh functionality.

### Position verdict

Build, in order:

1. Age and uncertainty propagation.
2. Short-horizon, step-based PDR with carrying-mode detection.
3. Coarse BLE proximity labels.
4. Bluetooth Channel Sounding for supported Android 16 pairs.
5. Wi-Fi RTT/NAN as a pair-mode enhancement.
6. Cooperative RSSI localization only as a research experiment.

Do not promise sub-metre crowd positioning from GNSS plus RSSI. A defensible product target is roughly 4–8 m outdoors under favourable conditions, 10–30+ m around severe obstruction, and 1–3 m relative pair-ranging where Channel Sounding or suitable Wi-Fi RTT is available.

***

## G. Security and privacy

### Static group key

A single static AES-GCM group key has four major costs:

1. Any member can decrypt all traffic for the key’s lifetime.
2. Compromise reveals stored historical ciphertext.
3. Any member can forge a frame attributed only by fields inside group-authenticated ciphertext.
4. Nonce uniqueness must hold across every member and reinstall sharing that key.

NIST requires GCM IVs to have only a negligible chance of repeating for a given key and recommends 96-bit IVs. Reusing a GCM nonce under the same key is a catastrophic failure mode. [govinfo](https://www.govinfo.gov/content/pkg/GOVPUB-C13-PURL-LPS70006/pdf/GOVPUB-C13-PURL-LPS70006.pdf)

The tuple `originId | packetId | crewId` is safe only if it is exactly 96 bits or handled correctly by GCM’s variable-IV processing **and** can never repeat under the same key. Reinstallation, packet-counter reset, origin-ID collision, restored backups or duplicated state can violate that requirement.

### Immediate cryptographic changes

- Add a random boot/session epoch generated with a cryptographic RNG.
- Use an explicit 96-bit nonce construction.
- Persist packet counters transactionally across crashes, or use a 64-bit random message nonce plus a collision-resistant sender domain.
- Include protocol version, message type, TTL limit, sender epoch and relevant clear routing fields as AEAD associated data.
- Use distinct keys for content encryption, admission tags and identifier rotation through HKDF.
- Rotate group epochs before an event, after membership changes and periodically during long events.
- Delete expired keys after the delay-tolerant retention window.

Official alerts should carry a digital signature from an offline-authorized authority key. Group AES-GCM proves only that some holder of the group key generated a message; it cannot establish that an official issued it.

### Forward secrecy

MLS provides asynchronous group key establishment with forward secrecy and post-compromise security for groups ranging from two to thousands. Full MLS may be operationally heavy for intermittently connected, infrastructure-free crews because all members must converge on group epochs. [rfc-editor](https://www.rfc-editor.org/subjects/messaging/)

A phased design is safer:

1. Use MLS or another authenticated group setup while internet is available before the event.
2. Derive per-event and per-time-epoch mesh keys.
3. Support overlapping current/previous epochs for delayed delivery.
4. Delete old epochs after all related packets and tombstones expire.
5. Evaluate sender hash chains for future per-message forward secrecy.

### Tracking identifiers

A stable public crew tag allows passive observers to link the same group over time even if message bodies are encrypted. Encryption does not hide traffic analysis.

Apple Find My broadcasts short-lived derived public keys and changes them approximately every 15 minutes to prevent persistent linking. Find My is end-to-end encrypted and anonymous, but it is a one-hop crowd-observation system whose finder devices later upload encrypted locations—not a peer-to-peer multi-hop emergency mesh. [support.apple](https://support.apple.com/en-om/guide/security/sec6cbc80fd0/web)

KHOJ should derive an unlinkable public routing tag:

\[
\text{tag}_t=\text{Truncate}_{64}\left(
\text{HMAC}(K_{\text{id}}, \text{timeSlot}\,\|\,\text{eventEpoch})
\right)
\]

Accept the previous, current and next slots to tolerate clock error. A 10–15 minute rotation is a reasonable starting point. Strangers do not need the stable crew identity to relay an opaque frame; the service UUID can identify KHOJ traffic while the rotating tag lets group members decide whether decryption is relevant.

Google’s crowd-finding network similarly uses end-to-end encrypted location reports and temporary identifiers, with anti-abuse aggregation and unknown-tracker safeguards. [support.google](https://support.google.com/product-documentation/answer/14796936)

### Replay and flooding

Controls should be applied before expensive decryption where possible:

- Strict maximum frame size and TTL.
- Version and type validation.
- Global, per-type and per-ephemeral-source token buckets.
- Small pre-decryption keyed admission tag for group traffic.
- Exact duplicate suppression for urgent frames.
- Separate quota for unauthenticated cross-group relay traffic.
- Per-member signatures for SOS/found-child messages if identity attribution is required.
- Authority signatures for official alerts.
- Hard cap on relayed bytes per minute in dense mode.
- No priority solely because a clear header claims “SOS.”

A malicious member holding the group key can still generate valid floods. Per-member credentials, revocation epochs and quotas are required to constrain that case.

**Verdict:** nonce hardening and rotating public identifiers are release-blocking security changes. Group-key forward secrecy can be phased, but official-message signatures should precede any deployment involving public authorities.

***

## H. Scale and simulation

### Simulator choice

Stock ns-3 model documentation includes 802.15.4 but not a maintained, standards-complete BLE extended-advertising model. Third-party BLE modules exist, but maturity and support for Android controller scheduling, extended AUX chains and modern Coded PHY vary. [github](https://github.com/Kir4kami/ns3-ble)

OMNeT++ has an older BLE PHY/link-layer model based on MiXiM and tested against OMNeT++ 4.4.1. Research Bluetooth Mesh simulations sometimes adapt an 802.15.4 radio, which risks modelling CSMA/CA even though BLE advertising lacks equivalent carrier sensing. [pdfs.semanticscholar](https://pdfs.semanticscholar.org/8383/c5611932e7e2592c716e2fdfc454a99192bd.pdf)

Recommended approach:

1. Build a small custom discrete-event PHY/MAC model for advertising.
2. Validate it against KHOJ traces.
3. Couple it to a higher-level mobility and routing simulator.
4. Use ns-3 or OMNeT++ only if their mobility/propagation infrastructure saves enough work to justify replacing the BLE MAC.

### Event-level PHY model

Model:

- Three primary and 37 secondary channels.
- `ADV_EXT_IND`, AuxPtr delay, `AUX_ADV_IND` and chaining.
- 1M, S=2 and S=8 airtime.
- Nominal advertising interval plus random advertising delay.
- Scan interval, scan window and channel rotation.
- Controller blackout intervals caused by local advertising and connections.
- Payload-update disturbance fitted from HCI traces.
- Co-channel overlap.
- Received power and capture effect.
- Body shadowing and orientation.
- Correlated fading rather than independent packet loss.
- Per-device asymmetry.
- Nearby/Classic inquiry duty.
- Android callback loss or coalescing as a separate layer.

BLE does not listen before advertising; random delay reduces persistent collisions but does not prevent them. The simulator must therefore avoid accidentally inheriting 802.15.4 carrier-sense behavior. [courses.grainger.illinois](https://courses.grainger.illinois.edu/cs439/fa2023/slides/mac-bt.pdf)

### Two simulation levels

**Detailed level, 10–500 phones**

- Full advertising events and collision overlap.
- Used to tune interval, frame size, PHY and suppression.
- Outputs collision fraction, capture outcomes and per-hop delivery.

**Abstract level, 100–5,000 phones**

- Empirical delivery probability conditioned on density, RSSI bin, frame airtime, concurrency state and handset class.
- Used for routing, mobility and store-carry-forward studies.
- Periodically checked against detailed scenarios.

### Validation ladder

1. One transmitter, one receiver, fixed payload.
2. Role reversal.
3. Simultaneous advertise and scan.
4. One versus two advertising sets.
5. Payload updates at 1,000, 500, 250 and 100 ms.
6. Nearby discovery and connected-idle states.
7. Three-phone line topology.
8. Five to twenty real advertisers.
9. Compare with published dense-advertiser behavior, including the 22–33% collision estimate around 200 tags. [mdpi](https://www.mdpi.com/1424-8220/20/1/107)

Fit model parameters only to training traces, then test against held-out device orientations, intervals and sessions. Do not insert a generic “40% loss rate”; the purpose is to explain the loss mechanism.

**Verdict:** a calibrated custom event simulator is more defensible than claiming stock ns-3 or OMNeT++ accurately models Android extended advertising.

***

## I. Platform limits

### Android scanning

Operational rules:

- Keep one long-lived scan instead of repeatedly restarting it.
- Use a hardware-eligible service-data filter.
- Use `ALL_MATCHES` and `reportDelay=0`.
- Record every scan failure callback.
- Treat scan start as successful only after receiving expected traffic or confirming HCI state.
- Do not automatically restart several times within 30 seconds.
- Re-establish deliberately after Bluetooth toggles, adapter resets or service death.

Vendor guidance reports scan throttling after more than five starts/stops in 30 seconds and possible downgrade of long scans, but these details have varied across Android generations and must be tested on each target build. [docs.silabs](https://docs.silabs.com/btmesh/latest/bluetooth-mesh-for-android-and-ios-adk/04-resources)

### Android 16

Android 16’s major relevant addition is the unified Ranging API, including BLE Channel Sounding, Wi-Fi RTT, UWB and RSSI ranging. Channel Sounding still requires compatible hardware; Android 16 alone does not make every phone capable. [source.android](https://source.android.com/docs/whatsnew/android-16-release)

Nothing in the public Android API guarantees uninterrupted simultaneous extended advertising and scanning. Android exposes requested modes, while controller and OEM firmware control actual radio arbitration.

### Background survival

KHOJ should maintain a device-qualification suite rather than relying on one Android contract:

- Screen on/off.
- App visible/backgrounded.
- Foreground service active.
- Battery saver on/off.
- OEM auto-start permission on/off.
- App swiped from recent tasks.
- One hour stationary.
- Walking for one hour.
- Thermal stress.
- Bluetooth toggled.
- Reboot and autostart.
- Nearby active and inactive.

Record process uptime, scan gaps, advertising-update callbacks and frames observed by a separate monitor. Provide OEM-specific setup guidance, but never silently request exclusion from battery optimization without explaining the battery effect.

### iOS

The exact current CoreBluetooth background restrictions were not independently verified from a primary source in this research pass and are therefore **UNCONFIRMED here**. Product planning should nevertheless treat iOS GATT relaying as best-effort until physical background tests prove otherwise.

Do not promise iPhone parity based only on successful foreground GATT tests. The acceptance test must cover locked screen, app background, process suspension, incoming notification delivery and reconnect after several minutes.

### OEM verdict

Xiaomi, Vivo/iQOO, Oppo, Realme and Samsung should be treated as distinct platforms. A feature is production-ready only when it passes an overnight screen-off delivery test on at least one current device from each family.

***

# Ranked improvements

## 1. Stabilize advertising data

**Change:** use one 1M extended advertising set, stop idle payload replacement, and hold each logical urgent frame unchanged for 0.75–1 second before advancing the queue.

**Expected gain:** if an advertising event has independent reception probability \(p=0.2\), ten opportunities provide \(1-(0.8)^{10}=89.3\%\) logical-frame delivery; at \(p=0.4\), this becomes 99.4%. Independence is optimistic, but repeated stable advertisements have strong published support in dense BLE experiments. [mdpi](https://www.mdpi.com/1424-8220/20/1/107)

**Cost and risk:** 0.5–1 second queueing for successive frames; stale repeated content; duplicate callback load.

**Under-hour test:** fixed one-second frame dwell versus 250 ms updates, two four-minute runs in each direction.

**Success metric:** median logical-frame delivery above 90%, fifth percentile above 80%, and no update-correlated receive gaps.

## 2. Instrument the controller path

**Change:** add structured app logs and collect Android Bluetooth HCI snoop traces for every experiment.

**Expected gain:** no direct delivery gain; converts speculation into separable host, controller, callback and RF hypotheses.

**Cost and risk:** engineering effort, large traces and possible sensitive metadata.

**Under-hour test:** verify every data-update call, completion, HCI command and received HCI report can be matched by monotonic timestamp.

**Success metric:** at least 99% of application updates matched to an HCI completion, with callback delay and data status measurable.

## 3. Disable always-on dual PHY

**Change:** default to 1M; use time-limited Coded bursts only for urgent traffic when recent 1M health is poor and density is low.

**Expected gain:** eliminate approximately 13.5 ms of S=8 secondary airtime per Coded event for a 200-byte frame, plus its primary events. The exact PRR gain is device-dependent.

**Cost and risk:** reduced range for receivers that can only hear Coded; mode switching may itself disturb advertising.

**Under-hour test:** one-set versus dual-set four-minute runs, with both phones simultaneously scanning and advertising.

**Success metric:** at least 20% fewer receive gaps or update stalls without materially reducing close-range logical delivery.

## 4. Replace RSSI far-first relaying

**Change:** use randomized relay slots, duplicate-count suppression and density-dependent relay probability. Retain RSSI only as a weak tie-breaker.

**Expected gain:** bound expected relays to roughly 5–10 per hop instead of one transmission per neighbour. RSSI cannot reliably represent distance under body blockage. [pmc.ncbi.nlm.nih](https://pmc.ncbi.nlm.nih.gov/articles/PMC11249359/)

**Cost and risk:** overly aggressive probability can partition sparse networks.

**Under-hour test:** three phones in a line; repeat 50 urgent packets under current and proposed logic.

**Success metric:** unchanged delivery, lower duplicate count, and lower p95 latency.

## 5. Separate urgent and state traffic

**Change:** immediate controlled flood for new urgent packets; Trickle-like dissemination for positions, alert versions and cancellation state. Suspend idle measurement frames during urgent activity.

**Expected gain:** first SOS is not delayed, while repetitive state traffic decays toward a much lower steady rate.

**Cost and risk:** more protocol states and timers.

**Under-hour test:** inject SOS while all three phones send position updates.

**Success metric:** SOS p95 delivery below 2 seconds over two hops, with routine airtime reduced by at least 50% during the urgent window.

## 6. Add anti-entropy summaries

**Change:** exchange a 512-bit, seven-hash Bloom summary plus count, epoch and 128-bit digest before re-offering cached packets.

**Expected gain:** reduce a possible eight-packet burst to a 64-byte summary when caches agree; approximate false-positive probability is 0.07% for 32 items.

**Cost and risk:** GATT/Nearby state machine; false positives need digest-triggered exact reconciliation.

**Under-hour test:** preload 32 items with 0, 1, 8 and 16 differences.

**Success metric:** all missing items identified, fewer bytes than blind re-offer in every case except near-total divergence.

## 7. Improve ACK and retry logic

**Change:** jittered retries at 1, 2, 4, 8, 16 and 32 seconds; add custody indications, aggregated destination ACKs and deletion tombstones.

**Expected gain:** a single lost initial SOS is retried after roughly 1 second instead of 10 seconds; exponential spacing limits later load.

**Cost and risk:** more metadata and potentially heavier early traffic.

**Under-hour test:** deliberately suppress the first receive opportunity on a three-phone route.

**Success metric:** p95 recovery under 3 seconds, and no resurrection after ACK propagation.

## 8. Harden nonce and identifier design

**Change:** explicit 96-bit unique nonce construction, boot epoch, durable counter or random nonce domain, rotating public crew tag and authority signatures for official alerts.

**Expected gain:** security rather than RF performance; removes catastrophic GCM-reuse and persistent-tracking risks. [learn.microsoft](https://learn.microsoft.com/en-us/security/engineering/cryptographic-recommendations)

**Cost and risk:** migration/versioning and clock-skew handling.

**Under-hour test:** simulate reinstall, counter reset, clock change and overlapping identifier slots.

**Success metric:** zero nonce duplicates across generated test histories; successful reception across adjacent rotation slots.

## 9. Make the link model honest

**Change:** rename the current target to system-availability failure, add controller/context features, calibrate probabilities and evaluate by device pair and venue.

**Expected gain:** fewer unnecessary Coded/Nearby escalations. No numeric gain is defensible before multi-device field data.

**Cost and risk:** more data collection and slower model iteration.

**Under-hour test:** replay held-out sessions through logistic rules with and without new controller features.

**Success metric:** improved PR-AUC and Brier score without worse urgent delivery cost.

## 10. Add age-aware PDR

**Change:** uncertainty ellipse, carrying-mode-aware step detection and a maximum 20–30 second inertial coast.

**Expected gain:** roughly 1–3 m relative drift over 30 seconds is an appropriate favourable-condition target; published results vary substantially by method and placement. [arxiv](https://arxiv.org/html/2407.21676v1)

**Cost and risk:** heading drift, battery and confusing false precision.

**Under-hour test:** walk a measured 100 m route with 30-second GNSS gaps.

**Success metric:** lower median error than constant velocity, with at least 90% of true positions inside the displayed uncertainty radius.

## 11. Add precise pair ranging

**Change:** use Android 16 Channel Sounding when both devices support it; fall back to Wi-Fi RTT/NAN, then coarse BLE proximity.

**Expected gain:** approximately ±0.5 m typical Channel Sounding or 1–2 m typical Wi-Fi RTT on supported devices. [developer.android](https://developer.android.com/develop/connectivity/wifi/wifi-rtt)

**Cost and risk:** limited hardware, connection setup and foreground requirements.

**Under-hour test:** 1, 3, 5, 10 and 20 m line-of-sight measurements in four orientations.

**Success metric:** p90 absolute error below 1 m for CS or 3 m for Wi-Fi RTT.

## 12. Build a calibrated simulator

**Change:** custom BLE event model plus empirical Android-controller blackout and update effects.

**Expected gain:** identifies unstable parameter regions before crowd trials; no direct production gain.

**Cost and risk:** model may create false confidence if not held out against real traces.

**Under-hour test:** reproduce one fixed-payload and one 250 ms update trace.

**Success metric:** simulated delivery and inter-arrival distributions within 10 percentage points of held-out measurements.

***

# First-week experiments

## Instrumentation fields

Log one row per transmit update, callback and protocol action:

- Monotonic nanoseconds and wall-clock time.
- Session/run ID.
- Phone model, Android build and app version.
- Advertiser handle and SID.
- Primary and secondary PHY.
- Advertising interval and requested Tx power.
- Payload sequence, packet ID, type, length and hash.
- `setAdvertisingData()` call and completion times/status.
- Scan start/stop time and failure code.
- `ScanResult.timestampNanos` and callback-arrival time.
- RSSI, reported Tx power, SID and PHYs.
- Complete/truncated/incomplete data status.
- Duplicate number for the logical frame.
- Nearby discovery/connection state.
- Number of GATT links.
- Screen, foreground-service and battery-saver state.
- Battery level/current and thermal status.
- Queue depth by priority.
- Local distinct-neighbour count.

Enable Bluetooth HCI snoop logging and extract:

- `HCI_LE_Set_Extended_Advertising_Data`.
- `HCI_LE_Set_Extended_Advertising_Enable`.
- `HCI_LE_Set_Extended_Scan_Parameters`.
- `HCI_LE_Set_Extended_Scan_Enable`.
- `HCI_LE_Extended_Advertising_Report`.
- Command status/complete errors.
- Advertising-set termination or resource errors.

## Physical setup

- Phones on non-metal stands, 1 m apart.
- Same height and fixed orientation.
- Mark positions and do not hand-hold.
- Screen initially on, charging disabled.
- Airplane mode with Bluetooth re-enabled; repeat selected tests with ordinary settings.
- No Nearby except where explicitly tested.
- Four-minute run: 30-second warm-up, three-minute measurement, 30-second cool-down.
- Repeat each critical run in both role directions.
- Reboot and cool phones before the final repeat.

## Day 1: Fixed baseline

Run:

| Run | Advertiser | Scanner | Sets | Payload updates |
|---|---|---|---|---|
| 1 | A only | B only | One 1M | Fixed |
| 2 | B only | A only | One 1M | Fixed |
| 3 | Both | Both | One 1M each | Fixed |
| 4 | Both | Both | 1M + Coded | Fixed |

Readout:

- Fixed Tx-only/Rx-only good, concurrent bad: controller time-sharing.
- One-set good, dual-set bad: advertising-set or airtime contention.
- Directional difference in runs 1/2: handset asymmetry.

## Day 2: Update cadence

With one 1M set, run fixed, 2,000, 1,000, 500, 250 and 100 ms payload-update periods. Keep advertised interval at approximately 100 ms.

For each update, measure:

- Time to HCI command completion.
- First receive timestamp for the new hash.
- Number of callbacks before the next update.
- Gap length around update time.
- Whether intermediate payload versions never appear.

Interpretation:

- Missing intermediate versions: updates are being coalesced, superseded or not aired.
- Receive blackout begins at every update: update operation disturbs events.
- HCI completion latency rises as cadence increases: command/controller bottleneck.
- HCI remains healthy but callbacks disappear: scanning/AUX or Android reporting.

## Day 3: Scan behavior

Run:

1. Service-data filter, `ALL_MATCHES`, `reportDelay=0`.
2. No filter, screen on.
3. Filter with deliberately positive report delay as a batching control.
4. Low-latency scan versus balanced scan.
5. One continuous scan versus restart every minute.
6. Screen on versus locked screen.

Do not restart quickly enough to trigger the five-in-30-second throttle reported in vendor guidance. [docs.silabs](https://docs.silabs.com/btmesh/9.0.2/bluetooth-mesh-for-android-and-ios-adk/04-resources)

Interpretation:

- HCI report exists but callback does not: Android reporting/filtering.
- Callback delay clusters around batch interval: batching.
- HCI scan parameters are not the expected window/interval: framework/OEM policy.
- Screen-off only failure: background/OEM restriction.

## Day 4: AUX and PHY

Run:

- Short legacy-compatible diagnostic advertisement where possible.
- Short extended 1M payload.
- 200-byte extended 1M payload.
- 200-byte extended Coded payload.
- One 1M and one Coded set together.
- Scan only 1M versus all supported PHYs.

Measure incomplete data status and HCI extended reports.

Interpretation:

- Short extended works but 200-byte extended fails: AUX airtime/follow or payload handling.
- 1M-only scan does not change the 1M extended result: primary scan PHY was not the cause.
- Incomplete/truncated reports increase with large payloads or second set: AUX scheduling.
- Legacy works near 100% while extended fails: extended path, not antenna range.

## Day 5: Bluetooth concurrency

Starting from the best fixed-payload configuration, enable one factor at a time:

- Nearby discovery.
- Nearby connected but discovery stopped.
- GATT connection idle.
- GATT notifications.
- Bluetooth audio, if relevant.
- Wi-Fi on/off.
- Second KHOJ advertiser.

Expected interpretation:

- Discovery alone hurts: inquiry/scan arbitration.
- Connected idle recovers: current policy of stopping discovery is correct.
- GATT idle hurts: connection-event scheduling.
- Wi-Fi has little effect while Bluetooth states do: shared Bluetooth controller, not Wi-Fi coexistence.

## Day 6: Three-phone relay

Arrange A–B–C so A and C cannot reliably communicate directly, using distance, rooms or RF attenuation rather than software drops.

Test 50 urgent packets under:

- Current relay delay and suppression.
- Randomized counter-based suppression.
- One versus two relay copies.
- Fixed one-second advertisement dwell.
- Current 10-second retry versus 1/2/4-second retry.

Metrics:

- A-to-C delivery.
- Median and p95 latency.
- Transmissions per delivered packet.
- Duplicate copies at C.
- Battery energy per run.
- ACK convergence and payload deletion.

## Day 7: Decision gate

Adopt a configuration only if it achieves:

- More than 90% median single-hop logical-frame delivery.
- More than 80% delivery in the worse direction.
- At least 99% urgent delivery within five seconds over the controlled two-hop route.
- No scan restart throttle.
- No unexplained gaps longer than two seconds.
- Lower transmissions per delivered urgent packet than the current design.

If fixed one-set advertising still receives only 30–60% in advertiser-only/scanner-only mode, obtain an independent BLE sniffer or third phone before changing routing. At that point, endpoint logs cannot distinguish missing transmissions from receiver loss.

***

# Reading list

## Bluetooth and Android

- [Bluetooth Core Specification 5.4, HCI](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host-controller-interface/host-controller-interface-functional-specification.html) — Normative commands, event semantics and extended advertising data updates. [bluetooth](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host-controller-interface/host-controller-interface-functional-specification.html)
- [Bluetooth 5.3 feature enhancements](https://www.bluetooth.com/wp-content/uploads/2021/01/Bluetooth_5.3_Feature_Enhancements_Update.pdf) — Clear explanation of extended advertising, AuxPtr and scheduling. [bluetooth](https://www.bluetooth.com/wp-content/uploads/2021/01/Bluetooth_5.3_Feature_Enhancements_Update.pdf)
- [NXP extended advertising documentation](https://mcuxpresso.nxp.com/mcuxsdk/latest/html/middleware/wireless/bluetooth/doc/Bluetooth%20Low%20Energy%20Application%20Developers%20Guide/topics/extended_advertising.html) — Primary/secondary channel and PHY mechanics. [mcuxpresso.nxp](https://mcuxpresso.nxp.com/mcuxsdk/latest/html/middleware/wireless/bluetooth/doc/Bluetooth%20Low%20Energy%20Application%20Developers%20Guide/topics/extended_advertising.html)
- [Android `ScanSettings.Builder`](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder) — Report delay and scan-mode API semantics. [developer.android](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder)
- [AOSP `ScanSettings`](https://android.googlesource.com/platform/frameworks/base/+/833559d/core/java/android/bluetooth/le/ScanSettings.java) — Framework definitions of low-latency and callback modes. [android.googlesource](https://android.googlesource.com/platform/frameworks/base/+/833559d/core/java/android/bluetooth/le/ScanSettings.java)
- [Android Ranging API](https://developer.android.com/develop/connectivity/ranging) — Android 16 Channel Sounding, RTT, UWB and RSSI integration. [developer.android](https://developer.android.com/develop/connectivity/ranging)
- [Android Wi-Fi RTT](https://developer.android.com/develop/connectivity/wifi/wifi-rtt) — Requirements and realistic 1–2 m guidance. [developer.android](https://developer.android.com/develop/connectivity/wifi/wifi-rtt)

## Discovery and dense BLE

- [Kindt et al., Neighbor Discovery Latency in BLE-Like Protocols](https://arxiv.org/pdf/1509.04366.pdf) — Exact analysis beyond the simple duty-cycle model. [arxiv](https://arxiv.org/pdf/1509.04366.pdf)
- [Optimizing BLE-Like Neighbor Discovery](https://arxiv.org/html/2009.04199v1) — Effective window and parameter-selection effects. [arxiv](https://arxiv.org/html/2009.04199v1)
- [Experimental Evaluation of Advertisement-Based BLE Communication](https://www.mdpi.com/1424-8220/20/1/107) — More than 200 real advertisers, collision and repetition measurements. [mdpi](https://www.mdpi.com/1424-8220/20/1/107)
- [Efficient Communication Scheme for BLE in Large Networks](https://pmc.ncbi.nlm.nih.gov/articles/PMC7664676/) — Density-dependent interval selection and discovery modelling. [pmc.ncbi.nlm.nih](https://pmc.ncbi.nlm.nih.gov/articles/PMC7664676/)
- [Bluetooth Mesh under the Microscope](http://arxiv.org/pdf/1908.09505.pdf) — Resource cost and collision consequences of flooding/retransmission. [arxiv](http://arxiv.org/pdf/1908.09505.pdf)
- [Bluetooth Mesh networking principles](https://docs.silabs.com/btmesh/9.1.0/btmesh-network-optimization/03-bluetooth-mesh-networking-principles) — TTL and relay-retransmission semantics. [docs.silabs](https://docs.silabs.com/btmesh/9.1.0/btmesh-network-optimization/03-bluetooth-mesh-networking-principles)
- [RFC 6206: Trickle](https://www.rfc-editor.org/rfc/rfc6206.html) — Suppression and interval adaptation for replicated state.

## Delay-tolerant routing

- [RFC 6693: PRoPHET](https://www.rfc-editor.org/rfc/rfc6693.html) — Standardized encounter-predictability routing.
- [Spray and Wait](https://doi.org/10.1145/1080139.1080143) — Bounded-copy alternative to epidemic replication.
- [Epidemic Routing](https://doi.org/10.1109/TNET.2003.813506) — Baseline anti-entropy/store-carry-forward design.

## Positioning

- [Android raw GNSS](https://developer.android.com/develop/sensors-and-location/sensors/gnss) — Raw measurements are mandatory from Android 10 onward. [developer.android](https://developer.android.com/develop/sensors-and-location/sensors/gnss)
- [Smartphone GNSS accuracy evaluation](https://journals.plos.org/plosone/article/file?type=printable&id=10.1371/journal.pone.0327896) — Real handset error distributions. [journals.plos](https://journals.plos.org/plosone/article/file?type=printable&id=10.1371/journal.pone.0327896)
- [Urban-canyon sidewalk matching](https://link.springer.com/article/10.1186/s43020-025-00159-8) — Multipath/NLOS scale and urban error reduction. [link.springer](https://link.springer.com/content/pdf/10.1186/s43020-025-00159-8.pdf?error=cookies_not_supported&code=ad456eaf-1dab-469a-8eb5-28922bd54e8c)
- [BLE versus UWB distance measurement](https://arxiv.org/pdf/2101.09075v1.pdf) — Why body-blocked RSSI ranging is unreliable. [arxiv](https://arxiv.org/pdf/2101.09075v1.pdf)
- [Pedestrian inertial navigation overview](https://arxiv.org/html/2407.21676v1) — Thirty-second drift comparison for inertial approaches. [arxiv](https://arxiv.org/html/2407.21676v1)
- [Bluetooth Channel Sounding overview](https://www.bluetooth.com/channel-sounding-tech-overview/) — PBR/RTT operation, accuracy factors and ambiguity. [bluetooth](https://www.bluetooth.com/channel-sounding-tech-overview/)

## Security

- [NIST SP 800-38D](https://www.govinfo.gov/content/pkg/GOVPUB-C13-PURL-LPS70006/pdf/GOVPUB-C13-PURL-LPS70006.pdf) — GCM nonce/IV uniqueness requirements. [govinfo](https://www.govinfo.gov/content/pkg/GOVPUB-C13-PURL-LPS70006/pdf/GOVPUB-C13-PURL-LPS70006.pdf)
- [RFC 9420: Messaging Layer Security](https://www.rfc-editor.org/rfc/rfc9420/) — Group forward secrecy and post-compromise security. [rfc-editor](https://www.rfc-editor.org/info/rfc9420/)
- [Apple Find My security](https://support.apple.com/guide/security/find-my-security-sec6cbc80fd0/web) — Rotating broadcast keys and end-to-end encrypted crowd reporting. [support.apple](https://support.apple.com/en-om/guide/security/sec6cbc80fd0/web)
- [Google Find Hub data protection](https://support.google.com/product-documentation/answer/14796936) — Temporary identifiers, E2E encryption and anti-abuse aggregation. [support.google](https://support.google.com/product-documentation/answer/14796936)

## Simulation

- [ns-3 model library](https://www2.nsnam.org/docs/release/3.44/models/html/index.html) — Shows the maintained built-in model scope and lack of standard BLE support. [www2.nsnam](https://www2.nsnam.org/docs/release/3.44/models/html/index.html)
- [OMNeT++ BLE model](https://omnetpp.org/download-items/BLE.html) — Available but old MiXiM-based BLE PHY/link-layer model. [omnetpp](https://omnetpp.org/download-items/BLE.html)
- [BLE 5 system-level simulator](https://github.com/behbad/Bluetooth-Low-Energy-5-System-Level-Simulator-BLE5) — Third-party implementation claiming auxiliary advertising support. [github](https://github.com/behbad/Bluetooth-Low-Energy-5-System-Level-Simulator-BLE5)

***

# Possibly wrong beliefs

- `SCAN_MODE_LOW_LATENCY` means the receiver is continuously available at the RF level.
- A 100 ms advertising interval guarantees ten transmitted payloads per second.
- Every successful `setAdvertisingData()` callback corresponds to a transmitted logical frame.
- Delivery per changing sequence number is the same metric as physical advertising-event PRR.
- Restricting scanning to 1M removes all extended-advertising secondary-channel effects.
- Disabling Wi-Fi isolates Bluetooth-controller scheduling.
- Weak RSSI means the relay is geographically farther away.
- Always-on Coded PHY necessarily improves end-to-end delivery in dense crowds.
- Two advertising sets provide independent redundancy.
- A 120-second duplicate cache is sufficient when urgent payloads live for ten minutes.
- End-to-end ACK reception automatically tells every relay to delete its cached copy.
- `originId | packetId | crewId` can never repeat across reinstall, restore or counter reset.
- AES-GCM with a group key authenticates which individual member sent an SOS.
- Encrypting a stable public crew tag prevents tracking.
- An AUC measured on two phones predicts performance on other OEMs.
- NPU execution is automatically more battery-efficient for a tiny logistic model.
- A 30-second velocity coast always improves location accuracy.
- BLE RSSI can meaningfully refine a GPS point to metre-level accuracy in a crowd.
- An iPhone that works as a GATT relay in the foreground will remain an equivalent background relay.
- Stock ns-3 or OMNeT++ models Android extended advertising and Qualcomm radio arbitration accurately.
