# M1 Demo Note — Completeness Test (GREEN)

Date: 2026-08-25 · Host: Thelio (c@sasquatch) · Emulator-5554 (Android 15)

## What was proven

1. **Go unit tests** — `go test ./voice/...` ok (offline, mocked).
2. **Live integration suite** (`voice/integration_live_test.go`, gated by
   `HERMES_VOX_LIVE=1` + `HERMES_VOX_HERMES_API_KEY`) — 6/6 PASS against the
   REAL gateway (http://100.84.47.125:8642, model hermes-agent):

```
--- PASS: TestLiveResponsesTurn        (1.31s)  reply="vox-live-turn-ok"
--- PASS: TestLiveResponsesChain       (6.74s)  codeword recalled via previous_response_id
--- PASS: TestLiveStreamTurn           (1.26s)  created+delta+completed, reply assembled
--- PASS: TestLiveStreamToolProgress   (2.29s)  function_call name=terminal + _output items
--- PASS: TestLiveRunCancelRoundTrip   (2.51s)  started -> running -> stop 200 -> cancelled
--- PASS: TestLiveStreamCancelMidFlight(3.00s)  ctx-cancel aborts the SSE stream promptly
```

3. **Build/verify gate** — `scripts/gate.sh` (one command): vet -> offline
   tests -> js-wasm -> gomobile bind -> STAGE AAR into app/libs (load-bearing;
   Gradle consumes the copy — a fresh bind without staging builds stale) ->
   `gradle clean assembleDebug`. Output: `GATE-GREEN`, app-debug.apk 67.9 MB.
4. **Android turn path through the REAL UI binary** — adb-driven:
   fresh launch -> typed URL + key + model -> tapped Connect ->
   status "Connected → the entity" -> typed "Say exactly: e2e-ui-ok" ->
   tapped Send -> the agent replied. Evidence: logcat accessibility dump shows
   `com.hermesvox:id/reply` text `e2e-ui-ok` and stream log
   `// agent → e2e-ui-ok`; screenshot confirms Connected + input + reply flow.

## Bugs the completeness test caught (the point of M1)

- **`POST /v1/runs` answers 202 Accepted** — `StartRun` only accepted 200 and
  failed instantly against the live agent. Offline mocks never saw it. Fix:
  accept 200/201/202 (voice/runs.go).
- **Staged-AAR staleness** — Gradle links `app/libs/mobile.aar` (a COPY);
  rebuilding the root `mobile.aar` without re-staging produced "up-to-date"
  builds against the OLD bind. Fix: gate.sh stages the AAR + `clean` build.
- **gobind Javadoc trap** — `*/` inside a Go doc comment (e.g. "Turn*/Stream*")
  terminates the generated Javadoc early -> broken Java -> bind fails.
  Fix: never put `*/` in doc comments of bind packages.

## Contract notes (measured live, 2026-08-25, Hermes 0.20.0)

- `/v1/responses stream:true` emits the OpenAI Responses SSE shape:
  `response.created` -> `response.output_item.added` (message | function_call |
  function_call_output) -> `response.output_text.delta` -> `response.completed`,
  each with `sequence_number`. There is NO separate `hermes.tool.progress`
  event on this build — tool progress = the function_call items.
- `/v1/runs`: POST -> 202 `{run_id, status:"started"}`; GET -> status
  running/completed/cancelled + output; POST /stop -> 200 `{status:"stopping"}`
  -> terminal `cancelled` (verified mid-run with a sleep-45 tool task).
- Streaming barge-in = close the HTTP connection (ctx cancel) — verified.
