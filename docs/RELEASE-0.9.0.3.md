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

- Built in CI by `release.yml` (run 41225227232) on `main`: two clean checkouts,
  **identical payloads**.
- Version stamp asserted before the build: `versionCode=146 versionName=0.9.0.3`.
- `apksigner verify --print-certs`: `CN=Hermes Vox, OU=ChezGoulet, O=ChezGoulet,
  L=Windsor, ST=VT, C=US`, certificate SHA-256
  `8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19` — the release key,
  the same certificate that signed 0.9.0.1 and 0.9.0.2.
- Three new unit tests cover the engine-visibility description.

```
sha256:  6cb0879e1f2e300184485b1fd42b6579547c4fc2ea5aa657b5f9dee5fefa116f
certs:   CN=Hermes Vox, OU=ChezGoulet, O=ChezGoulet, L=Windsor, ST=VT, C=US
```

**Device proof is still outstanding.** The acceptance is the reporter's log showing
`ttsReady=true` and no `event=tts-unavailable`; asked for on #330 when this ships.

## Known issues

- If a phone genuinely has no TTS engine, replies remain text-only; the app says so
  rather than failing the call. That behaviour is unchanged and intended.
- #328's cold-start diagnosis remains a hypothesis pending a debug log.

## Upgrade notes

None. Installs over 0.9.0.2 (versionCode 146, same signing key); no migration, no new
permissions.
