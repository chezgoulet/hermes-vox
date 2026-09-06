# SPEC — C3: audio lifecycle hardening (0.4.0 polish, batch 4)

## Why (from PLAN-0.4.0-polish.md C3)
A phone app that owns the mic + speaker must behave when the real world interrupts
it. Today it doesn't: no AudioManager focus handling exists anywhere in the app
(grep-verified 2026-09-06: no requestAudioFocus/onAudioFocusChange/onAudioFocusLosses),
and no device-route callback (no AudioDeviceCallback). An incoming call mid-reply is
undefined behavior; a headset switch mid-call likely keeps audio on the dead route.

## H1 — Audio focus (MainActivity or VoiceController — pick ONE owner, document why)
- Request AUDIOFOCUS_GAIN with CONTENT_TYPE_SPEECH attribution at call start
  (where the wake-lock is acquired today); abandon at call end (same lifecycle).
- onAudioFocusChange:
  - LOSS / LOSS_TRANSIENT -> immediately silenceAll("focus-loss") (the ONE cancel
    path from #69 — do NOT hand-roll new stop logic) + stop listening (controller
    pause flag) + log event=focus-change state=loss.
  - LOSS_TRANSIENT_CAN_DUCK -> TTS duck is NOT available on our one-track writer;
    treat like LOSS_TRANSIENT (silence beats garble). Log it distinctly.
  - GAIN after a pause -> resume listening (NOT auto-resume of the old reply; it
    was interrupted; treat like fresh turn window). If focus was lost for >30s, do
    not auto-resume at all: end the call cleanly (hang-up path) so the user sees a
    real state instead of a zombie mic.
- Foreground service keeps its own wake lock; focus loss does NOT stop the fg service
  (the user may want to re-accept the call later — keep it one tap).
- Never leak: abandonFocus in the exact paths that release wake today.

## H2 — Device route changes (AudioDeviceCallback)
- Register on call start; unregister on stop.
- TYPE_BLUETOOTH_SCO / USB_HEADSET / WIRED_HEADSET appear OR disappear mid-call ->
  log event=audio-route devices=<types>, and: rebuild capture+playback like a fresh
  call (stop -> 250ms -> start) so both halves re-attach to the new default route.
  AudioRecord/AudioTrack are created per call already — the rebuild is start()/
  stop() composition, NOT new engine code.
- SCO connection on Android often needs startBluetoothSco — DO NOT add SCO audio
  routing (out of scope, rabbit hole). Rebuild-on-change is the behavior; if the
  platform keeps SCO as call route, fine, our VOICE_COMMUNICATION capture follows
  the platform.

## H3 — Session hygiene (measurement only, per plan)
- event=turn gains session_turns=N (count since resetConversation/new call), so
  gateway context creep is visible in field logs. NO truncation/summarization
  behavior yet — we measure before we act (Christopher's rule: no defensive limit
  without data).

## Hard rules
- Do not touch: silenceAll internals, StreamRetirementState, fence, barge-watch,
  EndpointRule. focus-loss USES silenceAll; it does not reinvent it.
- Grep after: requestAudioFocus, onAudioFocusChange, AudioDeviceCallback all >0.
- Gate: /home/c/gradle-8.12.1/bin/gradle :app:testDebugUnitTest -q exit 0.
- Commit: 'polish: audio focus + route-change handling + session_turns logging (C3)'
- Print C3_DONE <hash> + FOCUS_OWNER <file + why> + SESSION_TURNS_SITE <file:line>.

## Verification (Torc)
- Read diff; confirm LOSS path routes through silenceAll and logs; confirm abandon
  symmetry with wake-lock release; confirm session_turns increments where
  resetConversation lives; independent gate with v2 harness.
