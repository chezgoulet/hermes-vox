# Hermes Vox 0.9.0.2

**Released:** 2026-10-31
**versionCode:** 145
**Type:** patch release — two user-reported fixes. No action required from users.

## What changed

### A phone with no system TTS engine can start a call (#330)

Reported from GrapheneOS: selecting "System (fallback)" on a phone whose TextToSpeech
engine could not initialise left the call unable to start, and the UI reported
*"Voice models failed to load"* — a message that named the wrong component.

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

Built in CI by `release.yml` (run 41216325109) on `main`, because the release keystore
exists only as the `KEYSTORE_AGE_KEY` repo secret.

- `assembleRelease` + `testReleaseUnitTest` — green.
- Version stamp asserted before the build: `versionCode=145 versionName=0.9.0.2`.
- **Reproducibility:** two clean checkouts built separately produced **identical
  payloads** — `689d1d011861420b0c1a6dbd869f64bb4945f7b450567e6f0d6da264df617fc3`.
- `apksigner verify --print-certs`: `CN=Hermes Vox, OU=ChezGoulet, O=ChezGoulet,
  L=Windsor, ST=VT, C=US` (cert SHA-256 `8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19`) —
  the release key, not the debug key.

```
sha256:  689d1d011861420b0c1a6dbd869f64bb4945f7b450567e6f0d6da264df617fc3
certs:   CN=Hermes Vox, OU=ChezGoulet, O=ChezGoulet, L=Windsor, ST=VT, C=US
```

## Known issues

- #328's underlying cause is not proven without a log from the reporter.
- System TTS replies remain unavailable when no engine can start; the app now says so
  instead of failing the call.

## Upgrade notes

None. Install over 0.9.0.1; no data migration, no new permissions.
