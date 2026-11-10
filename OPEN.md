# #538 — open questions (revised 2026-11-10)

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
