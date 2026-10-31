# #252 — `/new` during a live call

**Outcome of this lane: could not reproduce the reported permanent deafness. The reset does
re-arm, but it re-dials through a network round-trip, and a capture loop that dies while
`callLive` is true can never be re-armed. No behaviour was changed here** (see "Why no fix").

Device: Pixel 7 (panther), `com.hermesvox` 0.9.0.2 (versionCode 145) = `testing` @ `69c9a5e`,
Presence/Enhanced mode, on-device Whisper. Reporter: Pixel 8 Pro, GrapheneOS.

## What `/new` does to a live call

1. `newSession()` → `ConversationReset.plan(NEW, callLive)` → `endCall = rearm = true`
   (`MainActivity.kt:1971`, `ConversationReset.kt:28`).
2. `endCall()` **hangs the call up**: `callLive = false`, `liveController?.stop();
   liveController = null`, fg-service/focus/wake released, `exitCallUi()`
   (`MainActivity.kt:682-703`, `689`). `stop()` is terminal for that controller —
   `stopped = true`, `exec.shutdown()` (`VoiceController.kt:1259-1264`).
3. A background thread then does `deleteLastResponse()` (a real `DELETE /v1/responses/{id}`),
   *then* `resetConversation()`, *then* posts `startCall()` (`MainActivity.kt:1980-1988`).
4. `startCall()` → `openVoiceLine()` builds a **new** `VoiceController`, waits for the
   pipeline to warm, then `callLive = true` and `c.start(...)` opens a new `AudioRecord`
   (`MainActivity.kt:669-671`, `VoiceController.kt:583-608`).

So the reset is not a reset: it is a hang-up plus a redial, and the redial is chained behind a
network call.

## What the device showed

`dumpsys audio` → `RecordActivityMonitor` (the mic client is created/destroyed per call):

| event | detail |
|---|---|
| 12:46:44.806 | `rec stop session:4857` — `/new` pressed mid-call (foreground) |
| 12:46:47.215 | `rec start session:4873` — **2.4 s later**, new controller, new recorder |
| 12:48:07.586 | `rec stop session:4873` — second `/new`, app backgrounded immediately |
| 12:48:21.220 | `rec start session:4889` — **13.6 s later** |

In both runs the call came back: timer restarted at `00:03`, the soul re-greeted
(`AudioTrack` at 12:46:49), and the recorder reopened `silenced:false`. **Recognition was alive
after `/new`** — the reported "dead until you hang up" did not happen here.

## The two defects that remain, and where they live

**A. The re-arm waits on the server copy.** `deleteLastResponse()` runs before
`runOnUiThread { startCall() }` (`MainActivity.kt:1982` vs `1986`) with
`GatewayClient.TIMEOUT_MS = 20_000` (`core/GatewayClient.kt:49`). The call is already hung up
at this point, so `/new` leaves the line down for the DELETE plus a full pipeline re-init —
measured 2.4 s and 13.6 s. A slow or unreachable gateway makes it 20 s or, if the socket never
completes, never: the reported workaround ("hang up and start a new call") is the only way out.

**B. Nothing re-arms a capture loop that dies while `callLive` is true.** The loop exits
silently when the recorder cannot start — `try { r.startRecording() } catch (_: Throwable)
{ break }` (`VoiceController.kt:697`) → `loopActive = false` (`VoiceController.kt:1201`) — and
that is indistinguishable from a healthy call: `callLive` stays true, the call UI and timer
stay up, and `isListening()` (`VoiceController.kt:573`, `listening && loopActive`) is **never
consulted by any host code** (only named in a comment at `MainActivity.kt:722`). The one
re-arm helper, `resumeLiveCallIfAny()`, early-returns while the call is live
(`MainActivity.kt:720`). So any transient failure during the re-dial (recorder busy behind the
old one, fg-service/mic restriction while backgrounded, warm-gate timeout) converts into a
call that looks connected and hears nothing — permanently, until hang-up.

A and B together are the reporter's symptom. A is a small reorder; B is a change to the
capture loop's ownership of its own restart, which #228 is already rewriting.

## Ruled out (with the reason)

- **Reset tears the loop down and never re-arms it** (the 0.9.0.1 / c3f0fd7 shape): fixed and
  still fixed — `ConversationReset.plan(NEW, live).rearm == true`, and the device shows the new
  recorder opening.
- **`listening` / `loopActive` inverted:** on the re-arm both are fresh (`false`) on a new
  controller; the mismatch only appears *after* a loop death (defect B), not because of the reset.
- **Platform recogniser destroyed and not recreated:** `stop()` nulls `recognizer`
  (`VoiceController.kt:1218`) and a new controller builds its own; not the on-device path.
- **VAD gate released and never re-armed:** the Silero gate belongs to the controller and the
  re-arm constructs a new one (`VoiceController.kt:485`); `isWarm()` gates on it.
- **A generation counter invalidating the restart:** `genCancelled` / `replyGen` live on the
  controller (`VoiceController.kt:97-103`); the re-arm gets a fresh controller, so `stop()`'s
  `genCancelled = true` cannot leak into it.

## Why no fix in this lane

The failing test the lane requires cannot be built off-device: this project has no Robolectric,
`MainActivity.newSession()` is Android-only, and the one pure seam (`ConversationReset.plan`)
already answers `rearm = true` today — the defect is the *order of execution*, which the plan
does not express. Writing that ordering rule would be a new structure invented to make its own
test fail. Per the lane rule, behaviour is left unchanged.

## Recommended follow-up (not applied)

1. **A:** capture the response id before the drop and re-arm first, delete afterwards —
   `s.resetConversation()` → `runOnUiThread { startCall() }` → `deleteResponse(id)` on the bg
   thread. The plan gains the step order it is missing, and that order is then unit-testable.
2. **B:** give the capture loop a bounded restart when `startRecording()` fails instead of
   `break`, and let `resumeLiveCallIfAny()` heal a live call with `!isListening()`. This is
   #228's ground; hand it there rather than patching it twice.
3. Pin both with a device check on the reporter's GrapheneOS build (`event=session-reset`,
   `event=fg-service-start`, and the `RecordActivityMonitor` start line are the witnesses).
