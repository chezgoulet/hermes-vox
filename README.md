# Hermes Vox

**The voice of Hermes.** A thin, mode-selectable conversational-voice client that
feels like talking to a living AI entity (Sesame Miles/Maya fidelity) but **is**
the Hermes agent — same identity, full abilities, three inference modes.

## What it is

A thin Android client (Go / Ebitengine) that gives Hermes a face and a voice:
mic in → inference → TTS → animated avatar. The entity **is** Hermes — not a
separate persona layered on top. Hermes owns the reasoning, tooling, memory, and
context; the client only carries the conversation UX.

## Three modes (mode-selectable `VoiceBackend`)

The app picks the fidelity / sovereignty / convenience trade per situation, via a
single `VoiceBackend` interface:

1. **Local (on-device)** — Gemma 4 E2B (INT4, LiteRT-LM) + on-device STT
   (Moonshine / whisper) + TTS (piper / kokoro). Offline-capable, sovereign,
   ~10–25 tok/s hybrid.
2. **Remote self-hosted** — a model server on the Thelio/Odroid (lemonade /
   llama.cpp / VLLM / Ollama) running MiniCPM-o for *full-duplex* realtime
   speech-to-speech. Sovereign, no cloud; Hermes lives on that box too.
3. **Cloud** — OpenAI Realtime / xAI / Gemini, or Hermes's cloud model. Max
   fidelity + convenience; cloud privacy trade.

**The entity stays Hermes in all three.** Only the foreground voice/persona
backend changes; the mind (Hermes) is constant.

## Build pipeline (inherited from `cjtrowbridge/ebe-boilerplate`)

One-command, no-flag cross-platform build + verify:

```bash
python scripts/build.py --verify
make build            # Unix convenience
```

`scripts/build.py` inspects the Go toolchain, lists every target with its
feasibility + reason, builds the possible ones, and verifies the artifacts.
`releases/{goos}/{goarch}/app/` holds build output (gitignored). Deployment is
deliberately unconfigured — wire it to your release process when ready.

## Notes

- Go 1.26.4 · Ebitengine v2.9.9 · cross-platform (Android via gomobile + desktop).
- Derived from `cjtrowbridge/ebe-boilerplate` (MIT); the agentic-pipelines
  `pipeline.yaml` Ollama governance scaffold is optional.
- Model landscape, on-device numbers, and the LiteRT-LM integration path: see the
  House skill `hermes-voice-frontends` → `references/on-device-voice-models-2026-08-24.md`.
