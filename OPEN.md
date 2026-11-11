# #538 — open questions (revised 2026-11-10, after the reporter's log)

## What to ask the reporter next

The log settled the 0.9.1 hang: a reply under 4 s never starts playing, and the turn waits
124 s (`FINDINGS.md`, *The reporter's log*). These three asks confirm the fix and separate what
is left. All the lines are `d`/`w`, so they are in "Send full Hermes Vox log", and on this
build every line carries its date.

### 1. Run the fixed build and ask a few short questions

> "Install the build from this branch. In one call, ask three or four questions with one-line
> answers ('what time is it?', 'yes or no: …'). Then send the full log."

| Line | Reads as |
|---|---|
| `event=tts-track … startFrames=11025` (44.1 kHz voice) | The start threshold took (API 31+). |
| `event=tts-drain result=played … waitedMs=<a few thousand>` after each short reply, then `tts-retire` and `gate-release … reason=stream-done` | **D is fixed on their device.** |
| `event=tts-track … startFrames=default` + `event=tts-drain result=forced` | API < 31, or the threshold call failed. The backstop worked; ask for the Android version. |
| "working" still held with `tts-drain result=played` | **Not D.** Read the turn's `stream-stall` / `wait-silent` lines. A turn silent for 60 s must now write `wait-silent`; if it does not, the ladder is next. |

### 2. On 0.9.1, did they *hear* the short answers?

> "When the screen sat on 'working' after a short answer, had you heard the answer spoken?"

The log cannot say what was audible. A head that never moved (`reveal-hold … samples` above
`of`) implies the reply was never rendered. "No, I never heard it" confirms that, and closes
the first report's "TTS does not work" on the same mechanism. "Yes, I heard it" means the
head counter, not the audio, was stuck. The fix holds either way, but the explanation of the
first report would change.

### 3. The gateway's own log for one dated episode

> "With the fixed build, if a turn still hangs, note the time and send the gateway's log
> (Hermes and llama.cpp) for those two minutes."

| Gateway shows | Phone line it matches | Reads as |
|---|---|---|
| The response completed in seconds | `turn done: gen=N … err=` at the same time | A phone-side wait (D or a new one). |
| The request arrived, the model never produced a token | `stream-stall` ×n → `wait-silent` at 60 s | A silent run (S). The tunnel is not at fault. |
| No request at all | `connection lost (no data …)` at 60 s, or `run-admit … result=lost` | The tunnel / the path (S, H1). |

## Still undecided

- **First trigger of the voice cascade (link 1).** None of the 8 `stream-synth` failures in the
  log has a SherpaTts cause line, so it was one of 0.9.1's unlogged `false` returns: a closed
  fence, no engine, or null samples. This branch's adopt/CUT patch blames none of those. A log
  from this build with `tts-stream-adopt` or `tts-drop reason=cut|no_engine` would name it.
- **The old build's 45 silent turns** (25 `connection lost` at 60 s, 19 budget ends at 120 s,
  plus one at 72 s). None appear on 0.9.1 in this file. Ask 3 separates tunnel from run, if
  they recur.
- **`turn-abandon gen=5 route=runs reason=budget run=- elapsedMs=0`** (one line, new block). A
  typed turn submitted the instant the previous reply retired, with its budget already expired
  at 0 ms. It is unexplained, and it is the only instance.
- **The poll-abort fallthrough** (`VoiceController.kt:2663` → `:2833`) mislabels an abort as
  `err=timeout`. Cosmetic; see `DECISIONS.md` 27.

---

*The questions below were written from the report's text, before the log arrived. Ask 1
above replaces their item 2; items 1 and 3 still read correctly on this build.*


The reporter can't capture logcat (no root), but they can send the app's own log: **Settings →
About & Diagnostics → the log row → Send full Hermes Vox log**. That file receives
`VoxLog.d/w/e/er` lines. It does not receive `VoxLog.dd` lines unless the debug-file option is
on (`VoxLog.kt:65-78`). Everything below is a `d`/`w`/`er` line, so it is in the file they can
send. Nothing here has been observed on a device.

## What to ask the reporter

> Please reproduce one "The entity is working…" episode — ideally the first turn after `/new`
> and the turn after it — with Presence off. Note the clock time when you finished speaking,
> and whether you saw a card that said "Still working — keep waiting or stop?". Then send the
> full Hermes Vox log from Settings → About & Diagnostics.

In that log, these three things decide between the surviving hypotheses:

### 1. The turn's admission

- **Patched build:** `event=run-admit attempt=N result=admitted|lost|failed ms=… [code=202 run=…] [err=…]`
- **0.9.1:** `event=turn-abandon gen=N route=runs reason=… run=<id or -> elapsedMs=…`

How to read them:
- `result=lost … err=SocketTimeoutException` (patched), or `turn-abandon … run=-` (0.9.1),
  means **H1**: the request never got an answer, so the gateway did nothing and the reporter's
  btop is right.
- `result=admitted … run=<id>`, or `turn-abandon … run=<id>`, means **H2**: the gateway took
  the turn and the run was silent. Give the owner the run id; it can be matched in the
  gateway's own log. btop is not evidence either way here, because a provider wait is idle on
  the gateway.
- No `startStream ->` line for the turn at all means the turn never reached submit. Neither
  hypothesis covers that; it would need a new one.

### 2. How the episode ended, and how long it took

- `event=wait-silent gen=N idleMs=… tools=a/b — asking`, then
  `event=stream-silent … reason=…` / `event=turn-abandon … reason=…`
- or `event=stream-turn-failed gen=N err=…`

How to read them:
- `wait-silent` at about 60 s followed by an abandon at about 70 s means the ladder's card ended
  it. That is the "60–90 s".
- `tools=a/b` with `a > b`, then `turn-abandon … reason=budget` at about 120 s, means a tool was
  counted as running, so the card was held off. That is the "two minutes".
- `err=hermes stream: connection lost (no data from the gateway)` means the reader's 60 s
  watchdog fired before the card. With `result=lost`, that is H1.
- If none of these appear while the screen said "working" for minutes, the turn was **not** on
  the ladder. Then the 0.9.1 DROP wedge (`event=settle-drop … reason=already-spoken`, no
  `tts-retire`) comes back into play, contrary to my revised estimate.

### 3. Whether the voice was alive during the wait

- `event=wait-line gen=N n=1 waitedMs=…`, then `event=er-glue source=wait …`, then
  `event=tts-speak engine=…` or `event=tts-speak engine=none reason=…`
- Any `event=tts-fail` / `event=tts-degrade` / `event=tts-unavailable` lines in the same call.

How to read them: with Presence off, the app speaks "Still waiting on it." at 15 s.
- `tts-speak engine=supertonic` with no complaint from the reporter means the line was spoken
  and either not heard or not noticed. Ask about DND and media volume. This is where
  "Silence notifications during a call" could matter, through the platform rather than the app.
- `tts-speak engine=none reason=…` or a `tts-fail` chain means the voice leg really was down.
  The `tts-*` sequence then tests link 1 (`FINDINGS.md` A): two `stream-synth` failures in one
  gen, the second with no SherpaTts cause line before it.

## Still undecided

- **H1 vs H2 for the silent turn.** Item 1 decides it.
- **The barge-in failure during those turns.** `event=barge-watch mode=single-capture …`
  (`VoiceController.kt:1355`, in the file) shows the drain was armed for the turn, and
  `event=barge-in … mode=generation` (`:1543`) shows a barge fired. `event=barge-stat` is in the
  file but covers *playback* reads only (`:1404`, `:1536`), so it says nothing about a turn with
  no reply. The generation-mode probes (`barge-gap`, `barge-skipcheck`) are `dd` and need the
  debug-file option on for one run.
- **The DND correlation from the first report.** Item 3, plus the phone's Do Not Disturb
  *Media* setting.
- **Link 1 on this device.** It needs a `tts-*` chain from a call where the voice was demoted
  (the first report's shape). The second report's episodes may simply not contain one.
- **What `/new` changes that a relaunch doesn't.** Both reset the conversation
  (`MainActivity.kt:699`, `:3438`); only process-local state differs. If item 1 shows
  `result=lost` only on turns after `/new`, or only after an idle gap, the HTTP connection pool
  becomes the leading suspect. The next step would then be a test with connection reuse disabled
  for admission, not a guess shipped as a fix.

## How to check the patches on a device

- **Admission witness:** every turn on the runs route writes one `event=run-admit … result=admitted`
  line. An unanswered admission writes `result=lost` after about 60 s, and its retry writes a
  second line.
- **Voice cascade fix:** an episode that would have demoted the voice shows
  `event=tts-stream-adopt`.
- **Wedge fix:** a turn that would have wedged shows `event=settle-retire … reason=engine-swapped`,
  followed by `event=tts-retire` for the same gen.
