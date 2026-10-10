# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- **Members:** families and groups of friends in very large crowds (stadiums, concerts, festivals, stations, religious gatherings, melas such as the Kumbh). Often first-time smartphone users, older people, people holding a child or a bag; outdoors in strong sun, noise and pushing; often under stress because someone is missing. Mobile network is usually jammed or absent.
- **Helpers:** volunteers, event staff, security and police on the ground. They see who needs help nearby, scan wristbands of found children or elders, and walk to people. Police and officials can also send signed alerts.
- **Demo audience:** judges at the iQOO x Reskilll Grand Finale (Bengaluru, Oct 2026). Confirmed by the user: real users and the demo matter equally.

## Product Purpose
KHOJ ("search") finds your people in a crowd with no mobile network. Phones form an offline Bluetooth mesh; each member's position travels phone to phone. Success: a separated person is found or reunited quickly, and help reaches someone who pressed SOS.

## Positioning
Works with zero network and without everyone having a phone: wristband QR codes for children and elders let any helper alert the family's phone through the mesh. Strangers' KHOJ phones relay encrypted messages they cannot read. Precise finding uses real hardware ranging (Bluetooth Channel Sounding) where the phone has it, and the app never invents a direction it did not measure.

## Operating Context
- One-handed use while walking in a dense crowd; glances, not reading.
- Bright outdoor light; the screen must stay readable.
- Moments of panic: SOS, "X left the group circle", incoming SOS, "found" alerts.
- Setup happens calmly beforehand (create a group, scan the family QR, print wristbands).
- Demo: two or three iQOO phones mirrored on stage.

## Capabilities and Constraints
- Jetpack Compose, minSdk 29, target 36; no icon library (icons are drawn); fonts must work for Devanagari, Telugu, Bengali and Tamil.
- Six languages: English, Hindi, Marathi, Telugu, Bengali, Tamil.
- Features: group (crew) with QR/code join; members list with distance, compass direction and age; Find Member (hardware ranging, GPS arrow with uncertainty wedge, Bluetooth closeness bands, walking direction, last known); hold-to-SOS with siren; group circle alarm; meeting point; wristbands (make, print, scan, found reports); helper screen (SOS board, "I'm going", found-without-band notices, official alerts); pendant mode (fall/shake detection, Rewind camera); battery saver; settings; advanced diagnostics.
- Honesty rules: never show an arrow or distance the data does not support; show age and confidence.

## Brand Commitments
- Name KHOJ (Hindi "search"); engineering name LinkFill.
- The user asked for a bold, "fiery", innovative look (glass, blue/purple/soft-white gradients, glow, a radar-style finder with distance-based colour), but confirmed: **mostly calm, a few wow moments**. Emergencies stay unmistakable.

## Evidence on Hand
- Measured on iQOO I2501: Channel Sounding and BLE supported, no UWB (docs/DEVICE_PROFILE_IQOO.md). Mesh range results in docs/MESH_RANGE_RESULTS.md.
- No user studies, testimonials or field results on real crowds yet; do not fabricate any.

## Product Principles
1. Emergency first: SOS and alarms are reachable in one motion and impossible to misread.
2. Glanceable over readable: one big number, one direction, one colour tells the state.
3. Honest certainty: show how sure the app is; withhold what it does not know.
4. Works for everyone: low-literacy, older users, any of six scripts, any phone.
5. Calm by default, delight in a few chosen moments.

## Accessibility & Inclusion
Large touch targets (48 dp minimum, primary actions far larger), high contrast outdoors, never colour alone for meaning (pair colour with words and shapes), screen-reader labels on drawn icons, respects system font scale, reduced motion where the system asks for it.
