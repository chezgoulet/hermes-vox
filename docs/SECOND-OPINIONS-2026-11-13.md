# Two independent second opinions — Claude Opus vs a subagent, same brief

Run 2026-11-13 against `testing@8cff69b3`, each given the **identical brief**: the product vision,
what exists, the four findings from `RESEARCH-2026-10`, the Kyllene review and its §8, and the same
four questions (transport; built versus ideal; alpha sequencing; what would you do differently).
Both were told to read the repo, cite lines, change nothing, disagree out loud and rank by value per
effort. Neither was told the other existed.

- **A — Claude Code, Opus**, in a checkout on sasquatch, read-only tools. 2,505 words.
- **B — a subagent on a different model family**, in the mikoa checkout. ~2,300 words.

Full texts: `/tmp/opus-opinion.md` (sasquatch) and the delegation record for `deleg_a71b1256`.

---

## The two verdicts, side by side

**A:** a careful decision process around a call that is far from its own cadence target; the roadmap
spends four releases graduating features rather than closing that gap; the single-turn call is
"stable" only in the sense that it does not crash; on transport, stay on api_server (it already
carries approvals); the one architectural decision that is *wrong* is that barge-in destroys the
reply before classifying the sound.

**B:** a disciplined project aimed at a number it cannot reach and does not schedule; no release owns
the half of the number that is the agent's rather than the phone's; the documents that justify the
next six releases describe a codebase that no longer exists; the transport question is mostly a
non-question, and the app does not use the primitive the goal depends on.

They agree on the diagnosis to a degree that is itself informative — two models, same brief, same
repo, same conclusion about what is wrong. **Both were handed my errors and both caught them.**

---

## Where they agree (treat as true until disproved)

1. **Stay on api_server. Do not migrate; do not build the dashboard path yet.** Both found that the
   surface already carries what the vision needs — approvals, stop, a replayable event backlog,
   sessions — and that a second auth stack plus a stateful socket would cost the property that makes
   this project testable: HTTP-in/SSE-out reduces to a pure state machine with a golden corpus.
   A adds that the only dashboard-shaped feature on the roadmap is push with no call open, and
   B adds that the dashboard's single bearer is exactly the answer to #579's 401 — a fix, not a
   migration.
2. **The roster-by-conversation is the weak point, and the route already exists in the client.**
   `HostProfiles.PATH = "/v1/profiles"` is tried first; this gateway's api_server does not register
   it, so the app falls back to asking the model. The fix is upstream serving the route (or a
   decision to stop advertising the capability), not a transport change.
3. **The cadence gap is the central problem, and it is dominated by the mind.** The only published
   spoken-turn row is p50 9.06 s to first text, 11.81 s to first audio (`docs/BASELINE.md:63`) —
   against D18's 700 ms / 1.4 s. No issue in the tree owns the agent's first token.
4. **Barge-in's defect is the order of operations, not the threshold.** With Presence off, a trip
   sets `genCancelled` and calls `silenceAll` before any classification; with Presence on, the
   audible cut is still immediate and classification only decides whether the *mind* is cancelled.
   A backchannel kills the reply.
5. **Presence's 2.6 GB model is the wrong tool for a five-word line**, and both propose the same
   replacement shape: pre-rendered stems/catalogue, rendered where the model is free, with the model
   as an option rather than the default. B adds the numbers that make it decisive: 27.5 s warm on
   the first call of every launch on the release build (`docs/PRESENCE-COST.md:96,124`), and 2.6–3.4 s
   to render a line against a gap it is supposed to fill in 300 ms.
6. **Continuous listening's endpointing claim is empty.** B found the corpus: the shipped default is
   six false endpoints at an 800 ms median — *identical to the fixed pause it replaces* — and the
   eager setting buys 500 ms for eleven false endpoints on twenty-two clips. A arrived at the same
   conclusion from `SemanticEndpoint.DEFAULT_ON = true` contradicting the roadmap.
7. **The governance and the breadth are over-built relative to the voice.** A is blunt about it —
   a 1,436-line decision log whose main output has been corrections of itself, and a veto/consensus
   apparatus for a one-maintainer project; B is kinder but lists the same symptoms in code (a 60 fps
   particle renderer on a phone measured at 185 % of one core with the mic open).
8. **From scratch, both start with measurement**: A wants one instrumented loop that ships nothing
   until it improves; B wants a recorded overlap corpus replayed through the real pipeline, because
   the existing replay test replays modelled envelopes, not recordings, and the one full-pipeline run
   had AEC off.

---

## Where they disagree — and the one that matters

**1. Stop versus steer on a barge (B's finding; A did not make it).** B's sharpest claim is that a
barge **stops the run** (`core/Stream.kt:870` posts `/v1/runs/{id}/stop`), so there is no step left
to resume and §4's "resume the workflow at the correct step" cannot be built at all. The gateway
exposes `/v1/runs/{run_id}/steer`, and **I verified it refuses unless the run is still `running`** —
`gateway/platforms/api_server_runs.py:1399-1413`, whose comment says the status gate is deliberately
what rejects stop-then-steer. A's remedy is reply-level (pause playback, resume from the watermark);
B's is run-level (cut locally, classify, steer the live run, stop only on an explicit abort).

**Both are needed and they are different objects** — a reply and a run. B's is the harder one and the
one nobody in this pair has built; `run.steered` already flows through the app with "no surface for
them yet" (`docs/RUNS-ROUTE.md`). A's is the cheap half and is worth doing first.

**2. When Presence arrives.** A puts "the call at cadence" at 0.9.3 and Presence rebuilt afterwards.
B argues the inverse: Presence is the only perceptible component, the beat could ship *now* as the
default with the model optional, and turn-taking is meaningless before something is speaking — so
0.9.5 and 0.9.6 should swap. Both move Presence earlier than the roadmap has it; they differ on
whether the beat needs the model's release to land first.

**3. How long to wait on hybrid.** A would allow a read-only dashboard side channel if upstream will
not serve the roster on api_server. B would not build it at all until a feature requires action with
no call live (a lock-screen approval), and notes the gateway's own capability flags show config and
jobs write are separately grantable — so the credential that opens a call must never be the one that
can rewrite config.

**4. D18's shape.** A splits it into two targets (an on-device acknowledgement ≤ 700 ms, and the
mind's first audio with its own measured bar) and records it as a decision. B says D18 is written as
a client budget for a quantity a remote model dominates, and that nothing profiles the agent's first
token. Same observation, different artefact: a decision versus an issue and a measurement.

**5. Temperament.** A is willing to say the process itself is the problem; B stays inside the
engineering and treats the process as sound. For a project whose constraint is one maintainer's
attention, A's version is the more useful one even though it is less comfortable.

---

## Corrections to our own documents that both opinions force

These are not opinions; both opinions agreed and the code settles them.

| Document | Claim | Reality |
|---|---|---|
| `REVIEW-KYLLENE.md` §8 | "we have no recovery at all", "our barge gate is a level gate" | `Overlap.Kind`, `BargeCarry`, `EchoFloor`, `Resume.kt`, and a fail-closed approval grammar all exist. Corrected in that file today. |
| `RESEARCH-2026-10` §4 | "no notion of any of the three" recovery criteria | Stale; the file is marked historical and should be cited as such. |
| `ROADMAP.md` 0.9.4 | "semantic endpointing stays default-off" | `SemanticEndpoint.DEFAULT_ON = true`; only the alpha set guards it. |
| `ModelCatalog.kt:90` | ships Supertonic `…-2026-03-06` | D48 says the fix is the v3 bump; not shipped. |
| `SherpaTts.kt:22` | "the bake-off chose SUPERTONIC as the recommended voice" | D48 says Piper is the recommendation. Two documents disagree; the code follows the older one. |
| `docs/TTS-FIDELITY.md` | the cap "is not needed" (§finding) **and** "the concrete candidate fix" (§decision) | Self-contradictory; one paragraph must go. |

---

## What I would act on, merging both

Both opinions independently rank the same three first, in the same order:

1. **Give the comparator a D18 row with a verdict** — speech-end → first audio p50/p95 from F8 at
   n ≥ 30, gate off, default config. Until that exists, every claim about cadence is a shape argument.
2. **Cut locally → classify → steer the live run**, stopping only on an explicit abort. This is the
   change that turns "interruptible" into "responsive", and the server already refuses the wrong order.
3. **Ship the beat without the model** — pre-rendered stems as the default, the 2.6 GB renderer as an
   option — then re-time the gap it fills.

Then, in the order both roughly agree on: chunk shaping (half a day), error separation, the
`SemanticEndpoint` default fix and the Supertonic bump (both cross-file one-liners), the two-surface
map, and the release reorder (call at cadence becomes 0.9.3; phone tools and digital assistant become
a lane rather than a theme).

**The caveat neither opinion states strongly enough:** the 11.81 s is a **bench** row, taken with a
synthetic caller — a TTS clip played into the mic at volume 13/25 with AEC off — not a field
measurement. The direction is surely right (first byte 3–8 ms, first text 9.06 s is the mind), but
the magnitude of the *voice* half is unmeasured until someone speaks into the phone with the log
exported. That is one sitting with the owner, and it is the same sitting that fills F8.
