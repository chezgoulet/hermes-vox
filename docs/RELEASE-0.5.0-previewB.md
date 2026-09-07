# Hermes Vox 0.5.0-previewB — state-driven presence motion

## The fix
The being LOOPED; it did not report. Motion was chosen at the call site — `onState`
pushed a shape string plus a made-up level (`0.6f` for "speaking"), `onLog` pushed a
tool motif, and everything else fell through to the same dispersed idle aura. So a
15-second provider hang and a finished turn rendered *identically*: dead air was
indistinguishable from rest, and the "speaking" pulse ran on a constant rather than
on the voice.

Now the motion is a function of the work. A pure `MotionState` table decides **what
the being is doing**; `renderParams` turns that into the drive values; the existing
per-frame generative render reads them. No second animation loop.

## What it does
- **listening → breathing** — slow, low-amplitude, receptive.
- **thinking → gathering** — the field draws inward and orbits, tighter and brighter
  the harder the work is (`workload` ramps speed and brightness).
- **speaking → voice-coupled pulse** — amplitude locked to the **real** AudioTrack
  RMS, sampled in a ~20ms window *at the playback head*. Radius, speed and
  brightness all move with the syllable; at silence the ring is exactly its
  pre-previewB radius.
- **provider stall → waiting constellation** — a held, slowly drifting figure that
  widens with the age of the wait. This is the presence-with-latency win: a stalled
  provider becomes visible concentration instead of dead air.
- **barge / hush → recoil** — a fast outward flinch on its own clock: one gesture
  with an end, not a loop. One-shot, ~350ms, then back to what it interrupted.
- **tool call → the tool's motif**, **tool result → satisfied shimmer** — the
  existing `onTool` / `pulseTool` geometry, now reached through the table.
- **natural retire → settle**, **sustained rest → drift** — never frozen, never busy.

## The design "get"
Two precedence rules carry the whole thing, and they are why this is a table and not
a chain of ifs at the call site:

1. **STALL outranks the ambient signals.** `onState` keeps pushing "thinking" every
   poll while the provider is silent. Let those through and the being flips back to
   gathering ~33 times a second and the stall is never seen — the exact dead-air
   symptom. Only a resume, or an edge event, clears it.
2. **Edge events outrank the stall.** A tool call, a tool result, the mic reopening,
   a barge — these fire only when something actually happened, so they are their own
   proof the silence is over.

Two more that only show up on a device: the stall watch is deliberately **not** armed
while audio is audible (a long reply streams in ~1s and is then spoken for 10-15s
with no further SSE traffic — that is the being working, not a stall), and the
one-shot setters (`onTool`'s re-seed, `pulseTool`'s ramp) run on the signal **edge**
only. Called from the frame clock they would re-seed the generative shape 33 times a
second and saturate the workload, so the per-frame path is a separate `driveMotion`
refresh that touches drive values and nothing else.

The controller's existing `event=stream-stall` detection was log-only. It now also
reaches the display (`// stream-stall` / `// stream-resume`), so the authoritative
5s/15s signal and the local 2.5s presence threshold both drive the same lever.

## Blast radius
Additive to the avatar's motion only. `SpeechCursor`/`PlaybackClock` reveal loop,
`silenceAll`, the `StreamFence`, pause-first teardown, `StreamRetirementState`,
focus, `BargeGate`/`EscapeRule`, `ReplySettleRule`, `session_turns`, `CrawlView` role
logic and transcript composition are untouched. Every existing `AvatarView` setter
stays call-compatible — `RealtimeActivity` still drives it through `setState` /
`pulseTool` and renders exactly as before (the motion drive engages only when the
params describe the shape actually on screen).

## Device test
Ask for something with tool calls; watch the being gather inward, take the tool's
motif, and shimmer on each result. Let a slow provider hang: it should hold a
drifting constellation that widens, then snap back to work on the first token. Barge
mid-reply: one fast flinch, then back to listening. While it speaks, the ring should
move with the words — not on a metronome.

## Gate
`:app:testDebugUnitTest` — 137 tests, 0 failures (20 new `MotionStateTest` rows).
`:app:assembleDebug` clean. versionCode 85 / versionName 0.5.0-previewB.
