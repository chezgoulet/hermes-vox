# 0.9.0.1 — the /new fix

A hotfix on top of 0.9.0. Version code **144**.

## What was broken

Pressing `/new` during an active call left the call **deaf**: after it, nothing you said was
detected or transcribed, and the only way back was to hang up and call again.

`newSession()` tore the voice layer down by hand — four of the six lines of `endCall()` — and
left out the two that mattered. `callLive` was never cleared and the call UI never exited, so
the audio pipeline was gone while the app still believed it was on a call. `startCall()` begins
with `if (callLive) return`, and the only place `callLive` is cleared was `endCall()` — which is
exactly why hanging up worked and nothing else did.

## What changed

`/new` now calls `endCall()` — one honest teardown — and, when a call was live, re-arms through
`startCall()` once the session chain has actually been reset. `startCall() → openVoiceLine()` is
the same composition a Bluetooth route change uses, so there is no new lifecycle and no
in-place restart of a stopped controller.

**Measured on a Pixel 7**, `/new` pressed mid-call: `event=call-end`, then
`event=session-reset callLive=false rearm=true`, a fresh pipeline init, foreground service, wake
lock, audio focus, audio route, and the microphone reopening on a new session in **1.9 seconds**.
The call button afterwards reads "Hang up call" and the timer keeps running: the call is still
up, not merely re-labelled.

## Artifact

- `hermes-vox-0.9.0.1.apk` — versionCode **144**, versionName **0.9.0.1**
- sha256 `52be6683e27a08f2eadf44fa78ac24b6ffc2d465a3b407577b4dc440935c791d`
- signed `CN=Hermes Vox, OU=ChezGoulet` (certificate SHA-256 `8be7feed…bbb19`)
- reproducibility: the PR's `repro` job built the same source twice from different paths and
  compared them; it passed.

## Install

Obtainium picks it up from this release. Nothing to configure, nothing to migrate.

## Not in this release

Everything else in flight — the streaming hearing model, the presence work, the models screen —
stays on `testing` for 0.9.1.
