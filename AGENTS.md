# AGENTS.md — Hermes Vox

Hermes Vox is the voice of Hermes: a thin, mode-selectable conversational-voice
client (Android, Kotlin) where **the entity IS the Hermes agent**.
The client only carries the conversation UX + an avatar; Hermes owns the
reasoning, tooling, memory, and context.

## Iron rules

1. **The entity is Hermes, not a persona.** The foreground voice/persona backend
   may change with the mode, but the identity, memory, and tooling always come
   from Hermes (the background). Never build a "personality layer" that pretends
   to be something other than the Hermes instance.
2. **Thin client, no agent on-device** (by default). Local mode runs a small
   on-device model for the *foreground voice* + light tool-use; the reasoning,
   heavy tooling, and memory stay in Hermes (on-device tiny or server).
3. **Three modes, one interface.** All inference goes through a `VoiceBackend`
   interface with three implementations: `Local` (Gemma 4 E2B / LiteRT-LM),
   `SelfHosted` (Thelio/Odroid lemonade/llama.cpp/VLLM/Ollama), `Cloud`
   (OpenAI Realtime / xAI / Gemini / Hermes cloud). The client is mode-agnostic.
4. **Standalone but coexists.** It is one frontend to the same Hermes instance
   already serving Telegram/desktop/relay — it does not fork the agent.
5. **Secrets never in the repo.** API keys, relay tokens, and the Hermes
   `API_SERVER_KEY` live in the Android Keystore / env, never committed.
6. **Build pipeline is no-flag + verifies.** `bash scripts/gate.sh` (or
   `make test`) is the canonical one-command gate: it checks the toolchain
   (JDK 17, Android SDK), fetches the pinned runtime, builds the APK, runs every
   unit test, and confirms the stream parity harness ran. Never hand-run a
   partial build and claim done. (`scripts/build.py` / `make build` is the
   Ebitengine-boilerplate target-matrix driver; it builds no target of this app
   — it already built 0/10 before #126 — and awaits a keep-or-replace decision.)

## Architecture

```
[mic -> VAD -> sherpa STT] -> core.HermesSession -> Hermes gateway (the mind)
        ^                                   |
        +---- [TTS + avatar/being] <--------+   presence: LiteRT-LM (local)
```

- **Speech stays on the device**: Silero VAD and sherpa-onnx for recognition and
  synthesis. There is no cloud voice path and no backend abstraction — the Go
  `VoiceBackend` layer was dead code and was deleted in #47/#126.
- **The mind is on the gateway**: the app talks to it through
  `com.hermesvox.core.HermesSession` — the Hermes API server's `/v1/responses`
  (streamed, chained by `previous_response_id`), `/v1/models`, `/v1/health`,
  `/api/model/options` (`oxproxion :8642`, or the termux Hermes). Plain Kotlin on
  `HttpURLConnection`; see `docs/KOTLIN-CORE.md`.

## Conventions

- Commits: imperative, one concern per commit, never commit secrets.
- Keep the gate (`scripts/gate.sh`, `make test`) green — it is the build's core
  promise.
- The streamed turn's behaviour is pinned by `testdata/stream/` (fixtures +
  goldens, replayed by `StreamParityTest`). Change a golden only on purpose.
- On-device models + numbers + LiteRT-LM path are documented in the House skill
  `hermes-voice-frontends` → `references/on-device-voice-models-2026-08-24.md`.
