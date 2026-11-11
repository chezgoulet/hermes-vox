# Hermes Vox 0.9.1.3 — the cleanup cut

A repair release. Nothing here is a new feature except the screen that tells you what the call is
waiting for, and that exists because the repairs made the waiting visible.

## The short reply that held the call for two minutes

`#517` raised the streaming playback buffer from a quarter-second to four seconds so a long reply
could stream without a gap at every chunk boundary. A streamed `AudioTrack` does not start until its
start threshold is reached, and the platform default is the whole buffer — so **any reply shorter
than four seconds never started playing**, and the drain waited out its 120-second ceiling. The
gateway had answered in about two seconds and sat idle; the screen said "The entity is working",
the microphone took no turn, and barge-in had nothing to hear. `/new` appeared to fix it because
the next reply was usually longer.

The evidence is a field log: in its 0.9.1 block, **40 replies carried under four seconds of audio
and 22 of them ran to 126.8–127.1 s**, the turn budget expiring. Of the 83 replies of four seconds
or more, 9 ran that long.

Two changes: on Android 12+ the track starts after **a quarter-second** whatever its capacity (the
capacity stays four seconds — it is the synthesis worker's headroom), and every drain is bounded by
the audio actually owed, with a play head that has not moved in 400 ms played out instead of waited
on. `event=tts-drain result=played|forced|timeout|not-playing` names the outcome of every reply.

## The call says what it is waiting for

The call no longer opens until the mind, the ears and the voice are observed ready — and Presence
too, when it is on. The screen is the being assembling: particles gather as each part reports, one
short line names the leg still coming (**Connecting to gateway**, **Loading speech recognition**,
**Loading voice models**, **Loading local model**), and the call goes green with **Connection
established**.

Measured: green in **2.0 s** with three legs on a Pixel 7, and **6.1 s** with all four on a Pixel 9
with Presence on. If a leg fails, the app's existing refusal says which. After twenty seconds the
screen names what is still coming and offers **Talk anyway** — never for the ears or the voice,
which a call cannot do without.

## The rest of the cleanup

**Piper is the recommended voice.** On the same device and the same call, Supertonic dropped words
repeatedly while Piper was consistent; Supertonic is marked experimental until the cause is known
(#559), and the recommendation says so in one honest line. **The screen names the connection once**
— Hermes-Agent for the built-in default, the profile's own label otherwise, and the pill never
repeats it. **A saved profile can be removed** (long-press → Remove), and the profile the call is
using is refused so a live session never points at an address the app has forgotten. **Settings no
longer shrinks the call into a small window**, and **mood markers are never spoken aloud** — they
reached the synthesiser before this.

The profile list is now asked on **streamed** chat completions, which is the route that registers
the approval notifier: a tool call made while answering it can no longer happen invisibly (#533).
The body cap moved to 1 MB because a reasoning model streamed 158 KB for a 53-character answer.

## What was measured, and where

- Cold start to first frame: **183 ms** (Pixel 7).
- A five-minute call with Presence on: peak RSS **4.3 GB**, lowest `MemAvailable` **2.1 GB**,
  thermal state `none` throughout (Pixel 9).
- Every reply in the device pass ended `tts-drain result=played`; the interrupt landed with
  `reveal-freeze chars=187 of=279` and `cancelToSilenceMs=6`.
- Twelve regression paths are now written down and re-run per cut (`docs/REGRESSION.md`), with
  `scripts/regression-smoke.sh` doing the half that adb can decide.

## Known, and not fixed here

**The recogniser runs slower than real time on the field device** — RTF 1.0–1.8 against the 0.68–0.76
the same arm measures on a Pixel 7 in isolation, with `worker-busy` fallbacks. That is why turns feel
uneven; it is being measured (#569) and it is not a regression in this release.

Supertonic's dropped words (#559). The approval route (`/v1/runs`) and continuous listening are in
their own release, not this one.
