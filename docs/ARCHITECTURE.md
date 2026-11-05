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
  peers are your own gateway (and, if you set it up, the phone's MCP listener).
- **Open.** GPL-3.0, reproducible build, secret-free CI.

## The shape

```
 mic ─► Silero VAD ─► Whisper STT ─┬──────────────────────────► Hermes gateway (the MIND)
  ▲    (BargeGate)   (OfflineStt / │  POST /v1/runs (default), or /v1/responses after a 404
  │                   RemoteStt)   │  body session_id · ephemeral instructions · X-Hermes-Session-Key
  │                                │                  │ run events: text deltas, tool calls,
  │                                ▼                  │ commentary, approval.request, completion
  │                     Presence (the SOUL)           │
  │                     ErSoulTurn · ErFillers ·      │
  │                     GemmaExpress + VOX.md         │
  │                                │                  ▼
  │                                └──► ErArbiter (P0 stop · P1 mind · P2 soul · P3 filler)
  │                                                   │
  └─── barge-in (cuts audio + POST /v1/runs/{id}/stop) ◄── Supertonic / Piper TTS ─► speaker
                                                      │
                             AvatarView (the being) ◄─┴─► CrawlView (the words)
```

## The layers

### 1. The mind — Hermes (server, yours)
Vox never reasons, calls tools or stores memory itself (`AGENTS.md`, iron rules 1–2).
It talks to the Hermes API server. Plain Kotlin in `com.hermesvox.core`; no other
client library and no gateway patch.

**Turns.** A turn is a streamed request and it takes one of two routes
(`core/TurnRoute.kt`, `docs/RUNS-ROUTE.md`):

- **`POST /v1/runs` — the default (#404).** The admission answers `202 {run_id}`; the
  app then reads `GET /v1/runs/{run_id}/events` and answers an approval with
  `POST /v1/runs/{run_id}/approval`. A barge-in is `POST /v1/runs/{run_id}/stop`, so the
  gateway stops the generation itself rather than losing a subscriber.
- **`POST /v1/responses` — the fallback.** A gateway that answers the admission with
  **404** has no `/v1/runs` route. That answer is remembered per profile
  (`route_no_runs_profiles`, `RouteChoice.kt`) and the same turn is retried once on
  `/v1/responses`, chained by `previous_response_id`. Every other status — 401, 409,
  429, 500, a transport failure — is a real error and never falls back.

`core/Stream.kt` consumes both vocabularies into one `StreamState`: text deltas become
speech, `tool.started` / `tool.completed` items drive the being's tool motifs, and
`phase: commentary` items are shown as progress but never spoken as the answer. Both
readers honour the gateway's idle keepalive and keep a sequence-numbered backlog so a
dropped socket can reattach.

**Conversation identity.** On `/v1/runs` the conversation is a body `session_id` the
client owns and rotates on `/new`, a new call and a profile switch; on the fallback it
is `previous_response_id`. `X-Hermes-Session-Key` is the *long-term memory* scope, not
the transcript.

**What a voice turn tells the mind (D31).** The framing rides the request's ephemeral
`instructions` — the voice register, what the soul has already said, and the phone's
state — plus a per-request `model_options.reasoning` (default *low*). The caller's words
go as `input`, byte-exact. Protocol text never rides the user's message, and a typed
turn sends an explicit empty `instructions` so it cannot inherit the voice register
down the chain.

**Approvals (#282).** `/v1/runs` registers the approval notifier and emits
`approval.request` with the command, its description, the choices the gateway offers and
a request id. `core/Approval.kt` renders exactly those choices (`deny` always present),
answers once, and lets a timeout resolve to *no*. The debug arm in
`docs/APPROVALS-ACCEPTANCE.md` is test tooling, not a shipped surface.

The connector replaced a Go core bound with gomobile in #126; `testdata/stream/` holds
the fixtures that pin its streaming behaviour to what the Go core did, and to the runs
wire (`docs/KOTLIN-CORE.md`, `RouteParityTest`).

### 2. Hearing and turn-taking — on-device
- **VAD:** Silero via sherpa-onnx gates the open line; `BargeGate` decides when speech
  during playback is a real interruption (level + duration, with a level-only escape).
- **STT:** on-device via sherpa-onnx (`OfflineStt`) — Whisper base.en by default,
  tiny/small, NVIDIA Parakeet-TDT 0.6B v2, or the streaming Nemotron 0.6B model — or an
  optional self-hosted endpoint (`RemoteStt`). Recognition is *batch*: it sees a finished
  utterance. The streaming arm is the exception and keeps a live hypothesis that feeds
  partials. The turn text
  is always the **whole** utterance (`SttWindows` windows anything past 25 s); a partial
  may only *trigger* an early end on a genuine finished-sentence pause, and is reused as
  the text only when it covered the whole utterance (`EarlyStartRule`). Every transcript
  passes `TranscriptValidator` before it is a turn, and the words sent are shown briefly
  as a dim "heard" line (`HeardLine`). `tools/sttbench` measures all of this.
- **Continuous listening (Alpha, #228/#229).** The mic and the recogniser stay live for
  the call; partials are read every 100 ms and `SemanticEndpoint` judges the end of the
  turn from silence, the partial's stability and whether the words can end a sentence.
  Both switches default on *inside* the Alpha gate; with the gate off the old 800 ms
  silence endpoint runs: a conservative finished-sentence check, not semantic.
- **Barge-in:** one cut path stops audio, cancels the turn and releases the turn gate
  (`VoiceController`, `StreamFence`, `StreamRetirementState`). On the fallback route,
  closing the stream aborts the generation; on `/v1/runs` it also POSTs the run's stop. The
  interrupting words are kept from their onset (with pre-roll) and become the next turn
  (`BargeCarry`).

### 3. The voice — on-device
Supertonic (ten voices, 44.1 kHz) via sherpa-onnx is the recommended voice, chosen by a
measured bake-off ([voice bake-off][bakeoff]); Piper is the lighter option and the system
TTS the fallback. One engine (`SherpaTts` + `SherpaVoice`) streams by sentence behind the
same fence for every voice. Delivery follows the caller's mood as the soul heard it
(`VoiceMood`: speed and pause length). `SpeechCursor` locks the on-screen words to what
has actually been spoken.

### 4. The presence — the layer (the soul)
Presence is a layer on the one normal operation (D16), behind the Alpha gate. It adds a
small on-device model as the *soul's voice*, never a second brain ([design][er-design]):

- **Contract + Soul (`VoxSoul`, VOX.md).** The Hermes agent authors its own distilled
  voice file from its `SOUL.md`; the device mirrors it **read-only**, one document per
  profile (`VoxMirror`), and validates it. The device never writes identity back.
- **A warm soul (`GemmaExpress`, `SoulBudget`).** One rolling LiteRT-LM conversation:
  the persona is prefilled once, so each render only prefills its own directive, and
  the soul remembers what it already said this session.
- **The beat, not a gate (D30).** Every voice turn is submitted to the mind at once and
  the soul renders two to six words to fill the wait. The `<<SKIP>>` offer is retired:
  the mind always answers. The beat is dropped if the mind's first token gets there
  first, and delivery is recorded from playback (`ErHandoff`, `event=er-soul-played`).
- **The soul hears (`SoulAudio`, `VoiceMood`).** Gemma 4 E2B's own audio encoder takes
  the caller's last few seconds of audio alongside the transcript. The soul tags their
  tone (`{calm}` `{warm}` `{lively}` `{tense}`), which steers delivery. On-device only.
- **Safety lists stay deterministic (`ErIntent`).** Only backchannel-never-cancels and
  barge-cancels use enumerable rules. A 2B model is never in the abort path.
- **Presence ladder (`ErFillers`, `ErPresence`, `ErClips`).** Under 900 ms, silence;
  from 900 ms, one recorded nonverbal cue; no *words* before 4 s, then one in-character
  fail-soft line per window. Density is capped and user-adjustable.
- **One output (`ErArbiter`).** Strict priority: P0 stop · P1 mind · P2 critical soul ·
  P3 filler. Anything at P0–P2 cuts P3 immediately.
- **Measured (`ErTelemetry`).** Per call: soul-first-word, soul-spoke %, render
  p50/p95, arbiter and barge outcomes.

### 5. The phone acts — local actions and MCP (#421, D32–D35)
Two lanes, deliberately not one wire:

- **The caller's lane.** `LocalActionGrammar` recognises a timer, alarm, Do Not Disturb
  or volume command in one utterance, and `LocalActions` executes it on the phone with no
  network round trip. It is Tier 0 only: reversible, private, nothing outward-facing.
  The presence context then tells the mind what the phone did.
- **The mind's lane.** While a call is live, `PhoneMcpServer` serves **one** MCP tool,
  `phone`, on the phone's tailnet address (`100.64.0.0/10`, port 8765, `/mcp`), behind a
  128-bit bearer token stored in the Android Keystore. Every call returns one JSON
  result with `ok` and the facts of what happened; a refusal is a result with a `reason`,
  never a transport error. Tier 2 actions are refused (`approval-required`) and belong to
  the gateway's approval surface.
- **Grants.** `PhoneGrants` defines thirteen capability groups once, each Off / Ask /
  Allow and Off by default. The tool's advertised `enum` is built from the live grants on
  every `tools/list` and `tools/call`, so a revocation takes effect on the next call.
  Allow is a Tier 0 word only. `docs/MCP-PHONE.md` is the contract.

### 6. Profiles — saved connections
A profile is the whole connection: base URL (`https://host/p/<name>`), its own key, model,
agent name, session scope and provider (`Profiles.kt`). The list comes from the host
(`/v1/models` plus the agent's own answer, `ProfilesAsk` / `HostProfiles`); unknown names
are marked unavailable, never deleted. Switching writes the profile into the ordinary
prefs and rebuilds the session, so a switch starts a fresh conversation and ends a live
call.

- **Per-profile secrets.** Each key is encrypted by `SecureStore` (Android Keystore,
  AES/GCM) before it enters the profile list; nothing in the registry logs, shows or
  decrypts one.
- **Per-profile presentation.** Voice, model and look belong to the profile record and
  are applied by `ProfileScope`; a value a profile does not own falls back to the
  device-wide one. The connection itself, the Alpha gate, Presence's own switches and
  everything about the phone stay device-wide (`docs/PRESENCE.md`).

### 7. The file offer (#370, #386)
"Share with agent" never uploads a file to a service. The phone stages it, hashes it and
serves it **once** on the tailnet (`FileOffer`, `FileStage`, `FileServer`): a 100 MB cap,
a five-minute expiry, a one-time capability URL, and `410` after the first transfer. Text
under 8 KB travels inline; a URL becomes a small named `.txt`. The agent fetches and
verifies the hash, and nothing leaves the phone until the card is confirmed.

### 8. The Alpha gate (#402, D29)
**One gate for everything unfinished.** `AlphaGate` has a master switch, **off on a
fresh install**, and five sets a tester selects individually: **Presence**, **Continuous
listening**, **Turn-taking**, **Phone tools** and **Switching profiles**. A set runs only
when the master is on *and* that set is selected (`AlphaGate.allows`); with the master
off every alpha surface is dimmed and unreachable, and the runtime readers ask
`AlphaGate`, not the feature's own preference. A set's own prefs keep their meaning
inside the gate. Quiet calls (#325) is deliberately **not** a set: it is its own switch,
off by default, with no master in front of it. An upgrade keeps what was already running
(`AlphaGate.migrate`); a fresh install gets nothing.

### 9. The being and the words — UI
The being is drawn on the GPU (OpenGL ES 3, on its own thread: `BeingThread`,
`BeingRenderer`):
- **6,000 stateless particles.** Each one's position is computed in the vertex shader
  every frame from its identity and its shape's exact geometry (`BeingShaders`), so rings
  are rings, lids are curves and solids have depth-shaded backs.
- **27 shapes,** each designed to read as its name: the nebula, iris, galaxy, jellyfish,
  globe scan, constellation, terminal, flame, Möbius ribbon, black hole, flower,
  soundwave, lightning, atom, eye, ripples, radar, octopus, lit sphere, DNA helix, knot,
  aurora, harmonograph, tesseract, mandala, butterfly and hourglass.
- **Staggered morphs,** with a swirl mid-flight, between any two shapes.
- **Light:** two-level bloom and a luminance-preserving filmic tone curve, with an
  authored light-mode ground; categories that want trails get real frame feedback.
- **Frame pacing:** 60 fps, paused when not visible. It is back-pressured by a GPU fence
  and steps quality down (fewer points, a smaller buffer, one bloom level) if a device
  cannot keep up, so it can never starve the UI.

`AvatarView` remains the brain: state and tool → shape (`MotionState`, the per-state
picks), the palettes and visual categories. `CrawlView` renders the reply locked to the
voice and holds the scrollback (`docs/CRAWL-SCROLLBACK.md`). Both sit on true OLED black
in the dark theme.

### 10. Lifecycle and trust
- `VoiceService` (foreground, microphone type) owns the live call in the background and
  is also the MCP listener's lifetime.
- The gateway key is user-entered and Keystore-encrypted (`SecureStore`); release
  builds fail if a key injection is ever added. Backups are disabled. The stored form
  is marked (`enc1:`), so a legacy plaintext key containing `:` is read as plaintext
  rather than lost; a fallback that had to store plaintext is logged and retried on the
  next start.
- Models download in-app through a foreground service (`ModelDownloadService`,
  `ModelDownloads`), so they survive leaving the screen. Downloads are resumable over HTTP
  `Range` (`DownloadResume`), wait out network loss without failing, and are sha256-verified
  and zip-slip guarded before install (`ModelDownloader`).
- Cleartext is refused except loopback and a tailnet MagicDNS name while the tunnel is
  up (`core/CleartextPolicy.kt`).
- Logs are local-only and transcript logging is opt-in (`VoxLog`).

## How Vox maps to Sesame's four components

| Sesame component | Vox mechanism | Status |
|---|---|---|
| Emotional intelligence | The soul *hears* the caller's tone (Gemma audio input); mood steers the voice's delivery and the vibe Hermes is told | Built, Alpha (Presence); field-verify |
| Conversational dynamics | The beat on every turn, barge-in, backchannel hold, presence ladder, arbiter, speech-locked text, continuous listening with semantic endpointing | Built; continuous listening is Alpha; device checks outstanding |
| Contextual awareness | Tool-aware narration; Hermes' own memory and context; the phone's state in `instructions` | Shipped |
| Consistent personality | One identity: `SOUL.md` → agent-authored VOX.md → soul prompt, one VOX.md per profile | Built, Alpha (Presence) |
| *(beyond Sesame)* Agency | Real tools, memory, skills; tool calls visible in the being; the phone's own actions through one MCP tool | Shipped / Alpha (Phone tools) |
| *(beyond Sesame)* Sovereignty | On-device speech; your gateway only | Shipped |

## Gaps, honestly

- **The runs route's device pass.** `/v1/runs` is the default and its parity is proven
  off-device, but the nine checks in `docs/RUNS-ROUTE.md` — a live approval, a dropped
  socket, `/new` on a scoped install — still need a real phone and gateway.
- **Streaming STT on a phone.** The streaming arm exists (Nemotron 0.6B, opt-in) and
  feeds continuous listening, but its phone RTF and memory are not measured here; the
  bench numbers are host CPU. Whisper-base stays the default until a phone says otherwise.
- **Echo-tail judgement, in the field.** After a reply the mic reopens live; a short
  segment that ended inside the 450 ms tail window is dropped as echo (`EchoTailRule`).
  Its thresholds are unit-tested, not yet tuned against real speaker echo.
- **The beat, in the field.** Built and unit-tested; its latency on a real phone GPU
  is what the `express-probe … warm=` log line and the `soul(beat=…)` counters prove.
- **Voice expressiveness.** Supertonic is natural but has no emotion control; mood
  moves only speed and pauses. Prosody conditioned on the conversation is not yet
  runnable on Android; see the bake-off.
- **Half-duplex.** Vox listens *or* speaks (with barge-in), not both. Full-duplex
  models are the long-horizon path ([research][research]).
- **The phone's surface is narrow on purpose.** Twelve capability groups are defined;
  nine have no action yet, their grants are stored and their schema entries appear only
  when the capability lands. Tier 2 stays on the gateway's approval surface.

[sesame]: https://www.sesame.com/research/crossing_the_uncanny_valley_of_voice
[er-design]: DESIGN-enhanced-realtime-voice.md
[research]: research-on-device-voice-2026-08-27.md
[bakeoff]: VOICE-BAKEOFF.md
