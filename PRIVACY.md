# Privacy Policy — Hermes Vox

**App:** Hermes Vox (Android), version 0.5.7
**Effective date:** September 8, 2026
**Repository / source of truth:** https://github.com/chezgoulet/hermes-vox

This policy describes how the Hermes Vox Android app handles data. Every claim below is
grounded in the app's own source code; the relevant files are named inline and listed in
the [Sources](#sources) section, so you can verify anything here yourself.

Hermes Vox is a **client app for a gateway you control**. It gives a voice to "Hermes", an
AI agent that runs on a **Hermes gateway server that you (or your organisation) set up and
manage** — typically on your own private network. The app itself is not a service; it has
no accounts, no sign-up, and no servers operated by the app's developers. **The developers
of Hermes Vox never receive any of your data.**

---

## 1. What data is processed

Hermes Vox is deliberately local-first. It processes the following data, all of which stays
under your control:

- **Microphone audio (your speech).** When you talk to the app it records audio through the
  microphone permission (`RECORD_AUDIO` in `AndroidManifest.xml`, via the foreground
  "microphone" service `VoiceService`). By default your speech is transcribed **on your
  device** by downloaded Whisper speech-to-text models (see §4), with on-device Silero VAD
  detecting when you start/stop speaking (`OfflineStt.kt`, `SileroVadGate`). The
  transcription text is what the agent hears — see §2 for where it goes.
- **Text you type or send** to the agent, and the agent's replies (displayed and spoken
  aloud via on-device Piper TTS, `SherpaTts.kt`).
- **The gateway endpoint address and API key you enter** during onboarding
  (`OnboardingActivity.kt`) and in Settings. Both are entered by you; nothing is
  pre-configured or baked into the app (see §3).
- **Your model preferences** (which voice models, STT backend and voice mode you choose,
  theme, debug-log toggle, etc.), stored in the app's local preferences.
- **Local diagnostic logs.** The app keeps a local, size-bounded log on your device
  (`filesDir/logs/hermes-vox.log`, rotated at 5 MB — `VoxLog.kt`) and a local crash log
  (`filesDir/vox_crash.log` — `CrashLog.kt`). These logs are written only to your device.
  The code has a hard privacy rule: **transcript text, tool arguments, tool outputs and API
  keys are never written to these logs** — only metadata and lengths (see
  `RemoteStt.kt` and `VoiceController.kt`). An optional "Log spoken transcript" setting
  exists and is **off by default** (`VoiceController.kt`).

**What we do NOT collect:** no name, email, phone number, contacts, location, device
identifier, or advertising identifier is collected by the app. There is no account system.
No personal data is sent to the app's maintainers — there is no server of ours to send it
to.

## 2. Where data goes

- **On-device processing (default).** Speech capture, voice-activity detection (VAD),
  speech-to-text (STT), and text-to-speech (TTS) all run **on your phone** using models you
  download into the app's private storage (`ModelCatalog.kt`, `OfflineStt.kt`,
  `SherpaTts.kt`). No cloud is involved in these steps.
- **The Hermes gateway — your server.** Once your speech is turned into text, that text
  (and your later typed messages) is sent over the network **only to the gateway endpoint
  you configured in the app** (`mobile/session.go` — `HermesSession` connects to the
  `baseURL` you entered, using your API key for Bearer authentication). Hermes runs there
  and streams its replies back. This is the same gateway that serves your other Hermes
  front-ends; **the app never sends anything to any other destination.**
- **Optional remote STT (off by default).** The app can transcribe using an
  OpenAI-compatible speech server instead of on-device Whisper — but only if you
  explicitly choose the "remote" backend **and** supply its URL in Settings. There is a
  privacy hard gate in code: if no URL is saved, audio never leaves the device
  (`RemoteStt.kt`, `VoiceController.kt` — `resolveSttLeg`). If you enable it, your audio is
  sent to that URL — a server you chose — and the optional key for it is stored the same
  encrypted way as your gateway key (§3).
- **Model file downloads (only when you install models).** Voice models are downloaded by
  you, on demand, from their public upstream hosts over HTTPS — the k2-fsa sherpa-onnx
  model releases on GitHub by default (`ModelCatalog.kt`) and, for the optional Gemma
  expression model, Hugging Face (`litert-community`). These downloads contain only model
  files and carry no personal data.

**The important honest caveat about transport:** Vox speaks HTTP to your gateway. Over a
**private network you control** (a LAN, VPN or tunnel such as Tailscale/Nebula) your API
key and conversations are protected by that network's encryption. The app does **not**
force TLS, and if you point it at a gateway over the public Internet on plain HTTP, your
key and messages could be read by anyone on the path. The app's own network-security
configuration requires TLS for everything except explicitly listed local hosts
(`network_security_config.xml`), and the onboarding screen warns you about this
(`strings.xml`). Use a private network or TLS.

## 3. How your API key is stored

- The gateway API key is **entered by you** in the app. The app **never ships with a key**
  — there is no default, no baked-in value, and no build-time injection: a release-build
  guard in `app/build.gradle` fails the build if a key is ever added back. `GatewayKey.kt`
  enforces that an empty or unreadable stored key resolves to "missing" (never to a hidden
  fallback).
- The key is stored **encrypted at rest** using the Android Keystore (`SecureStore.kt`):
  an AES/GCM key is generated inside the Android Keystore and never leaves the device's
  key store, and the stored value is the ciphertext (`encrypt()` in `SecureStore.kt`,
  written in `OnboardingActivity.kt`). It is decrypted only in memory when needed.
- One honest edge case: if on-device encryption fails on a particular device (a rare
  Keystore error), the app stores the value locally unencrypted rather than losing it
  (`OnboardingActivity.kt`). In every case the key lives only in the app's local
  preferences on your device and is transmitted **only to the gateway you configured** —
  never anywhere else (`hv_key_hint` in `strings.xml`: "encrypted on-device, never sent
  anywhere but your gateway"). Legacy values written before encryption was added are
  migrated transparently on read (`SecureStore.kt`).
- **The APK contains no secret.** You can audit the source in the repository.

## 4. On-device models

Voice functionality uses models that you download into the app's **private storage**
(`filesDir/models/<id>`) from the in-app **Settings → Voice models** screen:

- **Silero VAD** — on-device voice-activity detection (hearing when you speak).
- **Piper TTS** — fully offline text-to-speech voice (sherpa-onnx runtime).
- **Whisper (tiny/base/small)** — on-device speech-to-text (sherpa-onnx runtime).
- **Gemma 4 E2B (optional)** — the on-device "expression layer" used only by the
  **Enhanced Realtime (alpha)** voice mode (`GemmaExpress.kt`, loaded via Google's
  LiteRT-LM engine). It runs entirely on your device, has no tool access, and generates
  conversational phrasing locally. Because of its separate license, it is **not bundled**
  with the app: it downloads separately from Hugging Face only if you choose to install it.

All model files are streamed, SHA-256 verified against a pinned hash, and unpacked
(zip-slip guarded) into app-private storage (`ModelDownloader.kt`). **No model file or
model data is ever uploaded anywhere.** Uninstalling the app or clearing its data removes
them (see §6).

## 5. Analytics, advertising and third-party sharing

- **There is no analytics SDK, no telemetry SDK, no advertising SDK, and no third-party
  data-sharing code in the app.** The dependency list contains only AndroidX, Kotlin
  coroutines, the sherpa-onnx runtime, and the LiteRT-LM runtime for the optional Gemma
  model (`app/build.gradle`). You can confirm there is no Firebase, Crashlytics, Sentry,
  or ad SDK anywhere in the repository.
- Crash diagnostics are written to a **local** file on your device (`CrashLog.kt`) and are
  never transmitted automatically. The in-app log exists for you to export manually if you
  choose to share it while debugging.
- We do not sell, rent, or share any data, because **no data ever reaches us.**

## 6. Data retention and deletion

- **Where data lives.** All app data — your preferences (including the endpoint and the
  encrypted key), downloaded models, logs — is stored **only in the app's private
  on-device storage** (`SharedPreferences "hv"`, `filesDir/`). Android auto-backup is
  explicitly disabled for this app (`allowBackup="false"` in `AndroidManifest.xml`), so
  app data is not copied to Google's cloud backup.
- **Conversation content.** The app does not keep a conversation history of its own; the
  only transcript that exists is whatever your **gateway** retains (that data is governed
  by your gateway's configuration and is yours to manage).
- **How to delete everything:**
  1. **Uninstall Hermes Vox** — this removes the app's data and models from your device
     and destroys the Android Keystore key that protects your API key; or
  2. **Settings → Apps → Hermes Vox → Clear storage** — removes preferences, the stored
     key material, downloaded models, and logs without uninstalling.
- Deleting the app does not delete anything on your **gateway** (your Hermes server), since
  that is a separate system you operate. Remove data there using your gateway's own tools.

## 7. Children

Hermes Vox is a technical client intended for adults who operate their own AI gateway. It
is **not directed to children** and collects no personal information from anyone, child or
adult. It contains no ads and no purchases. If you believe a child has used the app, note
that any data involved would go to the family's own gateway (operated by you, not by the
app's developers). Contact us below and we will help you understand what the app itself
stores (which is deletable per §6).

## 8. Contact

This privacy policy concerns the **Hermes Vox app** and is maintained by the project:

- Project repository (source code, issues): https://github.com/chezgoulet/hermes-vox
- Maintainer contact: chris@coveredbridgecookies.com

For questions about the **Hermes gateway** or any AI agent it runs — including what it logs,
retains, or sends to model providers — contact the person who operates your gateway
(usually you). The gateway is a separate, user-managed system; **data processed by your
gateway stays under your control and is not governed by this policy.**

## Sources

The code files behind each claim (all in the repository at the link above):

| Claim | Source file(s) |
|---|---|
| Mic capture + foreground mic service | `android/app/src/main/AndroidManifest.xml` |
| On-device Whisper STT + Silero VAD, offline | `android/app/src/main/java/com/hermesvox/OfflineStt.kt` |
| Remote STT opt-in hard gate; no transcript logging | `android/app/src/main/java/com/hermesvox/RemoteStt.kt`, `VoiceController.kt` |
| On-device Piper TTS | `android/app/src/main/java/com/hermesvox/SherpaTts.kt` |
| On-device Gemma expression layer (optional model) | `android/app/src/main/java/com/hermesvox/GemmaExpress.kt`, `ModelCatalog.kt` |
| Model catalog, sources, sizes, hashes; app-private install dir | `android/app/src/main/java/com/hermesvox/ModelCatalog.kt`, `ModelDownloader.kt` |
| Key entry in onboarding; encrypted write | `android/app/src/main/java/com/hermesvox/OnboardingActivity.kt` |
| AES/GCM Android-Keystore encryption | `android/app/src/main/java/com/hermesvox/SecureStore.kt` |
| No default/baked key; empty-key rule; build guard | `android/app/src/main/java/com/hermesvox/GatewayKey.kt`, `android/app/build.gradle` |
| Local-only logs, rotation, privacy logging rule | `android/app/src/main/java/com/hermesvox/VoxLog.kt`, `CrashLog.kt` |
| Gateway connectivity to user-entered endpoint (Go connector) | `mobile/session.go`, `voice/` (Go) |
| No third-party analytics/ad SDKs (dependency list) | `android/app/build.gradle` |
| No Android auto-backup | `android/app/src/main/AndroidManifest.xml` (`allowBackup="false"`) |
| Cleartext/TLS network config; private-network warning | `android/app/src/main/res/xml/network_security_config.xml`, `res/values/strings.xml` |
| Product privacy/security statements | `README.md` |
