# Hermes Vox — Handoff to the Next Agent

**Repo:** `chezgoulet/hermes-vox` (public) · **Host:** Thelio `c@sasquatch` · **App dir:** `/home/c/hermes-vox/android` · **APK:** release build, versioned `hermes-vox-<version>.apk`
**Latest release:** `0.3.7` (Latest). **Target:** 0.4.0 (see Gates).

---

## 1. What this is

A **fully local voice assistant for Android** that talks to the **Hermes gateway** (the "entity"). It's a real entity connector (Go→Java via gomobile), with on-device STT (Whisper via sherpa-onnx), warm on-device TTS (Piper via sherpa-onnx), Silero VAD barge-in/segmentation, a sci-fi OLED-dark presence-first UI, a model downloader, and a foreground voice service. Sovereign/local-first (canonical k2-fsa builds; LAN/tailnet `http://` ok).

## 2. Build / test environment

- **Build env (all on Thelio):** `GOTOOLCHAIN=go1.26.4`, `GOMODCACHE=/home/c/hermes-vox/.tools/cache/go-mod`, `GOCACHE=…/go-build`, `JAVA_HOME=/home/c/jdk-17.0.12+7`, `ANDROID_HOME=/home/c/Android/Sdk`, gradle at `…/gradle-8.12.1-…/bin/gradle`. Build with `ANDROID_HOME` + `ANDROID_SDK_ROOT` exported (SDK-not-found failures happen without it). Release build needs the `kotlin-reflect` exclude + the `-Xskip-metadata-version-check` flag.
- **Test emulator:** `emulator-5554` (Android 15, x86_64), `com.hermesvox` installed. Drive via the `adb` platform-tools: `adb -s emulator-5554 shell <cmd>`, `uiautomator dump` for element bounds, `screencap` for screenshots. **Capture bugs:** identical-size shots = wrong screen; `uiautomator` skips nodes when the keyboard's up; the animated avatar breaks uiautomator (use bounds from a `dump`).
- **Mandate (Christopher): run rounds of UI testing AND function testing through this test environment before shipping any build to the device.** Front-load regression-catching onto the agent.

## 3. Current state (what works vs open)

### Works
- **Warm Piper TTS** (on-device) — the reply is synthesized + played (the r15 "wait for playback to drain" fix).
- **Offline Whisper STT** (base.en blessed default) — `WAV → whisper → text` proven; `OfflineWhisperStt` config = no-arg `OfflineModelConfig()` + setters + `tokens` on the model config + `modelType="whisper"`.
- **Silero VAD** loads; **half-duplex realtime loop** (VAD-driven segmentation, `VOICE_COMMUNICATION` capture, hard speak-gate, noise-reject).
- **SSE streaming + text replies** through the gateway.
- **Model downloader** (canonical k2-fsa upstream, tar.bz2/onnx/zip + hoist), **wake-lock permission**, **full session log + "Share full log"** (FileProvider), **Test connection button** (Settings → Test connection: pings + tries a real `/v1/responses` stream + surfaces the full error), **mic settings read + logged**, **warming splash** (awaits models warm).
- **Release conventions:** tag === versionName === APK filename, `0.2.3`-style (no leading `v`), versioned APK name.

### OPEN (the 0.4.0 gate + bugs)
- **`401 Invalid gateway` on the stream** — device-side (your phone reaches the gateway). The full message: `{"message":"Invalid gateway API key (API_SERVER_KEY)","type":"gateway_auth_error","code":"gateway_auth_failed"}`. **Christopher confirmed the IP + key are correct** and the **Ping/status connects.** So the key is proven-valid → the stream `/v1/responses` auth path on the device is the suspect (per-endpoint check, or a wiring bug where the stream client doesn't get the key the Ping does). **The emulator has NO route to the Odroid gateway (ping error: null = network)** so the 401 must be reproduced on-device, OR route the emulator to the Odroid (same LAN/tailnet).
- **Realtime double-fire / one-turn-then-stops** — was mitigated by the clean-loop + `loopActive`/`turnInFlight` guards + a post-turn cooldown; still needs on-device verification that one turn → reply → speak → re-listen, no re-captured reply.
- **Enhanced realtime (Gemma presence line)** — not yet working; degrade gracefully on armv7 (LiteRT-LM is arm64-only).
- **Particle visuals** — need to be more interesting + reflect the work being done (tool calls, thinking, work).
- **Settings sub-menus** — **Mic** (VAD threshold, min-speech, min-silence "pause", max-speech, audio source + NS/AEC, barge-in — prefs wired + logged, UI rows to add) and **Particles** (cycle vs stay on a theme). Theme on raw tap is wrong (tap should mean "stop"), so move it into Settings.
- **Walkie voice toggle** (inline) + the **speak_responses** toggle exist.

## 4. Architecture lessons (from the real-device debugging — these are proven, not theory)

1. **Realtime must be HALF-DUPLEX (listen XOR speak via a hard speak-gate), NOT AEC.** The anti-echo for a turn-taking voice assistant is: the loop is locked until the reply's *speech* finishes (mic silent), so it can never hear itself. A hard mode-flip (`MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION`) broke playback + capture — avoid.
2. **Use sherpa-onnx VAD `pop()`/`acceptWaveform` segmentation, not hand-rolled RMS.** The RMS fallback fired on static/buzzing/clicking → false turns + the 401 flood. The VAD's threshold + `min_silence_duration_ms` are the noise gate.
3. **The double-fire root was multiple active capture paths** (the loop + the speak-callback re-listen + barge-in + re-entrant `start()`s). One owner, one loop; no external re-entry.
4. **"Ping works but the stream 401s with the same key is NOT a key problem."** When a status call succeeds but an action call 401s with the same credential, the key is valid → diagnose the action path's auth wiring or the per-endpoint check. Never re-blame the key once it's proven.
5. **`pipeline:` log is an init-time snapshot** — `sttReady=false` prints before the async models finish; look for the "loaded" lines. TTS can be generating right under a `ttsReady=false` line.
6. **TTS must auto-prefer the warm Piper** when installed (was defaulting to a silent System voice).
7. **A gate that's cleared mid-loop or only in a `finally` deadlocks** — `loopActive`/`turnDone` must be held for the loop's whole life + cleared on actual exit.
8. **sherpa `OfflineRecognizer.decode()` can SIGSEGV** — guard the STT with a `ready` flag + `@Synchronized` (never decode a not-actually-loaded recognizer).
9. **The app truncates the stream error** at ~40 chars (`Invalid gateway` cut off) — the Test-connection button was added to surface the full message; keep errors un-truncated.

## 5. Next-agent mandate

Take a **comprehensive best-practices, correctness, AND functionality review** of `chezgoulet/hermes-vox`, then run **implementation cycles** to bring it across the finish line. **Throughout: run rounds of UI testing and function testing through the Thelio test environment (`emulator-5554`) before shipping any build to the device** (use `adb` + `uiautomator` + `screencap`; verify every feature/fix/bug-squash there first). Also verify the *gateway stream* path (the 401) — the emulator can't reach the Odroid, so confirm the gateway route/VPN or rely on on-device logs.

## 6. Do NOT regress

- Squash everything against the 0.4.0 gate (realtime ok, enhanced realtime, particles + settings, mic settings) — never break the warm voice, the offline STT, the streaming, or the working realtime loop.
- Keep secrets out of the repo/chat (pipe via stdin/env); house creds live in the default profile env (do not read another profile's env).
- Keep the release versioning convention (tag === versionName === APK filename, no leading `v`).
