# Research notes and feasibility

## 1. Neural BLE error correction (Almeida et al., *Sensors* 25(19):6191, 2025)
"Error Correction in Bluetooth Low Energy via Neural Network with Reject Option" — <https://pmc.ncbi.nlm.nih.gov/articles/PMC12526567/>

**What it corrects:**
- **Errors:** single and double **bit** errors inside one BLE packet.
- **Layer:** the physical layer, in simulation (LE 1M, GFSK, 2 samples per symbol).
- **How:** a neural network classifies received signal samples, and its "reject" outputs mark uncertain bits. The method then flips combinations of those bits until the packet's CRC-24 passes.

**Reported results:** about 94–98% of single-bit errors and 54–68% of double-bit errors corrected, in simulation.

**What it needs:** access to the received signal samples, or at least the raw bits with the CRC, before the controller discards the packet.

**Model:**
- **Type:** an extreme learning machine with one hidden layer of 20–200 neurons.
- **Release:** none. No code or model is published.
- **Hardware:** the paper targets microcontrollers and FPGAs. Battery tests on real hardware are listed as future work.

**Can an Android app reproduce it? No.**
- **Hidden packets:** Android apps, and Nearby Connections in particular, never see packets that failed the CRC. The Bluetooth controller drops them.
- **No signal samples:** apps also get no access to signal samples.

So LinkFill AI does **not** claim to reconstruct radio packets. What we took from the paper is the **reject option** (Chow's rule). Our recovery model abstains when its two best predictions are too close or the input is unfamiliar, and hands the decision to deterministic rules. Abstentions are counted on the diagnostics screen.

## 2. Packet-loss modelling: Gilbert-Elliott
Real wireless loss is bursty, and a two-state Markov (Gilbert-Elliott) model is the standard simple way to represent that.
- Blazek and Mecklenbräuker fit it to vehicular packet traces.
- Haßlinger and Hohlfeld found simple Markov models capture Internet loss.

We use it to simulate links in `LinkSimulator`.

**Gap:** we found no published Gilbert-Elliott fit to BLE or Nearby Connections traces. Our parameters are **assumptions**. The fix is to log real acknowledgement timeouts from the diagnostics counters on real phones and fit the model to them. That is future work.

## 3. Application-layer redundancy and erasure codes
- **Tsimbalo, Fafoutis et al. (Bristol):** they studied packet loss in BLE connectionless mode, where retransmission is impossible. They added redundancy at the application layer and report correcting 80% of corrupted packets in a two-storey house. This motivates two of our mechanisms:
  - (a) **piggybacked copies** of recent location fixes (`ADD_REDUNDANCY` and the "retry + redundancy" baseline);
  - (b) **Reed-Solomon** erasure coding for bulk payloads.
- **McCann and Fendick:** they analyse how erasure coding changes residual loss bursts. Their analysis assumes independent losses, so it may understate bursts on a Gilbert-Elliott channel. Our FEC experiment uses the bursty model directly.

**Why no FEC on tiny location messages:** for a 36-byte fix, adding a parity *message* costs as much as resending. We measured this choice instead of assuming it:
- **Redundancy** (carrying the previous fix again, about 48 bytes) gives 87–99% recovery of first-transmission losses in simulation.
- **Reed-Solomon** is applied to the 4 KB test payload, where it avoids round trips.

## 4. Nearby Connections facts we rely on
- **Documentation:** the guides at <https://developers.google.com/nearby/connections/overview>.
- **Strategy:** we use `P2P_POINT_TO_POINT`, one phone to one phone.
- **Who decides acceptance:** both sides must call `acceptConnection`. Verification is left to the app, and Google advises always showing the authentication digits on both screens. We do, and we also mix the digits into our key derivation.
- **Permissions:** we took the per-API-level list from the getting-started page (Bluetooth, nearby Wi-Fi, location). Exception: the page caps `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` at API 31, but on an iQOO with Android 16 Nearby reported "missing permission access wifi state", so we declare both without a cap. They are auto-granted normal permissions.
- **No routing:** Nearby gives no multi-hop routing. We do not claim any.
- **Hidden radio frames:** apps see whole delivered payloads only.
- **Payload size:** BYTES payloads are small. Ours stay under 1,100 bytes.

## 5. The AI experiment as built
| Function | Status |
|---|---|
| 1. ML-assisted recovery-policy selection | Built. MLP trained on 25,318 simulated decisions, shipped in the app and used on-device |
| 2. FEC-assisted missing-data recovery | Built as deterministic Reed-Solomon. The model does not decide when to use FEC: there was no measured case where that helped |
| 3. Location-gap estimation | Built, **deterministic** (constant-velocity dead reckoning), labelled as an estimate. No trajectory model: we had no real walking traces to train or validate one honestly |

**Model quality, honestly:**
- **Validation error:** predicting each action's cost has a validation MSE of 0.596 s². Always predicting the average cost per action scores 0.610 s².
- **Why so close:** most of the cost is random future channel behaviour.
- **What still improves:** the action *ranking* still improves the outcome. The table in [RESULTS.md](RESULTS.md) shows lower staleness than the baselines in bursty scenarios, at the cost of more bytes. On a clean link, nothing beats anything.
