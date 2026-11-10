# #538 — open questions

These can't be settled without the reporter's `vox-diag` block and a logcat (or the app's own
log file) covering one episode, from the last good spoken reply through to "spoken replies are
off". Nothing below was observed on a device.

## What to ask for

The current 0.9.1 build already writes the lines below. Ask for the log around one episode,
and for these lines in particular:

1. **Every `event=tts-` line from the first `tts-fail` to `tts-unavailable`**, together with
   the `turn done: gen=` lines between them.
   - It discriminates **A, link 1 (the re-warm cascade)** from everything else. The cascade
     shows as two `code=stream-synth` failures for the **same gen**, and the second has *no*
     SherpaTts cause line before it (`reason=silent-stream`, `streamChunk:`, `buildStreamTrack:`).
   - The **first** `tts-fail` and the line just before it identify the first trigger: a track
     failure (`buildStreamTrack: …`), a silent synth (`reason=silent-stream`), an exception
     (`streamChunk: …`), or a failed re-warm (`event=tts-degrade … reason=rewarm-failed`, which
     points to memory or model load rather than the cascade).
   - The `engine=system` failure just before `tts-unavailable` names the off step: `timeout`
     (the watchdog, `WarmTts.kt:160`), `error-N`, `speak=-1`, or `silent`.

2. **`event=settle-drop gen=<n> reason=already-spoken`, and whether `event=tts-retire gen=<n>`
   follows it.**
   - It discriminates **C(v), the DROP wedge**, from C(ii)/(iv), a run that is genuinely slow
     or parked. A `settle-drop … already-spoken` with no hush or barge before it, and no
     `tts-retire` for that gen, means the screen's "working" was a wedged worker. The gateway
     had already said `turn done`.
   - If the "working" episode instead shows `event=stream-stall` / `event=wait-silent` /
     `event=stream-silent`, it was a real silent run. The patch does not address that, and the
     gateway side needs looking at.
   - `event=gate-timeout … sRunning=true turnInFlight=false` during the same window
     discriminates **B** (STT deaf because of the wedge) from a capture-side fault.

3. **The `event=call-quiet on|off` lines and their timestamps relative to the first
   `tts-fail`**, plus the phone's DND mode settings (whether *Media* is allowed during Do Not
   Disturb).
   - It discriminates **a causal DND link** from confounding. If the first `tts-fail` regularly
     follows `call-quiet on` within the call and *Media* is blocked, there is a platform path
     worth chasing. If episodes start long after `call-quiet on`, or also happen with the
     setting off, it is confounding.

## Undecided

- **The first trigger of the cascade.** Candidates are track failure, silent synth, and
  exception (`FINDINGS.md` A). The patch makes a track failure free and stops the cascade
  whatever the trigger, but a genuinely recurring silent synth would still re-warm once per
  occurrence.
- **The system voice → off step.** My leading candidate is the system engine's per-utterance
  watchdog (`text.length * 90 ms + 8 s`), which a long, number-heavy reply spoken as one
  utterance can exceed. It is unverified. If the log shows `code=timeout`, that watchdog needs
  its own issue.
- **Whether DND mutes the reply** on Android 17 / GrapheneOS when the app's interruption filter
  is applied, and whether that mode allows media. It needs a device with the setting on and a
  spoken reply. A silent-but-"spoken" reply would show normal `tts-speak` lines and
  `played N samples` with nothing audible.
- **Supertonic re-warm time on a Pixel 8 Pro.** The cascade needs the re-warm to finish while
  the reply is still streaming. The `SherpaTts loaded: supertonic … warmMs=` line, compared
  with the `tts-recover` timestamp, gives it.

## How to check the patch on a device

With the patched build, any episode that would have demoted the voice should show
`event=tts-stream-adopt`. Any that would have wedged should show
`event=settle-retire … reason=engine-swapped`, followed by `event=tts-retire` for the same gen
(see `FINDINGS.md` → Witness). If the reporter still reaches "spoken replies are off" on the
patched build, the log will now show a *real* engine fault at each step, and that fault is the
next thing to fix.
