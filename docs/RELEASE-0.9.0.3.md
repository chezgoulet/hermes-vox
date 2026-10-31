# Hermes Vox 0.9.0.3

**Released:** 2026-10-31
**versionCode:** 146
**Type:** patch release — one defect, reported from a phone. No action required from users.

## What changed

### System text-to-speech could never bind (#346, reported via #330)

After 0.9.0.2 the call opened correctly on GrapheneOS but stayed silent, with
`tts: system engine init failed code=-1` in the log. `-1` is `TextToSpeech.ERROR`, and it
reproduced on three different engines — the signature of a visibility failure, not an
engine failure.

The cause: the app declares no `<queries>` element for
`android.intent.action.TTS_SERVICE`, and it targets SDK 34. Since Android 11, package
visibility hides another package's services unless the app asks for them. The engine was
installed and other apps could speak; Vox could not see it.

The manifest now declares the query. When initialisation still fails, the log names the
engines that are visible — `none-visible` is the package-visibility signature,
`unavailable` is an unreadable list — so this class of failure can never again be
mistaken for "no engine installed".

## Verification

- `assembleDebug testDebugUnitTest` green on the release branch; three new unit tests for
  the engine-visibility description.
- CI green on the source PR (#347) before merge into `testing`.
- **Device proof is outstanding.** The acceptance is the reporter's log showing
  `ttsReady=true` and no `event=tts-unavailable`. Asked for on #330.

## Known issues

- If a phone genuinely has no TTS engine, replies remain text-only; the app says so
  rather than failing the call. That behaviour is unchanged and intended.
- #328's cold-start diagnosis remains a hypothesis pending a debug log.

## Upgrade notes

None. Installs over 0.9.0.2 (versionCode 146, same signing key); no migration, no new
permissions.
