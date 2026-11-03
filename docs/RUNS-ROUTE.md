# The `/v1/runs` turn route

Status: **the default (#404), device pass outstanding.** `StreamRoute.active` is
`TurnRoute.RUNS` for every install that declares a conversation scope; an install with
a blank scope stays on `/v1/responses` (see *The scope requirement*). The cut waits on
the device pass below (D29).

## Why

`POST /v1/responses` has no approval notifier on Hermes 0.22.6: a flagged command
resolves from `approvals.unattended_mode` with no event reaching the phone
(`docs/NOTES-api-events.md`, `docs/APPROVALS-ACCEPTANCE.md`). `/v1/runs` registers the
notifier, so it is the route that can carry an approval to the device. It also has a
barge-in the run itself learns about (`POST /v1/runs/{id}/stop`), `clarify.request`
control events, and a sequence-numbered event backlog (`GET /v1/runs/{id}/events`) a
dropped socket can reattach to.

## Where the switch lives

`android/app/src/main/java/com/hermesvox/core/TurnRoute.kt`:

```kotlin
internal object StreamRoute {
    @Volatile
    var active: TurnRoute = TurnRoute.RUNS        // #404
    fun routeFor(hasSessionKey: Boolean): TurnRoute =
        if (active == TurnRoute.RUNS && !hasSessionKey) TurnRoute.RESPONSES else active
}
```

`Streams.start` and `HermesSession.turnStored` read `routeFor` once per turn; tests
set and restore `active`. Nothing above `HermesSession` knows the route exists.

## The scope requirement (lifted in 0.9.1, H2)

A run has no response id. With neither a declared `X-Hermes-Session-Key` nor a body
`session_id` the gateway keys the run's session by its own `run_id`
(`api_server_runs.py`: `session_id = selected_session_id or run_id`) — every turn would
be a new conversation. Until the 0.9.1 review this kept an unscoped install (the scope is
**blank by default**: Onboarding, `SessionScope`) on `/v1/responses`, with no approval
card.

The run body now carries the session's own client `session_id` (see the gaps below), so
the conversation no longer depends on the scope. **Every install rides runs.** The scope
only decides long-term memory: with no key, memory follows the transcript, exactly as an
unscoped `/v1/responses` conversation did.

## The barge-in (#404)

- The run id and its stop are bound **atomically** with the cancel check
  (`StreamState.bindRun`): a barge-in landing at any instant either sees the stop or is
  seen by the reader — never neither.
- A barge-in **does not cut the admission**. Once `POST /v1/runs` is sent the gateway may
  already have made the run; cutting it would lose the only id that can stop it. The
  admission finishes (bounded by the idle timeout, on the daemon reader, with the stream
  already retired) and the run it names is stopped; no events are subscribed for it.
- `POST /v1/runs/{id}/stop` goes through the stream's own client (same bearer, same
  scope), off-thread. 200, 404 `run_not_found` and 409 `run_not_active` all mean there is
  nothing left to stop; nothing about the stop is ever thrown to the caller.
- `RunsStreamTest` pins each of these; `LiveGatewayTest` asserts the stop reaches the
  (fake) gateway on a barge-in.

## The mapping (runs wire → app vocabulary → consumer)

The translation lives in `core/Stream.kt` (`dispatchRun` / `normalizeRunEvent`). Both
readers write the same `StreamState`, and every event the app sees is the vocabulary
it already renders.

| Responses event | runs event (0.22.6) | Reader emits | What the app does |
|---|---|---|---|
| `response.created` | *(admission 202 `{run_id}`)* | `response.created` | dev log `// entity responding…` |
| `response.output_text.delta` | `message.delta` (`assistant.delta` accepted) | `response.output_text.delta` | `onDelta` → preview text, `streamFeed` → TTS; appended to the reply transcript |
| `response.output_item.added` (message, phase=commentary) + `response.output_item.done` | `message.interim` (`assistant.commentary` accepted) | `response.output_item.done` (commentary) | dev log `// entity: …` — the presence aside; never spoken |
| `response.output_item.added` (function_call) | `tool.started` | `response.output_item.added` (`function_call`) | dev log `◆ tool: name …`, `onTool(name, args)`, `[tool:name]` marker |
| `response.output_item.added` (function_call_output) | `tool.completed` / `tool.failed` | `response.output_item.added` (`function_call_output`) | dev log `◆ tool · …`, `onToolOutput`, `[tool-done]` marker |
| *(no equivalent)* | `tool.progress`, `reasoning.available` | same names (unknown to consumers) | ignored by today's consumers; never enters the reply |
| `response.completed` | `run.completed` (`done` accepted) | `response.completed` | turn settles; done poll carries the reply |
| `response.incomplete` | `run.failed` with `partial: true` (or `run.cancelled` / `run.interrupted` with text) | `response.incomplete` | done + whatever was said is the answer |
| `response.failed` | `run.failed` / `error` | `response.failed` | failure reason surfaced; the controller falls back to assembled text |
| `approval.request` | `approval.request` (identical payload) | `approval.request` | `ApprovalGate` card; answer posted to `/v1/runs/{id}/approval` |
| *(none)* | `approval.responded`, `approval.settled`, `clarify.request`, `clarify.settled`, `run.steered` | same names | no consumer yet; clarify is surfaced, not rendered |

Deliberate gaps:

- **No response id.** A run has none (the terminal event carries `output`, `usage`,
  `runtime`, not an id). The runs body therefore omits `previous_response_id`. The
  conversation is a **client-owned `session_id`** in the body (0.9.1 review H2), which
  the gateway ranks above `X-Hermes-Session-Key` (`api_server_runs.py`: body
  `session_id` > response chain > declared key > `run_id`). The key stays the
  long-term-memory scope; the `session_id` is the transcript. `HermesSession` makes one
  per session object and rotates it in `resetConversation` (`RESET_CHAIN`), so `/new`,
  a new call and a profile switch start a fresh transcript. A stored turn (`/compress`,
  the VOX.md directive) rides `/v1/runs` with the same id, because `/v1/responses`
  cannot address a body `session_id`. `lastID` is left untouched by a runs turn — it
  names a stored `/v1/responses` snapshot that a run cannot advance.
- **`tool.started` has no `args`.** The responses route carries the tool's raw JSON
  arguments; runs carries only the gateway's `preview` — the tool's primary argument as
  one line (`agent/display.py`, `build_tool_preview`). The tool marker lines and the tool
  name are identical; the detail text is not (see `RouteParityTest`). The operations line
  keeps its subject on both: `Operations.step` reads a non-JSON argument as that preview
  (`date`, a URL → its host, a path → its file).

## The interim commentary finding

The brief's target list did not name it, and it is the one thing this port had to
prove. On `/v1/runs` it is **`message.interim`**: `{run_id, text, already_streamed}`
(`api_server_runs.py`, `_execute_run._interim_cb`, the same contract as the TUI
gateway). It exists, so shipping the runs route does not lose the presence asides.

`already_streamed: true` means the text already went out as answer deltas; the
responses writer suppresses that commentary copy for exactly that reason, so the runs
reader drops it too. `RouteParityTest` pins both the aside and the suppression.

## Fixtures and the proof

`testdata/stream/runs-*.sse` (normal, tools, error, incomplete, approval, commentary,
clarify) are **hand-built from the gateway source**, not captured — no gateway was
reachable. The provenance line is in `testdata/stream/README.md`.

`RouteParityTest` replays a responses fixture and its runs twin through the real
`HermesSession` wait → poll loop and asserts both produce the same:

- reply text and reply transcript (including the `[tool:…]` / `[tool-done]` lines),
- terminal event type and done flag,
- error reason (the `hermes response failed:` / `hermes run failed:` prefix names the
  route on purpose),
- tool marker lines and tool names,
- presence asides,
- approval map (including the session-authority `prompt_id` → `request_id` handle).

`RunsStreamTest` covers the transport-only behaviour: barge-in posts `/stop`, a
dropped socket reattaches at `last_seq`, the body carries no chain head, and clarify
arrives as its own event.

## The device pass (the cut gate)

The parity proof is off-device. Run every check on a **scoped** install (Settings →
the conversation scope is not blank), or the turns ride responses and prove nothing:

1. One live voice turn on the runs route: text streams, TTS starts on the first delta,
   the reply settles once.
2. A tool turn: `◆ tool` lines render, the reply is spoken (not swallowed), and the
   transcript keeps its line breaks.
3. Barge-in: the run reaches `cancelled` in `GET /v1/runs/{id}` (not merely a closed
   subscriber) and the gateway stops generating.
4. An approval: the card appears on a run that parks in `waiting_for_approval`, the
   answer posts once, and the run resumes. This is the capability the port exists for.
5. A dropped socket: airplane mode mid-turn, restore, and the turn completes from
   replay rather than the "voice stream was lost" error.
6. `/compress` after a runs turn: with no response id on the route, confirm the
   compaction targets the same conversation the run wrote to. If it does not, the
   stored-turn path needs its own route-aware chain before the flip.
7. A `clarify.request` (when a tool asks): the event reaches the app without being
   mistaken for an approval.
8. `/new` on a scoped install: the next turn does not remember the last one. Before
   0.9.1's H2 fix it did: the run body carried no `session_id`, so the gateway resolved
   the declared key to the same conversation. The run body now carries a client
   `session_id` that `/new` rotates (pinned off-device by `RunsStreamTest`) — confirm on
   the device that "what did I just say?" after `/new` gets no memory of the turn.
9. An unscoped install: turns ride `/v1/runs` with no `X-Hermes-Session-Key`, carry the
   same body `session_id` each turn, and keep their conversation ("what did I just say?").
