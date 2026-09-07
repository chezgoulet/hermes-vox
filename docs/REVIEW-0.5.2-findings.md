I have everything I need. Here is the report.

---

# Hermes Vox — Adversarial Code Review
**Target:** `/home/c/vox-review` @ `9c1a95f` (main) · read-only · nothing modified
**Scope:** Part 1 (empty `tool_calls` attribution) + Part 2 (general correctness/maintainability)
**Verification performed:** full read of the Go module (`voice/`, `mobile/`, `cmd/`), full read of `VoiceController.kt`, `MainActivity.kt`, `AvatarView.kt`, `VisualStyle.kt` and every network/credential call site; exhaustive greps for `tool_calls` / `conversation_history` / `messages` across all file types; `go test ./voice/...` → **PASS** (`ok github.com/chezgoulet/hermes-vox/voice 0.812s`). `go vet` on `game/` fails only for a missing `X11/Xlib.h` in this sandbox (environment, not code).

---

## PART 1 — VERDICT: **Vox is EXONERATED as the *constructor* of `tool_calls: []`.** One real *contribution vector* exists, and it is not what was suspected.

### 1a. Vox cannot emit a `tool_calls` field. Proven, not assumed.

The string `tool_calls` (and `toolCalls`/`ToolCalls`) **does not appear anywhere in the codebase** outside `REVIEW-DIRECTIVE.md` itself. Same for `conversation_history`. The exhaustive grep across `*.go`, `*.kt`, `*.java`, `*.md`, `*.yaml`, `*.py` returns only the directive file.

The suspicion in the directive is confirmed: **Vox is stateless and sends only `previous_response_id`.** The entire request body is built in one place:

**`voice/responses.go:55-66`**
```go
func (c *HermesResponsesClient) buildBody(input string, previousResponseID string, stream bool) (map[string]any, error) {
	body := map[string]any{"model": c.model, "input": input, "stream": stream}
	if c.provider != "" { body["provider"] = c.provider }
	if previousResponseID != "" { body["previous_response_id"] = previousResponseID }
	return body, nil
}
```

That map has **exactly four possible keys** — `model`, `input`, `stream`, `provider`, `previous_response_id`. `input` is a plain Go `string`, never a structured array. This is the *only* body builder for `/v1/responses`; both the blocking path (`responses.go:90`) and the SSE path (`stream.go:99`) call it. There is no second serializer, no struct with omitempty-able slice fields, no map merge.

**The Android side never builds a request body at all.** The only JSON the Kotlin layer constructs for the gateway is the two conn-test probe payloads, which are hardcoded string literals (`VoiceController.kt:1339`, `1391`): `{"model":"","input":"hello","stream":true}`. Every real turn goes `VoiceController.runStreamedTurn` → `session.startStream(text)` → `mobile/session.go:149` → `voice/stream.go:285` → `buildBody`. Kotlin's only `JSONArray` use is *parsing* inbound SSE events (`VoiceController.kt:739`, `807`) and the model catalog (`MainActivity.kt:1023`).

### 1b. The one place Vox holds a message array — and why it still can't produce the bug

`voice/conversation.go:12-24` does keep a client-side `History []ChatMessage`, POSTed as `messages` by `voice/hermes.go:61`. But:

- `ChatMessage` (`voice/hermes.go:14-17`) has **exactly two fields**: `Role string`, `Content string`. There is no `tool_calls` field to serialize, empty or otherwise. It is structurally incapable of emitting the offending key.
- That path targets `/v1/chat/completions`, **not** `/v1/responses`.
- It is **unreachable from the app**: `HermesSession.TurnText` is exposed by gomobile but `grep -rn "turnText" *.kt` returns zero call sites. Dead surface.

### 1c. The real Vox-side contribution vector: **Vox chains `previous_response_id` to responses it aborted mid-generation.**

This is not "Vox builds an empty array." It is "Vox routinely asks the gateway to reconstruct history from a *truncated* assistant turn," which is a plausible way an assistant item that opened a tool call but never closed it ends up persisted with an empty `tool_calls`.

The mechanism, end to end:

1. **`voice/stream.go:144-148`** — `st.respID` is set at **`response.created`**, i.e. at the *start* of the turn, long before completion:
   ```go
   case "response.created":
       if env.Response != nil { ev.ResponseID = env.Response.ID; st.respID = env.Response.ID }
   ```
2. **`voice/stream.go:350`** — every `PollStreamJSON` payload carries `"response_id": respID`, whether or not the turn completed.
3. **`mobile/session.go:167-175`** — the mobile layer latches it unconditionally on *any* poll:
   ```go
   if json.Unmarshal([]byte(payload), &m) == nil && m.ResponseID != "" { s.lastID = m.ResponseID }
   ```
4. **`mobile/session.go:153-154`** — the next turn sends that id: `prev := s.lastID; return s.streams.StartStream(text, prev)`.

Now consider a barge-in. `VoiceController.bargeIn()` → `silenceAll()` → `session.cancelStream(sid)` (`VoiceController.kt:1242`) → `voice/stream.go:408` `st.cancel()` kills the HTTP request context mid-SSE. If the gateway had already emitted `response.output_item.added` with `item_type: "function_call"` (which `stream.go:153-158` handles, and which the app renders as `◆ tool:`) but not the matching `.done`, the connection dies with a tool call **opened and never closed**. `s.lastID` is *already* set to that response's id from step 3. The very next user turn chains onto it.

Barge-in is not an edge case here — it is a designed, frequent, first-class interaction (`bargeIn`, `hush`, `pauseForFocusLoss`, and `stop` all route through `silenceAll`). Every one of them cancels an in-flight generation and leaves the aborted response as the chain head.

**Honest bounding of this claim:** whether an aborted generation persists as `tool_calls: []` on the gateway side is *gateway* behavior I cannot observe from this repo. I am not asserting it does. What I *can* assert from this code is that Vox systematically hands the gateway an aborted response id as conversation state, and that this is the only mechanism in Vox capable of influencing what the gateway reconstructs. It is also **testable in one step**: reproduce a session with zero barge-ins/hushes and see whether the 400 recurs. If it does, Vox is fully clear; if it only appears after interruptions, this is the seam.

The suspicion that Vox sends a client-built history array is **refuted with certainty**. Nothing in Vox constructs, mutates, or transmits a `tool_calls` field.

---

## PART 2 — FINDINGS

### H1 — Long replies are dropped as "timeout" by a poll-*iteration* cap masquerading as a time budget
**SEVERITY:** HIGH
**LOCATION:** `android/app/src/main/java/com/hermesvox/VoiceController.kt:722`, `785`, `788`

**CONCLUSION.** The streaming loop is `while (!done && tries < 600)`, with `tries++` at line 785 executed once per poll iteration, and on falling out of the loop without `done`, line 788 does `throw Exception("timeout")` — surfacing `hermes: timeout` and **discarding the entire reply**.

The 600 cap only behaves as a ~60s timeout when the stream is *idle* (`waitStream(sid, 100)` blocks the full 100ms). But `WaitStream` (`voice/stream.go:376-385`) returns **immediately** whenever `len(st.events) > 0`. Under active streaming the loop therefore spins at roughly the SSE delta rate — one iteration per buffered batch, and since a poll round-trip through gomobile is far faster than the inter-token gap, batches are typically one event each. The cap becomes an **event counter, not a clock**.

**FAILURE SCENARIO.** The entity streams a ~600-token answer (≈2400 chars — an ordinary long reply, or a shorter one with several tool-call events, since `function_call` / `function_call_output` events also consume iterations). At delta #600 the loop exits with `done == false`, throws, and the user gets `hermes: timeout` instead of the answer that the gateway successfully generated and the app already has buffered in `text`. Time elapsed may be as little as 15-20 seconds. Ironically, the *faster* the provider streams, the sooner the reply is destroyed.

**SUGGESTED FIX.** Make the ceiling wall-clock, which is what the code intends:
```kotlin
val deadline = android.os.SystemClock.uptimeMillis() + 120_000L
while (!done && android.os.SystemClock.uptimeMillis() < deadline) { ... }
```
Drop `tries` entirely (it is otherwise only used in log lines). If a belt-and-braces iteration bound is wanted, it must be far above any plausible token count (e.g. 50_000) and must not be the primary exit.

---

### H2 — `/compress` runs on a **different response chain** than the voice turns, then hijacks the voice chain
**SEVERITY:** HIGH
**LOCATION:** `mobile/session.go:128-140` + `voice/conversation.go:57-67` vs `mobile/session.go:149-155`

**CONCLUSION.** `HermesSession` maintains `lastID` (`session.go:22`) for the streaming path. `Conversation` maintains an entirely separate `lastResponseID` (`conversation.go:23`) for the `turnStored` path. **Nothing ever synchronizes them.**

- `StartStream` reads `s.lastID` (`session.go:153`); `PollStreamJSON` writes `s.lastID` (`session.go:173`).
- `TurnStored` → `conv.TurnTextStored` reads and writes `c.lastResponseID` (`conversation.go:61`, `65`) — and *then* clobbers `s.lastID` with the result (`session.go:138`).

`turnStored` has exactly one caller in the whole app: `/compress` (`MainActivity.kt:1117`). Its documentation (`CompressCommand.kt:15`, `MainActivity.kt:1104-1106`) states it "rides the SAME server-side chain as the voice turns." **It does not.**

**FAILURE SCENARIO.** A user holds a 15-turn voice conversation (the exact scenario `CompressCommand.kt:90` cites as the motivation). `s.lastID` = `resp_15`; `conv.lastResponseID` = `""` (never touched by streaming). The user runs `/compress`:
1. `Response(ctx, "/compress", "")` — **no `previous_response_id`**. The compaction directive is sent into a brand-new, empty conversation. The gateway compacts nothing, or compacts the wrong thread. The card shown to the user reports success anyway (`CompressCommand.card` only checks for a non-blank reply).
2. `session.go:138` sets `s.lastID = <the fresh chain's id>`.
3. The next voice turn chains onto that fresh chain — **the entire 15-turn conversation is orphaned.** The user's context is silently destroyed by the command whose whole purpose was to preserve it.

**SUGGESTED FIX.** Collapse to one chain. Simplest correct change — have `TurnStored` use the session-level id directly and stop routing through `Conversation`'s private field:
```go
func (s *HermesSession) TurnStored(text string) (string, error) {
	...
	res, err := s.streams.Response(ctx, text, s.lastID)
	if err != nil { return "", err }
	s.lastID = res.ResponseID
	return res.Reply, nil
}
```
Then delete `Conversation.lastResponseID` (or have `TurnTextStored` accept the prior id as a parameter) so a second source of truth cannot reappear. Worth a unit test asserting that a `turnStored` after a streamed turn carries the streamed turn's id.

---

### H3 — The 0.5.0.1 teardown-crash guard `execSubmit` was written but **never wired up**; two submit sites have no guard at all
**SEVERITY:** HIGH
**LOCATION:** `VoiceController.kt:61-64` (definition, dead) vs `269`, `349`, `707`, `890` (raw `exec.execute`), `608` (`exec.shutdown()`)

**CONCLUSION.** `execSubmit` exists precisely to close the check-then-act race that produced the 0.5.0.1 crash — it re-checks `stopped`/`isShutdown` *and* catches `RejectedExecutionException`. `grep` confirms it has **zero call sites**. All four submissions call `exec.execute` directly:

- **`:707`** (`runStreamedTurn`) — has the manual pre-check at `:701`, so the window is narrow but the race the helper was written for is still open.
- **`:269`** (capture loop) — manual pre-check at `:268`, same narrow window.
- **`:349`** (partial-STT worker, submitted from the capture thread) — **no guard whatsoever**.
- **`:890`** (streaming-TTS worker, submitted from `streamBegin` on the **main thread**) — **no guard whatsoever**.

**FAILURE SCENARIO.** User ends a call (or the Activity stops with no live call, `MainActivity.kt:1290`) while a reply is settling. `stop()` runs `exec.shutdown()` at `:608`. A turn already in flight reaches `settleReply` → `streamBegin` → `exec.execute` at `:890` on the main thread → `RejectedExecutionException` propagates uncaught out of a `main.post` runnable → **process crash**, captured by `VoxLog`'s uncaught handler at `VoxLog.kt:88` and then `killProcess`. This is the same crash class the 0.5.0.1 guard was authored to prevent; the fix is present in the file but not applied.

**SUGGESTED FIX.** Replace all four sites with the helper, e.g. `:890` becomes `if (!execSubmit { ... }) { sRunning = false; return }`, and `:707` becomes `if (!execSubmit { ... }) { turnInFlight = false; releaseTurnGate(gen, "stopped"); return }`. The manual pre-checks at `:268`/`:701` can then be deleted as redundant.

---

### M1 — Every barge-in leaks a `streamState` in the Go `streams` map, for the life of the process
**SEVERITY:** MEDIUM
**LOCATION:** `voice/stream.go:291`, `307-308`, `340-343`, `401-409` + `VoiceController.kt:727`

**CONCLUSION.** Map entries are registered at `stream.go:291` and removed in exactly one place — `PollStreamJSON` when it drains a `done == true` poll (`:340-343`). `CancelStream` (`:401`) cancels the context but **never deletes the entry**; the comment at `:307-308` explicitly delegates cleanup to that final poll.

But on barge-in the Kotlin worker breaks out **before** that poll: `bargeIn()` sets `genCancelled = true`, and the loop's first statement is `if (genCancelled) break` (`:727`). The worker exits without ever draining `done`. The entry — holding the accumulated `text` `strings.Builder`, the buffered `[]StreamEvent`, and a `chan struct{}` — stays in the map forever.

There is a race in which the current iteration's poll happens to land after the goroutine set `st.done = true`, cleaning up incidentally; but the cancel unwind (closing the HTTP connection) is slower than the immediately-following poll, so the common case leaks.

**FAILURE SCENARIO.** A long hands-free session where the user interrupts the entity 40 times accumulates 40 orphaned `streamState`s, each retaining the full accumulated reply text of an aborted turn. Unbounded native-heap growth in the gomobile module across a session; nothing ever reclaims it.

**SUGGESTED FIX.** Make `CancelStream` own the removal — it is the terminal operation for that stream from the caller's perspective:
```go
func (c *HermesResponsesClient) CancelStream(streamID string) error {
	streamsMu.Lock()
	st := streams[streamID]
	delete(streams, streamID)
	streamsMu.Unlock()
	if st == nil { return nil }
	st.cancel()
	return nil
}
```
(`PollStreamJSON`/`WaitStream` already return a clean "no such stream" error for a retired id, and `VoiceController.kt:733`/`735` already swallow exactly that.)

---

### M2 — The Settings connection test POSTs a **real generation** to the entity and then abandons it
**SEVERITY:** MEDIUM
**LOCATION:** `VoiceController.kt:1386-1391` (`probeConnection`), same pattern at `1337-1345` (`testConnection`)

**CONCLUSION.** The "stream leg" of the probe is not a handshake — it is a genuine turn:
```kotlin
c.outputStream.use { it.write("{\"model\":\"\",\"input\":\"hello\",\"stream\":true}".toByteArray()) }
streamCode = c.responseCode
```
Three problems: (a) it makes the entity actually generate a response to the word "hello", which the gateway may persist into the session the user is in; (b) `"model":""` sends an empty model route, whose gateway-side fallback behavior is unspecified; (c) it reads only the status line and **never calls `disconnect()` or drains/closes the stream** — the generation runs on for nobody while the socket is returned to the keep-alive pool with an unconsumed body.

This is user-initiated (Settings → test, `includeStream = true`); the main screen correctly uses `includeStream = false`. So it is not the *routine* path — but it is an abandoned mid-flight generation on the live session, which is the same family as H3's chaining concern.

**FAILURE SCENARIO.** User taps "Test connection" mid-conversation. The entity is asked to answer "hello" on an unspecified model; the app throws the answer away; depending on gateway persistence, a stray turn now sits in the session history.

**SUGGESTED FIX.** The probe should test *reachability + auth*, not generation. `GET /v1/models` (the ping leg, already present at `:1375`) plus a `HEAD`/`OPTIONS`, or at minimum a POST with `stream:false` and a real model id that is immediately closed via `try { c.inputStream.close() } finally { c.disconnect() }`. Given `reconcile()` already lets the live channel overrule the probe on auth (`:1405-1406`), the stream leg arguably earns very little and could be dropped.

---

### M3 — `ModelDownloader`'s failure-cleanup is a no-op due to a shadowed variable; a failed unpack leaks the staging directory
**SEVERITY:** MEDIUM
**LOCATION:** `ModelDownloader.kt:67`, `94`, `96`, `100`

**CONCLUSION.** Line 67 declares `var tmpDir: File? = null` and it is **never assigned**. Line 94 declares a *new* `val tmpDir` inside the `try`, shadowing it. Therefore the cleanup at `:96` (`catch`) and `:100` (`finally`) — `tmpDir?.deleteRecursively()` — always sees `null` and does nothing.

Separately, `conn.disconnect()` is called only on the success path (`:85`); the early returns for non-200 and `unknown-length` (`:83`, `:87`) and any thrown exception leak the connection.

**FAILURE SCENARIO.** A corrupt/interrupted archive throws inside `unpkg`. `<filesDir>/models/whisper-small.tmp` — potentially hundreds of megabytes of partially-extracted model — is left on the user's device permanently. Retrying the download starts with `deleteRecursively()` on the same path so it self-heals *if* the user retries the same model, but a user who gives up or switches models never reclaims it.

**SUGGESTED FIX.** Assign the outer variable instead of shadowing: change `:94`'s `val tmpDir = File(...)` to `tmpDir = File(...)` and hoist the subsequent uses, or restructure into a `runCatching` with an explicit staging path computed before the `try`. Wrap the connection in `try/finally { conn.disconnect() }`.

*(Note: the archive handling itself is sound — SHA-256 is pinned and verified before unpack at `:88-91`, and both `untarBz2` and `unpkgZip` do proper canonical-path zip-slip checks at `:126` and `:148`.)*

---

### M4 — `RealtimeActivity` expects a **plaintext** key via intent extra and has no launcher; it is exported dead code
**SEVERITY:** MEDIUM (latent — currently unreachable)
**LOCATION:** `RealtimeActivity.kt:40-44`; declared in `AndroidManifest.xml:45`

**CONCLUSION.** This is the sibling-site class the directive asked about. `RealtimeActivity` reads `intent.getStringExtra("key")` and passes it **straight to `HermesSession(url, key, model)`** with no `GatewayKey.resolve` / `SecureStore.decrypt` step. Every other credential consumer resolves correctly — `MainActivity.storedKey()` (`:746`), `VoiceController.gatewayKey()` (`:1324`), `SettingsActivity:320`, `OnboardingActivity:37`, and `RemoteStt.apiKey()` (`:158`, for its own separate STT pref). So the K1 fix was applied consistently to every *live* path.

`RealtimeActivity` escapes the audit only because **nothing launches it** — `grep` for `RealtimeActivity` finds no `startActivity` anywhere. It is not currently a false-401 source; it is a trap for whoever wires it up next, since the pref it would naturally be fed (`prefs.getString("key")`) is ciphertext.

**SUGGESTED FIX.** Either delete the activity and its manifest entry, or make it resolve like everyone else and stop trusting an intent extra:
```kotlin
val key = GatewayKey.resolve(
    getSharedPreferences("hv", MODE_PRIVATE).getString("key", "").orEmpty(),
    SecureStore::decrypt)
```
Deleting is cleaner — `MainActivity` + `AvatarView` already cover this screen's function, and an exported activity that accepts a bearer credential from an arbitrary caller is a surface worth not having. (`MainActivity`'s equivalent intent path is correctly fenced to debug builds only — `MainActivity.kt:211`.)

---

### M5 — Sprite cache evicts by full `clear()`, producing a re-bake burst exactly when it is under pressure
**SEVERITY:** MEDIUM (perf, cycle-all only)
**LOCATION:** `AvatarView.kt:439-451`

**CONCLUSION.** Both caches do `if (cache.size >= CACHE_CAP) cache.clear()` before baking. On overflow, *every* resident sprite is discarded, so the next frames must re-bake from scratch — up to three `Bitmap.createBitmap` + `RadialGradient` bakes per frame (`glowFor(curBase)`, `glowFor(curAcc)`, `haloFor(curBase)`), the halo at 128×128 ARGB_8888 ≈ 64KB each. This is the one allocating path in an otherwise genuinely zero-allocation render loop.

The cache-key design (`spriteKey`, `:428-436` — 4-bit-per-channel colour plus 2-bit light-model buckets) and `CACHE_CAP = 48` are well reasoned, and `advanceCycle`/`applyFixedCategory` correctly drop the caches off the hot path on a category retarget. The cliff only bites when a cycle-all crossfade's eased colour walks past 48 distinct keys.

**FAILURE SCENARIO.** With `visual_cycle_all` on, a palette rotation crosses the cap mid-glide: a visible hitch plus a GC-pressure spike, on the same frames the crossfade is supposed to look smoothest.

**SUGGESTED FIX.** Evict one entry rather than all — `LinkedHashMap(cap, 0.75f, true)` with `removeEldestEntry`, or `android.util.LruCache`. One-line-ish change that removes the cliff entirely.

---

### L1 — `Conversation.History` grows unbounded on a path that is dead but reachable via the public gomobile API
**SEVERITY:** LOW
**LOCATION:** `voice/conversation.go:44-50`

`TurnText` appends two `ChatMessage`s per turn and re-POSTs the entire array every time, with no cap and no trimming. Only `Reset()` clears it. Currently harmless — no Kotlin caller — but it is exported through `HermesSession.TurnText` (`mobile/session.go:117`), so it is one call site away from being a live O(n²) upload. **Fix:** delete `TurnText`/`Chat`/`HermesClient` if the `/v1/responses` path is the committed architecture (the file header at `responses.go:14-17` says it is), or cap the history.

### L2 — `VoxLog.append` reopens the file per line and is unsynchronized across threads
**SEVERITY:** LOW
**LOCATION:** `VoxLog.kt:59-68`

`file?.appendText(...)` does an open/write/close syscall triple per log line, called concurrently from the main thread, the capture thread, the stream worker and the TTS engine thread with no lock (only `rotateIfNeeded` takes one). Interleaved partial lines are possible, and `writesSinceCheck` is a non-atomic `@Volatile` increment so the rotation cadence drifts. Not a correctness risk for the app, but it is I/O on the audio path. **Fix:** hold a single `BufferedWriter` guarded by the existing `rotationLock`, or hand appends to a single-thread executor.

### L3 — `sendText` reuses the live `turnGen` instead of arming a new one
**SEVERITY:** LOW
**LOCATION:** `VoiceController.kt:625-629`

Deliberate (the `:129-134` comment explains why a gen-keyed guard would be wrong), and `runStreamedTurn`'s `turnInFlight` check plus `voiceState.arm()` make it safe in practice. Flagging only because the invariant "one gen per turn" holds everywhere *except* here, which is the kind of asymmetry that breaks under a later refactor. Consider `turnGen++` in `sendText` and keeping the T3 epoch guard (which is already keyed on epoch, not gen) as the double-count defense.

### L4 — God-files
**SEVERITY:** LOW
`VoiceController.kt` (1619), `MainActivity.kt` (1305), `AvatarView.kt` (1296) — 4220 lines across three files, ~50% of the Kotlin. To the codebase's credit, the genuinely tricky logic has already been extracted into pure, unit-tested objects (`BargeGate`, `ReplySettleRule`, `StreamRetirementState`, `VoiceLoopState`, `EndpointRule`, `ConnectionPhase`, `SpeechCursor`, `VisualStyle`, `FloatRing`) with 20 test files behind them — this is better factored than the line counts suggest. The remaining split that would pay for itself: lift the streaming-TTS queue (`streamBegin`/`streamFeed`/`streamFinish`/`stopStreaming` + the `s*` field cluster, ~200 lines) out of `VoiceController` into its own class. It has a clean interface and no dependency on the mic path.

### L5 — `journal/` contains no Go source
**SEVERITY:** LOW
The directive lists `journal/` as part of the gomobile module; it holds a single markdown file (`2026-08-23.md`). Either stale scaffolding or a doc directory misfiled next to the Go packages. Cosmetic, but worth resolving so the module layout matches its description.

---

## Claims I checked that **did not** hold up

Recording these so they aren't re-investigated:

- **Sibling false-401 sites.** The K1 fix is applied consistently. Every live credential consumer resolves through `GatewayKey.resolve` or `SecureStore.decrypt`. The only unresolved read is `RealtimeActivity` (M4), which is unreachable. No live path sends ciphertext as a bearer token.
- **Render-loop allocation.** `onDraw` (`AvatarView.kt:1202`) and the integrator (`:1104-1177`) allocate **nothing** per frame — reused `RectF`, hoisted frame constants, a 4096-entry sine LUT, `field(p)` writing to `ftx`/`fty` scratch instead of returning a Pair. The `COUNT`-particle loop gains exactly the two multiplies the comments claim. The only per-frame allocation is the sprite bake, and only on a cache miss (M5).
- **Reveal-freeze transcript boundary.** `freezeCursor` (`:1140`) samples the playback head *before* `stopTts()` tears the track down (`silenceAll:1237-1238` ordering is correct), `revealedChars` is monotonic (`:1113`), and `stopReveal` (`MainActivity.kt:83-96`) correctly branches on `speechFrozen()` so an interrupted tail is never auto-completed. This is right.
- **Barge-in / escape rule.** `BargeGate.accumulate`/`decide` match their documented derivation, the caller's per-read clock discipline (`frameMs = n * 1000L / sr`) is consistent across both accumulators, and the grace/skip-state guards are correctly applied before the accumulators. The `sustainedMs = 0L` reset on `inGrace || stateSkip` (`:507`) is deliberate, not a leak.
- **`indexOfSentenceEnd` chunk seam.** Abbreviation and decimal handling (`isSentenceDot`, `:1556-1568`) is correct — `"71.5%"` and `"Dr. Chen"` both stay whole.
- **Zip-slip / model integrity.** Both extractors canonical-path-check; SHA-256 is pinned and verified *before* unpack.
- **Teardown drain.** `stop()`'s bounded 500ms wait for `loopActive`/`partialRunning` before releasing native handles (`:586-590`) correctly prevents use-after-free on `record`/`vad`/`stt`. The ordering (`silenceAll` → drain → release → `stopped = true` → `shutdown()`) is right; the gap is H3's missing guard on the *submit* side, not this side.

---

## Top 5 most worth fixing (impact per effort)

1. **H1 — the 600-iteration cap** (`VoiceController.kt:722`). Silently destroys completed long replies; ~3-line fix to a wall-clock deadline. Highest impact per effort in the report, and it is a user-visible "the entity didn't answer" bug today.
2. **H3 — wire up `execSubmit`** (`VoiceController.kt:61`, sites `269/349/707/890`). The fix is already written and sitting unused; four one-line call-site changes close a live crash race, two of whose sites have no guard at all.
3. **H2 — unify the response chain** (`mobile/session.go:138` / `conversation.go:61`). `/compress` currently orphans the user's conversation — the exact opposite of its purpose. Small, localized Go change plus one regression test.
4. **M1 — delete the map entry in `CancelStream`** (`voice/stream.go:401`). Three lines; removes an unbounded per-barge-in leak in the native module.
5. **Part 1 follow-through — instrument the chain, don't guess.** Log `previous_response_id` alongside the existing `turn done: resp=` line (`VoiceController.kt:766`) and add a marker when the chained id came from a cancelled turn. Then run one clean session with zero interruptions. That single experiment either clears Vox completely or pins the seam described in §1c — and it costs one log line.

**Bottom line for Part 1:** Vox does not build, hold, or transmit `tool_calls` in any form; the client-history hypothesis is refuted with file-level certainty. The only way Vox can influence what the model sees is by handing the gateway the id of a response it aborted mid-generation — worth ruling in or out with item 5 before further gateway forensics.

**REVIEW COMPLETE**

I made no modifications to the repository. `review.out` is still empty — say the word if you'd like this written there.
