# Hermes Vox — Architecture

A one-page map of how Vox works, what it is measured against, and where each piece
lives. Deeper rationale is linked from each section; this page is the entry point.

## The benchmark, and the thesis

The target experience is Sesame's Maya/Miles: a voice that feels *present*. Sesame
names four components of voice presence ([Crossing the uncanny valley of voice][sesame]):

1. **Emotional intelligence** — reading and responding to emotional context.
2. **Conversational dynamics** — natural timing, pauses, interruptions and emphasis.
3. **Contextual awareness** — adjusting tone and style to the situation.
4. **Consistent personality** — a coherent, reliable presence.

Sesame gets there with one model family (CSM: a Llama backbone plus a small audio
decoder). By its authors' own account CSM models the *content* of speech, **not the
structure of the conversation** ("turn taking, pauses, pacing"), and it is a voice
without an agent behind it.

**Vox's thesis:** split the problem along the line Sesame leaves open. The *mind* is a
real agent you already run — **Hermes**, with its tools, memory, skills and `SOUL.md`
identity. The *phone* owns everything that has to be instant: hearing, turn-taking,
interruption, the voice, the presence. A small on-device model (Gemma 4 E2B) gives
the presence words in the agent's own voice while the mind works. Where Vox aims to go
beyond Sesame:

- **It is your agent.** The entity has real tools and long-term memory, on a gateway
  you own. The same agent answers you on Telegram, desktop and here.
- **Conversation structure is engineered, not hoped for.** Barge-in, backchannel
  detection, a priority audio arbiter and a presence ladder are explicit, tested code.
- **Sovereign by construction.** Speech processing is on-device; the only network
  peer is your own gateway. No accounts, no vendor cloud, no baked keys.
- **Open.** Apache-2.0, reproducible build, secret-free CI.

## The shape

```
 mic ─► Silero VAD ─► Whisper STT ─┬─────────────────────────► Hermes gateway (the MIND)
  ▲    (BargeGate)   (OfflineStt / │   /v1/responses stream:true, previous_response_id,
  │                   RemoteStt)   │   X-Hermes-Session-Key  (voice/, mobile/ — Go)
  │                                │                 │ SSE: text deltas, tool calls,
  │                                ▼                 │ commentary, completion
  │                     ER presence (the SOUL)       │
  │                     ErIntent · ErFillers ·       │
  │                     GemmaExpress + VOX.md        │
  │                                │                 ▼
  │                                └──► ErArbiter (P0 stop · P1 mind · P2 soul · P3 filler)
  │                                                  │
  └──── barge-in (cuts audio + cancels stream) ◄── Piper TTS / system TTS ─► speaker
                                                     │
                            AvatarView (the being) ◄─┴─► CrawlView (the words)
```

## The layers

### 1. The mind — Hermes (server, yours)
Vox never reasons, calls tools or stores memory itself (`AGENTS.md`, iron rules 1–2).
It talks to the Hermes API server:

- **Turns:** `POST /v1/responses` with `stream: true`, chained by
  `previous_response_id` so the server keeps the full context, tool calls included.
  `voice/stream.go` consumes the SSE stream: text deltas become speech, `function_call` /
  `function_call_output` items drive the being's tool motifs, and `phase: commentary`
  items are shown as progress but never spoken as the answer. It honours the gateway's
  10s keepalive with an idle watchdog.
- **Barge-in:** closing the stream aborts the generation (`CancelStream`); the
  `/v1/runs/{id}/stop` path exists for run-based turns.
- **Identity scoping:** an optional `X-Hermes-Session-Key` keeps each person's
  long-term memory apart on a shared gateway (`voice/entity.go`).
- **Voice register:** voice turns carry a short per-turn prefix ("spoken, 2–4
  sentences, no markdown") — the mechanism Hermes' own CLI voice mode uses. It rides the
  user text, so it works on a stock gateway and never persists as an instruction.

The Go connector is bound into the app with gomobile (`mobile/session.go`); it is the
single source of truth for the wire protocol.

### 2. Hearing and turn-taking — on-device
- **VAD:** Silero via sherpa-onnx gates the open line; `BargeGate` decides when speech
  during playback is a real interruption (level + duration, with a level-only escape).
- **STT:** Whisper via sherpa-onnx (`OfflineStt`), or an optional self-hosted endpoint
  (`RemoteStt`). Whisper here is *batch* — it sees a finished utterance, not partials.
  That is the main structural latency gap (see Gaps).
- **Barge-in:** one cut path stops audio, cancels the stream and releases the turn
  gate (`VoiceController`, `StreamFence`, `StreamRetirementState`).

### 3. The voice — on-device
Piper TTS via sherpa-onnx (`SherpaTts`, streaming by sentence) with the system TTS as a
fallback. `SpeechCursor` locks the on-screen words to what has actually been spoken.

### 4. The presence — Enhanced Realtime (the soul)
Enhanced Realtime adds a small on-device model as the *soul's voice*, never a second
brain ([design][er-design]):

- **Contract + Soul (`VoxSoul`, VOX.md).** The Hermes agent authors its own distilled
  voice file from its `SOUL.md`; the device mirrors it **read-only** and validates it
  (the six Contract rules are byte-identical every time). The device never writes
  identity back.
- **The decision is the soul's output (`GemmaExpress`).** Each turn, Gemma either
  answers in character (greetings, emotion, small talk) or emits `<<ESCALATE>>`. The
  mind is engaged in parallel on every turn and its reply preempts the soul. So a wrong
  soul decision can only change *who speaks first*, never what is true.
- **Safety lists stay deterministic (`ErIntent`).** Only backchannel-never-cancels and
  barge-cancels use enumerable rules. A 2B model is never in the abort path.
- **Presence ladder (`ErFillers`, `ErPresence`, `ErClips`).** Under 900 ms, silence
  (the being's motion is the acknowledgment); from 900 ms, one recorded nonverbal cue;
  no *words* before 4 s, then one in-character fail-soft line per window. Density is
  capped (≤2 per 3 s) and user-adjustable.
- **One output (`ErArbiter`).** Strict priority: P0 stop · P1 mind · P2 critical soul ·
  P3 filler. Anything at P0–P2 cuts P3 immediately.
- **Measured (`ErTelemetry`).** Per call: soul-first-word, soul-spoke %, render
  p50/p95, arbiter and barge outcomes.

### 5. The being and the words — UI
`AvatarView` is the presence: a generative particle being with 20 archetypes. It reacts
to *real* agent state (listening, thinking, speaking, stalls, and each tool call's
motif via `MotionState`). `CrawlView` renders the reply locked to the voice.
Both are drawn on true OLED black.

### 6. Lifecycle and trust
- `VoiceService` (foreground, microphone type) owns the live call in the background.
- The gateway key is user-entered and Keystore-encrypted (`SecureStore`); release
  builds fail if a key injection is ever added. Backups are disabled.
- Models download in-app, sha256-verified, zip-slip guarded (`ModelDownloader`).
- Logs are local-only and transcript logging is opt-in (`VoxLog`).

## How Vox maps to Sesame's four components

| Sesame component | Vox mechanism | Status |
|---|---|---|
| Emotional intelligence | Soul answers emotion/small talk in the agent's voice; the vibe travels via VOX.md | Shipped (ER, alpha) |
| Conversational dynamics | Barge-in, backchannel hold, presence ladder, arbiter, speech-locked text | Shipped; semantic end-of-turn **blocked** on streaming STT |
| Contextual awareness | Tool-aware narration; Hermes' own memory and context | Shipped |
| Consistent personality | One identity: `SOUL.md` → agent-authored VOX.md → soul prompt | Shipped (ER) |
| *(beyond Sesame)* Agency | Real tools, memory, skills; tool calls visible in the being | Shipped |
| *(beyond Sesame)* Sovereignty | On-device speech; your gateway only | Shipped |

## Gaps, honestly

- **Streaming STT.** Offline Whisper gives no partial transcripts, so there is no
  semantic endpointing and turn-end waits on silence. A streaming recogniser is the 0.9
  prerequisite ([plan][plan08], M4).
- **The beat.** The soul should take the opening beat of *every* turn. Generating it
  depends on a prefill measurement from a real device; pre-canned stems were rejected
  as the same anti-pattern as the old keyword router.
- **Voice expressiveness.** Piper is fast but not CSM-grade prosody. The TTS seam
  (`SherpaTts`) is where a more expressive on-device voice would plug in.
- **Half-duplex.** Vox listens *or* speaks (with barge-in), not both. Full-duplex
  models are the long-horizon path ([research][research]).

[sesame]: https://www.sesame.com/research/crossing_the_uncanny_valley_of_voice
[er-design]: DESIGN-enhanced-realtime-voice.md
[plan08]: PLAN-0.8-accelerator.md
[research]: research-on-device-voice-2026-08-27.md
