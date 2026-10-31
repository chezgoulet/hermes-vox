# Hermes Vox 0.9.0.2

**Released:** 2026-10-31
**versionCode:** 145
**Type:** patch release — two user-reported fixes. No action required from users.

## What changed

### A phone with no system TTS engine can start a call (#330)

Reported from GrapheneOS: selecting "System (fallback)" on a phone whose
TextToSpeech engine could not initialise left the call unable to start, and the UI
reported *"Voice models failed to load"* — a message that named the wrong component.

Readiness now covers speech recognition and voice-activity detection only. Text to
speech is an output leg: when it fails, the call opens anyway, replies arrive as text,
and the status line names the actual cause (engine init code, or a missing `en-US`
voice). The log gains `event=tts-unavailable` and `event=tts-degraded`.

### The first utterance on a cold start is no longer dropped (#328)

The listening indicator used to appear as soon as the controller intended to listen,
before the microphone was capturing. On a cold start the first recognition attempt can
fail while the audio route is still coming up.

- The listening state is announced from `onReadyForSpeech`, with a 1.5 s fallback for
  devices that never deliver it.
- The first `ERROR_CLIENT`/`ERROR_AUDIO` in a call retries once after 400 ms
  (`event=stt-retry`), instead of going idle.

The cold-start diagnosis remains a hypothesis until the reporter supplies a debug log;
if it shows a different error, this is a mitigation rather than the cure and a follow-up
will say so.

## Verification

- `./gradlew :app:assembleDebug :app:testDebugUnitTest` — green on the release branch.
- CI on both source PRs (#332, #333) was green before merge into `testing`.
- `assembleRelease` + reproducible-build comparison: **not yet run** — see the release
  checklist. The APK hash and signature line below are filled in when the signed
  artifact exists.

```
sha256:  <to be recorded from the signed APK>
certs:   <apksigner verify --print-certs output>
```

## Known issues

- #328's underlying cause is not proven without a log from the reporter.
- System TTS replies remain unavailable when no engine can start; the app now says so
  instead of failing the call.

## Upgrade notes

None. Install over 0.9.0.1; no data migration, no new permissions.
