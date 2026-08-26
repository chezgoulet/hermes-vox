# Hermes Vox — Handoff to the Next Agent

**Repo:** `chezgoulet/hermes-vox` (public) · **Host:** Thelio `c@sasquatch` · **App dir:** `/home/c/hermes-vox/android` · **APK:** release build, versioned `hermes-vox-<version>.apk`
**Latest release:** `0.3.7` (Latest). **Working head:** `07607af` (un-released 0.3.7+n). **Target:** 0.4.0 (see Gates).

---

## 1. What this is

A **fully local voice assistant for Android** that talks to the **Hermes gateway** (the "entity"). It's a real entity connector (Go→Java via gomobile), with on-device STT (Whisper via sherpa-onnx), warm on-device TTS (Piper via sherpa-onnx), Silero VAD barge-in/segmentation, a sci-fi OLED-dark presence-first UI, a model downloader, and a foreground voice service. Sovereign/local-first (canonical k2-fsa builds; LAN/tailnet `http://` ok).

## 2. Build / test environment

- **Build env (all on Thelio):** `GOTOOLCHAIN=go1.26.4`, `GOMODCACHE=/home/c/hermes-vox/.tools/cache/go-mod`, `GOCACHE=…/go-build`, `JAVA_HOME=/home/c/jdk-17.0.12+7`, `ANDROID_HOME=/home/c/Android/Sdk`. Gradle: `/home/c/.gradle/wrapper/dists/gradle-8.12.1-bin/eumc4uhoysa37zql93vfjkxy0/gradle-8.12.1/bin/gradle`. **Helper script:** `/home/c/hermes-vox/vox_build.sh` (sets all env + runs `assembleRelease`; build with `--no-daemon`). The app depends on `app/libs/mobile.aar` (the gomobile Go connector) + `sherpa-onnx-1.13.6.aar` — rebuild `mobile.aar` only if you change the `voice/` Go code (gomobile).
- **Test emulator:** `emulator-5554` (Android 15, x86_64, headless `phonon_test` AVD), `com.hermesvox` installed. Drive via `adb -s emulator-5554` + `uiautomator dump` for element bounds + `exec-out screencap`. **The emulator has NO network** (cannot reach the Odroid gateway `100.84.47.125:8642` NOR the internet) — it verifies UI/pipeline/compile but NOT the live gateway stream, and no voice models are installed (0/6). Voice-loop runtime + the 401 need the device or a gateway-routed build.
- **Mandate (Christopher): run rounds of UI testing AND function testing through this test environment before shipping any build to the device.**

## 3. Current state (what works vs open) — updated 2026-08-26

### Works (verified)
- **Warm Piper TTS** (on-device); **offline Whisper STT** (base.en blessed default); **Silero VAD**; **half-duplex realtime loop** (VAD segmentation, `VOICE_COMMUNICATION` capture, hard speak-gate, noise-reject).
- **SSE streaming + text replies**, **model downloader**, **wake-lock**, **full session log + Share**, **Test connection button**, **warming splash**, **release conventions**.
- **NEW (this cycle, commit `07607af`, verified on emulator-5554):**
  - **Realtime speak-gate latch released on EVERY path** (stream-error, no-TTS, speak-off, no-reply, barge-in) — the loop no longer deadlocks after a failed/reply-less turn (the "one-turn-then-stops" + 401-flood symptom). A `hush()` is bound to the presence tap (tap = STOP). The **VAD threshold** is threaded into the Silero gate (was hardcoded 0.5f + logged-but-inert).
  - **Settings → Mic / capture** sub-menu rows (wired + logged in `VoiceController`): AEC/NS source, VAD sensitivity, min-speech, pause-to-end-turn, max-speech.
  - **Settings → Particles / presence** sub-menu (idle shape/theme + auto-cycle toggle). The theme was **moved off the raw avatar tap**; tap now = STOP. Idle theme visibly changes the avatar (verified: vortex spiral).
- **Release conventions:** tag === versionName === APK filename, `0.3.7`-style (no leading `v`).

### OPEN (the 0.4.0 gate + bugs)
- **`401 Invalid gateway` on the stream** — `{"message":"Invalid gateway API key (API_SERVER_KEY)","type":"gateway_auth_error","code":"gateway_auth_failed"}`. The key is proven-valid (Ping/`/v1/models` connects on-device). The Go stream client (`voice/stream.go` `streamInto`) DOES send `Authorization: Bearer <key>` correctly on `POST /v1/responses`. **Diagnosis (confirmed from the Thelio): the gateway at `100.84.47.125:8642` IS reachable from the Thelio and rejects any invalid key with this exact error; the emulator has no route, so the real-key stream behavior must be reproduced on-device or with the real key.** Per-endpoint auth/config on the gateway is the suspect — NOT the key. Needs the real key (device/onboarding) to fully close.
- **Realtime `autoOpenLine()` guard is a deadlock** — `lineOpen=true` is set then immediately `if (lineOpen) return`, so the line NEVER auto-starts and the warming retry can't re-enter. Compounded by **dual-controller ownership**: `VoiceService.onStartCommand` creates a *second* `VoiceController` and starts it, while `MainActivity` creates its own (never-started) controller. Two capture/loop owners = the double-fire root class. **Restructuring ownership is unverifiable in the emulator (no audio/gateway) — do it only with on-device tests.** This is the real remaining realtime blocker.
- **Enhanced realtime (Gemma presence)** — `GemmaExpress` + `VoiceOrchestrator` are wired; `handleModeUi` loads Gemma when mode=enhanced. Degrades gracefully (RoutedExpress fallback) when the `.litertlm` model is absent / arm64-only runtime. Circuit on the emulator is safe (no crash); on-device voice behavior + armv7 degrade path need verification.
- **Particle visuals** — could be more expressive of *which* tool is running (tool motifs exist: shell/web/memory/file). Refinement, not broken.
- **Walkie voice toggle** + **speak_responses** toggle exist.

## 4. Architecture lessons (proven — not theory)

1. **Realtime must be HALF-DUPLEX (listen XOR speak via a hard speak-gate), NOT AEC.** The hard mode-flip (`MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION`) broke playback + capture — avoid.
2. **Use sherpa-onnx VAD `pop()`/`acceptWaveform` segmentation, not hand-rolled RMS.** The VAD's threshold + `min_silence_duration_ms` are the noise gate.
3. **The double-fire root was multiple active capture paths** — one owner, one loop; no external re-entry. (Still codified above: the dual-controller ownership is the live instance of this.)
4. **"Ping works but the stream 401s with the same key is NOT a key problem."** When a status call succeeds but an action call 401s with the same credential, the key is valid → diagnose the action path's auth wiring or the per-endpoint check.
5. **`pipeline:` log is an init-time snapshot** — `sttReady=false` prints before the async models finish; look for the "loaded" lines.
6. **TTS must auto-prefer the warm Piper** when installed (was defaulting to a silent System voice).
7. **A gate that's cleared mid-loop or only in a `finally` deadlocks** — the speak-gate latch must be released on EVERY settle/error path (fixed this cycle).
8. **sherpa `OfflineRecognizer.decode()` can SIGSEGV** — guard the STT with a `ready` flag + `@Synchronized`.
9. **The app truncated the stream error** at ~40 chars — the Test-connection button surfaces the full message; keep errors un-truncated.

## 5. Next-agent mandate

Run a **comprehensive best-practices, correctness, AND functionality review**, then **implementation cycles** toward the 0.4.0 gate. **Throughout: run rounds of UI testing and function testing through `emulator-5554` before shipping a build** (use `adb` + `uiautomator` + `screencap`; verify every feature/fix/squash there first). The gateway-stream (401) needs the device or a gateway-routed build — the emulator has no network.

## 6. Do NOT regress

- Squash against the 0.4.0 gate (realtime ok, enhanced realtime, particles + settings, mic settings) — never break the warm voice, the offline STT, the streaming, or the working realtime loop.
- Keep secrets out of the repo/chat (pipe via stdin/env); house creds live in the default profile env store (do not read another profile's env).
- Keep the release versioning convention (tag === versionName === APK filename, no leading `v`).
