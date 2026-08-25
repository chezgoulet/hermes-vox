# AGENTS.md — Hermes Vox

Hermes Vox is the voice of Hermes: a thin, mode-selectable conversational-voice
client (Android + desktop, Go/Ebitengine) where **the entity IS the Hermes agent**.
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
6. **Build pipeline is no-flag + verifies.** `scripts/build.py` (or `make build`)
   is the canonical one-command build; it inspects the toolchain, lists targets
   with feasibility + reason, builds what's possible, and verifies artifacts.
   Never hand-run a partial build and claim done.

## Architecture

```
[mic/avatar + UI] -> VoiceBackend -> (Local | SelfHosted | Cloud) -> Hermes (background)
      ^                                                                    |
      +---------------------- TTS + avatar <------------------------------+
```

- `VoiceBackend` — the mode abstraction: `Stream()` (mic in), `Reply()` /
  `OnTurn()` (inference), `Play()` (TTS), `Avatar()` (the visual). Each backend
  wires to its own on-device / self-hosted / cloud stack.
- Hermes is the background: the app talks to it over the Hermes Relay / Runs API
  (`hermes-relay :9119`, `oxproxion :8642`, or the termux Hermes).

## Conventions

- Commits: imperative, one concern per commit, never commit secrets.
- Keep the build pipeline (`scripts/build.py`, `Makefile`) working — it's the
  boilerplate's core promise.
- On-device models + numbers + LiteRT-LM path are documented in the House skill
  `hermes-voice-frontends` → `references/on-device-voice-models-2026-08-24.md`.
