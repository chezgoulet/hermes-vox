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
- **STT:** on-device via sherpa-onnx (`OfflineStt`) — Whisper base.en by default,
  tiny/small, or NVIDIA Parakeet-TDT 0.6B v2 (opt-in; the most accurate and fastest on
  the bench) — or an optional self-hosted endpoint (`RemoteStt`). Recognition is *batch*:
  it sees a finished utterance. The turn text is always the **whole** utterance
  (`SttWindows` windows anything past 25 s); a partial transcript may only *trigger* an
  early end on a genuine finished-sentence pause, and is reused as the text only when it
  covered the whole utterance (`EarlyStartRule`). Every transcript passes
  `TranscriptValidator` (noise tags, implausible words/sec, runaway repetition,
  Whisper's "thank you"-on-silence priors, minimum speech ratio) before it is a turn,
  and the words sent are shown briefly as a dim "heard" line (`HeardLine`).
  `tools/sttbench` measures all of this (WER / RTF / hallucinations on a 25-clip corpus).
- **Barge-in:** one cut path stops audio, cancels the stream and releases the turn
  gate (`VoiceController`, `StreamFence`, `StreamRetirementState`). The interrupting
  words are kept from their onset (with pre-roll) and become the next turn
  (`BargeCarry`) — unless an Enhanced-Realtime HOLD judged them a backchannel.

### 3. The voice — on-device
Supertonic (ten voices, 44.1 kHz) via sherpa-onnx is the recommended voice, chosen by a
measured bake-off ([voice bake-off][bakeoff]); Piper is the lighter option and the system
TTS the fallback. One engine (`SherpaTts` + `SherpaVoice`) streams by sentence behind the
same fence for every voice. Delivery follows the caller's mood as the soul heard it
(`VoiceMood`: speed and pause length). `SpeechCursor` locks the on-screen words to what
has actually been spoken.

### 4. The presence — Enhanced Realtime (the soul)
Enhanced Realtime adds a small on-device model as the *soul's voice*, never a second
brain ([design][er-design]):

- **Contract + Soul (`VoxSoul`, VOX.md).** The Hermes agent authors its own distilled
  voice file from its `SOUL.md`; the device mirrors it **read-only** and validates it
  (the six Contract rules are byte-identical every time). The device never writes
  identity back.
- **A warm soul (`GemmaExpress`, `SoulBudget`).** One rolling LiteRT-LM conversation:
  the persona is prefilled once, so each render only prefills its own directive, and
  the soul remembers what it already said this session.
- **The decision is the soul's output (`ErSoulTurn`).** Each turn, one render either
  answers in character (greetings, emotion, small talk) or hands over with
  `<<ESCALATE>>` plus a generated **beat** — the few words a person says as they start
  to think. The mind's reply then cuts in (an explicit audio handoff).
- **The soul decides first; the mind is told (`SoulGate`, `MindSkip`).** The mind's
  submit waits a bounded, adaptive moment for the decision (the beat plays through
  it), then carries what the voice did. If the voice already answered, the mind may
  reply `<<SKIP>>` — filtered so it is never heard. No double answers, and the voice's
  line lands in the mind's own session history.
- **The soul hears (`SoulAudio`, `VoiceMood`).** Gemma 4 E2B's own audio encoder takes
  the caller's last few seconds of audio alongside the transcript. The soul tags their
  tone (`{calm}` `{warm}` `{lively}` `{tense}`), which steers the voice's delivery and
  becomes the vibe Hermes is told. On-device only, with a fallback to words alone if the
  audio path is unavailable.
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
- Models download in-app through a foreground service (`ModelDownloadService`,
  `ModelDownloads`), so they survive leaving the screen. Downloads are resumable over HTTP
  `Range` (`DownloadResume`), wait out network loss without failing, and are sha256-verified
  and zip-slip guarded before install (`ModelDownloader`).
- Logs are local-only and transcript logging is opt-in (`VoxLog`).

## How Vox maps to Sesame's four components

| Sesame component | Vox mechanism | Status |
|---|---|---|
| Emotional intelligence | The soul *hears* the caller's tone (Gemma audio input); mood steers the voice's delivery and the vibe Hermes is told | Shipped (ER, alpha); field-verify |
| Conversational dynamics | The beat on every turn, barge-in, backchannel hold, presence ladder, arbiter, speech-locked text | Shipped; semantic end-of-turn **blocked** on streaming STT |
| Contextual awareness | Tool-aware narration; Hermes' own memory and context | Shipped |
| Consistent personality | One identity: `SOUL.md` → agent-authored VOX.md → soul prompt | Shipped (ER) |
| *(beyond Sesame)* Agency | Real tools, memory, skills; tool calls visible in the being | Shipped |
| *(beyond Sesame)* Sovereignty | On-device speech; your gateway only | Shipped |

## Gaps, honestly

- **Streaming STT.** The offline recognisers give no streaming partials (the capture loop
  re-decodes snapshots), so endpointing is silence plus a conservative finished-sentence
  check, not semantic. A streaming recogniser is the 0.9 prerequisite ([plan][plan08], M4);
  the bench's streaming zipformer is fast but 2.6x whisper-base's WER on accents.
- **STT on a real phone.** The sttbench numbers are host CPU. Parakeet's phone RTF and
  resident memory (a 482 MB download) are unmeasured, which is why whisper-base stays the
  default. The vanish fix (`BargeCarry`) is proven from the code and unit-tested; its
  field witness is the `event=barge-carry` log line.
- **Echo-tail judgement, in the field.** After a reply the mic reopens live (no deaf
  cooldown); a short segment that ended inside the 450 ms tail window is dropped as echo
  (`EchoTailRule`, logged as `event=echo-tail-drop`). Its thresholds are unit-tested, not yet
  tuned against real speaker echo.
- **The beat, in the field.** Built and unit-tested; its latency on a real phone GPU
  is what the `express-probe … warm=` log line and the `soul(beat=…)` counters prove.
- **Voice expressiveness.** Supertonic is natural but has no emotion control; mood
  moves only speed and pauses. Prosody conditioned on the conversation (Sesame's CSM,
  or its compact descendant Marvis) is not yet runnable on Android; see the bake-off.
- **Half-duplex.** Vox listens *or* speaks (with barge-in), not both. Full-duplex
  models are the long-horizon path ([research][research]).

[sesame]: https://www.sesame.com/research/crossing_the_uncanny_valley_of_voice
[er-design]: DESIGN-enhanced-realtime-voice.md
[plan08]: PLAN-0.8-accelerator.md
[research]: research-on-device-voice-2026-08-27.md
[bakeoff]: VOICE-BAKEOFF.md
