# Plan — 0.9.1, "streaming in, behind a setting"

The first release after 0.9.0. Small on purpose: one meaningful change to what users
hear, the evidence that change needs, and the two cheap things 0.9.0 left on the table.

**Why this is a release and not a batch in 0.9.2.** The A/B says the streaming arm is
markedly better on accented speech — 11.9 % WER against 38.8 % — and that improvement
reaches a user the moment the arm is wired, *before* continuous listening exists. The
turn still ends on silence exactly as it does today; only the words get better. That is
worth shipping on its own, and it is the only way to find out how it behaves in the
kitchen before we build three things on top of it.

---

## 1. Wire the arm in (#207) — the prerequisite

- [ ] Catalogue entry: 160 ms chunk, 464 MB, sha256 pinned, runtime download only.
- [ ] **Licence screen**: the NVIDIA Open Model License is revocable and ships no licence
      file upstream — agreement, attribution and model-card link shown and accepted before
      the download, and the weights never bundled.
- [ ] **Setting**, with the Apache-2.0 Zipformer as the fallback. No silent migration of an
      existing install.
- [ ] **Partials**: a streaming recogniser produces them and the current loop does not
      consume them. Wire the stream without disturbing the whole-utterance path.

Nothing is measurable until this exists — an arm the app cannot load cannot be tested.

## 2. Co-residency (#179) — the gate

- [ ] One live call with VAD + the streaming arm + TTS + presence resident. Record RTF,
      peak RSS, worst `MemAvailable`, presence first token, stutter, thermal.
- [ ] **Decide**: default on 8 GB-class devices if RTF holds under 1.0 with everything
      resident; the Zipformer otherwise. Write the amending decision into `DECISIONS.md`
      with the numbers, not the intention.

## 3. The evidence, recorded rather than described

- [ ] **Live-gateway suite** (#167) in CI: the scripted protocol tests over a fake gateway,
      plus a nightly run against a real one. The suite exists (`LiveGatewayTest.kt`) and
      has never run in CI; a new streaming wire path is exactly what it is for.
- [ ] **Behaviour matrix** (#208) on the Pixel 7 and Pixel 9, as a table with the log line
      that proves each row.
- [ ] **Performance baseline** (#209) on the release APK, so 0.9.2 has something to drift
      against.

## 4. The two cheap things 0.9.0 left behind

- [ ] **Presence line: elapsed time.** The one number a mid-run line may honestly carry is
      the one the client knows and the model does not. Pass it in the directive and let the
      guard accept *that* number while still refusing invented ones. The skip path already
      logs what it refused, so this is decided from evidence.
- [ ] **Truncation triage** (#202 / discussion #203). Triage every report against the three
      log lines. Fix if the cause is named; if it is not, it stays open and the channel
      stays open. **It does not block this release** — that was the decision at 0.9.0 and
      nothing has changed.

---

## Exit gates for the cut

- the arm is selectable, co-resident, and the default decision is written with its numbers;
- gate green and one nightly fielded by the owner **in a real room**, on the arm;
- the live suite runs per-PR and nightly; matrix and baseline recorded for the cut;
- truncation either named and fixed, or still open with a reason.

Cut it the same way 0.9.0 was cut: bump on `testing` first (144), notes in
`docs/RELEASE-0.9.1.md`, the in-app banner entry, then merge to `main`, tag, reproducible
build, release, site dispatch. See `docs/RELEASE-PROCESS.md` for the two build gotchas.

## Not in 0.9.1, and where each went

- **Continuous listening** — 0.9.2. It needs the partials this release creates, and it is a
  capture-architecture change, not a setting.
- **Semantic endpointing and re-entry** — 0.9.3, behind continuous listening.
- **F-Droid** — its own track (draft #154), running in parallel; reviewer latency does not
  compress.
- **Multi-profile** (#174) — unscheduled; 1.0 or 1.x is the owner's call.
- **Wake word training** — needs ML compute the House does not have. Not on the path.
