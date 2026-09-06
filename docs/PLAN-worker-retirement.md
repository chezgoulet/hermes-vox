# SPEC — Worker-owner reply retirement + start-stream provenance (0.3.32.3)

## Field evidence (2026-09-06 12:44 log)
- Barge silence fix PROVEN: event=tts-stop reason=barge-in msSinceBarge=377 (was up to 4000).
- NEW BUG: long replies truncate. Turn 1 (288 ch reply): worker-break chunkIdx=2 at
  12:44:59.497, gate stream-done at 59.782 — only ~4.4s of audio played, rest of the
  reply never synthesized. Turn 2 (984 ch): chunkIdx=3, same shape. User: "your full
  message did not get spoken aloud."
- Mechanism (VoiceController.kt): the settle path calls finishStreaming→stopStreaming
  when the playback HEAD catches up to streamWritten — during a synth gap for the
  NEXT queued sentence (piper generate takes ~0.5-1s, head drains meanwhile).
  stopStreaming sets sClosed=true + clears sQueue; the worker (still holding the
  rest of the reply) breaks on `genCancelled || sClosed` (:713/:720). Two owners of
  "done": settle path AND synth worker. The fence added for barge silence is what
  makes the worker obediently abort — correct behavior against a premature close.
- Also: turn 2 firstAudio=19348ms with no record of which model/provider served the
  turn (startStream logs only the stream id).

## Commits (gate per commit: :app:testDebugUnitTest exit 0; never weaken tests)

### R1 — Single-owner retirement: worker retires, settle waits on the worker
1. VoiceController stream worker (the loop around :713-739): after ALL sentences have
   been handed to the engine and played, the worker itself is the ONLY party that
   closes the stream. Concretely:
   - Text side: when done=true arrives with ok completion, mark a new
     `@Volatile var sFinal = true` (queue no longer receives) INSTEAD of anything that
     closes the track. sLock.notifyAll() so a worker blocked waiting for the next
     chunk proceeds.
   - Worker loop: exit condition becomes (sClosed || (sFinal && queue empty)). On the
     NATURAL exit path (sFinal && drained), the worker itself calls
     finishStreaming() (waits for playback head of the LAST written chunk) and THEN
     stopStreaming() and posts the settle: listener onState + releaseTurnGate(gen,
     "stream-done") + LatencyStats fullReply, exactly where the current settle posts.
     Log `event=tts-retire gen=$turnGen chunks=$chunkIdx ms=$wallSinceStart`.
   - The text/settle path on done=true must NO LONGER call finishStreaming/
     stopStreaming/release directly. It only sets sFinal + notifies. (Gate-timeout
     stays as the backstop.)
   - Cancel paths (bargeIn/hush/stop) unchanged: they set sClosed and keep the
     immediate fence behavior that the field log just validated. msSinceBarge stays.
2. Race rules: sFinal/sClosed/sQueue mutated only under sLock (match existing style);
   worker checks genCancelled||sClosed before EACH streamChunk (existing); after
   finishStreaming on natural exit, re-check sClosed before releasing the gate so a
   late barge still wins (releaseTurnGate duplicate-guard already logs this).
3. Unit: pure StreamRetirementState object (sFinal, sClosed, queueEmpty ->
   decide: RETIRE / WAIT / ABORT) with truth-table tests; wire into the worker's exit
   conditions so the rule is testable off-device (same pattern as StreamFence/BargeGate).

### R2 — start-stream provenance log
- In startStream/runStreamedTurn start: `VoxLog.dd("event=start-stream gen=$gen
  model=$modelPref provider=$providerPref")` — dd (logcat full, file only debug
  mode) to respect metadata-only production rule (model ids are config, not user
  content; prefs values, lengths not needed).
- Also log the retirement timing aggregate on settle: event=turn gains
  ` played=${audioPlayedMs}` if cheaply available (skip if invasive — optional).

### R3 — version 0.3.32.3 (76) + docs/RELEASE-0.3.32.3.md
Notes: full replies play to the end again (worker owns retirement; settle waits on
it); barge/hush/hangup silence unchanged (377ms field-proven); start-stream model
logging for stall diagnosis. Device-test checklist:
1. Ask for a 3+ sentence answer -> every sentence audible; log shows one
   event=tts-retire, NO event=tts-stop worker-break before it.
2. Barge a long reply -> unchanged instant silence + tts-stop reason=barge-in.
3. Hang up mid-reply -> instant silence (unchanged).
4. /models switch deepseek<->mimo -> event=start-stream lines show the model change.
5. Export log; metadata-only production rule holds (start-stream is dd).

## Verification (Torc after loop)
- Diff review: sFinal set under sLock; no direct stopStreaming from text-settle path;
  natural-exit finishStreaming inside worker thread (NOT main); duplicate-release
  behavior on late barge during finishStreaming (gate race) preserved.
- Independent test gate on final tip; grep-counts for StreamRetirement + retire log;
  build 0.3.32.3 + full artifact verification + GitHub release.
