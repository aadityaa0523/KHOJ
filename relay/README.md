# KHOJ relay server

This server bridges the crew mesh over mobile data. Any crew phone with signal uploads mesh packets here and long-polls for its crew's packets. Phones far apart then reach each other whenever at least one phone in each area has data.

- **It can't read packets.** They are sealed with the crew key on the phone, and the server checks only the header magic and the crew tag.
- **Memory only:** at most 500 packets per crew, each kept for 10 minutes. Nothing is written to disk.
- **No dependencies.** It needs Node 18 or newer.

```bash
node server.js 8787      # listen on port 8787
node --test              # run the test
```

## For a demo
1. Run the server on a laptop.
2. In the app, open **Crew Mesh**, then **Relay server URL**:
   - **Phones on the same Wi-Fi:** use `http://<laptop-LAN-IP>:8787`.
   - **Over mobile data:** expose the server publicly, for example with a tunnel service, and use its https URL.

## API
| Method | Path | |
|---|---|---|
| POST | `/v1/crews/{crewHex8}/packets` | Raw packet body (200 bytes max on the phone, 256 here). 204 on success; 400 if it isn't a KHOJ packet for that crew; 429 above 60 uploads per IP per 10 s. |
| GET | `/v1/crews/{crewHex8}/packets?after=N&wait=S` | `{next, packets: [base64...]}`. With `wait`, it waits up to 25 s for new packets. |
| GET | `/healthz` | `{ok: true}` |

## Limits
- **No authentication:** anyone who knows a crew tag (it appears in packet headers) can download that crew's encrypted packets or upload junk. Junk fails the crew-key check on the phones and is dropped.
- **Rate limiting** is per IP only.
- **Hosting:** this is a demo relay, not a hardened service.
