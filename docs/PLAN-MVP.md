# Plan of attack — to the best version of this app

> **Superseded in part, 2026-11-13.** The sequence and the effort convention now live in
> `ROADMAP.md` § *The series — rebuilt 2026-11-13*: six milestones, effort **classes**, and no
> durations. What stands here is the reasoning — the MVP definition, the measurement principle that
> isolates the brain from the app, and the Presence design questions. Read this for *why*, the roadmap
> for *what next*.

Written 2026-11-13, after the Kyllene review, two independent second opinions, and the owner's
direction on what the project is actually for. This replaces `PLAN-1.0.md` and `PLAN-2.0.md` as the
plan of record beside `ROADMAP.md`; where the two disagree, this document wins and the roadmap is
amended in the same pull request.

**No dates.** A gate closes when its exit criteria are met (D42). Effort is stated in sessions —
one session is one sitting of focused work.

---

## 0. The five things the owner decided, and what each one changes

1. **Measure the app, not the brain.** The server has its own dynamics — first token 9.06 s p50 on
   the one bench we have, first byte 3–8 ms — and we do not control them. Every number in this plan
   is therefore split into **app-owned** (judged) and **brain-owned** (reported, never gated).
   This amends D18, which mixed them into one target no client can meet.
2. **Record the good runs.** We have kept the failures and thrown away the successes (#596), so
   every argument about how the product feels rests on a bad day and a robot. The instrument comes
   before the features.
3. **Hone the repo to what is real and useful.** 170 documents in `docs/`, of which a large share
   are build briefs for releases that shipped months ago. Truth first, because a stale document is
   worse than no document.
4. **Presence gets a fundamental design review, not a repair.** The current answer — a 2.6 GB
   language model that warms for 27.5 s on the first call of every launch to produce eleven to
   thirty-one characters — is not a version of Presence; it is one implementation of it that the
   evidence has now rejected.
5. **Define MVP, then build only toward it.** Everything else is deferred by default, and the
   project stops treating "graduate the alpha sets" as a goal in itself.

---

## 1. What MVP is

**MVP is a call you would keep having, on the default path, with the alpha gate off.** Four
promises, all testable on a device, none of them requiring an alpha set:

1. **It answers, and the app's own share of the wait is small.** From the moment the mind's first
   token arrives, sound begins within the app's budget — one number, measured, gated.
2. **It can be interrupted, and the work survives.** Speaking over the reply pauses it, classifies
   what was said, and then either resumes what you were hearing or steers the work that was already
   running. Cancelling the run is something you ask for, not something a cough does.
3. **It can do one real thing, with consent.** One phone action end to end — a timer, set by voice,
   spoken back before it runs — with an approval that fails closed and refuses while the device is
   locked.
4. **It holds up.** Thirty minutes, a dropped network, a locked screen, a phone call arriving
   mid-turn: no lost turns, no repeated speech, no stuck gate.

**Explicit non-goals for MVP.** Presence as a generative model; wake word; the digital-assistant
role; more than one phone-tool tier; localisation; profiles-as-a-feature beyond what exists;
multi-device. Each is a gate after MVP, and several may never happen.

**The constraint that makes MVP honest:** all four promises must hold with **every alpha set off**,
because that is what a new user gets. An MVP that needs Presence to feel alive is not MVP.

---

## 2. The measurement principle (this is the amendment that makes the rest possible)

D18 said: speech-end → first audio, p50 ≤ 700 ms. On this architecture that number is mostly the
server's, so it can never be met by the client and has never been measured. Replace it with two
rows, logged separately on every turn:

| Row | Owner | Judged? | What it means |
|---|---|---|---|
| **app-first-sound** | the app | **yes, gated** | first reply token (or the app's own acknowledgement) → first audible sample. Covers synthesis start, chunk queue, player warm-up. |
| **app-gap** | the app | **yes, gated** | p95 of the gap between consecutive speech segments within a reply |
| **brain-first-token** | the server | no — reported | request sent → first text delta. Context for every release, never a red |
| **turn-total** | both | no — reported | speech end → last audio. What the user feels, attributed by the two rows above |

**The app answers immediately regardless.** The one thing the client controls is the response to
having been spoken to, and it should not wait for the model to begin: an acknowledgement inside the
app's budget (a word, a tone, the being's beat — whichever MVP ships) means the *conversation* never
stalls even when the *answer* is slow. This is the honest resolution of "the brain is slow": the app
owns the first sound, the mind owns the content.

Two consequences worth stating plainly:

- the comparator gains these rows and keeps its exit codes; `brain-first-token` never fails a build;
- any release document that quotes a latency number must say which of the two it is.

---

## 3. Gate 0 — the repo tells the truth (parallel, first, cheap)

**Why first.** Every other gate is judged against documents, and several are currently false:
`REVIEW-KYLLENE.md` said we had no interruption recovery (we have `Overlap`, `BargeCarry`,
`EchoFloor`, `Resume`); `ROADMAP.md` says semantic endpointing stays default-off while
`SemanticEndpoint.DEFAULT_ON = true`; `SherpaTts.kt:22` calls Supertonic the recommended voice while
D48 says Piper; `TTS-FIDELITY.md` contradicts itself about the chunk cap. A stale document is worse
than none because it makes a session re-derive what it already had.

**What we do.**

1. **One index, three labels.** `docs/INDEX.md` becomes the only entry point and labels every file
   *current*, *historical* or *superseded*. Anything historical moves to `docs/archive/` in one
   commit — no rewriting, no deletion of history, just a door marked "the past".
2. **Duplicates are resolved by name, not by preference.** There are two `DECISIONS.md` — 213 lines
   at the root, 1,436 in `docs/` — and two matrix documents (`docs/MATRIX.md` 36 lines,
   `docs/BEHAVIOUR-MATRIX.md` 59). The root copy is read once, then deleted or merged; two files with
   one name is exactly how a document came to contradict a milestone.
3. **The archive list is large and mostly obvious** — `BUILD-DIRECTIVE-*`, `CLAUDE-BRIEF-*`,
   `PLAN-0[0-9]*` (19 files by pattern), `HANDOFF-*`, `SPEC-*`, `PLAN-0.9.1`, `PLAN-0.9.2`,
   `PLAN-0.9.x-to-1.0`, `PLAN-1.0`, `PLAN-2.0`, `0.9.1-SCOPE`, `BUDGETS-0.9.1`,
   `BARGE-WHY-0.7-WORKED`, `BARGE-REGRESSION-ANALYSIS`, `LOGGING-BLIND-SPOTS-AUDIT-2026-09-05`,
   `INVESTIGATION-px9-trueblack-aura`, `MATRIX.md`. Roughly 40 of 170 files.
4. **DOC-TRUTH checks only current documents.** An archived file cannot fail the gate; a current
   file must pass it.
5. **Dead code gets an inventory, not a reflex.** Every candidate is listed with its callsite count
   and a decision: delete, fold, or keep-with-a-reason. Nothing is removed in this gate except
   unreferenced files; behaviour-bearing removal is its own commit with the twelve paths re-run.

**Exit criteria.** `docs/` is ≤ 60 current files plus an archive; one `DECISIONS.md`; every current
doc passes DOC-TRUTH; a `docs/ARCHIVE.md` lists what moved and why, in one line each; the gate is
green on the cleaned tree.

**Unblocks.** Every later gate, because they all cite documents.

---

## 4. Gate 1 — the instrument, and good runs recorded

**Why.** We know what failure looks like and nothing about success. The bench cannot speak, the
walkthrough records verdicts and not logs, and the support export is reached when something breaks —
so the corpus is biased by construction (#596).

**What we build.**

1. **An app-owned timeline per turn**, in the log, one line: `event=tl turn=<id> speechEnd=<ms>
   sent=<ms> firstToken=<ms> firstSound=<ms> lastSound=<ms> segs=<n> gapP95=<ms> route=<runs|responses>`.
   This is what makes §2 measurable at all.
2. **Success sampling** — every fifth completed call, plus the first call after an install or
   upgrade, writes a bounded copy to `samples/` with `build=`, `gate=`, turns and outcome; keep ten.
3. **A one-tap "send this call — it went well"** beside the existing export, and a card in the
   walkthrough that asks for it on a *pass* as readily as on a fail.
4. **A recorded corpus in the test environment.** The functional suite gains a *conversation replay*
   case: a small set of **recorded human utterances** (the owner's own, captured with consent on the
   bench handset) played into the real microphone at calibrated level through the real pipeline,
   capturing the real log — overlapping with real TTS playback for the barge cases. This is the only
   way to measure interruption and echo without a human in the room every time.
5. **The comparator gains the §2 rows**, judged as stated, with `brain-first-token` reported.

**Exit criteria.** Ten sampled real calls (three installs) in `samples/`; a replay case that
reproduces a stored conversation in CI-adjacent runs; a release document quoting an app-first-sound
distribution with its n and build; the comparator failing correctly on a synthetic regression in the
new rows.

**Unblocks.** Every later gate — without this, all of them are arguments.

---

## 5. Gate 2 — the call at cadence (app-owned)

**What we build.**

1. **Chunk shaping** (`SpeechChunker`): first piece ~20–30 characters, then each piece growing to
   three times the previous *actual* length, one clip synthesised ahead, a decoupled player. Replaces
   the flat 30-character cap and its 4-second-track arithmetic. Fixes prosody resetting every two
   seconds and cuts engine invocations roughly fourfold on a long reply.
2. **Per-engine speech settings**: a Supertonic chunk profile separate from Piper's; ship the
   Supertonic 3 bundle (v3 removes the fragment loss measured at 27 words in 118 → 1); fix the
   catalogue entry and the "recommended voice" comment so code and D48 agree.
3. **Provisional endpoint acts, not just fires.** `PROVISIONAL` is currently "a latency hint and not
   acted on". Commit on provisional, with an amend path that extends and re-sends if speech resumes
   inside a window — the amend path is mandatory, because early commit without it is the #538 class.
4. **Semantic endpointing default off** until its own corpus shows a gain; today's shipped default
   is six false endpoints at the same median as the fixed pause.

**Exit criteria.** app-first-sound p50 and p95 recorded on the default path before and after, on the
same device and corpus; no regression in the twelve paths; engine invocations per reply down and
quoted; `cols`/`stt-reject` unchanged or better.

---

## 6. Gate 3 — interruption that keeps the work

**What we build.** The order, not the sensitivity:

1. **Cut locally → classify → decide.** The audible cut stays instant; classification (`Overlap`)
   now runs *before* anything is cancelled, and a backchannel resumes the reply from `Resume`'s
   watermark.
2. **Steer instead of stop.** Today a barge posts `/v1/runs/{id}/stop`, which ends the run; the
   gateway refuses `steer` after a stop, so the work cannot be resumed by construction. New order:
   steer the live run with what the user said; stop only on an explicit abort or hush, or when the
   steer is not acknowledged within one reply.
3. **A surface for `run.steered`** — the agent acknowledging guidance mid-run is currently dropped.
4. **Echo safety**: transcript-similarity rejection of self-echo, and pause-not-stop playback, so a
   false trip costs nothing.

**Exit criteria.** A recorded-corpus run showing: a backchannel does not cancel the reply; an
interruption steers the run and the agent acknowledges it; a deliberate abort still stops; no double
speech after a resume; the app-owned gap row unchanged.

---

## 7. Gate 4 — one real action, with consent

**What we build.** The narrowest end-to-end agentic loop, on the default path:

1. **Phone tools tier 1 only** — one action family (a timer is the proposal), default off per group
   as today, promoted for MVP as the single demonstrated action.
2. **Approvals** in speech and on screen: confirm the command back, allow-list grammar, second
   confirmation for anything broader than once, refusal while the device is locked — the last of
   which exists for phone approvals and must be verified for gateway approvals.
3. **A clarify surface.** `clarify.request` arrives mid-run and is currently silently ignored while
   the mic is open. One card, one spoken question.
4. **Persist the last N turns read-only**, marked unchecked, so an answer that never arrived does
   not look like silence.

**Exit criteria.** A spoken timer set end to end on the default path, with the approval line quoted;
a refused approval; a clarify answered; a locked-device refusal; all in one recorded session.

---

## 8. Gate 5 — Presence, redesigned (the review)

**The question Presence answers** is not "can we run a language model on a phone". It is: *while the
agent is working, what does the being say, when, and in whose voice?* Three candidate designs:

| Option | What it is | Cost on the device | Risk |
|---|---|---|---|
| A. Status quo | 2.6 GB E2B renders each line | 2,588 MB resident, 27.5 s first-call warm, 2.6–3.4 s per 5-word line | D18 forbids it competing at the turn boundary; it is the only perceptible cadence component and it is scheduled last |
| B. Catalogue (recommended) | ~200 authored stems per profile, rendered once on the host from VOX.md, chosen by rules and the gateway's own operations events | a few MB of audio, tens of ms | needs authoring; novel situations fall back to a generic line |
| C. Cues only | tones and motion, no words | negligible | loses the thing that makes it a being |

**Recommendation: B for MVP-Presence, A as an opt-in experiment, C as the floor.** The beat within
300 ms of a long operation starting, an acknowledgement, a completion line — all of those are
retrieval, not generation, and the two independent reviews reached the same conclusion from different
directions. The model stays available for the case the catalogue cannot cover, behind a switch, and
D19/D20's lexical guards become a property of the authored stems rather than a police force around a
model.

**The review must answer, in writing, before code:** (i) which lines exist and how many; (ii) what
triggers each (event, not timer); (iii) whose voice — same as the reply, or deliberately different;
(iv) what happens when the catalogue has no line; (v) how this is measured (it must appear in the
app-owned cadence row, not as a vibe).

**Exit criteria.** A design note with those five answers; the catalogue shipped behind the gate; a
measured beat latency; the E2B path still selectable; the strings and memory claims corrected
(`AlphaGate.kt`, `SettingsHints.kt`, `MemoryBudget.kt`).

---

## 9. The buildout list, by size

**Now, and they are small (an hour or less each).** Semantic endpointing default off · Supertonic 3
bundle and the catalogue/comment fix · one `DECISIONS.md` · archive the historical docs · error
separation (auth vs transport vs gateway, each spoken and shown) · `brain-first-token` reported in
the comparator · the `run.steered` log line · persist the last N turns.

**Now, and they are days.** Chunk shaping + decoupled player · recorded-corpus replay case · the app
timeline + success sampling · pause-not-stop with echo rejection · steer-instead-of-stop · the
Presence catalogue.

**Then.** Provisional-endpoint amend path · clarify surface · phone-tools tier 1 end to end · turn
state machine extracted from `VoiceController` · a real-room barge pass with the owner.

**Deferred by MVP, on purpose.** Wake word (cut it) · digital assistant · more phone-tool tiers ·
localisation · push notifications with no call open (the only feature that would justify a dashboard
credential) · any new alpha set · new D-numbered governance until Gate 2 has a measured row.

**Not doing, and the reason stated once.** Accounting for the brain's latency inside the app's
budget. A dashboard transport. An LLM rendering short lines by default. Tuning barge thresholds
without fixing the order. A coverage percentage. A second platform.

---

## 10. What would change this plan

- **If the recorded corpus shows app-first-sound already inside the bar**, Gate 2 shrinks to chunk
  shaping and prosody, and Gate 3 comes first. The plan's order follows the measurement, not the
  other way round.
- **If the corpus shows turn-total is dominated by the brain even on good calls**, then the app's
  job becomes the acknowledgement and the interim beat, and Gate 2's latency half is dropped.
- **If Presence's catalogue cannot cover the working lines in the review**, option A returns with a
  quantified budget instead of a guess.

---

## 11. The order, in one paragraph

Truth first, so the plan is judged against documents that exist in the code. Then the instrument, so
every later gate is a measurement rather than an argument — including the good runs we have never
kept. Then the call at cadence, app-owned, because that is the product. Then interruption that keeps
the work, because that is what separates a voice client from a talking button. Then one real action
with consent, because that is what makes it an agent. Then Presence, redesigned as retrieval rather
than generation, because it is the only thing in the app that makes the wait feel alive. Everything
else in the roadmap waits behind those five, and several of them will turn out not to be needed.
