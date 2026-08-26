# Hermes Vox — on-device test / verify checklist (the "nearly-complete MVP")

Sideload `android/app/build/outputs/apk/debug/app-debug.apk` on the Pixel (or
`adb install` on emulator-5554). Point it at the entity gateway
(`http://<tailnet-ip>:8642`, key user-entered in-app) + download the blessed
models in Settings → Voice models.

## What to verify on the DEVICE (the emulator is x86_64/no-NPU — function + logic
here; real speech + NPU are the device's job)

1. **Voice pipeline on real mic + speaker** (the emulator has a virtual mic only):
   - Tap 🎤 → talk → the being gathers (working) → on-device Whisper STT →
     Hermes answers → on-device Piper TTS speaks. Warm voice, no cloud.
   - Barge-in: talk over Hermes mid-answer → it cuts + re-listens (Silero VAD).
2. **The phone-call glue (Gemma presence, orchestration):** on a tool call, the
   being narrates ("let me dig into that...") via `speakGlue`, then Hermes's real
   answer preempts it. Give Hermes a tool-heavy prompt to see the narration.
3. **Gemma 4 E2B model (the on-device expression backend):**
   - Put `gemma-4-E2B-it.litertlm` (~0.8 GB text-only,
     `litert-community/gemma-4-E2B-it-litert-lm`) at
     `filesDir/models/gemma-e2b/gemma-4-E2B-it.litertlm`.
   - `GemmaExpress.load()` should log `GemmaExpress loaded`; until the LiteRT-LM
     runtime is wired (TODO(device) in GemmaExpress.kt), it falls back to the
     RoutedExpress stand-in — the phone-call orchestration still works either way.
4. **The design language:** presence-first being (dispersed aura → golden gyre →
   white sparkle), Star-Wars reply crawl (bright) + SSE (dim, dev console toggle),
   Rajdhani type, no pills.

## Still to build (the MVP tail)
- **The Gemma on-device runtime** (LiteRT-LM native init + call at the
  GemmaExpress TODO) — the real on-device expression model.
- **MiniCPM-o-on-Thelio realtime** (option 3): full-duplex S2S streamed from the
  house box to the phone (sovereign).
