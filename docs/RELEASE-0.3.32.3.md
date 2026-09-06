# Hermes Vox 0.3.32.3 — release notes

## New capabilities
- **Worker-owned reply retirement**: the synth worker is now the ONLY party that
  retires a reply. After every sentence has been handed to the engine and played,
  the worker waits for the playback head of the last written chunk, then closes the
  stream and posts the settle (`event=tts-retire`, gate release, latency aggregate).
  The text/settle path on `done=true` no longer closes the track — it only marks the
  stream final under the lock and wakes the worker. Full replies play to the end
  again: the premature-close bug (long replies truncating when the playback head
  caught up to `streamWritten` during a synth gap for the next queued sentence) is
  gone. This lands on top of the #69 immediate-silence fence: the same worker-break
  checks (`genCancelled || sClosed` before each chunk) that made barge/hush abort
  cleanly now double as the abort side of the retirement rule, and a late barge that
  lands during the tail drain still wins via the post-`finishStreaming` `sClosed`
  re-check + the gate's duplicate-release guard.
- **Barge/hush/hangup unchanged**: cancel paths keep the immediate-fence behavior
  field-proven at 377ms silence; `msSinceBarge` and `event=tts-stop reason=barge-in`
  logging are untouched.
- **Start-stream provenance** (diagnosis aid): each stream turn logs
  `event=start-stream gen model provider` via `dd` (logcat full, file only in debug
  mode) so stall diagnosis shows which model/provider served a turn.

## Device-test checklist
1. Ask for a 3+ sentence answer -> every sentence audible; log shows one
   `event=tts-retire`, NO `event=tts-stop worker-break` before it.
2. Barge a long reply -> unchanged instant silence + `tts-stop reason=barge-in`.
3. Hang up mid-reply -> instant silence (unchanged).
4. `/models` switch deepseek<->mimo -> `event=start-stream` lines show the model change.
5. Export log via Settings → Debug → logs; metadata-only production rule holds
   (start-stream is `dd`).
