# The turn route: from speech to audio

A **turn** is one thing you said and the answer to it. On this route the app submits that turn to
the gateway as a **run**: a unit of work with an id, a status, an ordered event stream and a stop
endpoint. The app admits the run, follows its events, turns the text into speech, and ends the turn
on the run's terminal event. This is the route every voice turn takes; the alternative,
`/v1/responses`, is only used for a gateway that does not have runs.

## What you need

- A Hermes gateway whose API server serves `POST /v1/runs`. The route is chosen automatically;
  there is no setting for it.
- The gateway's API key and a saved profile, as for any turn.
- Nothing else. A gateway without the route is detected on the first admission and remembered for
  that profile (see **The 404 fallback**).

Two terms used throughout: an **event** is one JSON record on the run's stream — a token of text, a
tool starting, an approval request, a terminal result. An **approval** is the gateway stopping to
ask whether it may perform a consequential action; the request arrives as an event and the answer
goes back to the run.

## The shape of a turn

1. **Submit.** The caller's speech is transcribed (or the text is typed) and handed to
   `HermesSession`. A voice turn carries the voice register and a per-request reasoning effort in
   the request's ephemeral `instructions`, plus the presence context when Presence is on; the
   caller's words go as `input`, byte-exact. `Streams.start` asks `StreamRoute.routeFor` for the
   route **once**, and that route is fixed for the life of the turn: a settings flip mid-turn
   cannot move a live stream between vocabularies. The route is `RUNS` — **the default (#404)**.

2. **Admit.** `POST /v1/runs` carries `model`, `input`, `instructions`, the optional `provider`,
   the client `session_id`, and optional `model_options.reasoning`. It also carries one
   **`Idempotency-Key`** for the turn, so a lost reply can be retried without starting the work
   twice. The gateway answers **202** with `{run_id}` (200 is accepted from older builds). The run
   id and its stop action are bound in one atomic step with the cancellation check, so a barge-in
   landing at any instant either sees the stop or is seen by the reader — never neither.

3. **Subscribe.** `GET /v1/runs/{id}/events` streams the run's events, each with a sequence number.
   A reattach sends `?last_seq=N` and receives exactly what it missed. The reader translates the
   runs vocabulary into the event shapes the rest of the app already renders.

4. **Render and speak.** `message.delta` appends text to the reply and feeds the voice; tool events
   add the `◆ tool` markers the transcript shows; `message.interim` carries the mind's own progress
   commentary as an aside.

5. **End.** `run.completed` (or `done`) is the terminal event and settles the turn; a failed run
   that had already produced text becomes the app's "incomplete" answer — what was said is still
   the answer. The done poll retires the stream.

6. **Report.** The app's turn ending is the audio: the reply is spoken, or a failed or empty turn is
   ended with one repair line. A turn the caller cut is not repaired — they know they cut it.

## What the events become

The translation lives in `core/Stream.kt` (`dispatchRun`). Both routes write the same stream state,
so every consumer above the session is route-blind.

| runs wire event | what the app does |
|---|---|
| `message.delta` (`assistant.delta` accepted) | reply text, preview, and the stream into TTS |
| `message.interim` (`assistant.commentary` accepted) | a commentary aside: logged, and spoken through the working-line guards when the narration voice is not `silent` and the reply has not started. `already_streamed: true` suppresses the copy that already went out as answer deltas |
| `tool.started` | tool marker and tool name, plus the gateway's one-line `preview` |
| `tool.completed` / `tool.failed` | the tool-done marker; `error=true` is the same fact |
| `tool.progress`, `reasoning.available` | ignored by today's consumers; never enters the reply |
| `run.completed` / `done` | terminal: settle the turn; the terminal `output` is the whole answer when no text streamed |
| `run.failed` with partial text, `run.cancelled`, `run.interrupted` with text | the app's "incomplete": whatever was said is the answer |
| `run.failed` / `error` | the turn fails with the gateway's reason |
| `approval.request` | the approval card; the answer goes to `/v1/runs/{id}/approval` |
| `clarify.request`, `clarify.settled`, `approval.responded`, `approval.settled`, `run.steered` | pass through to the app, which has no surface for them yet; a clarify is never mistaken for an approval |

## The 404 fallback, and why it is sticky

A gateway from before the runs API answers `POST /v1/runs` with **404**. That is the only answer
treated as "this host has no such route". The admission catches exactly that status, remembers the
answer for the active profile, logs

```
event=route-fallback reason=no-runs-route profile=<name>
```

and retries **the same turn once** on `/v1/responses`. Both readers fill the same stream state, so
nothing above the session can tell the two apart except that log line.

The answer is persisted per profile (`route_no_runs_profiles`) and loaded when that profile is
connected. The probe is therefore paid once per profile, not once per turn; a later turn — or a
later launch with the same profile — starts on responses. A profile switch loads that profile's own
answer, so one host's fallback never leaks into another. That is why the fallback is sticky: a
per-turn probe would send two requests for every turn against an old gateway.

A 404 is also how a host answers a `/p/<name>` profile it does not serve. That is a fact about the
address, not about the route, so it is reported as an error and never remembered as a fallback.

**Every other answer is a real error and never falls back.** 401, 409, 429, 500 and a transport
failure surface as the turn's error, because a bad key hidden behind another route is worse than a
visible failure. A profile whose admission succeeded keeps riding `/v1/runs`.

## Talking over the reply: the stop

A barge-in cuts the voice first, and the app then cancels the stream. On this route the events
connection is only a subscriber: closing it does **not** stop the run. `Streams.cancel` posts
`POST /v1/runs/{id}/stop` with the same bearer and scope, off the drawing thread.

- A barge-in **does not cut the admission**. Once `POST /v1/runs` has been sent the gateway may
  already have made the run; cutting the connection would lose the only id that can stop it. The
  admission finishes (bounded by the 60 s idle timeout), and the run it names is stopped. No events
  are subscribed for it.
- A **lost admission reply is retried once**. When the reply to a sent `POST /v1/runs` is lost —
  a reset, or no 202 before the idle timeout — the identical request is sent again with the same
  `Idempotency-Key`; the gateway replays the original `run_id` (`Idempotency-Replayed: true`)
  instead of doing the work twice. That id is bound, or stopped on a cancelled turn, like any other.
- **The stop is never an error for the caller.** A 2xx is the stop; 404 `run_not_found` (the
  record aged out) and 409 `run_not_active` (another gateway process) both mean there is nothing
  left to stop. A dead gateway or any other answer is the same to the caller: the turn is already
  retired and the mic is already theirs.

## Approvals arriving mid-run

The run can stop in `waiting_for_approval` and emit:

```
approval.request { run_id, request_id?, command (already redacted), description?,
                   reason?, choices: [once|session|always|deny] }
```

The request is a card in the app. The gateway's `choices` list is authoritative: the app renders
exactly what was offered and always makes `deny` available, even when the list is malformed. A
session-authority gateway may add a `prompt_id` and an `execution_generation`; the app echoes the
generation only when the event carried one.

The answer is `POST /v1/runs/{id}/approval` with `{choice, request_id}`, posted off the main
thread. One request is answered once: the first answer removes it and records its key, so a double
tap, a duplicate voice match or a late timeout is a local no-op, never a second POST. The only
automatic path is the timeout, and it always chooses `deny`; there is no auto-approve. A spoken
answer is a whole short utterance (at most three words), never a substring search: "I'm not sure"
and "where does that go" are not answers, and only a plain no denies. A positive answer takes the
least privilege the gateway offered (`once` before `session` before `always`).

While a card is up, the turn's clock pauses. The turn budget measures agent time, not wall time, so
a human reading the card is not charged against the run; several approvals accumulate their pauses
rather than restarting the clock.

If the events socket is lost while the run is parked on an approval, the card is **kept**, because a
lost stream is not a dead run. The app re-reads the run when it can; only a run known to have ended,
the app abandoning the turn, or the owner cancelling withdraws the card.

## When the route fails

| failure | what happens |
|---|---|
| admission 404 | remembered for the profile, same turn retried once on `/v1/responses` |
| any other admission status, or no route to the host | the turn fails with the gateway's error; no fallback |
| reply lost after the body went out | one identical retry with the same `Idempotency-Key`; a replay returns the original run id |
| events socket dropped | one immediate reattach from `last_seq`, then status reads after 1 s, 2 s and 4 s: a terminal status ends the turn, a live one reattaches from the last seq, and when the rounds are spent the run is stopped |
| gateway no longer retains the missed events | `replay.truncated` marks the hole; the terminal `run.completed.output` replaces the gapped text, tool markers kept. If the terminal event carries no text, the turn fails rather than speaking a sentence with a missing middle |
| no data for 60 s | the idle timeout ends the turn as `hermes stream: connection lost (no data from the gateway)`; any bytes, keepalives included, reset it |
| the run cannot be followed or stopped | the done poll says `run_end:"lost"`, which is what keeps a pending approval card on screen |

A **setup turn** — the one-button phone setup, `Streams.startPersistent` — rides the same route
with no clock. On a drop it reattaches to the **same** run, from `last_seq` or from the run's
status, for as long as the gateway says it is alive; the recovery path never stops it. Only
`Streams.cancel` ends the attempt — the owner cancelling or the screen going away — and that
cancel does stop the run. This is the one place where the ordinary turn ceiling would otherwise
cut a working run in half.

## Conversation identity

A run has no response id. The terminal event carries output, usage and runtime, not an id, so the
runs body omits `previous_response_id` and the app leaves its stored chain head untouched.

The conversation is a **client-owned `session_id`** in the request body. `HermesSession` makes one
per session object and rotates it in `resetConversation`, so `/new`, a new call and a profile
switch each start a fresh transcript. The gateway ranks it above the declared
`X-Hermes-Session-Key`, which remains the long-term-memory scope. A stored turn — `/compress`, the
VOX.md authoring directive, the skill install — rides `/v1/runs` with the same `session_id` and
blocks for the run's answer, because `/v1/responses` cannot address a body `session_id`.

## What is proven, and what is not

`testdata/stream/runs-*.sse` are **hand-built from the gateway source**, not captured; the
provenance line is in `testdata/stream/README.md`. `RouteParityTest` replays a responses fixture and
its runs twin through the real session and asserts the same reply text, transcript, terminal type,
error reason, tool lines and names, presence asides and approval map. `RunsStreamTest` covers the
transport-only behaviour: barge-in posts `/stop`, a dropped socket reattaches at `last_seq`, the
body carries no chain head, clarify arrives as its own event. `LiveGatewayTest` runs against a
deterministic fake gateway.

None of that is a live gateway, and no test in the repo talks to one. The route's behaviour against
a real Hermes build can only be confirmed on a device; the gate does not confirm it.

## What it writes, sends or changes

- One `POST /v1/runs` per turn, at least one events `GET`, and further reattach or status `GET`s only
  after a drop. A stop is posted when the turn is cancelled or the reader gives up; an approval
  answer is posted when the card is answered.
- The request fields above, plus one random `Idempotency-Key` per turn. The key is not a secret and
  names no user.
- The `event=route-fallback` log line the first time a profile's gateway answers 404, and the
  transport diagnostics the app already keeps.
- Nothing else on the gateway. The run is the gateway's record; the app holds only the stream and
  the conversation id.

## What it cannot do

- It cannot fall back on any answer except an exact 404 on admission. A 401, 409, 429, 500 or a
  transport failure is the turn's error, by design.
- It cannot probe again once a profile is remembered as having no runs route. The answer is stored
  under the profile's name and is not cleared when the profile is deleted, so a later profile with
  the same name inherits it.
- It cannot stop a run it never learned the id of. A run admitted while the app dies keeps working
  at the gateway with nobody following it.
- It cannot keep following a run it gave up on: when the recovery rounds are spent the run is
  stopped, not abandoned.
- It cannot present a gapped replay as the answer. When the backlog is truncated, the run's terminal
  output replaces the text or the turn fails; there is no third option.
- It cannot surface a `clarify.request`. It reaches the app as its own event and is not mistaken for
  an approval, but there is no screen for it yet.
- It cannot carry tool arguments on `tool.started`. The runs route sends the gateway's one-line
  `preview`, not the raw JSON arguments the responses route carries.
- It cannot answer an approval whose run is known to have ended, and cannot auto-approve anything;
  the only automatic answer is a timeout, and it denies.
- It cannot keep a conversation by response id. A run has none; continuity is the body `session_id`
  plus the declared scope, and a stored `/v1/responses` snapshot is never advanced by a run.
- It cannot prove a live gateway in the gate. The fixtures are hand-built and the parity proof is
  off-device.
