# KHOJ Architecture & Mesh Decisions

Every decision made at a gate or milestone is recorded here.

Format per entry:
- **Decision ID / Topic**
- **Date**
- **Question**
- **Options considered**
- **Evidence (data and reasoning)**
- **Decision**
- **Approved by**

---

### DEC-000: Baseline Commit on Main Before Branching

- **Date:** 2026-10-10
- **Question:** Should current uncommitted changes across `core/`, `app/`, `training/`, and `docs/research/` be committed as a clean baseline before branching to `mesh-improvements`?
- **Options considered:**
  1. Commit all working tree changes as baseline on `main`, then create `mesh-improvements`.
  2. Keep working tree dirty and branch to `mesh-improvements`.
- **Evidence:** Clean git history allows exact before/after diffs, repeatable builds, and reverting without data loss.
- **Decision:** Commit all current changes to `main` (commit `d76a1e6`) and branch `mesh-improvements` from there.
- **Approved by:** Project Owner (via interactive decision)

---

### DEC-001: Phase 2 Radio Configuration Gate

*(Pending Phase 2)*

---

### DEC-002: Phase 3 Single-Hop Delivery & Dwell Settings

*(Pending Phase 3)*

---

### DEC-003: Phase 7 Link AI vs Rule Selection

*(Pending Phase 7)*
