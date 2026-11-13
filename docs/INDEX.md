# Documentation index

**Rebuilt 2026-11-13.** Current documents only.

- **The plan:** `../ROADMAP.md` § *The series — rebuilt 2026-11-13* (six milestones, effort classes, no
  durations) and `PLAN-MVP.md` for the reasoning behind them.
- **The record:** `../TRACKER-STATE.md`, `REGRESSION.md` (twelve paths), `BASELINE.md`,
  `BEHAVIOUR-MATRIX.md`, `TTS-FIDELITY.md`.
- **The research:** `RESEARCH-2026-10-voice-agents.md` (read as historical — it predates `Overlap` and
  `Resume`), `REVIEW-KYLLENE.md`, `SECOND-OPINIONS-2026-11-13.md`.
- **The rules:** `../DECISIONS.md`, `GOVERNANCE.md`, `../SPRINT-PLAN.md` (superseded in sequence by the
  roadmap; still the description of how work is sequenced), `DOC-TRUTH.yml`.
- **The past:** `archive/`, 126 files, one line each in [`ARCHIVE.md`](ARCHIVE.md). Nothing there is a
  claim about the current build, and the gate does not check it.

**Two rules for adding a document.** If it describes the current build, it goes here and DOC-TRUTH can
check it. If it is a record of a decision, a build brief or a session, it goes to `archive/` and
`scripts/check-docs-tree.py` will insist on it.
