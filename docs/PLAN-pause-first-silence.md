# SPEC — Pause-first silence: stop must not wait for the write it is killing (0.3.32.4)

## Field evidence (2026-09-06 15:52 log, 0.3.32.3)
Full replies play to completion (event=tts-retire chunks=5; user confirmed).
Remaining defect: barge silence latency scales with the in-flight chunk —
msSinceBarge=2693 (turn 2), 5608 (turn 3). Cause verified in code:
SherpaTts.stopStreaming() line ~233 `while (writing) { trackLock.wait(50) }`
runs SYNCHRONOUSLY on main and waits for a WRITE_BLOCKING write of a whole
sentence (~up to 138k samples ≈ 6s audio) to finish draining. Silence waits
for the very audio it is trying to stop.

## Fix (F1) — pause immediately, teardown asynchronously
SherpaTts.stopStreaming():
1. fence.stop() (unchanged) — no new chunks may start.
2. `t.pause()` IMMEDIATELY, before waiting for `writing` (pause is safe concurrent
   with a write; it is stop/release that must never race the writer — #7 lesson).
   This is the user-audible silence. Return to caller right after — main thread
   unblocked in ms.
3. Hand teardown to a single @Volatile-guarded cleanup thread (one at a time; if
   one is already running for this track, no-op): t.stop() (unblocks the pending
   WRITE_BLOCKING write on-device), then wait for `writing==false` with a bounded
   loop (e.g. 2s poll), then t.flush(); t.release(). Log
   `event=tts-teardown ms=$waitedChunksReleased` when done. NEVER release while
   writing==true (SIGSEGV invariant preserved exactly as #7 documented).
4. streamWritten reset stays where safe (after release).
VoiceController: silenceAll unchanged (it already runs on main; it just no longer
blocks). finishStreaming's normal-completion path unchanged (RETIRE already drained).

## Fix (F2) — bound the write: chunk-slice streamChunk
streamChunk writes the whole sentence in one call. Slice `samples` into ~44100-sample
(2s) sub-writes, checking `streamFence.allowed` before each (so a stop between
sub-writes exits fast) and keeping the `writing=true` lock discipline. This bounds
any future drain-wait pathology to ~2s even on devices where pause-unblock differs.
No audio continuity change (one persistent track already).

## Fix (F3) — version 0.3.32.4 (77) + docs/RELEASE-0.3.32.4.md
Notes: barge/hush/hangup silence now immediate (pause-first; was up to ~6s,
proportional to the in-flight sentence). Device-test: 1) talk over a reply mid-long-
sentence -> silence within a few hundred ms; log shows event=tts-stop reason=barge-in
msSinceBarge small AND event=tts-teardown; 2) full replies still retire (no
regression of #71: event=tts-retire present, no worker-break on unbarge turns);
3) hang up mid-sentence -> instant silence, then tts-teardown.

## Verification (Torc after loop)
Diff review: pause() BEFORE fence wait; cleanup thread never releases with
writing==true; no double-cleanup thread; sub-write fence check present. Independent
test gate on final tip. Build + release chain as usual.
