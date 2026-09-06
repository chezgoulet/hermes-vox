# Hermes Vox 0.3.32.4 — release notes

## New capabilities
- **Pause-first silence**: barge/hush/hangup silence is now immediate. `SherpaTts.stopStreaming()`
  closes the fence and calls `pause()` on the caller's thread and returns in milliseconds —
  it no longer waits SYNCHRONOUSLY for the in-flight `WRITE_BLOCKING` write to drain before
  going silent. That old wait scaled with the sentence being cut (up to ~6s, proportional to
  the in-flight chunk); the field defect (`msSinceBarge` 2693 / 5608 in the 0.3.32.3 log) is
  gone. The #7 discipline is preserved exactly: `pause()` is safe concurrent with a write, but
  `stop()/flush()/release()` never run on the writer — teardown now happens on a single
  `@Volatile`-guarded cleanup thread that stops the track (which unblocks a pending write
  on-device), waits a bounded 2s poll for `writing==false`, and only then flushes + releases
  (`event=tts-teardown ms=…`). Never released while `writing==true` (no SIGSEGV).
- **Bounded writes**: `streamChunk` slices each sentence into ~2s sub-writes (44100 samples
  @22050Hz, `sr*2` at any rate) with a fence check before every slice, so a stop that lands
  mid-chunk exits between sub-writes instead of draining a whole-sentence write. This bounds
  any future drain-wait pathology to ~2s even on devices where pause-unblock behaves
  differently. No audio continuity change (one persistent track already).
- **Full replies unchanged**: the #71 single-owner retirement path is untouched — full replies
  still play to completion and retire via `event=tts-retire`; no worker-break on unbarge turns.

## Device-test checklist
1. Talk over a reply mid-long-sentence -> silence within a few hundred ms; log shows
   `event=tts-stop reason=barge-in` with a small `msSinceBarge` AND an `event=tts-teardown`.
2. Full replies still retire (no regression of #71): `event=tts-retire` present, no
   `worker-break` on unbarge turns.
3. Hang up mid-sentence -> instant silence, then `event=tts-teardown`.
4. Export log via Settings → Debug → logs; the teardown lines confirm the cleanup thread ran
   after the audible cut (never before it).
