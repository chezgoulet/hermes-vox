# Hermes Vox — sideload & on-device verify (the "make it work on the phone" guide)

The APK is a thin voice client whose mind is your Hermes agent. It must be on the
**same network as the Hermes gateway + the model store** (tailnet or LAN). The
**core (Realtime / Walkie Talkie) works on-device** with the blessed models
downloaded in-app; the on-device **Gemma model** is the Enhanced-Realtime
enhancement (downloadable, runtime is the last integrate step).

## Sideload

- **APK:** `android/app/build/outputs/apk/release/app-release.apk` (signed,
  sideloadable; 193 MB — bundles the on-device speech native libs).
  (Dev: `.../debug/app-debug.apk` also installs.)
- `adb install -r app-release.apk` on the device, or copy + tap to install
  (allow "install from unknown sources").

## First-run (onboarding)

1. **Entity endpoint** — `http://<host>:8642` (the Hermes API-server gateway on
   the tailnet). The device must reach it.
2. **API key** — user-entered (encrypted at rest, never committed). The entity
   connector does a real Ping (never fake-success).
3. **Agent name** — the Hermes profile name; shown center-top after connect.

## Download the blessed models (on-device)

**Settings → Voice models.** The blessed set downloads from the in-app source
(default the House store, configurable), stream → sha256-verify → unpack
(no cloud, no sideload):
- **Silero VAD** (barge-in)
- **Piper · en-US** (warm on-device TTS)
- **Whisper base.en** (on-device STT; tiny/small are options)

## Which mode does what

- **Realtime** — hands-free open line: talk, the being listens (VAD) + barge-in,
  Hermes answers, warm Piper speaks. Keyboard works too.
- **Enhanced Realtime** — same + the on-device **Gemma 4 E2B** expression layer.
  Download **Gemma 4 E2B (presence)** in **Settings → Voice models** (in-app,
  sha256-verified) — it's ~2 GB. Until then it gracefully uses the routed
  stand-in (the phone-call glue still works).
- **Walkie Talkie** — hold **PTT** to talk (release to send), or type + **SEND**.

## Verify on the device (the emulator is function-only; real mic/NPU here)

1. **Voice turn** — tap 🎤/PTT → talk → being gathers (working) → Hermes answers
   → Piper speaks. No cloud.
2. **Barge-in** — talk over Hermes mid-answer → it cuts + re-listens.
3. **The being** — reacts to real tool calls (terminal→bracket, web→scan,
   file→fold, memory→constellation).
4. **Gemma (Enhanced)** — download `gemma-e2b` in Voice models; the device loads
   it via LiteRT-LM + the being narrates the work in the phone-call voice
   (`GemmaExpress loaded:` logs). Note: the LiteRT-LM runtime targets the device
   NPU — the x86_64 emulator can't load it; verify on the Pixel.
5. **Heartbeat of the design** — the Star-Wars reply crawl, the eye-being,
   Rajdhani type, the three modes.

## Troubleshooting

- **"Connect first" / ping fails** — the device can't reach the gateway; check
  the tailnet/LAN + the endpoint (`/v1/models` must be reachable).
- **Model download fails** — the store source isn't reachable on the device; set
  a reachable source in Settings → Voice models.
- **STT unavailable** — the on-device Whisper model isn't installed (download it)
  or the backend is "Platform (Google)" without it. On-device Whisper is the
  local-first default.
- **No warm voice** — Piper isn't installed; it falls back to the system TTS.
