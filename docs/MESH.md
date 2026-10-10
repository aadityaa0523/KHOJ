# KHOJ Crew Mesh: design

Multi-hop messaging and position sharing for a crew (family or group), phone to phone over Bluetooth, with no mobile network. Every phone running the app relays every KHOJ packet. Only the crew can read its own packets.

## How it relates to beacon / RNDVU (github.com/Charrlllezz/beacon)

RNDVU gets its range from **Meshtastic LoRa radios**: each person carries a radio, and Bluetooth only links each phone to its own radio. We took these ideas from it:
- **short typed messages:** rally point, meetup, SOS, text;
- **crew filtering:** strangers' nodes never appear as crew;
- **simulation before hardware:** testing the protocol without real radios.

The difference: **KHOJ's mesh runs on the phones' own Bluetooth radios, with no extra hardware.** Packets are kept under 200 bytes and carry no Bluetooth-specific fields, so the same `MeshRouter` can later run over a LoRa radio as a long-range backbone. That transport is **not built**.

## Packet

There is a 37-byte header, then the body (at most 200 bytes in total):

```
'K' | version | type | flags | ttl | hops | linkSeq (2) | relayId (4) | crewTag (8) | runEpoch (4) | originId (4) | packetId (4) | originTime (4) | len | body
```

- **Link fields** (`ttl`, `hops`, `relayId`, `linkSeq`) change at every hop.
- **End-to-end fields** (`type`, `crewTag`, `runEpoch`, `originId`, `packetId`, `originTime`) never change, and the crew key authenticates them.
- **Flags:** bit `0x01` marks a frame from a battery-saver member (see Low-power members below).
- **Body:** AES-256-GCM under the crew key, with a 128-bit (16-byte) tag. The 96-bit nonce is `(runEpoch, originId, packetId)`. `runEpoch` is randomly drawn at each process start, completely protecting against nonce reuse if the packet counter or originId reset after an OS kill or crash. Repeated SOS messages reuse the exact original ciphertext.
- **Crew key:** HKDF-SHA256 from a 16-character code (about 80 bits) with salt `KHOJ crew v2`, deriving independent keys for `content`, `tag`, and `tunnel`. `crewTag` is an ephemeral 8-byte HMAC tag that rotates every 15 minutes, preventing physical tracking of static crew identifiers.
- **Limitations:** the crew key is static across the session without forward secrecy, but ephemerality shields metadata. Pre-decryption rate limits protect against denial-of-service frame flooding.

| Type | Use |
|---|---|
| BEACON | Position, accuracy, battery, name, and whether this phone can be ranged; every 3 s (every 15 s in battery saver) |
| SOS | Position + note; repeated every 10 s until a crew member acknowledges it, at most 6 times |
| RALLY | Meeting point + time; acknowledged like SOS |
| TEXT | Up to 100 bytes |
| ACK | End-to-end acknowledgement of an SOS or RALLY |
| NEIGHBOR | Unencrypted filler sent when idle, so neighbours can measure link quality. Battery-saver members send none. |

## Relaying: managed flooding

Each phone relays each new packet once, so packets cross the crowd hop by hop.
- **Duplicate filter:** a 2-minute memory of (originId, packetId). Carried packets (below) stay in it for their whole life.
- **Hop limit:** TTL 6 by default, 8 at most.
- **Relay delay:** 40 ms, plus up to 400 ms when the signal was **strong**, plus up to 250 ms of random jitter. Phones that heard a packet weakly are probably far from the last sender, so they relay first and push it furthest.
- **Suppression:** a pending relay is cancelled once 2 *other* relays have been heard carrying the same packet. In simulation, turning this off tripled the transmissions and reduced reach through collisions.
- **Forgeries:** a packet carrying our crew tag that fails authentication is neither delivered nor relayed.
- **Other crews:** their packets are relayed without being read.
- **Store-carry-forward (packet healing):** repeats from the originator stop after 6 tries, so a phone that arrives after that never hears the packet. So every phone also keeps up to 32 urgent packets (SOS, ACK, RALLY, PUBLIC_SOS, RESPOND, FOUND, FOUND_ACK, OFFICIAL) for their 10-minute life. When a phone it has not heard for 60 s appears, it is offered up to 8 of the newest. Offers are relays, so carriers suppress each other, and duplicates are dropped. Unit-tested: a late arrival receives an SOS exactly once, and re-offers never deliver twice.
- **No FEC in the mesh:** Reed-Solomon coding and `ReliableChannel` are used only in direct sharing. Mesh packets are single frames.

## Link quality, loss prediction and adaptation (from the research brief)

- **Link measurement:** every frame carries the sender's own `linkSeq`, which increases by one each 250 ms slot. Gaps in it give each neighbour's packet delivery ratio (PDR) directly. Battery-saver members send far less often. Signal strength (RSSI) is only known for frames that arrived.
- **Features:** distance (simulation only), RSSI median, minimum, spread, slope and drop, PDR over 0.5, 2 and 10 s, consecutive misses, time since the last frame, long-range mode, and queue depth.
- **Predictor:** logistic regression for "fewer than 2 of the ~2 expected frames arrive in the next 0.6 s". It is trained on simulated links and evaluated on held-out routes; see [LINK_PREDICTOR_RESULTS.md](LINK_PREDICTOR_RESULTS.md).

  | Features | PR-AUC |
  |---|---|
  | Distance only | 0.47 |
  | RSSI only | 0.69 |
  | PDR history only | 0.81 |
  | Full model | 0.83, with recall of 73% at a 10% false-alarm rate |

  This matches the brief's expectation that delivery history beats signal strength, and signal strength beats distance.
- **Adaptation:** the median predicted risk across neighbours picks the link mode. It steps up at once and steps down only after 10 s of lower risk.

  | Mode | When | Repeats per packet | PHY |
  |---|---|---|---|
  | Normal | risk < 0.3 | 1 | 1M |
  | Extra copies | 0.3 ≤ risk < 0.6 | 2 | 1M |
  | Long range | risk ≥ 0.6 or no neighbours | 3 | LE Coded, **if this phone supports it** |
- **Real-data logging:** the app writes labelled rows with the same features to `link_log.csv` in its files directory. Pull them with `adb pull`, then retrain to replace the simulated model with one fitted to real phones.

## Range (simulation; see [MESH_RANGE_RESULTS.md](MESH_RANGE_RESULTS.md))

The setup is a 2 km × 200 m crowd strip with 400,000 people, of whom only 0.25–3% run the app, and the radio parameters are assumed.
- **LE Coded:** crossed the whole strip in 5–6 hops in about 1.5 s, even at 0.25% adoption.
- **1M:** stopped near 1 km, because each hop is shorter and the hop limit runs out.

These are simulation results only. Phones, bodies and interference in a real crowd need measuring.

## Android radio (`BleMeshRadio`)

On-phone measurements and the optimisations they led to are in [DEVICE_PROFILE_IQOO.md](DEVICE_PROFILE_IQOO.md). In short:
- 1M and LE Coded sets run in parallel.
- Nearby runs on demand only.
- Scanning adapts to whether neighbours are around.


- **Sending:** non-connectable, non-scannable BLE extended advertising at TX_POWER_HIGH, about every 100 ms. The advertisement data is swapped every 250 ms slot. Battery-saver members advertise about once a second instead.
- **Low-power members** (Bluetooth Mesh's low-power node idea, adapted): battery saver is a Settings switch. It also turns on automatically at 15% battery when not charging. Members only; Helpers never use it. A low-power member beacons every 15 s instead of 3 s, advertises about once a second, uses power-saving scanning, does not relay or carry packets, sends no filler frames, and marks its frames with header flag `0x01`. Any normal phone that hears a flagged frame acts as its friend, and re-offers the carried packets stored since that phone's previous frame. Unit-tested; no phone run yet.
- **Receiving:** a continuous scan in low-latency mode on every supported PHY, filtered by the KHOJ service-data UUID. The filter keeps scanning alive with the screen off.
- **Limits:** phones without extended advertising (Bluetooth 5) no longer refuse to start. They join through GATT only. LE Coded is used only when `isLeCodedPhySupported()` is true; both phones need it.
- **Background:** a foreground service (`location|connectedDevice`) with a visible notification and a Stop button.

## Many radios, one mesh (like Google Nearby)

Google Nearby Connections hides Bluetooth and Wi-Fi behind one API. KHOJ does the same for its mesh, with four radios, so it can pick the best link or combine several.

| Radio | How | Range (rough, assumed) | Notes |
|---|---|---|---|
| Bluetooth LE advertising | Broadcast to everyone in range (`BleMeshRadio`) | Tens of metres; more with LE Coded | Always on: it is how phones discover each other and measure links |
| Wi-Fi Aware | Discover, then acknowledged messages to each peer (`WifiAwareTransport`) | Similar to or more than BLE | Needs Wi-Fi Aware hardware and Wi-Fi switched on; no network needed |
| Nearby Connections (cluster) | Auto-connects to up to 4 KHOJ phones; Google picks BT or Wi-Fi (`NearbyMeshTransport`) | Similar | Off by default (battery). Paused while direct sharing uses Nearby |
| GATT bearer | GATT server, legacy connectable "door" advert, links on demand (`GattBearer.kt`) | Like BLE, over the link | Cross-platform path for older phones and iPhones; **not yet tested on real phones** |
| Mobile data relay | Uploads to and long-polls `relay/server.js` (`InternetRelay`) | Unlimited, wherever someone has signal | A **bridge**, not a per-frame choice |

**GATT bearer (cross-platform):** every phone runs a GATT server and a legacy connectable "door" advert. A link opens only when at least one side lacks extended advertising (older phones) or is an iPhone. Two modern Android phones use advertising alone and do not connect over GATT. There are at most 4 links. Frames are split into chunks by core `GattFraming` (2-byte header) and rebuilt on arrival, and broken frames are dropped. Nearby Connections on iOS has no Bluetooth path to Android, and Multipeer is Apple-only, so GATT is the channel that reaches both platforms. The spec is in [GATT_BEARER.md](GATT_BEARER.md), and the research is in [CROSS_PLATFORM_RESEARCH.md](CROSS_PLATFORM_RESEARCH.md). **Not yet tested on real phones.**

**How a frame is routed:** `TransportSelector` decides which radios carry each frame.
- **Auto (default):**
  - Bluetooth carries every frame.
  - The best other local link is added when Bluetooth's measured delivery is below 80% or the other link is better.
  - **Every** local link is used for SOS, rally points and acknowledgements, or when predicted risk is 0.6 or more.
- **Best only** and **All** are also available.
- **Quality per radio:** Bluetooth uses the median neighbour delivery ratio. Wi-Fi Aware, Nearby and the relay use their own acknowledgements (Laplace-smoothed recent success).
- **Duplicates:** the router's duplicate filter drops copies that arrive by several routes.

**Mobile data bridge:** a phone with data uploads every packet it originates, and every packet it **first** hears over a local radio (its own crew's packets and anyone's SOS), regardless of local relay suppression. Phones that download a packet re-broadcast it into their own local mesh.

The first version uploaded only when a phone itself relayed. Suppression cancelled most relays in dense crowds, so the bridge rarely fired: the far end got 37% reach at 1% adoption. With the bridge it got 100%.

**Simulation result** (`MESH_RANGE_RESULTS.md`, assumed radio numbers): 2 km crowd strip, 0.25% adoption.

| Radios | Reach at 500–1000 m | Reach at 1–1.5 km | Reach at 1.5–2 km |
|---|---|---|---|
| Bluetooth 1M alone | 79% | 0% | 0% |
| + Wi-Fi Aware | 100% | 48% | 0% |
| + data relay, with 10% of app phones online | 100% | 100% | 100% |

With the data relay, the far end was reached in about 2.3 s.

## Not done yet

- Not yet run on two or more real phones together. Range, PHY switching and background behaviour need field tests.
- LoRa transport (the packet format is ready; the radio code is not).
- Wi-Fi Aware and Nearby cluster transports are untested on real phones; whether the iQOO has Wi-Fi Aware is unchecked.
- Retraining on real link logs.
- Per-member keys and member removal.
- GATT bearer on real phones (see above).
- iOS app: not built, because no Mac is available. The protocol is made iOS-compatible (16-byte tags, shared test vectors in `docs/interop/`). A Swift reference package is being written and is not compiled yet.
