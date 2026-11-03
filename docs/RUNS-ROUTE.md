# The `/v1/runs` turn route (behind a switch)

Status: **built and proven, not shipped.** `StreamRoute.active` is
`TurnRoute.RESPONSES`; no user-visible behaviour changes until that one line flips.

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
    var active: TurnRoute = TurnRoute.RESPONSES   // <-- the flip
}
```

`Streams.start` reads it once per turn and picks the reader; tests set and restore it.
Nothing above `HermesSession` knows the route exists.

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
  `runtime`, not an id). The runs body therefore omits `previous_response_id` and the
  chain rides `X-Hermes-Session-Key`, which Vox always stamps. `lastID` is left
  untouched by a runs turn — it names a stored `/v1/responses` snapshot that a run
  cannot advance.
- **`tool.started` has no `args`.** The responses route carries the tool's raw JSON
  arguments; runs carries only the gateway's `preview`. The tool marker lines and the
  tool name are identical; the detail text is not (see `RouteParityTest`).

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

## Before flipping the default

The parity proof is off-device. A device pass still needs:

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
