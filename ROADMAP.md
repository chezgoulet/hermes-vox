# Hermes Vox — Roadmap

**Rebuilt 2026-11-19** after the owner's rulings on the three-lens review (D49–D54). It replaces the
M0–M5 series of 2026-11-13. The old series is not deleted from history: it is in git, and the diff is
in the pull request that landed this file.

Two things the owner asked for, and this document obeys them:

- **Promotions happen as part of the series, not after it.** Every release names the set it promotes
  or the row it removes, and the alpha panel shrinks as the series goes.
- **Polish rides along.** A rough edge found in use ships in the next cut. Polish is not a theme, does
  not need a version, and is never deferred to keep a milestone tidy.

And one thing the review established, which changes the shape: **a gate is not a release.** A gate has
an exit a script prints. A release has a sentence a user could say. The old series had two gates
wearing version numbers. They are separated here.

`docs/PLAN-MVP.md` holds the reasoning behind the MVP and the two app-owned latency rows. This document
holds the order. Where the two disagree, this document wins and `PLAN-MVP.md` is amended in the same
pull request.

---

## What MVP is, and the gap to it

**MVP is a call you would keep having, on the default path, with the alpha gate off.**
(`docs/PLAN-MVP.md:45-46`.)

Four promises make that sentence true. Measured against the code at `ecd7ffc3`:

| Promise | State on the default path | What closes it |
|---|---|---|
| One reply that starts within the bar and ends promptly | **partly met** | `app-first-sound` and `app-gap` judged per cut (G0.2), then R2 |
| An interruption that keeps the work | **architecturally unmet** — a barge posts `/stop`, and a stopped run cannot be steered | R3 |
| Durability: an hour of use does not degrade | **unmeasured** — no soak artefact exists | F5 (`scripts/soak.sh`), first real run |
| A voice that is pleasant to be interrupted by | **behind the alpha switch** | P (Presence programme), R2 |

**The gap is not a version number.** It closes when those four are true with the gate off, and each
release says which promise it moved. Anything that does not move one of the four is polish, an
instrument, or future work — and is labelled as such.

---

## The rules every release obeys

From `docs/GOVERNANCE.md`, not negotiable per release:

- **No date.** A release ships when its gates are green. The series is an order, not a calendar.
- **One theme, one sentence, one device pass.** A release is one sentence a user could say, and at most
  one sitting on the phone. A second theme is the next release.
- **Every release says what it promotes.** A promotion is a line in the release, with the bar that
  justified it quoted. A set that fails its bar stays gated and gets a card — that is a result, not a
  failure.
- **Nothing graduates on an instrument that does not exist.** If the bar is not in the tree and
  runnable, the promotion is not scheduled.
- **A promotion changes no default** unless the release flips that default in the same change, or the
  promotion is a major (D45) with a design note and a call.
- **One human run** of the candidate before the tag, by someone other than the cutter wherever a
  volunteer exists, recorded in the release document.
- **Twelve regression paths**, the closed tracker read by `scripts/check-cut.py`, and the evidence
  rule: no claim in a note that was not measured on that build, and no number without the file, the
  rule and the script behind it.
- **No release waits on a person** (D47). What cannot be verified before the cut ships marked
  *pending*, with a card queued and the release note saying so.

**Effort classes** (relative size, never duration): **XS** one edit, no design · **S** contained change
with a test · **M** a component or behaviour change with a design note · **L** architectural,
cross-file, a decision and a device pass · **XL** a codebase sweep that ratchets down rather than
finishing in one go.

---

## The polish lane

Polish is **continuous and belongs to no milestone** (D31). A wording, a colour, a timing, a rough edge
found in use is fixed when it is found and ships in the next cut, listed under *Also in this release* in
the note. The lane is exempt from the one-theme rule for the same reason the extraction lane is: a rule
that forced polish into a release would either delay the release or inflate the theme.

Two constraints keep it honest: polish changes no claim and promotes nothing, and it still needs its
catcher if it fixes a defect. A defect is never "polish".

---

## How a set leaves the gate

A set is **stabilised** by being measured with the instruments and judged against its own bar, and
**promoted** by a device pass: it is removed from `AlphaGate.FeatureSet`, its settings stop being
dimmed, the app skill and the docs lose their "behind the gate" language, and the pass is recorded in
the release document.

**The panel census, stated once.** Six sets today. **Four after R1** (Wake word removed, Digital
assistant promoted). **Three after R3** (Turn-taking). **Two after R4** (Phone tools). The two that
remain — **Presence** and **Continuous listening** — are decided by the two programmes below, not by a
release number, and the panel empties when they are decided rather than at a fixed version.
`docs/MILESTONES.md` is amended in the same pull request; its "three after 0.9.3 … none after 0.9.6"
contradicted this document before this rebuild.

---

## G0 — the instruments, finished (a gate; no version, no release note)

**No user-visible change. Nothing below it ships if the instruments fail.** The exit is four commands,
and **none of them depends on the owner**.

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| G0.1 | `event=turn` gains `route`, `segs`, `lastSound` | The log can say which route a turn took and how many speech segments it played | One gate-off log line reading `… route=runs segs=<n> lastSound=<ms>`, field order pinned | `LatencyStatsTest` + `grep 'event=turn'` | S | — | `gapP95` as a raw field (derived in G0.2) |
| G0.2 | `app-gap` row: p95 of the gap between consecutive speech segments | The app's stutter between phrases is a judged row, not an impression | `baseline-diff.py` prints an `appGap` row at n≥20 with a verdict, and its test fails on a synthetic regression in it | `baseline-report.py`, `baseline-diff.py`, `test_baseline_diff.py` | S | G0.1 | A new tolerance value (the table default holds until a corpus exists) |
| G0.3 | Success sampler (`#596`): every fifth call and first-after-install, ten kept, stamped `build=` and `gate=` | The project keeps good runs, not only failures | A unit test rotates ten and evicts the eleventh; one real sample on the device carries its build and gate | new `SampleStoreTest` + one device call | S | — | Ten real calls from three installs (a card); **any upload — see D54** |
| G0.4 | The docs count is enforced, not printed | "≤60 current files" is a gate, not a habit | `check-docs-tree.py` exits 1 above 60 and `gate.sh` runs it | `check-docs-tree.py` in `gate.sh` | XS | — | Further archiving |
| G0.5 | A literal counter for the three families, in the gate | The localisation number is counted, not remembered | The script prints Kotlin sentence literals, layout `android:text` literals and `<string>` count, with its rule named | new `scripts/count-literals.py` | S | — | Fixing any literal; the "840" |

**G0 exit:** `bash scripts/gate.sh` prints `GATE-GREEN` and its output contains the docs count and the
literal count; `baseline-diff.py` prints a verdict on both app-owned rows at n≥20; `soak.sh` reports;
the sampler's test passes. **If any of the four is UNKNOWN, G0 is not closed**, and no release is cut
until it is.

---

## R1 — 0.9.2 · *"Nothing on the alpha panel is a switch that does nothing."*

**Theme:** the panel and its switches tell the truth. **Promotes:** Digital assistant. **Removes:** Wake
word, its enrolment screen and its false opening sentence. **Also folds in** the three XS defects found
in the review (D53).

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| R1.1 | Wake word leaves: `WAKE_WORD` out of `FeatureSet`, `WakeEnrolActivity` deleted, its settings row gone, the "wakes the app hands-free" sentence deleted | There is no row that records your voice for a detector that does not exist | `FeatureSet` has five members; `grep -rn WakeEnrolActivity` → 0; a fresh-install screenshot shows five rows | `AlphaGateTest` + one device screenshot | XS | — | A detector (`#45`, future); keeping enrolment for later |
| R1.2 | Digital assistant graduates: `DIGITAL_ASSISTANT` out of `FeatureSet`; the row reachable with the master off | The gesture works or it does not — it is not an alpha experiment | Enum has four members; the row opens the system assistant settings with the master off | `AlphaGateTest` + one device tap | XS | — | Proving the gesture on every OEM (a card) |
| R1.3 | `SemanticEndpoint.DEFAULT_ON = false`, and the test that pins `true` updated | The shipped default matches the decision, on the one switch where they disagreed | `grep DEFAULT_ON SemanticEndpoint.kt` → `false`; the test asserts `false` | `SemanticEndpointTest` + one gate-on log | XS | — | Deleting semantic endpointing (`#229`, 0.10) |
| R1.4 | `mm_warm.pcm` and `mhm_warm.pcm` exist, **or** the rotation names clips that do | An acknowledgement is audible two turns in three | `ls assets/clips` shows both, or `ErClips.kt` names three that exist; a test asserts every name in `clipFor` is on disk | new `ErClipsTest` | XS | — | New recordings beyond the three names |
| R1.5 | `ErClips.play`'s result is checked by its caller | A missing clip is a logged failure, not silence | A test removes one asset and the caller reports `reason=clip-missing` | unit | XS | — | A fallback voice |

**Sentence after updating:** *"Nothing on the alpha panel is a switch that does nothing, and it answers
me when it acknowledges."*
**Release evidence:** three screenshots, five unit tests, twelve paths, one human run.
**Not in this release:** phone tools, continuous listening, Presence.

---

## R2 — 0.9.3 · *"It starts talking sooner, and it stops dropping words mid-sentence."*

**Theme:** the app's own share of the turn. One media change, declared in the note's first paragraph per
the cut template's own rule. **Promotes:** nothing.

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| R2.1 | Supertonic 3 bundle (`#589`): `ModelSpec`, checksum, URL, label | The second voice no longer loses words at chunk seams | Words-lost on the ten committed sentences ≤ v1.6 and ≤ Piper; `verify-model-checksums.py` passes | `scripts/tts-fidelity.py` | XS | D48 | Changing the default voice (Piper stays recommended) |
| R2.2 | Synthesis ahead: render clip N+1 while N plays, player decoupled | The first sound does not wait for the whole first sentence | `app-first-sound` p50/p95 before/after, same device, same ten utterances, with n and build | `baseline-report.py` + `verdict-f8.sh` | M | G0.2 | A new audio stack |
| R2.3 | First-piece shaping: 20–30 chars, growing, **capped at 45** | The phrase boundary stops resetting prosody every two seconds | Chunk distribution printed (median, p95 chars); `app-gap` p95 down; `event=tts-retire full=true` still | `StreamTextChunkerTest` + G0 rows | S | G0.1, G0.2 | Any cap above 45 (needs a larger track, D48) |

**Sentence:** *"It starts talking sooner, and it stops dropping words mid-sentence."*
**Release evidence:** two distributions with build and n; the words-lost table; twelve paths; both
app-owned rows judged (exit 0, not 3).

---

## R3 — 0.9.4 · *"If I talk over it, it stops talking and keeps the work."*

**Theme:** interruption. **Promotes:** Turn-taking — this is the release that exercises it. **Also
builds** the recorded stimulus corpus, because barge and echo are its only readers.

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| R3.0 | Recorded stimulus corpus + replay bench: fixed utterances in three environments, replayed into the real mic through the real pipeline | Barge and echo can be measured without a person in the room every time | The same replay produces the same `event=turn` rows twice; the corpus README names consent and each environment; the bench runs against synthetic audio before any human recording | new `scripts/replay-bench.sh` | M | G0.1 | Whether a conversation was *good*; driving recordings as a blocker (a card) |
| R3.1 | Cut locally → classify → steer the live run, stop only on an explicit abort | A cough no longer ends the work | Log shows `POST /v1/runs/{id}/steer` and **no** `/stop` on a barge; the run is still `running`; `run.steered` received | replay bench + log assertions | L | R3.0; gateway `steer` | The amend path (R3.2); the feel of it (a card) |
| R3.2 | Amend path: commit on `PROVISIONAL`, extend and re-send if speech resumes inside the window | An early endpoint costs a moment, not a turn | One `event=turn` per utterance, no second reply, no dropped phrase, in a replay that pauses mid-sentence | replay bench | L | R3.1 | Endpoint tuning |
| R3.3 | Echo rejection by transcript similarity, and true pause-not-stop | A false trip costs nothing | A replay with TTS playing shows `reason=self-echo` rejections and no dropped reply | replay bench | M | R3.0 | Level-threshold changes |
| R3.4 | `run.steered` gets a surface | When the agent takes new guidance, you can see it took it | The acknowledgement appears once on screen and once in the transcript | unit + device | S | R3.1 | A new screen |
| R3.5 | Turn-taking graduates (promotion only) | Backchannel, barge classification and resume are normal app behaviour | `TURN_TAKING` out of `FeatureSet`; a replay shows a backchannel that does not cancel and a resume that does not repeat, with the master off | `AlphaGateTest` + replay + twelve paths | S | R3.1–R3.3 | Changing capture or endpoint defaults |

**Sentence:** *"If I talk over it, it stops talking and keeps the work."*
**Release evidence:** the replay run and its log assertions; whether it *felt* right is a card, and the
note says **pending** in those words. **This release may not claim "interruption is understood" — only
"the mechanism steers, and nothing is double-spoken".**

---

## R4 — 0.9.5 · *"I can set a timer by voice, and it reads it back before it does it."*

**Theme:** one real action with consent, on the default path. **Promotes:** Phone tools.

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| R4.1 | The House `vox` entry names this handset; phone tools passes: a timer set by voice, spoken back before it runs, and one refusal | The agent can act on the phone once, end to end | A timer set by voice; one refusal quoted; an audit line per action | `approval-harness.py` + device pass | S | `#578`'s host fix | Any group above Tier 1; any Allow-by-default |
| R4.2 | Phone tools graduates (promotion only) | Granting a group is a setting, not an experiment | `PHONE_TOOLS` out of `FeatureSet`; a fresh install with the master off can reach the screen; every group still defaults Off | `AlphaGateTest` + screenshot | XS | R4.1 | Changing any group's default |
| R4.3 | Locked-device refusal applies to **upstream** approvals, not just phone ones | A yes on the lock screen is not a yes | A locked phone answers an `approval.request` and gets `approval_locked`, spoken; one guard, called from both paths | unit + locked device | S | — | The timeout or the card's two words |
| R4.4 | Clarify surface: one card, one spoken question, the answer continues the run | When the agent needs to ask, it asks instead of going quiet | A `clarify.request` mid-run produces one card and one spoken question; the answer resumes the same run | gateway fixture + device | M | R4.1 | New layout; a second surface |

**Sentence:** *"I can set a timer by voice, and it reads it back before it does it."*
**Release evidence:** one recorded session containing all four lines, quoted in the note.

---

## R5 — 0.9.6 · *"When something fails, it tells me which part failed."*

**Theme:** the failure path speaks. Not a new capability. **Promotes:** nothing.

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| R5.1 | Error separation: a refused key, no transport and a gateway 5xx are three spoken sentences and three `reason=` values | You stop debugging settings that were never the cause | Three induced failures produce three distinct sentences and log reasons; a unit test pins the mapping | unit + `#376`'s arm | S | — | A retry policy; a dashboard |
| R5.2 | Persist the last N turns read-only, marked unchecked | An answer that never arrived does not look like silence | After a failed turn the previous N are readable and marked; survives process death; never reused as context | unit + device | S | — | Any write-back; exporting off the device |
| R5.3 | `#202` closed or re-scoped against **three** named lines in the merged field log | The report that spawned the whole regression set gets read | A table naming the three lines, the rule that counted them and the verdict | `field-log-turns.py --since-field` | XS | — | "Nineteen candidates" (no source) |

**Sentence:** *"When something fails, it tells me which part failed."*
**Release evidence:** the three induced failures, the N-turn surface, the `#202` table.

---

## P — the Presence programme (the differentiator, and its own order)

Presence is the feature the owner named as the product's differentiator (D51). It is therefore not a
promotion to be squeezed between releases and not something to retire by default: it gets a programme
with its own decision gate, and it enters the series when its bar is met.

**What is true today.** The renderer is a 2.6 GB model producing eleven to thirty-one character lines,
measured at **2,646 MB idle PSS** and **27,505 ms to warm**, against a 220 MB idle budget and a 1.5 GB
process ceiling (`docs/BUDGETS-0.9.1.md`). Every word it says costs that. The "13-stem catalogue" one
document calls presence is the interjection library; the words still come from the renderer.

| # | Unit | Claim it earns | Exact evidence | Instrument | Effort | Depends on | Not included |
|---|---|---|---|---|---|---|---|
| P.1 | The model decision (`#562`): bench a smaller renderer, or no model for most lines | The being speaks without a 2.6 GB resident cost | A written comparison of at least two options against the idle and process budgets, with the number each one lands on | presence bench scripts + `dumpsys meminfo` | M | — | Buying a model; training |
| P.2 | Authored line catalogue: the lines it may say, how a line is chosen, what happens when the catalogue misses | The wait has a voice that was written, not sampled | The catalogue committed as data; a miss falls back to silence **and says so in the log**; every line traceable to its source | new `CatalogueTest` + one device call | L | P.1 | Improvisation; a second persona |
| P.3 | Budget ceiling enforced: presence-on idle and peak PSS asserted per cut | Presence cannot quietly become the reason a phone swaps | `baseline-diff.py` carries the presence rows for gate-on runs; a cut over the ceiling fails | comparator + soak | S | P.1 | Changing the 220 MB budget |
| P.4 | Measure against `#217`'s bar, then promote or record the failure | The being is judged by a bar, not by affection | `#217`'s bar quoted with the measured value; the promotion line names it; if it fails, the card and the reason | `#217` instrument | S | P.2, P.3 | A simultaneous redesign of turn-taking |

**P exit:** either a promotion with `#217`'s bar quoted and the budget held, or a written retirement with
the panel row removed. **What may not happen is what the old series proposed:** promoting the current
implementation while calling it a rebuild, on a call the same document says is not yet good.

---

## C — continuous listening (a condition, not a release)

Promotes when `#569` shows the recogniser keeping up on the field device (RTF at or below real time on a
recorded run) **and** `#405`'s budgets are re-measured with it on. The release that promotes it sets
`ContinuousCapture.DEFAULT_ON = false` in the same change. Claim: *"the mic stays open and the words
still arrive on time."* The `#569` A/B (`scripts/stt-ab.sh`) is the instrument — it exists; it has not
been run. For whoever runs it: `stt=` is the microphone being open, not the recogniser; read
`sttDecodeMs` and `event=stt-rtf`.

---

## 0.10 — translation (after P and C are decided, and after G0 is closed)

Gate, not a theme: packs beyond French, plural and gender rules, RTL, the contract's first translator
feedback. **No behaviour change on the default path.** Its gate list: a pseudo-locale build runs the
twelve paths; `count-literals.py` reports zero literals outside resources; RTL unclipped; one language's
strings handed over.

---

## Standing instruments — not release content

`#208` (single-device), `#209` (closed; the comparator now carries it), F4, F5 and F8 run on every
release and belong in the cut checklist. An instrument with a milestone label hides that it is not
running, which is what happened to the last three.

`#279` is **not** a standing instrument. It needs a stranger, calendar and mail tools, and an approval;
it is acceptance at the freeze, run once, with the result recorded.

---

## The extraction lane

String extraction is a **lane, not a theme**: it runs beside every release from R1 until the freeze, and
it is exempt from the one-theme rule precisely because a rule that forced it into one release would make
the freeze a big-bang. Each release extracts its own strings as it goes; the freeze release carries the
coverage checks.

---

## The freeze

Begins at the tag the owner names. Terms: layout, navigation, settings structure and user-visible strings
frozen; bug fixes, themes, translations and behaviour tuning that adds no screen are not.

---

## How a contributor plugs in

- **Every open issue is in a release or in `future`, and `future` says why.** An issue with neither is a
  bug in the tracker, not a backlog.
- **A release's issues are its scope.** Work outside the theme belongs on the next one; say so on the
  issue rather than opening a pull request.
- **Lanes:** hearing, conversation, surfaces, translation. A lane has no owner; it has a reviewer, and
  that is whoever last landed a change in it.
- **Definition of done:** the change, the catcher that would have caught its defect, and the evidence
  line that shows it worked on a device if it touches one.

---

## What this roadmap does not carry

The shipped history and the long ER research sections are records, not plans. They live in
`docs/PLAN-0.8-accelerator.md`, `docs/DESIGN-enhanced-realtime-voice.md` and `docs/DECISIONS.md`, and
this document does not repeat them.
