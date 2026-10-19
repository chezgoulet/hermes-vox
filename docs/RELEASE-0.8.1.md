# Hermes Vox 0.8.1 — "the headset release"

Version code **142** · `versionName` 0.8.1 · cut from `main` on 2026-10-19.

A point release: one behavioural fix, one correction to the front door, and two
pipeline faults found while releasing 0.8.0.

## Your headset becomes the call's route (#149)

A paired Bluetooth headset used to stay on the media route: Vox talked to the phone's own
microphone whatever was attached, while every other calling app on the device used the
headset. Android only moves a call onto a headset when the *communication* route names it —
a dialer gets that from the telecom stack, and a self-managed voice app has to ask for it.
Vox never did.

It does now, at every line open — and again if a headset is attached mid-call:

- **Android 12 and up:** `AudioManager.setCommunicationDevice()`, the current API. The
  older `startBluetoothSco()` mechanism was deprecated in Android 14 in favour of it.
- **Android 11 and older:** the legacy SCO link, which the platform confirms
  asynchronously (so the first turn there is the one to doubt).
- **Wired and USB headsets** are selected the same way.
- The selection is released when the call ends, and every decision is logged
  (`event=comm-route …`), so a report has something to read.

**Not yet verified on hardware.** There is no Bluetooth headset in the loop that wrote
this: the change follows Android's own self-managed call guide and is covered by unit
tests, not by a field test. `DEVICE_VERIFY.md` step 8 is the check, and its result —
either way — is the most useful thing to send back.

Two limits, stated rather than discovered:

- A headset **microphone** needs the default `VOICE_COMMUNICATION` capture source. With
  Settings → Mic → hardware AEC/NS switched **off**, capture is a raw phone mic and stays
  on the phone.
- A headset connected for media only (A2DP, no microphone) is **not** a route and is never
  selected. The candidate list is logged, so that case is visible rather than silent.

No new permission is involved: this is audio routing (`MODIFY_AUDIO_SETTINGS`, already
declared), not a Bluetooth API call — so there is no "Nearby devices" prompt to grant.

## The front page describes the release you have

`main`'s README still described the 0.7-era app: Piper as the voice, the retired
screenshots, no Enhanced Realtime, no Supertonic. The rewrite written for 0.8.0 landed on
`testing` and never reached `main`, so every visitor to the repository read the wrong
thing. It is here now, along with the roadmap, the device guide, the privacy and security
notes, and the licence notice, all brought up to 0.8.

## Provenance of the nightlies

Two faults found while releasing 0.8.0, fixed and proved by the nightly that followed:

- A nightly built from `testing` was **tagged on `main`** — `action-gh-release` targets the
  default branch unless told otherwise — so the tag's name and the commit it pointed at
  disagreed. Nightlies are now tagged at the commit they were built from.
- `testing` was left a **version code behind** the release, so a nightly could not be
  installed over it: Android refuses the downgrade. The bump happens on `testing` before
  the cut now, which is what the older releases did.

## Not in this release

- **G1** — the GPU initialising on a Tensor G4 — is still unrun, and still unrecordable
  without the hardware (`docs/RELEASE-0.8.0.md`).
- The real-phone checks opened with 0.8.0 — the soul's beat latency on a phone GPU,
  Parakeet's on-phone speed, echo handling after a reply — remain open in `ROADMAP.md`.
