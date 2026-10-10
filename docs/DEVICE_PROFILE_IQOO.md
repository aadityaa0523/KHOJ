# Device profile: iQOO I2501 (Snapdragon SM8850, Android 16)

Measured on the phone over adb on 2026-10-10, with one phone and no peers in range.

## Radio capabilities
Sources: `dumpsys bluetooth_manager` (HCI controller section), `pm list features`, and the app's own capability log (`adb logcat -s KHOJ`).

| Capability | Value | Use in KHOJ |
|---|---|---|
| Bluetooth controller | HCI/LMP version 0x0e = **Bluetooth 6.0** | |
| LE Coded PHY (long range) | **Yes** (LE feature bit 11) | A second advertising set on LE Coded, running all the time |
| LE 2M PHY | Yes | Not used |
| Extended advertising | Yes, up to **1,650 bytes**, **16 advertising sets** | Two of our own sets (1M + Coded), plus Nearby's |
| Periodic advertising | Yes | Not used |
| LE Power Class 1 | Yes (bit 15, up to +20 dBm in hardware) | Android exposes at most `TX_POWER_MAX` = **+1 dBm** to apps, confirmed in the advertiser dump |
| Advertising coding selection (S=2/S=8) | Yes (bit 40, Bluetooth 6.0) | No public Android API found; not used |
| Channel Sounding | **Yes** (feature flag and LE bit 46) | Possible future "last metres" finder; needs paired phones |
| Hardware scan filters | 64, offloaded | Our filter on the KHOJ service-data UUID is offloaded |
| Wi-Fi Aware | **No** | Wi-Fi Aware transport disabled; Nearby used for the Wi-Fi path |
| Wi-Fi Direct | Yes | Used underneath by Nearby Connections |
| Wi-Fi RTT | No | |

## What the radio actually ran
From the advertiser map in `dumpsys bluetooth_manager` while the mesh was on:
- **Our set 1:** extended, non-connectable, **LE 1M** primary and secondary, interval 160 × 0.625 ms = **100 ms**, **+1 dBm**.
- **Our set 2:** the same on **LE Coded**.
- **Nearby's set:** a separate legacy 1M advertisement (tag `nearby_connections`).
- **Our scan:** filter `ServiceDataUuid=6b686f6a-...`, `LOW_LATENCY` while starting up.
- **Errors:** 0 advertising errors after about 400 frames at 4 data updates per second per set.

## Measured cost (`top`, one phone, no peers)
| State | App CPU | Bluetooth process CPU |
|---|---|---|
| Mesh screen open (Compose redrawing every 500 ms) | ~8% | ~9% |
| App in background, mesh on, Nearby always on | ~1.3% | **~10%** |
| App in background, mesh on, Nearby off | ~1.2% | **~2.8%** |
| After 60 s alone, adaptive scan (dropped to low power) | ~0–3% | **~3.1%** |

**Conclusion:**
- **Dual-PHY Bluetooth advertising is cheap.**
- **Nearby's always-on discovery is the expensive part,** about 3–4x the Bluetooth process cost, and it bought nothing without peers.

## Optimisations applied after these measurements
1. **Always run 1M and LE Coded advertising sets in parallel** where Coded is supported. This replaces switching PHY by mode, which tore down the set (losing frames) and only reached long-range listeners once risk was already high.
2. **Nearby on demand:** it starts only when Bluetooth already hears KHOJ phones *and* there is a pending SOS or rally, high predicted loss, or "every link" mode is chosen. It then runs for 60 s.
3. **Adaptive scanning:** low-latency while neighbours were heard in the last 60 s, something urgent is pending, or in the first minute. Otherwise it drops to balanced, which this phone's stack applied as `LOW_POWER` in the background. Switches are limited to one per 30 s, because Android blocks frequent scan restarts.
4. **Default radios per phone:** Wi-Fi Aware where present, otherwise Nearby (on demand).
5. **Battery-optimisation prompt:** the app is not exempt by default (`dumpsys deviceidle whitelist`). The Crew Mesh screen offers the system "Allow background running" dialog. That choice belongs to the user.

## Still to measure (needs 2-3 phones)
- Real reception range on 1M versus LE Coded, outdoors and in a crowd.
- Whether the scanner hears Coded advertisements reliably while also scanning 1M.
- Whether the power-saving scan still catches frames fast enough in the background, and how quickly it returns to fast scanning.
- Nearby on-demand connection time.
- Battery drain per hour with the screen off.
