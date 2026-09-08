# Hermes Vox 0.6.5 — the silent-reply fix

From Christopher's field test (both Realtime and ER): fillers repeated the
same few phrases, then the reply rendered as text but was **silent**.

## Root cause — the fillers racing the reply's audio

Two bugs, one class:

1. **Track replacement.** A glue one-shot's `play()` REPLACES `streamTrack`
   and resets `streamWritten` mid-reply. The streaming worker kept writing
   the reply's remaining chunks into the orphaned track object; the playback
   head was lost; the answer never played. Fix: `speakGlue` now REJECTS
   non-critical glue while a streamed reply is live, and `speak()` (the
   reply's one-shot) closes the stream first if one is open.

2. **The fence war.** A glue's `stopTts()` closes the fence — which is also
   the reply's `streamChunk` gate — and a glue's `play()` re-opens it.
   Open/close fought between glue and reply, silently dropping reply chunks.
   Fix: when a glue ends and a streamed reply is open, the fence re-opens
   (`er-fence-reopen`).

3. **Filler repetition.** The lag line repeated every ~3s: the trailing 3s
   density window let `recentCount` fall back to 0, re-arming fail-soft. The
   lag line is now keyed to a monotonic per-window count — once per
   mind-work window.

## Also

- **Logging**: ER diagnostics (`er:intent`, `er-barge-*`, `er-arbiter`) now
  ALWAYS reach the exported log (`VoxLog.er`) — the "nothing in the logs"
  report was these lines hiding behind the debug-file toggle.
- **Restore defaults**: the ER keys join the Entity restore scope (and the
  switches rebind); `speak_responses` joins the TTS restore scope.

## Verification

Thelio gate on a clean clone. versionCode 109 / 0.6.5.
