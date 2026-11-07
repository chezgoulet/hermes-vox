# How Hermes Vox is put together

Hermes Vox is an Android voice client for Hermes, the agent you run yourself. The phone carries the
conversation and everything that must be immediate: the microphone, voice-activity detection,
speech recognition, speech synthesis, interruption by voice, the on-screen words and the figure
that shows state. The server you run carries everything that must be true: reasoning, tools,
long-term memory, skills and identity. The two halves meet on one exchange at a time, over HTTP.

## The words this document uses

- **The gateway** is the Hermes API server you run. The app addresses it by base URL and
  authenticates with its `API_SERVER_KEY`.
- **The agent**, or **the mind**, is the Hermes instance behind that gateway. It is the only part
  that reasons, calls tools or remembers. Vox does not run a second agent.
- **A turn** is one message exchange: the caller's words go to the mind; the reply comes back as a
  stream of events.
- **A run** is the gateway's execution of one turn on the `/v1/runs` route. It has a run id and a
  resumable event stream.
- **A call** is the open, hands-free line. The microphone stays live under voice-activity
  detection, and the caller can interrupt by speaking.
- **The being** is the GPU-drawn figure that shows state: resting, listening, thinking, speaking,
  or the motif of a tool the mind is running.
- **Presence** is the optional layer that gives the phone's small voice its turn: a greeting, the
  opening beat (two to six words while the mind starts to answer), and short asides during a long
  run.
- **The soul** is the on-device voice Presence uses. It is a small language model, not an agent:
  it produces short lines in the agent's style and has no tools.
- **A profile** is one saved connection: base URL, key, model value, voice and look.
- **A session** is one conversation as the host files it. The `/sessions` command lists and adopts
  one.
- **Phone tools** is the optional surface through which the mind acts on the phone, over the
  Model Context Protocol (MCP), the protocol the gateway uses to reach tools.
- **An approval** is the gateway's request for consent before a consequential action.
- **The alpha gate** is the one switch that opens unfinished feature sets.

## What you need

To run the pieces this document describes you need a Hermes gateway with its endpoint and
`API_SERVER_KEY`, an Android phone (API 24 or later), the three required on-device models
(Silero VAD, Supertonic, Whisper base.en), and a network route between the phone and the gateway.
A tailnet is a private network run by a mesh VPN; Tailscale is the one whose addresses the
phone's servers bind to. For Presence you also need the Gemma 4 E2B model and a phone with 8 GB
of memory. For phone tools and file offers you need Tailscale on the phone, because those servers
bind only to a tailnet address.

---

## 1. Audio in, and hearing — on the phone

The microphone feeds Silero VAD through sherpa-onnx. `BargeGate` decides whether speech during
playback is a real interruption: level and VAD agreement must both hold for a sustained window,
with a level-only escape for the case where the echo canceller starves the VAD.

Recognition is on-device through `OfflineStt`. The default is Whisper base.en; the catalogue also
offers Whisper tiny and small (English and multilingual), NVIDIA Parakeet-TDT 0.6B v2 and, as a
separate opt-in arm, NVIDIA Nemotron streaming 0.6B. Recognition is batch: it sees a finished
utterance. `SttWindows` splits anything past 25 seconds. A streaming partial may end a capture
early only on a genuine finished-sentence pause, and may stand in for the text only when it
covered the whole utterance (`EarlyStartRule`). Every transcript passes `TranscriptValidator` —
Whisper's non-speech tags, decoder loops and prior phrases on near-silence are rejected — before
it becomes a turn. The words that were sent appear briefly as the dim "heard" line
(`HeardLine`).

Optional **continuous listening** keeps the microphone and recogniser live for the whole call.
Partials are read every 100 ms and `SemanticEndpoint` judges the end of the turn from silence,
partial stability and whether the words can end a sentence. With continuous listening off, the
normal 800 ms silence endpoint runs.

The optional **remote recognition** backend (`RemoteStt`) sends the utterance to an
OpenAI-compatible transcription URL you configure instead of using the on-device model.

**It cannot** recognise without a downloaded model; the default model is English. It cannot judge
a semantic endpoint without continuous listening. It cannot send microphone audio anywhere unless
you configure the remote backend.

## 2. The turn route — phone to gateway

A turn has two possible HTTP routes, chosen in `core/TurnRoute.kt`:

- **`POST /v1/runs` — the default.** The admission answers `202 {run_id}`. The app reads
  `GET /v1/runs/{run_id}/events`; the run's sequence-numbered backlog lets a dropped events socket
  reattach from its last `seq`. A barge-in sends `POST /v1/runs/{run_id}/stop`. An approval is
  answered with `POST /v1/runs/{run_id}/approval`.
- **`POST /v1/responses` — the fallback.** Only an admission that answers **404** falls back to
  this route, once. The answer is remembered per profile (`RouteChoice.kt`). The request streams
  an event stream (SSE) in the OpenAI Responses shape and is chained with `previous_response_id`.
  Any other status, or a transport failure, is a real error and does not fall back.

`core/Stream.kt` reduces both wire vocabularies to one `StreamState`: text deltas become speech,
`tool.started` / `tool.completed` drive the being's tool motifs, and `phase: commentary` items are
shown as progress but never spoken as the answer.

**Conversation identity.** On `/v1/runs` the conversation is the body `session_id`, which the
client owns and rotates on `/new`, a new call and a profile switch. A session adopted from the
host replaces that id with the host's. On the fallback route the conversation is
`previous_response_id`. `X-Hermes-Session-Key` is the long-term-memory scope, not the transcript.

**What a voice turn tells the mind.** The caller's words go as `input`, byte-exact. The voice
register, what the soul has already said, and the phone's state ride the request's ephemeral
`instructions`, which the gateway appends to the system prompt for that request and does not
persist. A per-request `model_options.reasoning` defaults to low.

**Approvals.** The runs route registers the gateway's approval notifier and delivers
`approval.request` with the command (already redacted), its description, the choices the gateway
offers and a request id. `core/Approval.kt` renders exactly those choices — `deny` is always
present — answers once, and lets a timeout resolve to no. The responses route never registers the
notifier, so no approval reaches the phone on the fallback.

**It cannot** carry approvals on the fallback route. It cannot reattach a dropped responses-route
stream: the runs route is the one with the sequence-numbered backlog. It cannot fall back on any
answer other than a 404.

## 3. The mind — on your gateway

Hermes owns the reasoning, the tools, the memory, the skills and the identity. Vox sends text and
gets events; it does not inspect or reproduce the agent's state.

The app reads four gateway surfaces:

| Surface | What the app uses it for |
|---|---|
| `GET /v1/models` | Verifying the connection and adopting the agent name the host answers with |
| `GET /v1/health` | The connection card |
| `/api/model/options` | The gateway's model catalogue |
| `GET /api/sessions` and `GET /api/sessions/{id}/messages` | Listing the host's conversations and reading one transcript |

The profile list comes from the host too: the app asks in conversation and proves names with
`/v1/models`, and uses `GET /v1/profiles` when the host serves it.

**It cannot** reason, call tools or keep memory for the agent. It cannot see the agent's internal
state beyond the events the gateway streams.

## 4. Speech out — on the phone

Supertonic (ten voices, 44.1 kHz) is the default voice engine; Piper is the lighter alternative
and the system voice is the fallback. The voice leg acquires one engine at a time
(`TtsEngineHost`). The reply is cut into clause-sized chunks from the live stream
(`StreamTextChunker`) and handed to the engine as it arrives; a barge-in cuts the audio and stops
the chunk being played. Delivery follows the caller's mood as Presence heard it (`VoiceMood`:
speed and pause length). `SpeechCursor` locks the on-screen words to what has actually been
spoken.

A barge-in runs one cut path: it stops audio, cancels the turn, releases the turn gate, and on the
runs route stops the run on the gateway. The interrupting words are kept from their onset with
pre-roll and become the next turn (`BargeCarry`).

**It cannot** speak and listen at the same time. It cannot change the voice's emotion: mood moves
only speed and pauses.

## 5. The being and the words — on the phone

The being is drawn with OpenGL ES 3 on its own thread (`BeingThread`, `BeingRenderer`):
6,000 stateless particles whose positions are computed in the vertex shader, 27 shapes, staggered
morphs between any two, two-level bloom, a filmic tone curve and an authored light-mode ground.
It targets 60 fps, pauses when not visible, and steps quality down — fewer points, a smaller
buffer, one bloom level — if a device cannot keep up.

`AvatarView` maps state and tool to shape. `CrawlView` renders the reply and holds the scrollback;
both sit on true black in the dark theme and on paper in the light theme.

**It cannot** show what the agent did not report. State narration never enters the scrollback, and
the figure is an indicator, not a control surface.

## 6. Presence — the optional layer, on the phone

Presence runs only when the alpha gate allows it *and* its own switch is on. It adds Gemma 4 E2B
on LiteRT-LM, GPU first with a CPU fallback. It is expression, never authority:

- **VOX.md.** The agent authors a distilled voice file on the gateway. The phone mirrors it
  read-only, one document per profile (`VoxMirror`), validates it, and never writes identity back.
- **The beat, not a gate.** Every voice turn is submitted to the mind at once. The soul renders
  two to six words to fill the wait, or stays silent. The beat is dropped if the mind's first
  token gets there first.
- **The soul hears.** Gemma's audio encoder takes the caller's last few seconds of audio alongside
  the transcript and tags their tone. The audio never leaves the phone.
- **The ladder.** Under 900 ms: silence. From 900 ms: one recorded nonverbal cue. No words before
  4 seconds; after that, one in-character line per window, density-capped.
- **One output.** `ErArbiter` gives strict priority: P0 stop, P1 mind, P2 critical soul, P3 filler.
- **Safety stays deterministic.** Only backchannel-never-cancels and barge-cancels are enumerated
  rules; the 2B model is never in the abort path.
- **Read-only context.** Presence reads the phone's action registry through `PhoneReadout`, which
  exposes no mutating call, and device facts for the turn's context. It never acts on the phone.

**It cannot** answer a question or take a turn from the mind. It cannot call a tool or change the
phone. It needs the 2.6 GB model; below a phone with 8 GB of memory it may fail to load, and it
holds about 3 GB while a call is live.

## 7. The phone tool surface — the mind, through MCP

When the **Phone tools** set is allowed, `PhoneMcpServer` serves one MCP tool, `phone`, at
`http://<tailnet-address>:8765/mcp`. The server binds only to the tailnet address — never
`0.0.0.0`, never the LAN, never cellular — and only while a call is live or during the one-button
setup's open window. Every request needs a bearer credential: the phone's own 128-bit token, or
the API key of the active connection.

`PhoneGrants` defines thirteen capability groups once. Each is **Off**, **Ask** or **Allow**, and
all start Off. The tool's advertised `enum` is built from the live grants on every `tools/list`
and `tools/call`, so revocation lands on the next call. Off means the actions are absent from the
schema. Ask raises the phone's own card, and the action runs only when the agent calls again with
that approval id and the same arguments. Allow runs without a card. Every call returns one JSON
result with `ok` and the facts of what happened; a refusal carries a reason, not a transport
error. A timeout is recorded as uncertain, not as a refusal of the work.

`LocalActions` executes the action through `PhoneEngine`, which owns the ids, the registry and the
undo table. Presence and the mind read the same registry, so the phone cannot contradict itself.
`docs/MCP-PHONE.md` is the operational contract; `docs/MCP-SURFACE.md` is the action-by-action
one.

**It cannot** act when no call or setup window is open. It cannot be reached off the tailnet. It
cannot run an action in a group left Off, and it never advertises an action the phone does not
implement. The phone does not parse the caller's words to choose an action: every voice turn goes
to the mind, and the mind decides.

## 8. Profiles, sessions and the alpha gate

A profile is the whole connection plus the presentation that belongs to it: base URL, its own
encrypted key, model and provider, agent name, session scope, voice and look (`Profiles.kt`). The
list comes from the host. Unknown names are marked unavailable, never deleted. The app is a
picker, not an editor: nothing in Vox creates or changes a profile on the host. Switching writes
the chosen connection, rebuilds the session and starts a fresh conversation; a live call ends.

A session is a conversation the host already files. `/sessions` reads `GET /api/sessions` at the
host root, lists what the key can see, and adopts one by setting the `session_id` on subsequent
runs. The transcript stays the host's; another surface may be writing it while the phone reads it.
The phone keeps a bounded in-memory scrollback, not a stored history.

The **alpha gate** (`AlphaGate`) has one master switch, off on a fresh install. Six sets are
selected individually: **Presence**, **Continuous listening**, **Turn-taking**, **Phone tools**,
**Wake word** and **Digital assistant**. A set runs only when the master is on and that set is
selected. Continuous listening also requires Presence, because it is how the being hears; Phone
tools is independent.

The master switch is the row immediately above **the alpha block** at the end of Settings. The
block has four rows — Presence, Phone tools, Wake word, Digital assistant (`AlphaBlock`).
Continuous listening and Turn-taking are switches inside Hearing, gated by their own sets. With
the master off every alpha surface is dimmed and unreachable. Quiet calls is not a set: it is its
own switch in Hearing, off by default. Sessions and profile switching are outside the gate and
available on every install.

**It cannot** create a profile or edit one on the host. It cannot run an alpha set with the
master off, and it cannot run continuous listening without Presence.

## 9. Files — share with agent

"Share with agent" never uploads to a service. The phone stages the file, hashes it, and serves it
once on the tailnet (`FileOffer`, `FileStage`, `FileServer`): a 100 MB cap, a five-minute expiry,
a one-time capability URL, and `410 Gone` after the first transfer. Text under 8,000 bytes travels
inline instead. The agent fetches and verifies the hash. Nothing is sent until the SEND on the
confirmation card.

**It cannot** offer a file when Tailscale is down; it offers the inline path for small text
instead. It cannot serve the same file twice.

## 10. Lifecycle and storage

`VoiceService` is a foreground service of type `microphone` — an Android service that keeps
running with a notification — and it keeps the process and the call alive in the background. The
phone's MCP listener lives and dies with the call, plus the setup window. Models download through
a foreground `dataSync` service (`ModelDownloadService`), resume over HTTP `Range`, wait out
network loss, and are sha256-verified and path-traversal guarded before install.

The gateway key is user-entered and encrypted with a key in the Android Keystore
(`SecureStore`). Backups are disabled. A release build fails if a key is ever baked into the
build. Cleartext is refused for every host except loopback and a tailnet MagicDNS name, and the
app refuses even that while the tunnel is down (`core/CleartextPolicy.kt`). Logs are local, and
transcript logging is off by default.

**It cannot** keep a call's microphone service alive without the foreground notification. It
cannot guarantee encrypted key storage on a device whose Keystore refuses to encrypt: that value
is kept locally in plain text, logged, and retried at the next start.

---

## What talks to what

```
phone                                                        host
─────                                                        ────
mic → Silero VAD → recognition ── text turn ───────────────► Hermes agent (the mind)
                                   instructions, scope         ├─ reasoning
                                   ◄── text, tool events ──────┤  tools
                                                               ├─ memory
speech ← TTS ← reply ────────────── stream ───────────────────┤  skills
                                                               └─ identity (SOUL.md → VOX.md)
being ← state and tools ─────────── events
words ← SpeechCursor ────────────── text

Presence (Gemma 4 E2B) ← VOX.md mirror (read-only) ◄─────────── agent authors VOX.md
Presence → beat, asides → speech                              (never written back)

mind ── MCP tools/call ──► phone :8765/mcp (tailnet, call/setup only)
                          └─► LocalActions → PhoneEngine → the action, one result

mind ── HTTP GET ─────────► phone file offer (tailnet, one transfer, after SEND)
```

## The boundary

| On the phone | On the host | Never sent anywhere |
|---|---|---|
| Microphone capture, VAD, recognition (default), speech synthesis, Presence model, the being, the reply text on screen | Reasoning, tools, long-term memory, skills, identity, the agent-authored VOX.md, the transcript record | Microphone audio (unless the remote recognition backend is configured), Presence's tone audio, wake-word enrolment recordings, local logs, the Keystore key |
| Preferences, encrypted gateway key, model files, a staged share copy, a read-only VOX.md mirror | The authoritative VOX.md, profile list, session list, the approval request and its recorded answer | The bearer key beyond the gateway you configured |

The app's outbound traffic is: turns and their instructions to the gateway, the gateway reads
listed above, the model downloads you start, and — only if you configure that backend — the audio
for a turn to your remote recognition endpoint. A shared file is served on the tailnet only after
you confirm, and only to the agent that fetches its one-time URL. Its inbound listeners are the
phone-tools MCP server and the one-shot file server, both on the tailnet address and both closed
when their window ends.

## What it cannot do

- It cannot work without a Hermes gateway you run.
- It cannot reason, call tools or keep long-term memory on the phone.
- It cannot carry approvals on the `/v1/responses` fallback route.
- It cannot reattach a dropped `/v1/responses` stream.
- It cannot listen and speak at once.
- It cannot run Presence without its model and memory bar.
- It cannot act on the phone without the Phone tools set, a setup with the agent and a grant.
- It cannot keep the phone-tools listener up when no call or setup window is open.
- It cannot reach a phone that is not on the tailnet.
- It cannot create or edit profiles.
- It cannot store a conversation history on the phone.
- It cannot change the voice's emotion; mood moves speed and pauses only.

## Where to read more

- [docs/INDEX.md](INDEX.md) — every living document, and the records kept separately.
- [docs/RUNS-ROUTE.md](RUNS-ROUTE.md) — the default turn route and the checks a device must pass.
- [docs/KOTLIN-CORE.md](KOTLIN-CORE.md) — the `com.hermesvox.core` connector.
- [docs/PRESENCE.md](PRESENCE.md) — Presence, VOX.md and the beat.
- [docs/MCP-PHONE.md](MCP-PHONE.md) and [docs/MCP-SURFACE.md](MCP-SURFACE.md) — the phone tool.
- [docs/MODELS-SCREEN.md](MODELS-SCREEN.md) — the model catalogue.
- [PRIVACY.md](../PRIVACY.md) and [SECURITY.md](../SECURITY.md) — data handling and the threat
  model.
