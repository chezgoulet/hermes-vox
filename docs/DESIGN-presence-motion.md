# DESIGN — Presence & motion system (next after transcript-sync) — FOR YOUR EYES, not built yet

## Headline
You already have a generative particle-being (`AvatarView`: 320 light-bodies that
rearrange into SHAPES = iris/listening/vortex/scan/bracket/constellation/lumen/
waveform/bloom, seeded per-call so no two states look identical). The wish isn't
"add an avatar" — it's "make it move like it's *working,* not like a screensaver."
The design below turns real work-signals into motion. I'm putting the *vocabulary*
in front of you before I build, because "more interesting" is a taste call and I'd
rather get it ~right than polish the wrong thing.

## Principle: motion = state, and the state is already in the log
The log already carries everything the being needs. Map each to a distinct motion:
- `onState("listening")` → **breathing** (slow, low-amp field, receptive)
- `onState("thinking")` → **gathering** (particles draw inward, brighten, orbit —
  this is the PRESENCE win: a provider stall becomes visible concentration)
- `onState("speaking")` → **voice-coupled pulse** (the existing waveform/budge,
  but amplitude LOCKED to the real RMS level of the AudioTrack, so it genuinely
  dances with the voice — this is the "synced to the work" feel)
- `event=stream-stall` (the latency!) → **waiting constellation** (particles hold a
  pattern that slowly drifts, unmistakably "the entity is still here, still working"
  — converts dead air into presence)
- `event=barge-in` / hush → **recoil** (fast, respectful — it heard you)
- `event=tool call` (has `onTool` already) → **motif** (the being adopts the tool's
  geometry — spreadsheet → grid, weather → wisp, search → sweep)
- `event=tool result` (has `pulseTool` already) → **satisfied shimmer**
- `event=tts-retire` (reply done naturally) → **settle back to breathing**
- low-workload idle → gentle **drift** (never a frozen blob, never a busy blob)

## The build (once you bless the vocabulary) — a MotionState machine
One `MotionState` enum + a per-frame scheduler in AvatarView that reads the latest
signal (state, amplitude, tool, stallMs, workload) and picks the active shape +
its drive params. Pure-JVM `MotionState` table (like BargeGate/ReplySettleRule) so
transitions are unit-testable. New SHAPES added as generative parametric functions
(existing pattern). Default set of maybe 3-4 NEW shapes to start (not 20), proven
in-device, then expanded — the blast-radius control.

## What I want from you before building
1. The vocabulary above — does it match what you picture, or is anything off?
2. **Stall = "waiting constellation"** is the one I most want your read on: it's the
   single biggest presence lever (it makes provider latency feel like thinking),
   and it's the piece that turns a technical limit into character. Good bad?
3. Motion *feel* floor: fast/minimal vs rich/energetic — because the being is
   decorative-but-meaningful vs the centerpiece is the real fork (Enh. Realtime
   makes it the centerpiece, so I lean rich — but that's your inbox).

Once you say go on those three, it builds as 0.5.0 - B, gated + reviewed like every
B-iteration, and it pairs with ER as the same "presence" release.
