# KHOJ safety features: wristbands, volunteers and police, pendant

The pitch: KHOJ puts an iQOO in a waterproof pendant to watch over a member of a family or group. Helpers (volunteers, staff, security and police) can reunite anyone, including a child who carries no phone, with no network and no central database.

## What is built
| Feature | How it works | Verified |
|---|---|---|
| **Wristband QR** | The family phone creates a printable band: name, age, a phone number, the meeting point, and the family's public key (compressed P-256) in a QR code. | Created and saved as a printable PNG on an iQOO. The QR decodes with a standard ZXing reader (unit test). |
| **Found report** | A volunteer scans the band. The app encrypts the report (volunteer's location, desk name, note) to the band's key (ECDH + AES-GCM) and sends it over the mesh or relay. Only the family phone that made the band can read it. It replies with an acknowledgement, and the volunteer sees "family's phone received it". Reports repeat every 10 s until acknowledged, at most 6 times. Phones that carry urgent packets also hold the report for 10 minutes. | Unit test: volunteer → stranger's phone (sees only ciphertext) → family. Not yet run on two phones. |
| **Roles** | Member (with family or friends) or Helper (volunteers, staff, security, police). Helpers are the backbone: Nearby and fast scanning always on, and no crew needed. | The earlier role screens were verified on the phone. The merged Helper screen is not yet tested on a phone. |
| **SOS board** | Every SOS also goes out as a public SOS (kind, location, name) so responders nearby see it with distance and direction. "I'm going" tells the person "Volunteer X is coming". | Unit-tested message handling. Two-phone run pending. |
| **Official alerts** | Police sign alerts with the authority key (ECDSA P-256). Every app verifies them against the bundled public key and drops forgeries. Police and officials unlock once by scanning the authority key QR in Settings (`secrets/authority_police_qr.png`). | Unit test: valid signature accepted; wrong key or edited text rejected. |
| **Found-person notice** | Someone found without a band: the volunteer broadcasts a short description, and every KHOJ phone lists it with distance. | Unit-tested codec. |
| **Pendant: fall and panic** | Fall = free fall, impact, then 2 s still. Panic = 4 hard shakes in 2 s (Android does not let apps read volume keys in the background). Either starts a 10 s countdown: vibration, a Hindi then English voice prompt, and "Cancel SOS" in the app and the notification. Then crew SOS plus public SOS. | Unit tests: fires on fall and shake patterns, never on 20 s of simulated walking. Not yet tested by physically dropping a phone. |
| **Rewind** | The pendant's rear camera keeps a small photo every 3 s for 3 minutes, with time and GPS, in memory only. It pauses while the QR scanner needs the camera and is erased when the mesh stops. A crew member silent for 45 s triggers "Lost contact, open Rewind". | On the iQOO: the foreground service ran with the camera type; 11 → 23 photos in 30 s with the app in the background; the Rewind screen shows them. |
| **SOS siren and hold** | Holding SOS for 2 s sounds a loud rising and falling siren on the alarm stream at maximum alarm volume, then restores the volume. The phone vibrates hard, and crew phones that receive the SOS vibrate too. A big STOP SIREN button silences it. After an SOS, the phone stays at full power for 10 minutes. | Written. **Not yet tested on phones.** |
| **Group circle** | The group picks 30, 50, 100 or 200 m in Settings (default 50). A member outside the circle makes every phone vibrate and notify "X left the group circle". A member out of radio range for 45 s also makes phones vibrate. | Rule unit-tested (`Circle.kt`). **Not yet tested on phones.** |
| **Urgent packets carried by phones** | Every phone keeps up to 32 urgent packets (SOS, ACK, RALLY, PUBLIC_SOS, RESPOND, FOUND, FOUND_ACK, OFFICIAL) for 10 minutes. It offers up to 8 of the newest to a phone it has not heard for 60 s. Duplicates are dropped. | Unit-tested: a late arrival gets an SOS exactly once, and re-offers never deliver twice. No phone run yet. |
| **Battery saver for members** | Members can switch it on, or it turns on at 15% battery when not charging. Beacons come every 15 s, and the phone does not relay or carry. Helpers never use it. Nearby phones act as its friend and offer it carried packets. | Unit-tested. No phone run yet. |
| **Precise finding (Find Member)** | Being in the group is consent, so there is no switch. Channel Sounding ranging starts by itself within about 40 m. The person found sees "X is finding you" and can pause for 30 minutes. | Written. **Not yet run between two phones.** |

## Edge cases judges raised, and our answers
| Edge case | Answer |
|---|---|
| Child or elder has no phone | Printed wristband. Scanning it alerts only their family. Anyone without the app can still read the name and number. |
| Most people don't have the app | Helpers (volunteers, staff, security, police) carry it as part of duty and form the backbone. Families install it before the event. The relay bridges any phone with data. |
| No network | Bluetooth mesh (1M + LE Coded), Nearby on demand, GATT links for older phones and iPhones, and store-and-forward relay. Phones carry urgent messages for 10 minutes. Reports repeat until acknowledged. |
| Fake or tampered band | A band that fails to parse, or carries an invalid key, is rejected. A forged report cannot be decrypted by the family. |
| Fake "found" message to a family | Only someone who scanned the band holds its key, and the report is bound to that crew and band. |
| Fake police alert | ECDSA signature checked against the bundled authority key. Forgeries are dropped silently. |
| Flooding with fake SOS | Each sender is limited to 5 public packets per 10 s at every relay. |
| False fall or panic alarm | A three-stage fall detector, a 4-shake gesture, and a 10 s spoken countdown with Cancel. |
| Privacy of photos | Rewind keeps photos in memory on the pendant only, for 3 minutes, and never sends them. |
| Privacy of location | Crew traffic is encrypted. A public SOS shares location only when someone asks for help. The band carries no family location. Precise finding needs no switch, because being in the group is consent. The person found sees a notice and can pause it for 30 minutes. |
| Elder can't operate a phone | Pendant mode runs without touching the screen: fall, shake, and a Hindi voice prompt. |
| Phone low on battery | Battery level is in every beacon. At 15% (not charging), battery saver starts: beacons every 15 s, no relaying. A last-gasp beacon is not built yet. |
| Camera busy (scanning while Rewind runs) | Rewind pauses and resumes; each side releases only its own camera session. |
| No GPS fix yet | SOS goes out as "position unknown" and re-sends with the position once GPS finds one. |
| A member leaves the group | The group circle (30, 50, 100 or 200 m, set in Settings) makes every phone vibrate and say who left. |

## Two-phone test and demo (about 5 minutes)
1. **Phone A (family):** KHOJ → Member (with family or friends) → create a crew → create a wristband → show the band on screen, or print it.
2. **Phone B (Helper):** KHOJ → Helper → Start (no crew needed).
3. **Phone A:** Start. Turn on Pendant safety (allow the camera).
4. **Phone B:** "Scan their wristband" → point at phone A's band → "Alert the family with my location".
   - **Expected:** phone A notifies "Riya found by …, 40 m NE" and its feed shows it. Phone B shows "family's phone received it".
5. **Phone A:** hold SOS for 2 seconds (or shake hard 4 times, then wait 10 s). Phone A should sound the siren.
   - **Expected:** phone B's SOS board shows it with distance, and phone B vibrates. Tap "I'm going", and phone A shows "Volunteer … is on the way".
6. **Police:** on phone B, open Settings and scan the authority key QR (`secrets/authority_police_qr.png`) shown on the laptop screen. Send "Ghat 4 closed, use Ghat 6".
   - **Expected:** phone A shows it under "Official alerts (verified)".
7. **Phone A:** open Rewind and scroll back through the last minutes.

## Not built yet (planned)
- **Snan Guard:** water-dip detection with the barometer, light sensor and motion through the waterproof pouch.
- **Crowd-density map** from Bluetooth device counts.
- **Last-gasp beacon** at low battery.
- **iOS app:** no Mac is available yet.
- **Person detection** in Rewind frames.
- **Keystore:** moving the crew code, band keys and police key into the Android Keystore.
