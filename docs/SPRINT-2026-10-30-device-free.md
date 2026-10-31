# Sprint specification — the device-free half of 0.9.1

**Status:** filed 2026-10-30 · **not dispatched**
**Owner:** Christopher · **Executor:** `opencode2` sessions on sasquatch

---

## Why this sprint exists

The `0.9.1` gate is #279: a stranger, with no tutorial, completes a calendar task
end to end by voice on a phone. That gate cannot be met in CI, and neither can a
large part of the milestone behind it — #191 needs logcat from a failing device,
#202 says in its own text that it must not be reported from an emulator, #179 is a
live co-residency run, and #208 and #209 are per-release hardware measurements.

The Pixel 7 is not currently attached to the build host, so the whole release
would otherwise stall behind a cable. This sprint takes the subset of `0.9.1`
(plus two small `0.9.2` items that unblock it) whose proof is CI-shaped. When a
device is available again, what remains is field verification rather than
implementation.

**This sprint does not close `0.9.1`.** It removes the work that does not need a
phone so that the work that does becomes the only thing left.

---

## Scope — in, in this order

Each item becomes one `opencode2` session: one issue, one branch off `testing`,
one pull request into `testing`, **not merged**.

### 1. #240 — an interrupted reply can still play audio that was already in flight
`sev/medium`. The four-rule contract is written down in the issue and in
`docs/NOTES-voicemanager.md` §3: exactly one end event per request id; no audio
after it; a cancelled id permanently dead for the session; and client-side,
discard in-flight audio for that id while stopping playback the instant the
cancel is issued.

**Why first:** #228 (continuous capture) needs the same lifecycle, so this is
substrate rather than a standalone fix.

**Proof without a device:** unit tests over a fake transport that injects a late
frame carrying a cancelled id. Acceptance is stated in the issue and is
observable in a harness.

### 2. #223 — the transcript layout is empty in Presence
`sev/medium`. `appendConvo()` is only called on the plain path, so the user who
chose the readable transcript — the accessibility reason the option exists — gets
an empty pane.

**Proof without a device:** feed the Presence path into the transcript and assert
turn text and asides land in order.

### 3. #257 — Presence: a minimal directive, and a guard that repairs format
The measured refusals say 10 of 25 are `too-many-words`: the code is policing the
model's formatting while claiming to police its honesty. Split it — content is
policed, format is repaired.

**Constraint:** do not change any bound. #256 measures what the model actually
invents; this issue repairs shape and needs no rates to be safe.

**Proof without a device:** each repair path (over-length cut at a clause, markup
stripped, trailing courtesy dropped, supplied number spoken) has a test showing
the line is **spoken** rather than dropped; the impossible-result refusal keeps
its test; a test asserts the five-rule directive no longer carries the old lexical
prohibitions.

### 4. #224 — Settings: split Visuals into Being, Hearing, Voice and Presence
Pure reorganisation of an existing screen, with a stated grouping and the rule
that every row gets a one-line statement of what it changes.

**Why before #222:** the split lands in `0.9.1`; the selector retires in `0.9.2`.
Doing them in that order avoids touching the same screen twice.

### 5. #222 — Presence is a layer, not a mode: retire the selector
`0.9.2`-labelled, pulled forward only because #224 makes it a small follow-on.

**The part that matters is the migration:** stored `realtime` → off, `enhanced` →
on. Nobody's setting may be silently reversed.

**Decision needed before merge:** whether the code lands now with the selector
still visible, or waits for the `0.9.2` cut. Landing it early and leaving the flag
default-on would reverse the very choice the migration exists to protect.

### 6. #235 — the partial-STT worker re-decodes snapshots from scratch
Cost grows with the square of the utterance. The streaming arm already offers the
correct shape; the capture loop does not call it.

**Acceptance as written:** decoded audio-seconds per utterance linear in length,
proven by instrumenting a two-minute turn.

**Risk:** this may not be provable without a device if the instrumented turn needs
real audio. **If a fake audio source cannot prove it, stop and record the item as
device-gated rather than claiming the acceptance.** This is the item most likely
to convert.

### 7. #252 — `/new` during an active session breaks speech recognition
Diagnose first. The reporter's hypothesis is that the VAD/ASR loop is not re-armed
on session reset. That is testable in a harness if the controller can be driven
without audio.

**Constraint:** the capture loop is also what #228 changes, which is **not** in
this sprint. If the fix requires restructuring the loop, stop and file what is
needed instead of half-preparing a branch that will conflict.

---

## Out of scope — device-gated, do not attempt

| Issue | Why not now |
|---|---|
| #191 | Needs logcat from the failing attempt; the issue says not to close it on a successful start alone |
| #179 | A live co-residency run on the Pixel 7 — the gate for adopting the streaming arm |
| #202 | The issue says explicitly not to report it from an emulator |
| #208 | A two-device behaviour matrix, recorded per release |
| #209 | A performance baseline on the release APK, two devices |
| #279 | The `0.9.1` acceptance run itself |
| #330 | System TTS fallback never loads — needs a device to reproduce |
| #328 | Cold-start double-attempt — needs a device |
| #251, #275, #280–#285 | UX and judgement work: states, operations, approvals, discovery, light-mode art |
| #165 | F-Droid path B is an `fdroiddata` recipe and a buildserver run, not an app-code session |

---

## Definition of done, every item

- Branch `fix/<issue>-<slug>` cut from `testing`; PR **into** `testing`, never
  `main`, per `docs/GIT-FLOW.md`.
- Unit tests added or updated for the behaviour, not just for the build.
- `./gradlew --no-daemon assembleDebug testDebugUnitTest` green.
- The docs self-tests green — they assert the app-skill and presence-report claims
  still hold.
- `bash scripts/check-version-code.sh` green.
- PR body states **what was proven locally** and **what is device-deferred**.
- **No invented output.** A test that could not run is named as not run.
- Not merged, not pushed to `testing` or `main`, issue not closed.

---

## Risks, stated plainly

1. **#235 probably converts to device-gated.** If so, this sprint delivers six of
   seven and the seventh waits for hardware — that is the correct outcome, not a
   failure.
2. **#252's root cause is unknown** and may live in code #228 is about to rewrite.
   A speculative fix there is worse than a filed diagnosis.
3. **#222 and #257 are `0.9.2` items.** Pulling them forward is cheap now and
   risks muddying the `0.9.1` cut. Land the code, keep the user-visible change
   behind the release boundary, and decide the merge timing deliberately.
4. **Nothing here proves anything about Presence on a phone.** The render budget
   (#249), memory behaviour (#191) and turn-taking in a real room (F3b) are all
   untouched by this sprint.

---

## What this sprint does not do

It does not close `0.9.1`. It does not touch the presence render budget, the
memory question behind #191, or real-room turn-taking. It does not produce a
nightly that anyone should judge the product by — a green CI run is not a good
call.
