# Handoff Brief: Hermes Vox — UI Overhaul, Completeness Test & Build-Out

You are taking over **Hermes Vox** — *the voice of Hermes*. A thin Android client where the entity you talk to through your phone is **the very same agent you set up under your Hermes profile** (same memory, identity, tools). This session's job: a **fundamental UI overhaul**, a **completeness test** of the whole pipeline, and a **build-out** of the remaining gaps. This brief is self-contained — read it, then read the `hermes-vox-development` skill (the authoritative owner's guide), then build.

## 1. Ground Truth — Read Before Building

- **`hermes-vox-development` skill** (house category) — the owner's guide: the design pillars, the architecture, the GPU allocation, the verified Hermes 0.20.0 API contract, the Android ecosystem stack, the build recipes. Read it fully.
- **`hermes-voice-frontends` skill + its `references/`** — the voice-frontend landscape + the session's captured references (Android shell recipe, runs/cancel/gomobile gotchas, avatar/settings, MVP UI spec, the `res/` dir pitfall, the SpeechRecognizer guard).
- **Repo** `chezgoulet/hermes-vox` (private). **Working copy is on the Thelio** at `/home/c/hermes-vox` — that's where you build. Read `go.mod`, `voice/*.go`, `mobile/*.go`, `game/*.go`, `cmd/app/*`, and the `android/` project.
- **Task focus is the Android client** (`android/`). The Go `voice` package is the source of truth for the entity connection.

## 2. Project Structure

```
hermes-vox/ (module github.com/chezgoulet/hermes-vox)
  voice/        — Go: Backend interface + Cloud/Local/SelfHosted, HermesClient (/v1/chat/completions),
                  HermesResponsesClient (/v1/responses, previous_response_id), HermesRunClient
                  (StartRun/RunStatus/CancelRun — the barge-in), Conversation, Config (secret-safe env).
  mobile/       — the gomobile-bind package: HermesSession (TurnText/TurnStored/StartRun/RunStatus/CancelRun)
                  + the game Start/update-touches. `gomobile bind` → mobile.aar (gitignored).
  game/         — the Ebitengine Game (the avatar/GL shell; js-wasm target). Not the current focus.
  cmd/app/      — the entrypoints (build-tagged desktop/js + android).
  android/      — the Android Gradle project (the MVP UI). app/build.gradle uses Gradle 8.12.1 + AGP 8.7.3 + Kotlin 1.9.24 (jvm 17). The client.
```

The Go `voice` + `mobile` packages are DONE + committed + tested (the entity connection, the `/v1/responses` path, the runs/cancel).

## 3. Context — What's Done, What's Missing

**Built + committed (green, running on the emulator):**
- The Go entity connector: `HermesClient`, `HermesResponsesClient` (server-side context, `previous_response_id`), `HermesRunClient` (start/poll/**cancel** — the barge-in abort), `Conversation`, `Config` (secret-safe, `Default()` points at the verified local agent).
- The Android client: `MainActivity` (connect + Send + Talk + Settings + Realtime + Clear), `VoiceController` (SpeechRecognizer STT → `turnStored`/run → TextToSpeech + an **AudioRecord RMS barge-in** that cuts the TTS + `CancelRun` the agent), `AvatarView` (the visual entity reacting to state), `RealtimeActivity` (the immersive view), a Settings dialog, and an **MVP UI**: true OLED-black + cyan/violet sci-fi theme (`values/` + `values-night/`), **no pre-filled IP** (URL empty, release-ready), a **stream log**, a theme toggle (system/dark/light), rounded buttons.
- The client builds + installs + runs on **emulator-5554** (android-35, on the Thelio).

**Missing / where the build-out goes (the gaps):**
- **Fundamental UI overhaul** — the current UI is a functional skeleton. It needs a *designed* modern sci-fi interface: better hierarchy, a cleaner connection flow (first-run/onboarding), a polished avatar presentation, a proper settings screen (not a bare dialog), refined motion/transitions, Material-3-inspired surfaces while keeping the OLED/cyan identity.
- **The SSE/stream is polled, not truly streamed** — "see the SSE calls" wants the actual SSE consumption (`/v1/responses` `stream:true` + the `response.*` events + `hermes.tool.progress`), rendered live in the stream log + feeding the avatar's "working" state from real tool-progress.
- **The voice is Android TextToSpeech** — the warm voice (Kokoro/Piper via sherpa-onnx) is not wired.
- **No foreground service** — the app doesn't listen/run in the background.
- **The VAD is a simple RMS threshold** — Silero VAD (on-device) is the finer trigger.
- **`RealtimeActivity` crashes on the headless emulator** (the Google SpeechRecognizer service faults at the binder level — a known emulator limitation; the code is guarded but still faults). Handle gracefully — degrade to text/type.
- **A completeness test** — there's no end-to-end harness proving the whole pipeline works. Build one (a Go integration test against the real Hermes gateway with the key from env, + a way to verify the Android client's turn path). (The Hermes API key lives in the House env store — use that, never the session dumps.)

## 4. Philosophy / Framing

- **The entity IS Hermes** — the phone is the front of house (voice + presence + control); the mind is the Hermes agent on the Odroid (via cloud API + tools; P40 reserved for agentic offload). The persona lives in the entity's system prompt; the phone voices it.
- **The smart controller, not a frontier full-duplex host** — VAD-triggered listening + barge-in via stream-cut + `CancelRun`. No frontier speech model.
- **Configurable, agnostic pipeline** — STT/TTS/duplex each selectable (on-device / RX 590 / Odroid), exposed as settings, user-entered secrets (never baked/committed).
- **Build the framework primitive; the content is the proof** — the UI should be reusable + clean, not one-off.

## 5. Visual / Design Direction

- A **modern sci-fi dark UI**: true OLED black (`#000000`), cyan (`#00e5ff`) + violet (`#8b5cf6`) accents, smooth rounded surfaces, subtle glows/borders, generous spacing, a sleek avatar presentation. Inspired by modern dark UIs (Linear/Vercel/Raycast-level polish) but with the sci-fi identity. Dark by default + a system/dark/light toggle.

## 6. Required First Deliverable (Completeness Test)

1. `go test ./voice/...` green (the Go connectors) — already green.
2. A **real integration test**: `HERMES_VOX_HERMES_API_KEY` (gateway key from the House env store) + a turn against the live local agent (`http://100.84.47.125:8642`, model `hermes-agent`) → assert a reply + a `previous_response_id` chain + a StartRun → CancelRun round-trip.
3. A **build/verify gate**: `gomobile bind` → `mobile.aar` + Gradle `assembleDebug` + `adb install` on `emulator-5554` + launch (APK builds+installs+runs), and `js-wasm` builds.
4. An **end-to-end Android turn test** (real device or mocked STT) that proves Talk → STT → entity → TTS → barge-in cancel works.

## 7. Expected Outcomes — Milestones

1. **Completeness test green** — the Go integration test + the build/verify gate + an Android turn path proof.
2. **UI overhaul** — a designed, modern sci-fi interface: first-run onboarding, a polished main screen, a real settings screen, refined motion, an elevated avatar presentation.
3. **Real SSE/stream** — the stream log shows the actual SSE events (`response.created`, `response.output_text.delta`, `hermes.tool.progress`, `response.completed`) as they arrive; the avatar reflects real tool-progress.
4. **Warm voice** — Kokoro/Piper via sherpa-onnx (on-device) or the RX 590 as the TTS, selectable; graceful fallback to Android TTS.
5. **Foreground service** — the voice pipeline runs in the background (`FOREGROUND_SERVICE_MICROPHONE`).
6. **Realtime view hardened** — the immersive view renders reliably (graceful degradation when STT is unavailable, no crash), on a real device + the emulator.

Each milestone must produce a demo note (verification transcript: the commands run + the output).

## 8. Primitives to Build

- **`StreamClient` (Go)** — consume `/v1/responses` `stream:true` SSE, emit the event types + text deltas + tool-progress to a callback; the app logs them + feeds the avatar.
- **`VoicePipeline` config (Android)** — the per-piece backend selection (STT/TTS/duplex) + the entity config, exposed in Settings, persisted.
- **`AvatarView` state feed** — driven by the REAL agent state (from the SSE/stream + the run status), not a guess.
- **`WarmTts` (Android)** — Kokoro/Piper via sherpa-onnx → `AudioTrack` (PCM float), low-latency, with system-TTS fallback.
- **`VoiceService` (Android)** — the foreground service owning the mic + audio pipeline in the background.
- **The design system** — the sci-fi components (surfaces, buttons, the avatar, the log) as reusable primitives.

## 9. Out of Scope

- The **P40** — reserved for agentic work (Qwen3.8-27B). Never put the speech on it.
- **Frontier full-duplex models** (Moshi/MiniCPM-o/PersonaPlex on-device or local) — the smart-controller pattern is the path.
- The **Ebitengine avatar/GL shell** (`game/`) — the visual entity is the Canvas `AvatarView`.
- **Changing the Hermes gateway / the Hermes agent** — you consume it, never modify it.
- **Cloud API integrations** — local-first. No OpenAI/xAI/Gemini keys.
- **Committed secrets** — the API key is always user-entered in the app.

## 10. Verification Gates (run, don't guess)

- `cd /home/c/hermes-vox && GOTOOLCHAIN=go1.26.4 GOMODCACHE=/home/c/hermes-vox/.tools/cache/go-mod go test ./voice/...`
- `gomobile bind -target android -androidapi 23 -javapkg com.hermesvox -o mobile.aar github.com/chezgoulet/hermes-vox/mobile` (after `gomobile init`).
- `cd android && <gradle> --no-daemon assembleDebug` (Gradle 8.12.1, AGP 8.7.3, Kotlin 1.9.24).
- `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk` + `adb shell am start -n com.hermesvox/.MainActivity` + confirm the process runs.
- `go vet ./voice` clean, `js-wasm` builds (`GOOS=js GOARCH=wasm CGO_ENABLED=0 go build ./cmd/app`).
- Commit + push with `git -c user.name='Hermes Vox' -c user.email='chris@coveredbridgecookies.com'`.

## 11. Environment & Operational Gotchas

- **The build host is the Thelio** (`ssh c@sasquatch`). Every ssh command must export: `JAVA_HOME=/home/c/jdk-17.0.12+7`, `ANDROID_HOME=/home/c/Android/Sdk`, `ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.2.9519653`, `GOBIN=/home/c/.local/bin`, `GOMODCACHE=/home/c/hermes-vox/.tools/cache/go-mod`, `GOCACHE=/home/c/hermes-vox/.tools/cache/go-build`, `GOTOOLCHAIN=go1.26.4`, `GOMOBIN=$GOBIN`, `PATH=...`. Non-interactive ssh does NOT source `.bashrc` — use a script file + `bash -lc`.
- **Gradle is not on the PATH.** Use the cached binary directly: `/home/c/.gradle/wrapper/dists/gradle-8.12.1-bin/eumc4uhoysa37zql93vfjkxy0/gradle-8.12.1/bin/gradle`. Do NOT use the wrapper (it's missing `gradle-wrapper.jar`). **AGP 8.7.3 + Gradle 8.12.1 + Kotlin 1.9.24** is the good pair (AGP 8.2.2 + Gradle 8.2 FAILED).
- **gomobile bind returns ≤2 values** — `(string, bool, error)` fails ("too many result values"). Use `(T, error)` (empty = not-done poll state).
- **NDK 25.2** (not 30 — NDK 30 gives `ANativeActivity_onCreate` dup). The Ebitengine-org `github.com/ebitengine/gomobile` is the patched helper.
- **`res/` dirs must exist before upload** — `mkdir -p res/values res/values-night res/drawable` or scp fails and the build dies on unresolved `@color`/`@drawable`/`@style`.
- **The emulator's SpeechRecognizer service is flaky/absent** — it can fault at the binder level and crash an Activity (the Realtime view). Guard it; degrade gracefully (text/type fallback). True mic/audio needs a real device.
- **The Hermes endpoint** is live at `http://100.84.47.125:8642` (model `hermes-agent`, `Bearer API_SERVER_KEY`). The key is a secret — enter in-app / env only, never commit.

## 12. Definition of Done

"A fundamentally overhauled, modern sci-fi Hermes Vox client running on the emulator (and a real device) that: (1) passes the completeness test — the Go entity connector is integration-tested against the live agent, the APK builds+installs+runs, and a turn path is proven end-to-end; (2) has a designed UI (OLED/cyan sci-fi, onboarding, a real settings screen, refined motion, an elevated avatar); (3) shows the real SSE/stream + the agent's tool-progress live, feeding the avatar; (4) has a warm selectable voice (Kokoro/Piper) with graceful fallback; (5) runs in a background foreground service; (6) has a hardened Realtime immersive view. All gates green, committed + pushed, with demo notes."
