# Mesh research (October 2026)

Two research reports produced by AI research agents from the prompt in the KHOJ project chat ("how can we make the mesh more accurate, stronger and better"). They are saved exactly as received.

| File | What it is | Main idea |
|---|---|---|
| [MESH_RESEARCH_A_hci_diagnosis.md](MESH_RESEARCH_A_hci_diagnosis.md) | "KHOJ Wireless-Mesh Improvement Plan" | Our 30-60% frame reception at 1 m is most likely caused by changing the advertised data every 250 ms (`setAdvertisingData`), made worse by two advertising sets plus scanning. Plan: log HCI, hold each frame still for ~1 s, drop to one 1M set, then change routing. 12 ranked changes and a 7-day experiment plan. |
| [MESH_RESEARCH_B_crowd_scale_roadmap.md](MESH_RESEARCH_B_crowd_scale_roadmap.md) | "Engineering Analysis and Optimization Roadmap for Crowd-Scale BLE Mesh Networking" | Longer report (about 860 lines) covering the same nine questions (A to I), a ranked optimisation plan, a root-cause experiment plan, and a reading list. |
| [MESH_IMPROVEMENT_PLAN.md](MESH_IMPROVEMENT_PLAN.md) | Our phase-wise plan built from both reports | Phases 0-10: measure, diagnose, fix the radio, then crowd routing, healing, security, Link AI, Find accuracy, background survival, simulator. Written so someone with no context can execute it. Not implemented yet. |
| [ANTIGRAVITY_PROMPT.md](ANTIGRAVITY_PROMPT.md) | Hand-over prompt for an agent | Paste into Antigravity to run the plan. |

## Read this before acting on them

- Neither report has been checked by us. Their citations and numbers come from the agents' web search and were not verified. Anything marked UNCONFIRMED inside them really is unconfirmed.
- Nothing in them has been tested on the phones yet. The first experiment to run is report A's: advertiser-only on one phone, scanner-only on the other, one 1M set, payload held fixed, then repeat with updates every 1000, 500, 250 and 100 ms.
- The reports disagree in places and use different assumptions; compare them before adopting a change.
- Measurements the reports start from: two iQOO I2501 phones 1 m apart catch about 30-60% of each other's Bluetooth frames at -40 to -65 dBm. Wi-Fi off, a third phone off, and 1M-only scanning did not change it.
