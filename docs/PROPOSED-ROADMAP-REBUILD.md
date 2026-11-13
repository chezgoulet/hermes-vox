# Proposed roadmap rebuild — for sign-off

Nothing here is applied. `ROADMAP.md` is unchanged until this is approved. The document exists so
that the *shape* can be argued with before any work starts.

Built from: the capability audit of 2026-11-13 (two independent readings, agreeing on the substance),
`docs/PLAN-MVP.md`, and the owner's direction of the same day.

---

## The five rules this roadmap obeys

1. **Testing is front-loaded.** The instruments that prove a promotion come before the promotions, and
   nothing leaves the alpha gate on evidence that does not exist.
2. **Built things first.** Work that already exists is promoted, measured and corrected before
   anything new is built. Nothing ready is held back to keep a milestone tidy.
3. **Then least effort to most.** Items are ordered by effort class, not by theme or by prestige.
4. **No time estimates.** Ordering is by effort and dependency only. With agentic execution, a
   duration is a guess wearing a number's clothes; a wrong guess becomes a promise.
5. **Localisation is a standard, not a release.** Every new build follows the string standard from
   today, every *touched* file is brought up to it, and the series ends by **proving readiness** so
   that 0.10 can be translation rather than archaeology.

**Effort classes** (relative size, no duration attached): **XS** a one-sitting edit, no design ·
**S** a contained change with a test · **M** a component or a behaviour change with a design note ·
**L** an architectural change across several files, a decision, and a device pass · **XL** a sweep
across the whole codebase that must be ratcheted down rather than finished in one go.

---

## Milestone 0 — the gate everything else is judged by

**No user-visible change. Ships nothing if the instruments fail.** This is the only milestone that
must precede all others, because every later exit criterion is one of these lines.

| Item | State | Work | Effort |
|---|---|---|---|
| Extend `event=turn` with `lastSound`, `segs`, `gapP95`, `route`, and `firstToken→firstSound` | exists, partial (`LatencyStats.kt:122`) | finish | S |
| `app-first-sound` and `app-gap` rows in `baseline-diff.py` | absent; `app-gap` has no source at all | build | S |
| **Success sampling** (`#596`): every fifth call plus first-after-install, ten kept, one-tap "send this — it went well" | absent | build | S |
| **Recorded-utterance corpus** replayed into the microphone through the real pipeline | absent; existing replays are modelled envelopes and a JSON trace | build | M |
| Thirty-minute soak harness | absent | build | S |
| Repo truth: one `DECISIONS.md`, an archive for the ~126 files already labelled *historical*, DOC-TRUTH on current files only, a file count the gate enforces | absent | build | S |
| Kill the four false statements the audit found in `ROADMAP.md` (dead constants, phone-tools breadth, wake word "removed", Presence "new") | — | fix | XS |

**Exit:** a release document can quote an `app-first-sound` distribution with its build and n; ten
sampled real calls exist from three installs; a recorded conversation replays reproducibly; the soak
runs for thirty minutes and reports; `docs/` is ≤60 current files plus an archive.

**Depends on:** the owner recording 10–20 spoken utterances on a handset (the only item in this
document that an agent cannot do).

---

## Milestone 1 — promote what already works (least effort, highest user benefit)

Each row is a promotion with a pass, not a build. Order is by effort within the milestone; any row
that passes can ship as soon as it passes — **the milestone does not need to empty before a release is
cut.**

| Item | What already exists | Pass needed | Effort |
|---|---|---|---|
| **Approvals** by touch and voice, timeout to *no* | `PhoneApproval.kt`: two-state consent, 120 s timeout, tap and voice, audited | device proof of one request and one refusal | XS |
| **Supertonic 3** bundle (`#589`) | ruled in D48, absent from `ModelCatalog` | swap bundle, checksum, marker list, bench word-loss on the phone | XS |
| Semantic endpointing default **off**, and the test that pins `true` updated | live at three sites; `AlphaGateTest` asserts true | flip + update test deliberately | XS |
| **Wake word** and **Digital assistant** leave the panel | both still first-class sets | delete the sets, rows, enrolment screen, store | XS |
| **Phone tools**: promote the tiers that are stable | **27 actions across 13 groups**, already implemented, all default off | one action per tier on a device, `#578`'s stale-address confirmation, then remove the rows for the tiers that pass | S |
| **`#404` runs route, `#281` operations line** | both shipped and on the default path | close them as facts, not tasks | XS |
| Retest batch `#538`, `#496`, `#373`, `#374` | fixed in code with catchers | four walkthrough cards | XS |
| **Turn-taking**: acknowledgements, backchannels, resume | `Backchannel.kt`, `Overlap.kt`, `Resume.kt` built and gated | bar written for turn-taking, then a pass | S |
| **Continuous capture** graduates on the capture property | built, gated, coupled to Presence by a gate-time rule only | D38 amendment, budgets re-measured, `event=capture-continuous on=true` on every call | M |
| **Presence**, as the being | a **13-stem catalogue with its own selector and isolated player already ships**; the 2.6 GB renderer is the part that does not earn its cost | scale the catalogue (rules + authored stems), keep the model as opt-in, re-point `#217`'s bar | M |

**Exit:** every promoted set has a pass recorded, its bar quoted, and its gate row removed. Any set
that fails its pass stays gated and gets a card — that is a result, not a failure.

---

## Milestone 2 — small builds that unblock quality

| Item | Why now | Effort |
|---|---|---|
| **Error separation**: auth (401 on this profile's key) vs transport vs gateway, each spoken and shown | the app currently says "unreachable" for a refused key; it has cost the owner two wild goose chases | S |
| **Locked-device refusal for *gateway* approvals** | exists for phone cards only; the gateway path never consults the keyguard | S |
| **`run.steered` surface** | the event arrives and is dropped (`Stream.kt:1036`), so guidance is invisible | S |
| **Clarify surface** | `clarify.request` arrives mid-run and is silently ignored while the mic is open | S |
| **Persist the last N turns**, read-only, marked unchecked | an answer that never arrived currently looks like silence | S |
| **Per-engine speech settings** | one chunk cap and one speed register serve two engines with different rates | S |
| **`#202` triage** of the nineteen log-flagged candidates | a script exists; nobody has run it | XS |

---

## Milestone 3 — the medium build: how a reply is spoken

| Item | What it fixes | Effort |
|---|---|---|
| **Chunk shaping + synthesis ahead**: first piece ~20–30 chars, growth to 3× the previous actual, one clip rendered while the current plays, player decoupled from synthesis | prosody resets every two seconds; latency; four times fewer engine calls on a long reply | M |
| **A/B of the recogniser** (`#569`'s arms), gate off | the "slow recogniser" has never been measured on the default path | M |

---

## Milestone 4 — the hard, mind-facing build: interruption

**Design note first (D45): it changes what a barge does for every user.**

| Item | Why it is the hardest | Effort |
|---|---|---|
| **Steer instead of stop**: cut locally, classify, steer the live run, stop only on an explicit abort | a barge currently posts `/v1/runs/{id}/stop`; a stopped run cannot be steered, so the work cannot be resumed by construction | L |
| **Amend path**: commit on `PROVISIONAL`, extend and re-send if speech resumes | the verdict is computed and discarded today; early commit without amend is the double-speech class (#538) | L |
| Transcript-similarity echo rejection; true pause-not-stop (the comment claims it; the code stops, flushes and releases) | a false trip currently costs the rest of the reply | M |

**Exit:** on the recorded corpus — a backchannel does not cancel; an interruption steers and is
acknowledged; a deliberate abort stops; no double speech after a resume.

---

## Milestone 5 — localisation readiness verified (the last release of the series)

This is the release the owner asked for: **not translation, proof that translation can start.**

**The standard, adopted now and applied to every new build from today:**

1. every user-visible string lives in a resource; no literals in Kotlin, layouts or toasts;
2. no sentence built by concatenation — format arguments and plurals, never `a + b`;
3. numbers, dates, durations and units through locale-aware APIs, with logs pinned to `Locale.ROOT`
   (already true since 2026-11-12);
4. no layout that only fits English;
5. a **pseudo-locale build in the gate**, so a missing string or a clipped layout fails before a
   translator ever sees it.

**The standing correction policy:** any file touched for any reason is brought up to that standard in
the same change. Not a sweep, not a lane, not a promise — a rule about the code you are already in.

**What the milestone contains:** the ratcheting literal count driven to zero (~840 Kotlin sentence
literals, 234 layout literals, a 27-line `strings.xml`); `<plurals>`; a right-to-left pass; the
voice-language mismatch warning; speech recognition following the UI locale with an explicit
override; per-locale gate coverage; and a pseudo-locale run of the twelve paths.

**Effort: XL** — and it is the one place this roadmap *raises* the previous estimate, because the
audit found the extraction has barely begun.

**Exit:** zero literals outside resources as measured by the gate; the twelve paths green under a
pseudo-locale; RTL layouts unclipped; and a translation contract with one language's worth of strings
ready to hand over.

## Then 0.10 — translation

Packs beyond French, plural and gender rules, RTL, the contract's first translator feedback, and
`#229` (which needs a non-English answer to graduate). **No behaviour change on the default path.**
If something needs one, it waits for 0.11.

---

## What is deliberately not in this roadmap

**Push notifications with no call open** (the only feature that would justify a dashboard credential),
**a second platform**, **any new alpha set**, **Karaoke-style word highlighting**, and **any
dashboard transport work**. Each returns only if everything above is done and the owner asks for it.

**The dashboard question is settled by evidence, not preference:** the api_server already carries
turns, approvals, stop and a replayable backlog; the roster is a missing upstream route, not a missing
surface; and a second auth stack would cost the property that makes this app testable off-device.

---

## What this roadmap needs from the owner

1. **Sign-off on the shape** — particularly Milestone 1 shipping piecemeal as things pass.
2. **Ten to twenty spoken utterances recorded on a handset**, with consent, for the corpus. Without
   them, interruption and echo stay judged by ear.
3. **One sitting per promotion** to judge the pass, and the stranger's run at Milestone 5.

## What would change it

- If the corpus shows `app-first-sound` already inside its bar, Milestone 3 shrinks and Milestone 4
  starts sooner.
- If a promotion fails its pass, the set stays gated and the milestone continues past it — nothing
  waits.
- If the literal count turns out to be an order of magnitude smaller than the audit's estimate, the
  final milestone sheds effort but does not move earlier: the standard still applies to everything
  built between now and then.
