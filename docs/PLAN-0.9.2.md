# Plan — 0.9.2, Presence

0.9.2 is where Presence stops being a mode you can find in Settings and becomes the thing the app
is. One name, one switch that means on or off, and a conversation that behaves: it acknowledges
while you speak, it yields when you interrupt, it resumes where it was, and it does not say a thing
nobody asked for.

Milestone label: `milestone/0.9.2`. Roadmap: [`../ROADMAP.md`](../ROADMAP.md).
Background: [`PRESENCE.md`](PRESENCE.md) — what the layer is, where it came from, and what it is for.

**The dependency is deliberate.** Every behaviour here rests on 0.9.1's continuous listening:
partials, semantic endpointing and an open mic. 0.9.1 answers whether the machine can afford it;
0.9.2 answers whether the conversation is worth it.

## 1. The selector goes (#222 — D16 completes here)

`presence_enabled`, default **on**. `voice_mode` is migrated rather than dropped: a stored
`realtime` becomes off, a stored `enhanced` becomes on, so no existing install has its choice
silently reversed. There is no mode label and no degraded-mode pill; when the model is missing or
will not start, the Settings row says what is missing and why, and the app is simply normal
operation.

Two visible facts replace three states: Presence is on or off (the user's choice), and the model is
ready or not ready (a fact).

## 2. The transcript and first run (#223, #230)

- **#223** — the transcript layout is empty in Presence; it is the surface a user reads to know what
  the being heard and said, so it cannot be blank.
- **#230** — first run: gateway, microphone permission, the recommended models, Presence and its
  model, then a first call. Honest when a step cannot be completed, skippable for someone who knows
  the app — and written once, against the names that now exist.

The settings reorganisation and the Being controls that this section used to carry (#224, #225) are
0.9.1: the app's shape and its look are mechanics, and this release only has to trim the Presence
section once the selector is gone.

## 3. The aside: a minimal directive and a guard that repairs (#257, #256)

Content is what the code polices; format is what the code repairs (D20). An eleven-word line is cut
at a clause boundary, markdown and courtesies are stripped, and only content is refused: a result
that does not exist, or a number nobody supplied.

- **#256** — measure what the model actually invents, from the bench and the field corpus, before
  any bound is loosened. Rates come from the bench; failure *classes* come from field reports.
- **#257** — the minimal directive (who you are, what this moment is, never claim a result, one
  breath, silence is valid) and the repair path. Every remaining bound in code cites a documented
  failure and says what would retire it.

## 4. The render budget (#249)

The turn render takes ~2.5 s against a 900 ms gate. That is not an ordering bug; the request is
already in flight before the gateway submit. Three honest options, decided by the field numbers:
move the gate, make the render smaller, or let the beat carry the turn so the soul's line is not
racing the mind's first token. Pick one and record why.

## 5. The conversation behaviours (#245, #243, #242)

- **#245** — backchannels: the being acknowledges while it listens, on speech onset and partials
  rather than on turn boundaries. The most human item on the list.
- **#243** — after an interruption, resume the step and address it; do not repeat what was already
  heard.
- **#242** — classify an overlap — interruption, backchannel, third party, background — rather than
  measuring its level. The adaptive barge floor (F3b) is the mechanism: sample the room at call
  start instead of trusting an absolute threshold.

## 6. The instrument and the bar (#217)

Presence has been judged by ear. The log already carries `er-presence line/skip/stop`
(line = one aside offered),
`er-soul kind=working`, `er-render-skip` and the `soul(...)` counters — `scripts/presence-session-report.py`
summarises a session from them: slots offered, asides spoken, skips by reason, repeats, render
p50/p95, and any line that landed after the reply's first text.

**The bar for this release:** at Conversational, over one long run, at least two thirds of offered
slots speak, nothing repeats, nothing lands after the reply's first text, and render p95 stays under
three seconds. Then one real-room night, by ear, against the same run's numbers.

## 7. What the conversation still owes (F3b, F6a, barge truncation)

- Real-room turn-taking: the in-cabin endpointer A/B that has been open since 0.6.8.
- **F6a** — narration into the presence loop: the tool event routed through the loop so the line is
  topical and density-governed.
- Truncation after a barge in session history: the model must not believe it said things the caller
  never heard.

## Exit gates for the cut

- there is no mode selector, and an upgraded install keeps the behaviour it had chosen;
- a stranger installs, is walked through the gateway, the models and a first call, and can skip it;
- the bar above is met on one long run, with the summary in the release notes;
- one real-room night with the owner;
- the remaining bounds in the guard each cite a failure, and the measured ones are retired;
- the standing cut checklist passes: live suite green, reproducible build, matrix and baseline
  recorded, no open severe bug without a written reason.

## Not in 0.9.2, and where each went

- **Continuity, the household, the store rail** — 1.0.
- **Entity calls, remote control of the interface, wake word** — unnumbered and community-led
  (#268).
- **Voice and soul model choice** — still decided by measurement (D4, `docs/VOICE-BAKEOFF.md`), not
  by this release.
