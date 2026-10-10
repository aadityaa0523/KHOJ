# KHOJ: the app, in one page

**KHOJ finds your family in a crowd, even with no mobile network. Helpers and police can reunite anyone, including a child who carries nothing.**

KHOJ works at any crowded place: stadiums, concerts, festivals, stations, religious gatherings and melas. The Kumbh (Prayagraj, Haridwar, Nashik, Ujjain) and the Varanasi ghats are examples. It runs on ordinary Android phones and was tested on an iQOO with a Snapdragon SM8850 and Android 16. The phone can be worn in a waterproof pendant.

## Who uses it
| Person | What they do in KHOJ |
|---|---|
| **Member (family or friends)** | One group per family or friend group. Everyone sees each other with an arrow and distance. SOS. Meeting point. Wristbands for children and elders. |
| **Child or elder without a phone** | Wears a printed wristband QR. Anyone who finds them scans it, and only their family's phone gets the alert. |
| **Helper** (volunteers, staff, security, police) | Their phone becomes part of the network backbone. They see everyone asking for help nearby, scan wristbands and register people found without a band. |
| **Police and officials** | Everything a Helper has, plus official alerts that every phone verifies by signature. They unlock this by scanning the authority key QR in Settings. |

## The emergency-first screen (member)
1. **Status strip:** green "Offline network on · 2 family nearby", or red "KHOJ is off" with a Turn on button.
2. **Alert banners:** big and coloured, with an arrow to the place.
   - "Lost contact with Amma, last seen 120 m north-east", with a "See what your pendant saw" button.
   - "Riya has been found by Volunteer Ghat 4".
   - "Help is coming: Volunteer Ravi is on the way".
   - Verified official police alerts.
3. **A giant red SOS button:** press and **hold for 2 seconds**. A ring fills while holding, so a pocket bump won't send it. A loud siren sounds, the phone vibrates hard, and a big STOP SIREN button appears. It alerts the family (encrypted) and, with location, Helpers and police nearby. With no GPS fix yet, it sends "position unknown" and sends the position as soon as GPS finds one.
4. **My family:** each member shows a **compass arrow pointing at them**, the distance, and how long ago they were heard. The group circle (30, 50, 100 or 200 m, set in Settings) makes every phone vibrate when someone leaves it.
5. **Meeting point:** an arrow to it, plus one button, "Make my place the meeting point".
6. **Big buttons:** Found someone (scan a band), Bands for children and elders, Rewind, Settings.

**Helper screen (volunteers, staff, security, police):**
- A "People needing help" list with arrows and an **I'm going** button.
- A big **Scan wristband** button.
- "Found someone without a band" (sends a description to everyone).
- The official-alert box (police and officials, after the key scan).
- A smaller SOS button.

**Fall or panic:** the whole screen turns red with a huge countdown and an **I'M OK** button the size of a palm. The voice prompt plays in the chosen language.

## One-time setup (about 1 minute)
1. **Language:** English, हिन्दी, मराठी, తెలుగు, বাংলা, தமிழ். The whole app switches instantly.
2. **Name and role:** "With family or friends" or "Helper". Police and officials scan the authority key QR later, in Settings.
3. **Family group:** create one, then show its QR. Other members **scan the QR** to join (a typed code also works).
4. **Permissions:** one screen explaining why, then "Start KHOJ".

After setup, KHOJ opens straight to the emergency screen and the offline network starts automatically.

## Camera: where it is used
| Feature | What the camera does |
|---|---|
| **Wristband scan** | Reads a child's or elder's band QR, offline, with no internet or cloud. |
| **Join a family** | Scans the family QR instead of typing a code. |
| **Police key** | The police phone scans the authority key QR once to unlock signed alerts. |
| **Rewind (pendant mode)** | The pendant's rear camera keeps a small photo every 3 s for the last 3 minutes, with time and place, **in memory only**. After a separation, the family scrolls back to see where they were together. It pauses while the scanner uses the camera and is erased when KHOJ turns off. |

## Languages
- **Covered:** all emergency screens, notifications and the spoken fall or panic prompt, in English, Hindi, Marathi, Telugu, Bengali and Tamil. Directions use local words (उत्तर, ईशान्य, ఉత్తరం, வடக்கு…).
- **Phone settings:** on Android 13+, the language can also be chosen in the phone's per-app language settings.
- **Voice:** the spoken prompt uses the phone's text-to-speech voice for that language, falling back to English if the phone has none.
- **Not covered:** the "Advanced" engineering screens (radios, diagnostics, direct sharing) stay in English.
- **Translation quality:** the translations were drafted by the development team's AI assistant and **need review by native speakers** before public use.

## What runs underneath
- **Network:** an offline mesh over Bluetooth (normal and long-range LE Coded at once), with GATT links for older phones and iPhones, Wi-Fi Aware or Nearby when useful, and a mobile-data relay when anyone has signal. See [MESH.md](MESH.md).
- **Encryption:**
  - family traffic is encrypted;
  - wristband reports are encrypted to the family's key;
  - police alerts are signed.
- **Recovery:** messages repeat until confirmed, phones carry urgent messages for 10 minutes for anyone who arrives later, and link loss is predicted to decide when to send extra copies. See [LINK_PREDICTOR_RESULTS.md](LINK_PREDICTOR_RESULTS.md).
- **Safety features:** see [SAFETY.md](SAFETY.md) for the full edge-case table and the two-phone demo script.

## Edge cases (short version)
| Situation | KHOJ's answer |
|---|---|
| Child has no phone | Printed wristband; family-only encrypted "found" alert; name and number readable by anyone |
| Most people lack the app | Helpers (volunteers, staff, security, police) are the backbone; families install it before the event |
| No mobile network | Bluetooth mesh with long-range mode; reports repeat until confirmed, and phones carry urgent messages for 10 minutes for late arrivals |
| Elder can't use a phone | Pendant: fall and shake detection, spoken countdown, no screen needed |
| Accidental SOS | Hold-to-send for 2 s; 10 s countdown with I'M OK for automatic triggers |
| Member leaves the group | Group circle (30, 50, 100 or 200 m, set in Settings) makes every phone vibrate and say who left |
| No GPS fix yet | SOS goes out as "position unknown" and re-sends with the position once GPS finds one |
| Phone battery low | Battery saver at 15% (members only): beacons every 15 s and no relaying; Helpers never use it |
| Fake police message or fake found report | Signature check; family-only encryption |
| Privacy | Rewind photos never leave the phone; location is public only when you ask for help |

## Verified on the phone so far
- **Language screen:** shows all six scripts, and choosing हिन्दी switched the whole setup to Hindi without losing the step.
- **Family step:** shows the family QR.
- **Wristband:** created and saved as a printable image.
- **Pendant mode:** ran with the camera foreground service, and Rewind kept capturing with the app in the background.
- **Not yet verified:** two-phone flows (band scan → family alert, SOS → Helper "I'm going", police alert) are unit-tested only.
- **Not yet tested on phones:** the SOS siren and vibration, the group circle, GATT links for older phones and iPhones, and automatic precise ranging (Channel Sounding) between two phones.

## Not built yet
- Snan Guard (dip detection through the waterproof pouch)
- Crowd-density map
- Low-battery last beacon
- An iOS app (no Mac available yet)
- Native-speaker review of the translations
