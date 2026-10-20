# Plan — 0.9.1, "streaming in, behind a setting"

The first release after 0.9.0. Small on purpose: one change to what users hear, and the
presence tuning that change makes measurable.

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
- [ ] **Setting**, with the Apache-2.0 Zipformer as the fallback. No silent migration.
- [ ] **Partials**: the current loop does not consume them. Wire the stream without
      disturbing the whole-utterance path.

Nothing is measurable until this exists.

## 2. Co-residency (#179) — the gate

- [ ] One live call with VAD + the streaming arm + TTS + presence resident: RTF, peak RSS,
      worst `MemAvailable`, presence first token, stutter, thermal.
- [ ] **Decide** and write it into `DECISIONS.md` with the numbers, not the intention:
      default on 8 GB-class devices, or the Zipformer, or the arm on request only.

## 3. Presence tuning — the third leg, with an instrument

Presence is the part users actually hear, and so far it has been judged by ear on one
session. This release gives it a rig and a bar.

**The instrument.** The log already carries `er-presence line/skip/stop`, `er-soul
kind=working`, `er-render-skip` and the `soul(...)` counters. What is missing is a summary
of a *session*: slots offered, lines spoken, skips by reason, repeats, render latency
p50/p95, and any line that landed after the reply. That summary is the tuning instrument —
produce it from the log rather than reading lines by hand.

**The levers, in order of evidence:**

- [ ] **The guard's bounds.** Seven words and no digits were inherited from the beat. If the
      skip log shows refusals for length, the bound is wrong for these lines — and a number
      the *client* supplies (elapsed time) is not an invented fact. Pass the elapsed time in
      the directive and let the guard accept that number while refusing others.
- [ ] **The directive's wording**, once refusals are visible: the soul should sound like it is
      working, not like it is announcing that it is working.
- [ ] **The cadence registers** (Quiet / Conversational / Chatty) — confirm on a real long
      run, not on the 76-second test that produced them.
- [ ] **Mood mapping** (calm / warm / lively / tense → speed and pause) — unmeasured since the
      voice changed to Supertonic.

**The bar for this release:** at Conversational, over one long run, at least two thirds of
the offered slots speak, nothing repeats, nothing lands after the reply's first text, and
render p95 stays under three seconds.

**To read a refused line's text, switch on *Settings → Log transcripts*.** It is off by
default; without it the skip line records `why=` and `text=<hidden>`.

## 4. The two cheap things 0.9.0 left behind

- [ ] **Elapsed time in a mid-run line** — the same change as the guard above.
- [ ] **Truncation triage** (#202 / discussion #203): every report against the three log
      lines. Fix if the cause is named; otherwise it stays open with the channel running.
      **It does not block this release** — that was the 0.9.0 decision.

---

## Exit gates for the cut

- the arm is selectable and co-resident, and the default decision is written with its numbers;
- the presence bar above is met on one real long run, with the summary in the release notes;
- one real-room night with the owner on the arm;
- the **standing cut checklist** passes — it is not release content, see
  `PLAN-0.9.x-to-1.0.md`: live gateway suite green, behaviour matrix on two devices,
  performance baseline recorded, no open severe bug without a written reason.

Cut it the way 0.9.0 was cut: bump on `testing` first (144), notes in
`docs/RELEASE-0.9.1.md`, banner entry, merge to `main`, tag, reproducible build, release,
site dispatch. `docs/RELEASE-PROCESS.md` carries the two build gotchas.

## Not in 0.9.1, and where each went

- **The listening half** — 0.9.2, both halves together behind separate switches.
- **F-Droid** — its own rail (draft #154), switching on whenever it is accepted.
- **Multi-profile** (#174), **wake-word training** — homeless by decision, not by accident;
  see the to-1.0 plan.
