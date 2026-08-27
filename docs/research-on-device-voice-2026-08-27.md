# On-Device Voice Interaction — Open-Source Landscape, Best Practices, and Patterns for Hermes Vox

**Research date:** 2026-08-27 (Torc). Goal: find the best open-source implementations + best
practices for exactly what we're building — hands-free on-device conversational voice
(Sesame-style presence, streaming TTS, barge-in, wake-word ambience) — and map concrete
patterns onto Hermes Vox's two metamodes (in-app realtime + future full ambience).

---

## 1. The problem space, in one line
A phone-call-grade voice presence is a **latency + turn-taking + robustness** problem, split
into independently-streaming stages. The winning pattern (Retell, The Prompt Bench, Smallest.ai,
Gladia — all agree) is: **every stage streams and overlaps** the next, and you budget latency
per stage, not end-to-end.

**The canonical latency budget (real-time voice, 2026):**
- network ≈ 50 ms
- VAD / turn-taking ≈ 150–300 ms
- LLM time-to-first-token ≈ 150–400 ms
- TTS time-to-first-audio ≈ 100–200 ms
- STT runs **in parallel** with the caller (emits a **partial transcript every ~50 ms**), so it
  "adds almost nothing" on top if streamed.

Two rules fall out:
1. **STT must emit PARTIAL hypotheses** as the user speaks (streaming ASR), so the LLM can start
   and barge-in stays natural. Waiting for the full utterance before transcribing is the single
   biggest avoidable latency.
2. **TTS must accept partial sentences and synthesize early** — we already do streaming chunks.

---

## 2. The reference implementations that matter

### Sesame AI (the north star we're emulating)
- **CSM-1B** (github.com/SesameAILabs/csm) — the Conversational Speech Model: a 1B Llama-based
  backbone + **residual vector quantization** + a **small linear-head audio decoder**, so the
  *voice* generates fast (low-latency) while staying expressive (breath, intonation). The
  "uncanny valley" blog + FlowHunt both emphasize **single-stage + efficient decoder = low
  latency**.
- Sesame's **Personal Agents iOS/Android app**: voice-first; the agent "follows" — web search,
  notes, reminders — while you talk. So the *presence* is the voice; the agent's work is
  backgrounded (the **Sesame school** we chose).
- **Implication:** our TTS (Piper) is lighter than CSM-1B and fine for in-app; CSM-1B is the
  desktop/server-grade "more human" voice. Stream it (we do) + keep the voice as the presence,
  not a terminal feed.

### sherpa-onnx (the foundation we already use)
- k2-fsa/sherpa-onnx ships on-device ASR/TTS/VAD/**speech enhancement**/diarization for Android,
  iOS, Linux, Raspberry Pi, NPUs, 12 languages. Has a **streaming ASR server** example
  (`python-api-examples/streaming_server.py`) — audio streams in, partial hypotheses come out.
  VAD settings are tunable (Medium article on sherpa-onnx VAD params).
- **Implication:** we're already on the right platform. The upgrade path for lower latency is
  **streaming ASR** (whisper streaming / zipformer streaming) instead of full-utterance Whisper —
  that gives partial transcripts (rule #1). Speech enhancement (RNNoise/DeepFilterNet-style) is
  also already in sherpa-onnx for the loud-street problem.

### Full-duplex speech-to-speech (the "full ambience" endgame)
- **Moshi** (kyutai-labs/moshi) — the first open-source **full-duplex** (listens AND speaks
  simultaneously) spoken LLM: **Mimi** streaming neural audio codec + "inner monologue." ~160 ms
  theoretical / ~200 ms practical latency. Desktop/server (7B+). This is the true simultaneous
  conversation model.
- **MiniCPM-o 4.5** (OpenBMB, 9B) — end-to-end full-duplex multimodal, "pocket-sized," approaches
  Gemini 2.5 Flash; flagged by the local-LLM community as the viable *on-device/local* full-duplex
  alternative to Sesame CSM. The strongest candidate for on-device **full ambience**.
- **Implication:** for the **full-ambience metamode**, a single full-duplex model (MiniCPM-o on the
  phone; Moshi on a server) beats pipelined STT→LLM→TTS for naturalness + barge-in — but it's a
  big, separate build. Our half-duplex + streaming pipeline is the right **in-app** MVP; full-duplex
  is the ambience roadmap.

### Continuous-loop wake-word assistants (the "full ambience" architecture)
- **kiwi-voice** (ekleziast/kiwi-voice) — "Voice interface for OpenClaw with speaker recognition,
  voice-gated security, real-time barge-in, multi-provider streaming TTS." Loop: mic → **wake word**
  (ML model or text match) → local STT (Faster Whisper) → **speaker ID** → LLM (via OpenClaw
  WebSocket) → **streaming TTS** → repeat. Self-hosted, privacy-first. **This is the closest working
  blueprint for our full-ambience metamode.**
- **Fono** (fono.page) — a complete voice-AI stack (STT, TTS, local LLM, **wake word**, speaker ID)
  in one small binary (desktop; integrates sherpa-onnx FunASR/Qwen3-ASR/FireRedASR).
- **Vosk** (alphacephei/vosk-api) — on-device ASR for Android/iOS/Pi. **OpenClaw Assistant** +
  **takeout_assistant** are Android voice assistants using Vosk + on-device wake word.
- **Wake-word engines:** Picovoice **Porcupine**, **openWakeWord**, or a small ML model (kiwi-voice).
  **VAD:** Silero (we use), WebRTC VAD.
- **Implication (full ambience):** a wake-word-gated continuous foreground service — capture → wake
  word → STT partials → speaker-ID gate → agent → streaming TTS — is the build. Speaker ID is a
  nice privacy/security gate (only respond to the owner), which matches Christopher's sovereignty +
  privacy instincts.

### On-device model sizing / noise (from our own State Street test + these projects)
- **SenseVoice** (QwenAudio) — on-device ASR via llama.cpp/GGUF, ~254 MB q8, built-in VAD — a
  compact, edge-friendly ASR option. sherpa-onnx bundles speech enhancement, which is the real fix
  for the loud-street STT ceiling we hit (Whisper base hallucinates; small is heavy/slow).

---

## 3. Directly-applicable patterns → Hermes Vox

| Pattern | Where it comes from | Apply to Vox | Priority |
|---|---|---|---|
| **Streaming ASR with partial hypotheses** | sherpa-onnx streaming; Retell/Smallest.ai | Replace full-utterance Whisper transcribe with streaming partials so the agent starts forming + turn-taking latency drops. (Biggest in-app win left.) | ★★★ |
| **Streaming TTS (chunks)** | we already did | Keep. Overlap with STT/LLM. | ✓ done |
| **Half-duplex + barge-in (in-app)** | our current | Correct for MVP; keep. | ✓ done |
| **Latency budget + measurement (TTFB/partials/final, P50/P95)** | Gladia / Retell | Add STT latency metrics (frame size, endpointing/VAD thresholds) + a debug panel; tune to ~500 ms. | ★★★ |
| **Speech enhancement / NS** | sherpa-onnx; kiwi-voice, Fono | Use sherpa-onnx noise suppression more aggressively for loud-street; verify VOICE_COMMUNICATION AEC/NS + consider RNNoise/DeepFilterNet path. | ★★ |
| **Wake-word + continuous loop** | kiwi-voice, Fono, Vosk assistants | The "full ambience" metamode: wake word (Porcupine/openWakeWord) + foreground service + VAD + STT + agent + streaming TTS. | ★★ (future) |
| **Speaker ID / voice-gated security** | kiwi-voice | Optional privacy gate for ambience (only respond to known voice). | ★ (future) |
| **Full-duplex S2S model** | Moshi, MiniCPM-o | Ambience endgame: MiniCPM-o 4.5 on-device (or Moshi on a server) for true simultaneous conversation + natural barge-in. | ★ (future, big) |
| **Deterministic barge-in testing** | Softcery "real-time vs turn-based" | Add an explicit test: user interrupts → TTS stops promptly → loop returns to listening (we fixed this; add an automated check). | ★★ |

---

## 4. Recommended next steps for Hermes Vox (in order)

1. **Streaming/partial STT** (in-app realtime): use sherpa-onnx streaming ASR (or whisper streaming)
   so the agent gets partial transcripts as you speak → agent can start early + the turn feel is
   phone-call-grade. Measure + tune frame size / endpointing.
2. **STT latency instrumentation**: log TTFB / partial / final per turn (P50/P95) so we can
   regression-test, not guess.
3. **Robustness in noise**: lean on sherpa-onnx speech enhancement (NS) + keep VOICE_COMMUNICATION;
   optionally surface a "Whisper small (accuracy)" vs "base (speed)" choice clearly.
4. **Begin the full-ambience spike**: a wake-word-gated always-on foreground service (Porcupine /
   openWakeWord + Silero VAD + our streaming pipeline + optional speaker-ID gate), modeled on
   kiwi-voice. Then evaluate a full-duplex model (MiniCPM-o) as a v2 ambience engine.

---

## 5. Key sources
- Sesame: github.com/SesameAILabs/csm · sesame.com/blog/crossing-the-uncanny-valley-of-voice · flowhunt.io
- sherpa-onnx: github.com/k2-fsa/sherpa-onnx (incl. python-api-examples/streaming_server.py) · VAD params (Nadira Povey, Medium)
- Full-duplex: kyutai-labs/moshi · OpenBMB/MiniCPM-o · localaimaster.com moshi realtime guide
- Continuous-loop/wake-word: ekleziast/kiwi-voice · fono.page · alphacephei/vosk-api · OpenClaw Assistant · takeout_assistant
- Latency/turn-taking: retellai.com/how-real-time-voice-ai-works · thepromptbench.com/latency-budgets · smallest.ai · gladia.io/measuring-latency-in-stt · softcery.com realtime-vs-turn-based
- Edge ASR: QwenAudio/SenseVoice (llama.cpp GGUF, ~254 MB)
