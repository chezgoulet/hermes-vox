# The Kotlin voice core (#126)

Until #126 the app reached Hermes through a Go library (`voice/`, `mobile/`) bound
into the APK with gomobile as `mobile.aar`. It is now plain Kotlin in
`android/app/src/main/java/com/hermesvox/core/`, on `HttpURLConnection`, with no new
dependency. The app calls the same methods it called before; only the import
changed (`com.hermesvox.mobile.HermesSession` → `com.hermesvox.core.HermesSession`).

## What moved

| Go (deleted) | Kotlin | Notes |
|---|---|---|
| `mobile/session.go` `HermesSession` | `core/HermesSession.kt` | Same method names and shapes. Methods throw `HermesException` (a gateway answer) or `IOException` (transport) where gomobile threw `Exception`. `waitStream` takes a `Long` (gomobile mapped Go `int` to `long`). |
| `voice/responses.go`, `voice/ping.go` | `core/ResponsesClient.kt` | Request body, the voice prefix (`USER_TURN_PREFIX`), stored turn, ping. |
| `voice/stream.go` | `core/Stream.kt` | The SSE framer, event dispatch, poll/wait/cancel registry, `plainText`. |
| `voice/gateway.go` | `core/GatewayClient.kt` | `/api/model/options`, `/v1/health`, `DELETE /v1/responses/{id}`. |
| `voice/entity.go` | `core/Entity.kt` | URL joining, bearer + `X-Hermes-Session-Key`, bounded error bodies, per-exchange deadline. |
| `encoding/json` | `core/Json.kt` | A strict reader/writer; see below. |
| `voice/*_test.go`, `mobile/session_test.go` | `core/HermesSessionTest.kt`, `StreamTest.kt`, `JsonTest.kt`, `StreamParityTest.kt` | Ported, plus the parity harness. |
| `voice/integration_live_test.go` | `core/LiveGatewayTest.kt` | Same env gating (`HERMES_VOX_LIVE=1` + `HERMES_VOX_HERMES_API_KEY`). |

### The four fixes, and where they live now

1. **Wall-clock turn deadline (H1).** It was always app-side: `StreamPollGate` and the
   poll loop in `VoiceController` (120 s). Unchanged. Below it, the stream's **idle
   watchdog** (60 s without a byte, keepalives included) is now the socket read
   timeout, which also bounds the wait for response headers.
2. **`/compress` rides the streamed chain (H2).** One chain head,
   `HermesSession.lastID`: every poll that carries a `response_id` moves it, and
   `turnStored` sends it as `previous_response_id` and replaces it with the reply's id.
   Pinned by the `compress` fixture (the third request must chain off
   `resp_compress`).
3. **Cancel retires the stream (M1).** `cancelStream` removes the entry under the
   registry lock before closing the connection, so a barge-in never leaks the
   buffered state. Pinned by the `barge` fixture and `StreamTest`.
4. **Writer/poller race (#16).** Every field of a stream's state is read and written
   under one lock that the reader thread and the poller both take. The push wake
   (#39) is a capacity-1 queue, drained by the poll that takes the batch.

### Why a hand-written JSON reader

The stream needs three things `org.json` does not give: strict validity (the SSE
framer asks whether buffered data is already one complete value, and `org.json`
accepts `{a:1}`), the raw source text of a value (`function_call` arguments pass
through raw, as Go's `json.RawMessage` did), and working off-device (android.jar's
`org.json` is a stub in JVM unit tests). `Json.kt` follows the `encoding/json` rules
the gateway's events relied on: keys match case-insensitively with the last
occurrence winning, a wrong-typed field drops the whole event, `null` is the zero
value, and a lone surrogate escape decodes to U+FFFD. The `malformed` fixture pins
those rules against Go's own output.

## What was deliberately dropped

- **The runs API** (`StartRun`, `RunStatus`, `CancelRun`, `voice/runs.go`): zero call
  sites. Barge-in uses stream cancellation.
- **The stub backends** (`voice/backend.go`, `voice/backends.go`: `Cloud`, `Local`,
  `SelfHosted`) and `Conversation.TurnAudio`: no production caller (#47). The
  `VoiceBackend` idea in AGENTS.md is not code yet.
- **`/v1/chat/completions`** (`HermesClient`, `TurnText`, the client-side history):
  never called by the app.
- **`Config` / `LoadFromEnv`**: the Go-side env loader. The app takes its settings
  from Settings; the live test reads the same env names itself.
- **The callback `Stream()`**: only Go tests used it. The app uses poll/wait.

## Behaviour that differs from the Go core

None of these show up in the fixtures. Each is a property of running on the
platform's HTTP stack instead of Go's.

- **The app's network security config now applies to entity turns.** Go's `net/http`
  ignored it, so cleartext `http://` to a LAN IP worked for turns even though
  `network_security_config.xml` (and DEVICE_VERIFY's troubleshooting) say only
  `*.ts.net` may be cleartext. On the platform stack the policy applies, so a
  cleartext LAN gateway will be refused, as the conn-test probe already was (this
  follows from the platform's documented behaviour; it was not exercised on a device
  in #126). Likewise user-installed CAs are now trusted for HTTPS, as that file
  intends; Go's `crypto/x509` searched the system CA directory, not the user store.
- **HTTP/1.1 only.** Go negotiated HTTP/2 where offered. SSE is unaffected.
- **Transport error text** comes from the JVM/OkHttp (`Connection refused`, …), not
  Go (`dial tcp …`). Gateway-produced errors (`hermes response failed: …`,
  `hermes stream: connection lost …`, `hermes stream 502 Bad Gateway: …`) are
  identical; the fixtures check them.
- **Overall deadlines** for blocking calls (120 s stored turn, 20 s gateway calls,
  10 s ping) are enforced by tearing down the connection, matching Go's
  `http.Client.Timeout`; the message is `… timed out after Ns`.
- **A connect timeout** (10 s) is reported as a connect failure, not as "connection
  lost"; only a silence after connecting trips the idle watchdog.

## The parity harness

`testdata/stream/*.sse` are SSE bodies lifted from the Go tests' inline bodies (plus
`tools`, built from the documented event shapes, and `malformed`, synthetic edge
cases). Before the Go code was deleted, a Go program (`tools/streamdump`) replayed each
one through the Go session and wrote `*.golden.json`: every event the app would see,
the poll snapshot at each deterministic point, cancel semantics, whether a held
connection was closed, and every request the gateway received. `StreamParityTest`
replays the same fixtures through the Kotlin session with the app's own wait → poll
loop and compares structurally.

At the cutover both sides matched all 10 goldens. Each side was checked to fail on
drift: making Kotlin end the `[tool-done]` marker with a newline failed `tools`, and
editing `error.golden.json` failed Go's test. The goldens are now the frozen
specification; `scripts/gate.sh` fails if `StreamParityTest` did not run and pass.

One golden records a quirk rather than a design: in `tools`, the answer delta after a
tool output is appended to the `[tool-done]` line, so `plainText` drops it and the
done poll's `text` is empty. The Go core did this too. Fixing it is a behaviour change
and needs its own decision and a regenerated golden.

## Adding a fixture

See `testdata/stream/README.md`. In short: write `<name>.sse` (directives `voice`,
`hold`, `idle_timeout_ms`, `cancel_after_events`, `stored`), run

```sh
cd android
STREAM_GOLDEN_UPDATE=1 ./gradlew testDebugUnitTest --tests '*StreamParityTest'
```

which writes only goldens that are missing, then read the new golden line by line
before committing both. It was produced by the code under test, so it is a
specification only once a person has checked it.

## The one breaking change: HTTPS entity endpoints

Turns now go through the platform HTTP stack, so the app's network security
policy finally applies to them: **cleartext is refused for every host except a
tailnet MagicDNS name**. A gateway addressed as `http://192.168.x.x:8080` stops
working on upgrade.

The way out is one command on the gateway host — `tailscale serve --bg 8080` —
after which the endpoint is `https://<machine>.<tailnet>.ts.net`. Any HTTPS
terminator works as well. The failure message in the app names this, and
`DEVICE_VERIFY.md` has the step-by-step under "Upgrading to 0.9".
