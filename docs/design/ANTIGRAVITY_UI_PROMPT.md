# Prompt for Antigravity (UI/UX rebuild)

Paste everything below the line into Antigravity with the workspace at `C:\Users\Aadityaa\linkfill`.

---

You are rebuilding the look and feel of KHOJ, an offline crowd-safety Android app (Jetpack Compose, minSdk 29). **Only the UI changes.** The behaviour stays exactly as it is.

## Read first
1. `docs/design/khoj_prototype_v3.html`. This is the design to match. Open it in a browser (`python -m http.server 8765 --directory docs/design`, then http://localhost:8765/khoj_prototype_v3.html). Read its CSS and JS for exact colours, sizes, radii, spacing and motion. Use the "Night", "Simple mode" and screen buttons on the right.
2. `PRODUCT.md` (users, principles, accessibility rules).
3. The current UI: `app/src/main/java/app/linkfill/ui/` (`CallTheme.kt`, `CallKit.kt`, `CallRadar.kt`, `CallPages.kt`, `Khoj.kt`, `FindMember.kt`).

## Hard rules
1. **Edit only** `app/src/main/java/app/linkfill/ui/*`, `app/src/main/res/*` and the theme setup in `MainActivity`. **Never** edit `core/`, `MeshController`, `MeshUiState`, `FindMemberSession` or anything under `mesh/`, `find/`, `location/`, `service/`. If a screen needs data that isn't in `MeshUiState`, work it out in the UI from what is there, or leave it out and tell me.
2. **Keep every existing feature.** The prototype shows only some screens (see the feature table below). Do not delete a feature because it isn't drawn; restyle it in the same visual language.
3. **No new features.** No voice dictation, no live notifications, no new permissions, no fake data in the real app. The prototype's names and numbers are sample data only.
4. **Honesty rules stay.** Never show an arrow or distance the data doesn't support; keep showing how old the data is and how sure it is. Never use colour alone to carry meaning (pair with words or shapes).
5. `./gradlew :core:test` must stay green (147 tests). `./gradlew :app:assembleDebug` must build.
6. Do not change phone settings or grant permissions yourself. Never uninstall the app (it wipes data); use `adb install -r`.
7. Do not commit `secrets/`. Work on a new branch `ui-redesign`, with one small commit per screen. Do not push without asking.
8. **Never claim something works unless you have run it on a phone.** For every screen, attach a screenshot of the real app (`adb exec-out screencap -p`) next to the matching prototype screen, and list every difference honestly.
9. Strings: put every new piece of text in `values/strings.xml` (English). Hindi, Marathi, Telugu, Bengali and Tamil must still work; new strings may stay English for now, but nothing may be hard-coded in Kotlin. Text must not break when it is 40% longer.
10. Accessibility: touch targets at least 48 dp (primary actions much larger), TalkBack labels on every drawn icon, respects system font scale up to 200%, respects "remove animations".

## Design language (from the prototype)
- **Style:** refined, iOS-like. Frosted glass for the sheet, tab bar and mini-bar (use `Modifier.blur`/`RenderEffect` on Android 12+ and a flat translucent fallback below it). Soft real shadows, spring motion, large bold titles, inset rounded lists, initials-on-gradient avatars.
- **Font:** keep the bundled Bricolage Grotesque unless it looks wrong in the screenshots, in which case say so and propose a swap. Indic scripts fall back to the phone's Noto fonts.
- **Colours** (copy exact values from the prototype's CSS variables): Day is default; Night is the toggle. Accent blue `#0A84FF`, green `#30B350` / `#30D158` (good), orange `#E58A00` / `#FF9F0A` (warning), SOS red `#FF3B30`. Member colours: Maa `#FF6482`, Papa `#32ADE6`, Aarav `#FF9F0A`, Didi `#BF5AF2`, Nani `#A2845E` (assign by member in order from a fixed list, not by name).
- **Distance colour** (Find screen): `#4FB6F5` far, `#34D399`, `#FFC23D`, `#FF8A1F` near, on a log scale between 5 m and 200 m (see `heat()` in the prototype). The Find screen background darkens that colour so white text stays readable (contrast 4.5:1 or better; check it).
- **Motion:** spring sheet snaps at three heights, rolling distance digits, arrow rotates with a spring, "on target" pulse when within 9 degrees and over 15 m, sonar pulses on the radar every few seconds. Keep all of it off when "remove animations" is set.

## Order of work (do one at a time, stop and show me the screenshots after each)
1. **Theme and components** in `CallTheme.kt` / `CallKit.kt`: palettes (Day, Night), type scale, glass surface, press feedback, avatar, row, inset card, segmented control, large title, buttons (primary, glass, SOS).
2. **SOS flow.** Check how SOS works today (`HoldSos`, `SosDock`, `CountdownScreen` in `Khoj.kt`). The prototype shows tap, then a 5 s countdown with a big cancel. Only change the interaction if it is purely a UI change; if it changes behaviour, keep the current behaviour and tell me.
3. **Find screen** (`FindMember.kt`): adaptive colour background, arrow with the uncertainty wedge, rolling distance, word status, steps estimate ("about N steps", derived as distance divided by 0.7 m, labelled "about"), the honest info line (heard, GPS, compass accuracy), speaker-free (no voice yet), stop button. Keep every existing mode and note (see `FindUi`).
4. **Activity tab** (`BoardTab`), **Group tab** (`GroupTab`, pass-style card, range selector), tab bar, mini-bar for an active Find.
5. **Remaining screens, restyled:** Help tab (`HelpTab`, `BandsPage`, `ShowBandPage`, `FoundBandPage`, `ScanPage`), Settings/Profile (`SettingsTab`, `ProfilePanel`, language picker, battery guidance), Helper tabs (`HelperScanTab`, `HelperAlertsTab`), `SetupFlow`, `RewindPage`, splash.
6. **Simple mode:** a layout choice in Settings that shows big faces, a giant SOS block and a Find screen without the technical line. Only if it can be done without new behaviour; otherwise ask.
7. **Radar last** (`CallRadar.kt`, `RadarTab`): safe-zone disc, heading cone, people as bubbles (name and distance), far people pinned to the edge with an arrow, a ghost bubble for people not heard recently, draggable glass sheet below it. This is the hardest screen; take your time, and show intermediate screenshots. Keep the existing rule that people without a known position are not drawn.

## Features that exist in the app but are not drawn in the prototype (keep them, restyle them)
| Feature | Where it is now |
|---|---|
| Language (English, Hindi, Marathi, Telugu, Bengali, Tamil) | Setup and Settings |
| Found someone: scan a wristband, found report, "found without a band" notice | Help tab, `FoundBandPage`, `ScanPage` |
| Wristbands: make, show, print | `BandsPage`, `ShowBandPage` |
| Helper roles (volunteer, police): scan tab, SOS board with "I'm going", signed official alerts | `HelperScanTab`, `HelperAlertsTab` |
| Setup, create or join a group (QR or code), role choice, permissions | `SetupFlow` |
| Settings: night mode, profile, battery guidance, background location, police key scan, advanced diagnostics | `SettingsTab` |
| Meeting point, short text messages | Activity |
| Pendant mode and Rewind camera | `RewindPage`, Help |
| Battery saver, low-power member | Settings |
| Circle range (30/50/100/200 m) | Group |

Old screens `Common.kt`, `HomeScreen.kt`, `MeshScreen.kt`, `SenderScreen.kt`, `ReceiverScreen.kt`, `SafetyScreens.kt` may still be reachable. Before deleting any of them, show me where each is used (search the code) and delete only if nothing reaches it.

## Report format after each step
- Files changed.
- `:core:test` and `:app:assembleDebug` results.
- Before/after screenshots from a real phone (phones: `adb devices -l`; one is `10BFAX1BU80010U`).
- What differs from the prototype, and anything you skipped or weren't sure about.
- Keep it short and plain.

Start with step 1. Before you write code, list the files you plan to change.
