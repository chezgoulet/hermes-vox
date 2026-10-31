# #252 — `/new` during a live call

**Outcome of the 0.9.0.2 lane: could not reproduce the reported permanent deafness. The reset
does re-arm, but it re-dials through a network round-trip, and a capture loop that dies while
`callLive` is true can never be re-armed. No behaviour was changed there** (see "Why no fix").

**#351 (this lane) fixed the ordering half and measured it: the line now re-arms in ~2 s no
matter how long the server-copy DELETE takes (22.1 s → 2.1 s against a gateway that never
answers the DELETE).** The capture loop's own restart is still #228's ground.

Device: Pixel 7 (panther), `com.hermesvox` 0.9.0.2 (versionCode 145) = `testing` @ `69c9a5e`,
Presence/Enhanced mode, on-device Whisper. Reporter: Pixel 8 Pro, GrapheneOS.
The #351 numbers below are re-measured on the same phone running 0.9.0.3 sources
(versionCode 146) as a **debug** build, against a local fake gateway so the DELETE's latency
is a knob (see "How #351 was measured").

## What `/new` does to a live call

1. `newSession()` → `ConversationReset.plan(NEW, callLive)` → `endCall = rearm = true`
   (`MainActivity.kt:1981`, `ConversationReset.kt:53`).
2. `endCall()` **hangs the call up**: `callLive = false`, `liveController?.stop();
   liveController = null`, fg-service/focus/wake released, `exitCallUi()`
   (`MainActivity.kt:692-713`, `699`). `stop()` is terminal for that controller —
   `stopped = true`, `exec.shutdown()` (`VoiceController.kt:1259-1264`).
3. The plan's steps then run **in plan order** (#351): `resetConversation()` →
   `startCall()` → the DELETE, the last step, on its own thread
   (`MainActivity.kt:1995-2012`). The response id the delete needs is captured *before* the
   reset drops the chain head (`MainActivity.kt:1994`).
4. `startCall()` → `openVoiceLine()` builds a **new** `VoiceController`, waits for the
   pipeline to warm, then `callLive = true` and `c.start(...)` opens a new `AudioRecord`
   (`MainActivity.kt:669-671`, `VoiceController.kt:583-608`).

So the reset is not a reset: it is a hang-up plus a redial. Since #351 the redial no longer
waits on the server copy, and the server copy is deleted after (and concurrently with) it.

## What the device showed (0.9.0.2, before the ordering fix)

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

## The two defects that remained

**A. The re-arm waited on the server copy.** `deleteLastResponse()` ran before
`runOnUiThread { startCall() }` with `GatewayClient.TIMEOUT_MS = 20_000`
(`core/GatewayClient.kt:49`). The call is already hung up at this point, so `/new` left the
line down for the DELETE plus a full pipeline re-init. A slow or unreachable gateway made it
20 s or, if the socket never completed, never. **Fixed in #351** (see below).

**B. Nothing re-arms a capture loop that dies while `callLive` is true.** The loop exits
silently when the recorder cannot start — `try { r.startRecording() } catch (_: Throwable)
{ break }` (`VoiceController.kt:697`) → `loopActive = false` (`VoiceController.kt:1201`) — and
that is indistinguishable from a healthy call: `callLive` stays true, the call UI and timer
stay up, and `isListening()` (`VoiceController.kt:573`, `listening && loopActive`) is **never
consulted by any host code** (only named in a comment at `MainActivity.kt:722`). The one
re-arm helper, `resumeLiveCallIfAny()`, early-returns while the call is live
(`MainActivity.kt:720`). So any transient failure during the re-dial (recorder busy behind the
old one, fg-service/mic restriction while backgrounded, warm-gate timeout) converts into a
call that looks connected and hears nothing — permanently, until hang-up. **#228's ground;
still open.**

A and B together are the reporter's symptom. A was a reorder; B is a change to the capture
loop's ownership of its own restart, which #228 is already rewriting.

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

## Why the 0.9.0.2 lane left it alone

The failing test the lane required could not be built off-device: this project has no
Robolectric, `MainActivity.newSession()` is Android-only, and the one pure seam
(`ConversationReset.plan`) already answered `rearm = true` — the defect was the *order of
execution*, which the plan did not express. Per that lane's rule, behaviour was left unchanged.
#351 answered it by giving the plan the order it lacked (a step list), which is pure and
unit-testable, and by executing the plan instead of a hand-written statement sequence.

## #351 — the ordering fix, and what the device says now

`ConversationReset.Plan` is no longer three independent booleans: it carries an ordered step
list (`RESET_CHAIN`, `REARM_CALL`, `DELETE_SERVER_COPY`) and the re-arm precedes the delete in
every plan that has both (`ConversationResetTest.theRearmPrecedesTheServerCopyDelete_351`).
`newSession()` runs the plan in that order; the delete gets its own thread and its failure is
logged (`event=session-delete-failed`), never rethrown into the call path.

### How #351 was measured

Same Pixel 7, 0.9.0.3 sources, **debug** builds of the pre-fix tree and of #351, so the app
could be pointed at a controllable gateway. That gateway
(`/tmp/fake_gw.py`, `/v1/models` + `/v1/responses` + `DELETE /v1/responses/{id}`) answers
everything immediately except the DELETE, whose latency is a knob. A `/compress` first stores
a response (`lastID`), so the `/new` really does issue the DELETE. Witnesses: the app's own
`event=` lines (ms), the fake gateway's DELETE start/finish, and the `RecordActivityMonitor`
session id of the active recorder (polled at 100 ms; the deltas are seconds).

`/new` is pressed from the commands sheet mid-call; "background" means `KEYCODE_HOME`
immediately after the press, as in the 0.9.0.2 run.

| build | DELETE latency | re-arm, foreground | re-arm, background |
|---|---|---|---|
| 0.9.0.3, pre-#351 | 200 ms | 2.34 s | 16.56 s |
| 0.9.0.3, pre-#351 | 30 s (client gives up at 20 s) | **22.08 s** | 15.83 s |
| #351 | 200 ms | 1.94 s | 17.72 s |
| #351 | 30 s (client gives up at 20 s) | **2.07 s** | 15.77 s |

The recorder sessions behind the #351 rows (old → new, from `RecordActivityMonitor`):
foreground 5185→5193 and 5201→5209; backgrounded 5193→5201 and 5209→5217.

The pre-#351 foreground row is the defect, on the same build family as the 0.9.0.2 numbers
above: the re-arm follows the DELETE, so it tracks the gateway's latency (2.34 s → 22.08 s).
With #351 the same 30 s DELETE is issued at the tap instant and simply runs on beside the
call: the re-arm stays at ~2 s, which is the local pipeline warm (`warm-wait retry=1` at
~0.2 s, recorder at ~1.8 s), not the network.

The delete's failure path is also exercised: in the backgrounded run the app lost DNS when it
went to the background, and the log says
`event=session-delete-failed id=resp_fake_N err=Unable to resolve host …` **5.6 s after the
press, while the call was already on its way back** — housekeeping failed, the user still got
a microphone.

Backgrounded, nothing in this table depends on the DELETE either before or after #351: the
15–18 s there is Android refusing `startForegroundService()` from the background plus the warm
gate retrying (`event=fg-service-start-failed`), which is defect B / #228, not the reset.

## Follow-up

1. ~~**A:** capture the response id before the drop and re-arm first, delete afterwards.~~
   Done in #351.
2. **B:** give the capture loop a bounded restart when `startRecording()` fails instead of
   `break`, and let `resumeLiveCallIfAny()` heal a live call with `!isListening()`. This is
   #228's ground; hand it there rather than patching it twice.
3. Pin both with a device check on the reporter's GrapheneOS build (`event=session-reset`,
   `event=fg-service-start`, and the `RecordActivityMonitor` start line are the witnesses).
