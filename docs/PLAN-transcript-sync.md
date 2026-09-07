# SPEC — Speech-locked transcript (0.5.0-preview A)

## Symptom (Christopher, 2026-09-07)
"The text that appears as the agent is speaking disappears faster than the agent
can say it. I wish it was synced."

## Root mechanism (verified in code)
MainActivity.Listener.onDelta (`MainActivity.kt:620`): `replyBuf += text;
reply.setText(replyBuf)` — appends EVERY SSE text delta the moment it arrives. The
SSE stream delivers the whole sentence in ~1s; Piper renders it to audio over
8-15s. So the visible `CrawlView` (`R.id.reply_crawl`) races 3-4 sentences ahead
of the voice. `onReply` (`:643`) later clobbers it with the full final text. The
transcript is driven by *delta arrival*, not by *what has actually been spoken*.

## Fix — a speech-locked cursor
Reveal text at the pace audio is played, using the playback head of the persistent
AudioTrack, not delta arrival. Keep the full composition separate from the
live "speaking" reveal.

### L1 — VoiceController: account what the engine is playing
The streaming worker hands each phrase to `SherpaTts.streamChunk(text)`. Track, per
turn, an ordered list `chunkStats: MutableList<Pair<Int,Int>>` of `(textLen, samples)`
as each chunk is fed, plus `spokenText` (concatenation, == the full reply when done).
Expose:
  - `charAt(samplesPlayed: Int): Int` via a pure `SpeechCursor` object:
    walk the segments accumulating samples; chars spoken = sum of textLen of fully
    covered segments + linear interp within the partial one
    `(samplesPlayed - samplesBefore) / segSamples * segTextLen`. Monotonic.
  - `VoiceController.speechCursor(): Int` — ask `SherpaTts` for
    `getPlaybackHeadPosition()` (samples played) and run it through `SpeechCursor`.
  - one-shot path (`speak(finalText, gen)`) = a single segment `(finalText.length,
    samples.size)` — same cursor handles it with no special-casing.
Freeze: after any cancel (hush/barge/stop) the gate releases; subsequent calls
return the value captured at release — the unspoken tail is NOT revealed (you
never show words the entity was cut off before saying). A pure `frozenAt` guard.

### L2 — SherpaTts: expose samples played
Add `fun playedSamples(): Int` = `streamTrack?.playbackHeadPosition ?: streamWritten`
(safe when track null / not started). No new thread; reads the head on demand.

### L3 — MainActivity: reveal, dim the tail
Replace the raw `reply.setText(replyBuf)` in onDelta with a short-lived
`postOnAnimation` loop (or Handler every ~80ms) active ONLY while `state=="speaking"`:
  - `val spoken = controller.speechCursor()`
  - `reply.setText(spokenText, dimTail = true)` where chars `< spoken` are normal and
    chars `>= spoken` are dimmed (0.35 alpha) — so you SEE what's coming but the
    "spoken" boundary is unmistakable and moves with the voice.
  - Cancel state -> loop stops, cursor frozen, dimmed tail stays dimmed (or is
    collapsed) — never auto-completes.
  - `onReply` still sets the final full text on completion (unchanged KEEP).
  - Keep a small `minRevealMs` so a fast reply doesn't stutter-frame.

### L4 — Pure SpeechCursor + tests (JVM, no Android)
`SpeechCursor(charSegments, sampleSegments)` object in its own file:
  - `charsSpoken(samplesPlayed)` for (a) fully-covered, (b) partial-interp,
    (c) past-the-end clamp, (d) empty.
  - monotonic: increasing samplesPlayed never decreases charsSpoken.
  - freeze: `charsAt(samples)` where samples constant -> constant.

## KEEP-LIST (do not touch)
silenceAll, StreamFence, pause-first teardown, StreamRetirementState, focus
(C3), EscapeRule + BargeGate (0.4.0.2/3), ReplySettleRule (0.4.0.4), session_turns
counters, CrawlView setRole/role logic, avatar state setters. This is additive
to the *display* only.

## Gate
cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon exit 0. Build requires android/local.properties
(sdk.dir=/home/c/Android/Sdk) + libs copied from /home/c/hermes-vox/android/app/libs
(mobile.aar, sherpa-onnx-1.13.6.aar) if missing in the clone.

## Deliverable
One commit 'display: speech-locked transcript — reveal with the voice, dim the
unspoken tail' version 0.5.0-previewA (84). Print:
  DIAGNOSIS (confirm/refine the delta-vs-playback read, with file:line)
  FIX (cursor + reveal wiring, what you changed)
  TEST_TABLE (SpeechCursor rows)
  RISK (frame cost, system-TTS fallback, fast-reply stutter, device differences
  in playbackHeadPosition reliability + the time-based fallback you chose)
