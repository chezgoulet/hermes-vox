# BRIEF — 0.4.0.4: the ghost re-speak after hush. YOU are the diagnostician.

## Symptom (user, 2026-09-07 00:16 local): "that doesn't work very well at all"
Evidence log: the 0.4.0.3 field log (removed in the log sweep). Two calls.

Call 1 (turn gen=1): deepseek stalled ~19.6s, then 5 streamed piper chunks played
(~13s of speech). User hushed at 22:17:31.460. ONE MILLISECOND later "turn done"
(199 chars, done=true). Then 22:17:32.862: "piper generated 225146 samples (199
chars)" — the ENTIRE reply re-synthesized as one monolithic non-streamed speak()
block — and after call-end (22:17:39) it still "piper played ... waited 200ms" at
22:17:43 — GHOST VOICE speaking through the destroyed foreground service, minutes
after the user tried to silence it.

## Torc's preliminary read (HINT — confirm/destroy with code)
settleReply (VoiceController ~:810): on done, branch `if (streamed && supportsStreaming)`
— but the hush ALREADY ran stopStreaming(), which sets streamed=false. So the
completed turn falls to `else if (shouldSpeak()) -> speak(finalText, gen)` = re-speak
the whole reply that was already (partly) spoken and just cancelled. Plus: the
non-streaming speak()->play() path builds a bare AudioTrack with NO fence integration
(SherpaTts.play() at ~:114), and stop() only fences the STREAM track — so it is
uncancellable, unbargeable, and outlives teardown. Call 2 shows the model side
(30s stall, no reply, clean stop) — that one is fine.

## Your deliverable (0.4.0.4)
1. Confirm the ghost re-speak mechanism from code line-by-line (and find anything
   else that path hides — e.g. does the same race exist for reply-error settles?).
2. Fix with the smallest correct rule. Direction (verify, don't trust): a settle
   must NEVER (re)speak a reply that already emitted audible audio for its gen —
   the per-turn firstAudioPushed latch is a ready-made witness; and if the gate was
   already released for that gen (cancelled), settling must not start ANY speech.
   Keep text-only replies (never streamed, nothing spoken) still speaking normally.
3. Make the non-streamed speak()/play() path fence-aware: stop()/hush/call-end must
   silence it within the same msSinceBarge budget as streaming. Reuse the existing
   trackLock+StreamFence discipline; do not invent a parallel one. If play() writes
   are long, apply the same sub-write slicing pattern.
4. Tests: a pure decision where possible (e.g. 'shouldReSpeak(genSpokeAudio,
   gateReleased, streamed, hasText)' table) + rows: hush-then-done MUST NOT
   re-speak; clean text-only turn MUST speak; done-while-streaming MUST retire
   (no regression of #71). Existing 19 BargeGate rows untouched.
5. KEEP-LIST: silenceAll/fence/retirement/focus/route/probes/endpointing internals.
6. Gate: cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
   :app:testDebugUnitTest -q --no-daemon exit 0. Create android/local.properties
   (sdk.dir=/home/c/Android/Sdk) and copy libs from /home/c/hermes-vox if missing.
7. versionCode 83 versionName 0.4.0.4 + docs/RELEASE-0.4.0.4.md (honest risk section).
8. One commit 'barge/settle: 0.4.0.4 — no ghost re-speak after cancel; cancellable
   speak path'. Print CLAUDE_DONE <hash> + DIAGNOSIS + FIX + REJECTED + RISK.
