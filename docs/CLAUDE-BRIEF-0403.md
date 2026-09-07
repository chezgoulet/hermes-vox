# BRIEF — 0.4.0.3: make barge-in cut through loud TTS reliably. YOU are the diagnostician.

## Your mission
The user talks over the app's own speech during long replies; roughly half the time
nothing fires. A previous engineer has theories (listed at the bottom as HINTS ONLY —
treat them as hypotheses to test or destroy, not instructions). You own the diagnosis.
Repo: Hermes Vox Android app at THIS directory, at main (0.4.0.2).

## Evidence (read these first)
- docs/evidence-0403-failure.log — the 0.4.0.1 field session AFTER the escape shipped.
  Read the barge-watch / barge-nearmiss / barge-in / realtime lines with timestamps.
  User complaints: turn ~21:18:28 "spoke over most of what you just said and it didn't
  work" (turn 3); ~21:21 "tried to interrupt and it did not work" (turn 10).
- docs/PLAN-0402-level-escape.md — what the escape was supposed to do.
- The code: BargeGate.kt (pure decide fn), VoiceController.kt drain loop (search
  '0.4.0.2' and 'barge-nearmiss'), the probes from 0.4.0.1 (gap/nearmiss/skipcheck).

## What the data shows (verify, don't trust)
- Seven barges DID fire (2-12ms) — the VAD path works when VAD agrees.
- Failed attempts log barge-nearmiss with peakRms up to 0.228 and vad=false,
  including TWO frames above the escape bar (0.16) during turn 10 (0.172, 0.182)
  — yet no fire. sustainedMs in nearmiss lines is the VAD sustain, NOT the
  escape's own sustainedLevelMs (blind spot we know about).
- Mic reads were current (no barge-gap lines) -> starvation is dead.

## Your deliverable (0.4.0.3)
1. ROOT-CAUSE the turn-10 non-fire with evidence from the log + code. If the strict
   contiguous accumulator is the cause, say so; if something else (frame size math,
   clock discipline, grace window overlap, floor computation with the *1.6 both in
   caller bar AND inside decide?), prove it from code lines.
2. Fix it. Design constraints:
   - barge-in must cut reliably when a person talks over loud TTS at conversational
     volume, WITHOUT resurrecting the self-cut era (app's own echo at 0.15-0.17 must
     not fire on its own; the era's evidence: rms 0.153-0.172 clusters firing while
     nobody spoke).
   - Probes must see the escape's accumulator too (report levelSustain= in nearmiss).
   - Everything else is KEEP-LIST: silenceAll, fence, StreamRetirementState, focus,
     route, endpointing, retirement, probes semantics, Settings slider (keep working,
     retune defaults if you must — document).
3. Tests: BargeGateTest gains rows simulating the REAL failure shape (frame series
   with brief dips around the bar at 0.17-level, vad=false — must fire within ~400ms;
   pure echo-shape series at 0.15-0.17 amplitude-modulated — must NOT fire).
4. Gate: cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
   :app:testDebugUnitTest -q --no-daemon MUST exit 0. NEVER weaken/delete an existing
   assertion (except genuine spec reversals you list and justify).
5. One commit: 'barge: 0.4.0.3 — <your fix in a phrase>'. VersionCode 82 /
   versionName 0.4.0.3 + docs/RELEASE-0.4.0.3.md (honest risk section as usual).
6. Print, at the very end:
   CLAUDE_DONE <hash>
   DIAGNOSIS <2-4 sentences, root cause with file:line evidence>
   FIX <what you changed and why it beats the alternative(s) you rejected>
   REJECTED <alternative approaches you considered and why dropped>
   TESTS_ADDED <rows>
   RISK <what could regress, with your mitigation>

## HINTS ONLY (previous engineer's beliefs — destroy or confirm with evidence)
- Strict contiguous sustain (reset-to-0 on ANY sub-bar read at 64ms granularity)
  probably can't accumulate through real speech; a leaky/decay accumulator (lose
  ~100ms per sub-bar read, 50ms grace) probably fixes it.
- Default 400ms might want to be 300ms.
- You may find the truth is entirely different. The floor*1.6 double-application
  (caller bar vs decide's bar) is a plausible bug nobody has checked. Start there
  if you like — but only believe what the code says.
