# Prompt for Antigravity

Paste everything below the line into Antigravity, with the workspace opened at `C:\Users\Aadityaa\linkfill`.

---

You are taking over engineering on KHOJ, an offline crowd-safety Android app. Phones form a Bluetooth mesh with no mobile network and no extra hardware. Your job is to carry out a phased plan that improves the mesh, using two research reports that are already in the repo.

**Read these files first, in this order, before you write any code:**
1. `docs/research/MESH_IMPROVEMENT_PLAN.md`. This is your plan. Read sections 0 to 6 fully; they cover the project, the problem, the ground rules, setup and the code map.
2. `docs/research/README.md`.
3. Skim `docs/research/MESH_RESEARCH_A_hci_diagnosis.md` and `docs/research/MESH_RESEARCH_B_crowd_scale_roadmap.md`. The plan cites their sections; open them when a phase points you there.

**How to work:**
- Do the phases in order: 0, 1, 2, 3, then 4 to 9. Phase 10 is optional. Finish a phase's exit criteria before starting the next one, unless the plan says that phase can run in parallel.
- At the start of each phase, write a short checklist from that phase's steps. Then read the files listed under "Read first" before editing them.
- Phases 2, 3 and 7 end at a **decision gate**. Stop there and show me the evidence (numbers and what they mean) and your recommendation. Wait for my answer before continuing. Also stop and ask me whenever the plan says "ask the owner".
- Keep `docs/research/BENCH_RESULTS.md` and `docs/research/DECISIONS.md` up to date as you go (Phase 0 creates them). Every number needs a date, a build git hash, the phones used and the setup.
- After every code change, run `./gradlew :core:test`; it must stay green. Change tests to match new behaviour, but never delete a test to make it pass.
- Make small commits on the branch `mesh-improvements`, one per logical step. Each message says what changed and what was measured. Ask me before pushing to GitHub.

**Hard rules (these override anything in the reports):**
1. Use phones only. No extra hardware, no BLE sniffer, no map downloads, no server the mesh depends on.
2. Never invent measurements, citations or results. Write "not measured" or "UNCONFIRMED (report A/B)" instead. Treat claims in report B marked "confirmed" as unverified hypotheses.
3. Do not change phone system settings or grant permissions yourself. This includes Developer options, the HCI snoop log, battery optimisation and location "all the time". Tell me exactly what to tap and wait.
4. Never uninstall the app from a phone; that wipes its data. Always use `adb install -r`. Every phone in a multi-phone test must run the same build.
5. Never commit `secrets/`.
6. Keep the product decisions listed in section 4 of the plan:
   - Find is arrow-first, with no compass words, no percentages and no "last known" text.
   - SOS means siren, torch blinking and a message with location, and it works without GPS.
   - Precise Find switches on automatically when the person is in range.
   - Wi-Fi Direct is used for Find only.
   - Link AI stays on the Qualcomm NPU unless I agree to change it.
7. If you change the wire format (`MeshPacket`, `MeshMessage`, `CrewKey`, `GattFraming`), you must also do all of these:
   - regenerate `docs/interop/khoj_test_vectors.json` by running `InteropVectorsTest`;
   - update the Swift port in `ios/KhojMesh/`;
   - update `docs/MAC_SETUP_IOS.md`;
   - bump `MeshPacket.VERSION` when old phones would misread the new frames.
8. Match the style of the surrounding code: Kotlin, short KDoc comments that explain *why*, no speculative abstractions. Write the smallest change that fully does the step.

**Environment (Windows, Git Bash):**
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/Aadityaa/linkfill
./gradlew :core:test
./gradlew :app:assembleDebug
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> logcat -s KHOJ
```
- Prefix commands that pass phone paths with `MSYS_NO_PATHCONV=1`.
- USB connections drop often, so re-check `adb devices` before each step.
- There are three test phones; `10BFAX1BU80010U` is an iQOO I2501.
- If a phone shows `unauthorized` or is missing, ask me to connect it and approve it.

**When you report back at a gate or at the end of a phase, include:**
- what you changed (files);
- test results;
- phone results as a before/after table;
- anything that failed, anything you skipped and why;
- the decision you need from me, with your recommendation.

Keep reports short and plain.

Start with Phase 0. Your first step is to run `git status`. If there are uncommitted changes, ask me whether to commit them as a baseline before you create the branch.
