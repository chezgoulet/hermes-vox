# SPEC — C4: firstAudio truth + log rotation (0.4.0 polish, batch 5, final before release)

## Why
Two honesty defects in the observability we now steer by:
1. **firstAudio lie**: VoiceController pushes LatencyStats.pushFirstAudio on the
   first TEXT delta (field `firstAudioLatch` in the delta branch), not on first
   actual audio. Every latency number quoted from the field (5118/19348/29962ms)
   overstates-to-misstate the audio start. With Enhanced Realtime baselining next,
   the metric must mean what its name says.
2. **unbounded log**: VoxLog file appends forever. A public app's rolling log must
   cap size and rotate. (House rule: bound storage, don't bound user behavior.)

## K1 — firstAudio at real audio, firstText alongside
- VoiceController: rename current latch->firstTextLatch; push LatencyStats
  pushFirstText (new field, kept in event=turn as firstText=) there.
- NEW: push firstAudio exactly once when SherpaTts.streamChunk FIRST sub-write of
  a turn completes its first audible write (track.play() issued with data). Clean
  seam: SherpaTts callback or controller-side flag set on first successful
  streamChunk write for turnGen — Torc picks whichever avoids cross-class churn;
  document the seam in the diff.
- event=turn keeps field names (stt= firstByte= firstAudio= fullReply=) but
  firstAudio now = real audio; adds firstText=. Existing field numbers become
  incomparable across versions — note that in release notes.
- LatencyStats + test updated (firstText/firstAudio both recorded per turn).

## K2 — Log rotation (VoxLog)
- Cap active log file at 5MB; on exceed rotate current -> .1 (single generation,
  .1 overwrites old .1), keep writing to fresh file. Size check cheap (file
  length) on open + every ~50 writes, not per line.
- Export path ships BOTH files (or the merged pair) so field logs stay complete.
- No new permissions, no behavior change to the dd (debug-only) channel.
- Unit-testable seam: rotation decision as a pure function (bytesWritten/cap ->
  ROTATE|KEEP) + test rows.

## Rules
- Touch NOTHING in: silenceAll, fence, retirement, barge, focus (C3), endpointing.
- No version bump (release bump is the separate cut commit after this merges).
- Gate: /home/c/gradle-8.12.1/bin/gradle :app:testDebugUnitTest -q exit 0.
- Commit: 'polish: firstAudio measured at real audio + firstText added; VoxLog 5MB
  rotation (C4)'  (one commit)
- Print K1_SEAM <how firstAudio is hooked> + K2_ROTATION_SITE <file:line>.
