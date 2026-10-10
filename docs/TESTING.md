# Testing

## Automated tests (JVM)

```bash
./gradlew :core:test
```

There are 31 tests in `core/src/test`. They cover:
- **ProtocolTest:**
  - envelope round trip;
  - malformed, oversized, wrong-magic, wrong-version, unknown-type and length-mismatch rejection;
  - expired, from-the-future and bad-timestamp rejection;
  - location payload with redundancy;
  - invalid coordinates;
  - the other payloads.
- **ReliableChannelTest** (two channels wired back to back with switchable loss):
  - delivery and acknowledgement;
  - duplicates discarded but acknowledged again;
  - a lost ACK leading to a retransmission that is deduplicated;
  - bounded retries ending in "stale";
  - backoff bounds;
  - no retransmission after expiry;
  - latest location superseding the previous one;
  - gap detection and recovery by request;
  - recovery from redundancy;
  - wrong session and wrong key rejected;
  - a tampered header failing authentication;
  - reliable stop-sharing;
  - the policy being consulted with correct features;
  - the replay window;
  - the sealer's associated data.
- **FecAndPolicyTest:**
  - GF(256) inverses;
  - Reed-Solomon rebuilding from any k of k+m shards (200 random cases);
  - failing honestly with too few shards;
  - deterministic rules;
  - the ML policy choosing the cheapest action and abstaining when unsure;
  - model file round trip;
  - missing, garbage, wrong-feature and truncated model files reported as unavailable;
  - the bundled model loading;
  - the gap estimate's radius and withdrawal;
  - location states;
  - distance and bearing;
  - ECDH agreement, and the authentication digits being bound into the keys.

Not covered automatically:
- **`LinkSession` and the Nearby, location and service code:** these need Play services and two radios. Use the manual guide below.
- **Android instrumentation tests:** none yet.

## Simulation experiment

```bash
./gradlew :core:trainPolicy     # retrain the model (writes the bundled model file)
./gradlew :core:runExperiment   # regenerate docs/RESULTS.md
```

## Manual two-phone test

You need two Android 10+ phones with Google Play services. Use phones, not emulators: Nearby needs real radios.

1. **Install** on both phones: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.
2. **Go offline but keep local radios:** on both phones turn **mobile data off** and disconnect from any Wi-Fi network. Keep **Wi-Fi switched on** (Nearby may use Wi-Fi Direct or a hotspot without internet), keep **Bluetooth on**, and keep **Location on**.
   - Do not rely on airplane mode. It may switch Bluetooth and Wi-Fi off, and whether they stay on is device-specific.
3. **Phone A:** set a name and tap **Share My Location**. Read the permission explanation, tap Continue, and grant Nearby devices, Location (While using the app) and Notifications. The screen should say *Waiting for someone to find you*.
4. **Phone B:** tap **Find Someone** and grant permissions. Phone A appears in the list as "Name #1234". Tap it.
5. **Confirm:** both phones show the same 4-digit code. Confirm on B ("Codes match"), then on A ("Codes match, share").
   - **Expected:** both show "Securing", then A shows *Sharing with ...* and B shows *Live*. A notification with Stop appears on both.
6. **Verify transfer:** on B check the latitude and longitude, the accuracy, "Received ... s ago" counting up to about 1 s and resetting, the distance, and the arrow (hold B flat and turn it).
   - On A, check *Messages sent / acknowledged* increasing and *Last acknowledged* staying under 2 s.
7. **Loss and recovery:** on either phone open **Diagnostics**, then set *Simulated loss* to **Harsh**.
   - **Expected:** *Missing messages detected*, *Recovery requests sent*, *Recovered by retransmission* and *redundant copies* increase. *Recent decisions* lists model or rule decisions with their timing in microseconds. B's status may briefly show *Updates delayed: showing an estimate*.
   - Set the loss back to **Off** afterwards.
8. **FEC:** in Diagnostics tap **Send FEC test payload**, ideally with Harsh loss on.
   - **Expected on the other phone:** either "Rebuilt all 4096 bytes from N of 14 chunks ... SHA-256 verified", or "Only N of 14 chunks arrived; 10 are needed".
9. **Break the link:** carry A away until the link drops, or turn A's Bluetooth and Wi-Fi off for 20 s, then back on.
   - **Expected:** both show *Disconnected, reconnecting*. B's position goes stale, then a labelled estimate appears, then the estimate disappears once its uncertainty exceeds 50 m. When the radios return, the phones reconnect without a new code and the notice says *Reconnected*.
10. **Stop:** on A tap **Stop Sharing**.
    - **Expected:** B shows *... stopped sharing* and *The shared location has been cleared*. Both notifications disappear. On A, Diagnostics shows no new messages.
11. **Deny a permission:** on a fresh install, deny a permission. The app should explain, offer *Try again* and *Open Settings*, and not start.

## What has been verified so far
- **Unit tests:** `:core:test` passes (31 tests).
- **Build:** `:app:assembleDebug` builds.
- **One phone (iQOO I2501, Android 16):** the app installs and launches, the bundled model loads and its status shows on the Home screen, and the permission explanation and the system location prompt appear.
- **Not yet verified:** the full two-phone flow, steps 3–11. Only one phone was connected during development.
