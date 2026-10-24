---
name: hermes-vox-app
description: "Support Hermes Vox, the Android voice client for Hermes. Use when someone asks what the app runs, where a setting lives, what a log line means, or why a call, transcript, voice, model download or Presence layer behaves unexpectedly."
version: 1.0.0
author: Hermes Vox maintainers
license: GPL-3.0
---

# Hermes Vox — what the app actually runs

This skill describes the shipping Android client in this repository: what runs where,
every setting a user can see, every `event=` line the app writes, the model catalogue, and
the failure modes the field has actually produced. It is written so support can be given
from a log rather than from a guess.

**How to read the references.** Every factual claim ends with `(path:line token)`: the file,
the line, and a literal token that must appear on that line. `scripts/check-skill-claims.py`
verifies all of them on every gate run, so a reference that no longer holds fails the build.
If you are reading this and a line number looks odd, the checker is authoritative — run
`python3 scripts/check-skill-claims.py` and fix or delete the claim.

**One rule for answering.** Never tell a user a feature exists because this file mentions a
setting; check the row's reference. The most useful answers here are the ones that say what
is absent (section 7) or a preview (the wake word).

---

## 1. What it is, in four lines

1. **Hermes Vox is an Android voice client for Hermes, a self-hosted agent you run yourself.**
   There is no hosted service and nothing to sign up for (README.md:29 `Bring your own gateway`).
2. **By default, hearing and speaking happen on the phone.** Silero VAD, Whisper/Parakeet/Nemotron
   STT and Supertonic/Piper TTS run on-device, fully offline (docs/ARCHITECTURE.md:32 `Speech processing is on-device`);
   the two opt-in exceptions are in the table below.
3. **The thinking happens on your gateway.** The app sends the turn's text to
   `/v1/responses` and streams the reply back; tools, memory and context stay server-side
   (docs/ARCHITECTURE.md:61 `POST /v1/responses`).
4. **By default nothing leaves the phone except that turn** — text to your own gateway, plus
   model files fetched over HTTPS when you install them (PRIVACY.md:70 `your server`). Two
   opt-in paths send audio elsewhere: the remote-STT backend and the platform (Google)
   recogniser; both are described in section 2 and must be named when a user asks about
   privacy.

**Presence** is the optional layer that gives the agent a body and a voice on the phone: an
on-device Gemma 4 E2B model, the mirrored `VOX.md`, the being and its asides
(docs/DECISIONS.md:254 `D16`). It is an expression layer, never a second brain
(docs/ARCHITECTURE.md:107 `never a second`).

---

## 2. The pieces and where they run

| # | Leg | Runs | What it is | Reference |
|---|---|---|---|---|
| 1 | Microphone | on-device | `AudioRecord`, 16 kHz mono; `VOICE_COMMUNICATION` by default so the platform AEC/NS applies | (android/app/src/main/java/com/hermesvox/VoiceController.kt:583 `MediaRecorder.AudioSource.VOICE_COMMUNICATION`) |
| 2 | VAD | on-device | Silero VAD via sherpa-onnx decides speech vs silence and gates the open line | (android/app/src/main/java/com/hermesvox/VoiceController.kt:457 `SileroVadGate`) |
| 3 | STT | on-device by default | sherpa-onnx offline recogniser (Whisper base.en default) — batch: it sees a finished utterance | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:137 `DEFAULT_STT_MODEL`) |
| 3a | STT alternative | **remote** | optional OpenAI-compatible server; audio is POSTed to the URL the user typed | (android/app/src/main/java/com/hermesvox/RemoteStt.kt:9 `audio never leaves the`) |
| 3b | STT alternative | **remote (platform)** | Android `SpeechRecognizer`; the device's provider may process audio off-device — do not call it local | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1265 `createSpeechRecognizer`) |
| 4 | The mind | **remote** | your Hermes gateway: `POST /v1/responses` with `stream: true`, chained by `previous_response_id` | (android/app/src/main/java/com/hermesvox/core/HermesSession.kt:22 `previous_response_id`) |
| 5 | TTS | on-device | Supertonic (recommended), Piper, or the System voice as fallback | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:123 `defaultTts`) |
| 6 | Presence (optional) | on-device | Gemma 4 E2B via LiteRT-LM on the GPU; speaks asides in the agent's mirrored voice | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:105 `gemma-e2b`) |
| 7 | Avatar + words | on-device | GPU-drawn being and the speech-locked crawl | (docs/ARCHITECTURE.md:145 `6,000 stateless particles`) |

Only leg 4 is a network peer. Barge-in cancels the stream rather than leaving it running
(docs/ARCHITECTURE.md:67 `closing the stream aborts the generation`).

**What the agent is doing** is the pill under the agent's name — the state line, up for the
whole call and mirrored in the call notification. While a tool runs it names the tool in plain
words and what it acts on ("Searching the web · trail conditions"), adds "still working, N s"
on a long step (android/app/src/main/java/com/hermesvox/Operations.kt:116 `fun running`), and
on a step that reported an error says what failed and why, held until the next turn
(android/app/src/main/java/com/hermesvox/Operations.kt:101 `fun failure`). It is the agent's
own report of the step, not a check that anything changed in the world.

### Modes, stated honestly

There is **one normal operation**: a hands-free open line with VAD, barge-in and a turn to
your gateway (android/app/src/main/java/com/hermesvox/OnboardingActivity.kt:23 `hands-free, always listening`).
Presence is a **layer on that**, not a second mode (docs/DECISIONS.md:254 `presence is a layer`).

What the build actually does, as opposed to the direction D16 records:

- The layer is gated on the `voice_mode` pref being `enhanced`
  (android/app/src/main/java/com/hermesvox/MainActivity.kt:2087 `ModelCatalog.MODE_ENHANCED`).
- That pref defaults to `realtime`
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:125 `ModelCatalog.MODE_REALTIME`), and the
  Settings picker still calls the two values "Realtime" and "Enhanced Realtime (alpha)"
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:949 `Enhanced Realtime (alpha)`).
- Inside the layer, the Presence switch (`er_presence`) defaults to on
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:280 `"er_presence", true`).

So: a fresh install runs normal operation; Presence is available but off until the mode is
switched. D16's replacement — one boolean `presence_enabled`, default on, no mode label
(docs/DECISIONS.md:262 `presence_enabled`) — is the recorded direction, **not** what this
build ships. Do not tell a user Presence is on by default.

---

## 3. The settings map

All prefs live in one `SharedPreferences` file, `hv`
(android/app/src/main/java/com/hermesvox/SettingsActivity.kt:24 `getSharedPreferences("hv"`).
"Default" is what an untouched install reads. Settings is a home list of groups with nested
sections (android/app/src/main/res/layout/activity_settings.xml:49 `grp_list`).

**0.9.1 renamed the sections by what the user changes (#224):** *Mic & Barge-in* is now
**Hearing**, *Spoken replies* is **Voice**, *Visuals* is **Being**; the Presence rows moved out of
*Entity & Connection* into their own **Presence** section, and the sound cues moved into **Being**.
Every row carries a one-line statement of what it changes
(android/app/src/main/java/com/hermesvox/SettingsHints.kt:1 `package com.hermesvox`). A user on an
older build still sees the old names.

### Voice mode (top-level row)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Voice mode | `voice_mode` | `realtime` | Realtime, or Enhanced Realtime which adds the on-device Presence layer | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:185 `MODE_REALTIME`) |

### Entity & Connection

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Profiles (tap the agent's name on the main screen) | `profiles`, `profile_active` | the current connection, saved on first open | Saved connections — name, address (`https://host/p/<profile>` for a Hermes profile), key, model, scope — one active at a time; the header and notification name the active one once there are two | (android/app/src/main/java/com/hermesvox/MainActivity.kt:423 `profiles.showSwitcher()`) |
| Test entity connection | — | — | One probe of `/v1/models` (+ optionally `/v1/responses`), reported as ok / warming / key / FAILED | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:983 `testConnectionAsync`) |
| Entity endpoint | `url` | "" | The gateway base URL; also decides whether the app is "configured" | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:159 `prefs.getString("url", "")`) |
| Teach your agent this app | (action) | — | Asks the agent to install this skill on its gateway, or copies the `hermes skills install …` command (with `-p <profile>` for a profile address) | (android/app/src/main/java/com/hermesvox/AppSkill.kt:36 `fun command`) |
| API key | `key` | "" | Bearer credential, Keystore-encrypted at rest | (android/app/src/main/java/com/hermesvox/core/Entity.kt:52 `Bearer`) |
| Model | `model` | `hermes-agent` | The model route sent on every request | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:221 `"hermes-agent"`) |
| Entity scope | `session_scope` | "" (blank) | Optional `X-Hermes-Session-Key`; blank means "declare no identity" | (android/app/src/main/java/com/hermesvox/SessionScope.kt:33 `session_scope`) |
| New conversation | (action) | — | Clears context and ends any live call | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:198 `New conversation`) |

### Hearing (was "Mic & Barge-in")

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Barge-in | `duplex` | true | Talking over the entity interrupts it | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:559 `"duplex", true`) |
| Noise & echo cancel (AEC) | `mic_aec` | true | Uses `VOICE_COMMUNICATION` capture (hardware AEC/NS) instead of raw MIC | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1137 `set_mic_aec`) |
| Extra noise suppression | `ns_extra` | true | Adds a platform `NoiseSuppressor` on the record session | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1138 `set_ns_extra`) |
| Streaming partial transcripts | `partial_stt` | true | Runs partial decoding to end a turn early on a finished-sounding pause | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1139 `set_partial_stt`) |
| Voice-call echo routing | `tts_voice_usage` | **false** | On: playback uses the voice-communication usage so the platform AEC gets an echo reference; off (the field-A/B default) plays on the media stream and measured louder | (android/app/src/main/java/com/hermesvox/SherpaTts.kt:161 `kill-switch`) |
| Mic sensitivity (VAD) | `vad_threshold` | 0.50 | How sure Silero must be before a sound counts as speech | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1147 `"vad_threshold"`) |
| Pause to end your turn | `vad_silence_ms` | 800 ms | Silence that closes an utterance | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1149 `"vad_silence_ms"`) |
| Early-start pause | `vad_early_silence_ms` | 450 ms | How final a partial must sound before the turn ends early | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1151 `"vad_early_silence_ms"`) |
| Min speech before a turn | `vad_min_speech_ms` | 300 ms | Shortest speech that becomes a turn | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1153 `"vad_min_speech_ms"`) |
| Max speech per turn | `vad_max_ms` | 15000 ms | Past this, the segment only closes on a real pause — a continuous speaker is not chopped | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1155 `"vad_max_ms"`) |
| Barge sensitivity | `barge_rms_min` | 0.10 | The ceiling for the playback barge floor; the floor itself is measured from the reply's echo | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1157 `"barge_rms_min"`) |
| Barge grace | `barge_grace_ms` | 500 ms | Ignore interruption attempts this soon after speech starts | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1159 `"barge_grace_ms"`) |
| Level-only barge | `barge_level_only_ms` | 200 ms | Sustained loud voice cuts the entity even when VAD misses it through echo; 0 disables | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1161 `"barge_level_only_ms"`) |

### Transcription (STT)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Speech-to-text backend | `stt_backend` | `on-device` | `on-device`, `platform` (Google), or `remote` (a server you run) | (android/app/src/main/java/com/hermesvox/VoiceController.kt:495 `BACKEND_ONDEVICE`) |
| STT model | `stt_model` | `whisper-base` | Which offline recogniser runs | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:137 `whisper-base`) |
| (streaming arm) | `stt_stream_model` | unset | Selecting a streaming model stores it here and leaves `stt_model` as the fallback | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:148 `stt_stream_model`) |
| Voice CPU threads | `voice_threads` | Auto (0) | Thread override for STT and TTS; applies on the next model load | (android/app/src/main/java/com/hermesvox/VoxThreads.kt:41 `fun stt`) |
| Remote STT URL | `stt_remote_url` | "" | The server audio is POSTed to; blank means the remote leg is unavailable | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:874 `KEY_STT_REMOTE_URL`) |
| Remote STT model | `stt_remote_model` | `whisper` | The model name sent to that server | (android/app/src/main/java/com/hermesvox/RemoteStt.kt:14 `DEFAULT_REMOTE_MODEL`) |
| Remote STT key | `stt_remote_key` | "" | Encrypted bearer key for that server | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:876 `KEY_STT_REMOTE_KEY`) |
| Test STT server connection | (action) | — | Probes `/v1/models` then `/api/v1/models` and reports ok/FAILED | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:911 `event=stt-remote-test`) |

### Voice (TTS; was "Spoken replies")

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Text-to-speech | `tts` | unset → best installed (`supertonic` → `piper` → `system`) | Which engine speaks | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:123 `fun defaultTts`) |
| Delivery | `voice` | `system` (shown as "Natural") | Synthesis register: Natural / Bright / Deep | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1481 `"voice", "system"`) |
| Speaker | `tts_speaker_<model>` | Supertonic F2 (id 1), Piper 0 | The engine's built-in voice; per engine | (android/app/src/main/java/com/hermesvox/VoiceSpeakers.kt:27 `defaultId`) |
| Speak responses | `speak_responses` | true | Whether replies are voiced at all | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:562 `"speak_responses", true`) |

### Presence (Enhanced Realtime only)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Soul narration | `er_presence` | true | Presence's spoken asides (mm, breath, fillers); off keeps the layer wired but quiet | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:280 `"er_presence", true`) |
| Semantic barge-in | `er_semantic_barge` | true | Lets "take your time" keep the mind working instead of cancelling it | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:286 `"er_semantic_barge", true`) |
| The soul hears your tone | `er_soul_hears` | true | Gemma's audio encoder reads the caller's tone; audio never leaves the phone | (android/app/src/main/java/com/hermesvox/SoulAudio.kt:17 `"er_soul_hears"`) |
| Narration voice | `er_presence_voice` | `sounds` | `silent` (motion only), `sounds` (mm/breath clips), `spoken` (sentence fillers) | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:310 `"er_presence_voice", "sounds"`) |
| Presence while working | `er_presence_density` | `conversational` | How often Presence speaks during a long mind run: quiet 30 s / conversational 18 s / chatty 10 s intervals, with caps | (android/app/src/main/java/com/hermesvox/PresenceCadence.kt:32 `Density.CONVERSATIONAL`) |
| Echo guard | `er_echo_skip_ms` | 700 ms | Silences barge capture for this long after Presence speaks, so the phone does not hear itself | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:348 `"er_echo_skip_ms", 700f`) |
| Resync VOX.md | (file, not pref) | not mirrored | Pulls the agent-authored voice-export and mirrors it read-only | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:431 `event=vox-resync`) |

### Wake word (preview)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Wake word | (no pref) | "0 of 8 takes · not listening" | Records enrolment takes into an encrypted store; nothing is trained and nothing listens | (android/app/src/main/res/layout/activity_settings.xml:202 `Wake word (preview)`) |

The row's subtitle is computed from the take count and the words "not listening"
(android/app/src/main/java/com/hermesvox/SettingsActivity.kt:822 `not listening`).

### Appearance & Layout, Being, About

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Theme | `theme` | `system` | System / Dark / Light | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:824 `label("theme", "system")`) |
| Screen layout | `layout_mode` | `presence` | "Being" (avatar fills the screen) or "Transcript" | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:824 `layout_mode`) |
| Keep screen on during calls | `keep_screen_on` | true | A window flag while a call is open; no wake lock | (android/app/src/main/java/com/hermesvox/ScreenAwake.kt:8 `KEEP_SCREEN_ON_DEFAULT = true`) |
| Visual category | `visual_category` | `lumen` | Re-colours/re-lights/re-paces the being in every state | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:91 `DEFAULT = "lumen"`) |
| Cycle all categories | `visual_cycle_all` | false | Rotates through every visual family while idle | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:77 `DEFAULT_CYCLE_ALL = false`) |
| Energy | `visual_energy` | 1.0× | Motion energy multiplier | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:92 `DEFAULT_ENERGY = 1f`) |
| Glow / bloom | `visual_glow` | 1.0× | Bloom multiplier | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:93 `DEFAULT_GLOW = 1f`) |
| Density | `visual_density` | 100 % | How many of the particles are drawn (30–100 %) | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:100 `KEY_DENSITY`) |
| Speed | `visual_speed` | 1.0× | How fast the being's own clock runs — drift, shimmer, breath | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:104 `KEY_SPEED`) |
| Trail | `visual_trail` | the category's | Light trails behind each particle; 0 keeps the category's own | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:108 `KEY_TRAIL`) |
| Hue | `visual_hue` | 0° | Turns the whole palette around the colour wheel | (android/app/src/main/java/com/hermesvox/VisualStyle.kt:112 `KEY_HUE`) |
| Speaking / Listening / Thinking shape | `visual_shape_speaking` / `_listening` / `_thinking` | `soundwave` / `eye` / `radar` | Which shape fires in each active state | (android/app/src/main/java/com/hermesvox/AvatarView.kt:91 `DEFAULT_SHAPE_SPEAKING = "soundwave"`) |
| Presence shape / theme | `particles_theme` | `aura` | The idle shape family | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1400 `"particles_theme", "aura"`) |
| Cycle themes while idle | `particles_cycle` | true | Rotates the idle shape | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1406 `"particles_cycle", true`) |
| Sound cues | `sound_cues` | true | The being's own thinking/listening/error cues | (android/app/src/main/java/com/hermesvox/CuePolicy.kt:55 `DEFAULT_ENABLED = true`) |
| Cue level | `sound_cue_level` | 70 % | Relative to media volume | (android/app/src/main/java/com/hermesvox/CuePolicy.kt:57 `DEFAULT_LEVEL = 70`) |
| Cues play | `sound_cue_scope` | `presence_off` | **Only when Presence is off** (default) or **Always**. While Presence speaks its asides, cues would double the same channel, so by default they are quiet then — "I hear no cues" with Presence on is this setting, logged as `event=cue … reason=presence` | (android/app/src/main/java/com/hermesvox/CuePolicy.kt:47 `DEFAULT_SCOPE = SCOPE_PRESENCE_OFF`) |
| Soft ping while the agent works | `sound_cue_ping` | true | A quiet single note every 6 s while the agent is working, stopping when the answer arrives | (android/app/src/main/java/com/hermesvox/CuePolicy.kt:50 `DEFAULT_PING = true`) |
| Check in after a silent call | `sound_cue_idle_s` | 120 | After this many seconds of a call with no input and no output, one rising two-note cue (`event=cue-idle`); never / 1 / 2 / 5 min | (android/app/src/main/java/com/hermesvox/CuePolicy.kt:53 `DEFAULT_IDLE_S = 120`) |
| Cues when the ringer is silent | `sound_cues_silent` | false | Opt-in: cues still play (or tap) when the ringer is silent | (android/app/src/main/java/com/hermesvox/CuePolicy.kt:56 `DEFAULT_SILENT = false`) |
| Developer console | `dev_console` | false | Shows the raw SSE drawer on the main screen | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:565 `"dev_console", false`) |
| Log spoken transcript | `log_transcripts` | false | Prints what was heard/decided into logs — privacy sensitive | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:568 `"log_transcripts", false`) |
| Verbose file log | `debug_log` | false | `dd()` probes reach the log file; also disables log rotation | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:571 `"debug_log", false`) |

### Commands (the `/` button on the main screen)

Grouped by what they do — Conversation, Connection, The agent, Help — each row stating its effect;
a command that runs in place shows *queued / running… / done / failed* on its row instead of a toast
(android/app/src/main/java/com/hermesvox/CommandCatalog.kt:75 `RunState`).

| Command | What it does | Reference |
|---|---|---|
| `/new` | A fresh conversation; the agent forgets this one's thread. Ends and re-dials a live call | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:42 `Start a fresh conversation`) |
| `/clear` | (also `/reset`) Clears the screen and starts over; the agent keeps its copy. Re-dials a live call | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:45 `Clear the screen and start over`) |
| `/compress` | (also `/compact`) Asks the agent to compact a long conversation | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:48 `compact a long conversation`) |
| `/reconnect` | Reaches the gateway again and re-dials a live call if it answered | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:51 `Reach the gateway again`) |
| `/health` | (also `/status`) Whether the agent is up, and its version | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:54 `Show whether the agent is up`) |
| `/models` | Choose which model the agent thinks with | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:57 `Choose which model the agent thinks with`) |
| `/help` | The list, with these same lines | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt:60 `What each command does`) |
The **Being** section opens with a live preview: the same being the main screen draws, from the
same prefs, walking through resting, listening, working and speaking — so a look is judged in
Settings, not by leaving it (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1313 `previewStates`).

**How the being shows a command.** Each command's kind maps to one of six motions — sweep
(inspect), pulse (choose), converge (compress), scatter (start over), ring (reconnect), settle
(help) — held while it runs; it ends in a bloom when it worked and in the hourglass, in the
waiting colour, when it failed (android/app/src/main/java/com/hermesvox/CommandMotion.kt:43 `fun verbFor`).
The log says which: `event=command-motion` (android/app/src/main/java/com/hermesvox/MainActivity.kt:1812 `event=command-motion`).

**Finding a command.** The commands screen has a search box that matches what a person wants
("it's slow", "can't reach it", "start over"), not only command names
(android/app/src/main/java/com/hermesvox/CommandCatalog.kt:84 `fun search`), and leads with what fits the moment —
*reconnect* and *health* after a failed turn, *compress* in a long conversation
(android/app/src/main/java/com/hermesvox/CommandCatalog.kt:114 `fun suggest`). A failed turn's line on the main screen
ends "tap to reconnect" and does it; on a caller's first three calls, twenty silent seconds bring
the hint "Ask anything — or say "what can you do?"" (android/app/src/main/java/com/hermesvox/Discovery.kt:17 `STUCK_AFTER_MS`).

### Voice models

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Model source | `model_source` | the k2-fsa upstream release URL | Where model archives are fetched from; scheme-less input falls back to the default | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:47 `DEFAULT_SOURCE`) |
| Licence acceptance | `model_licence_accepted_<id>` | none | Stores the licence name the user accepted; the name changing asks again | (android/app/src/main/java/com/hermesvox/ModelLicences.kt:13 `model_licence_accepted_`) |

**Profiles, said plainly.** A profile is saved only after the gateway answers `/v1/models`
with its key: "Not served" means that gateway has no such profile, "Key rejected" means each
profile needs its own key (android/app/src/main/java/com/hermesvox/Profiles.kt:69 `NOT_SERVED`).
Switching ends a live call and starts a new conversation — the old conversation belongs to the
old profile (android/app/src/main/java/com/hermesvox/MainActivity.kt:1453 `resetConversation`). The
app cannot list a gateway's profiles; they are added by hand.

### Prefs that have no Settings screen

Do not send a user hunting for these.

- `vad_max_hard_ms` — hard utterance ceiling, read with a default of 0 = disabled
  (android/app/src/main/java/com/hermesvox/EndpointRule.kt:31 `DEFAULT_HARD_MS = 0`). This tree
  never writes it, so the ceiling is always off; do not offer it as an adjustable setting.
- `stt_language` — decode language for a **multilingual** Whisper; the shipped `.en` models
  ignore it and always decode English (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:165 `stt_language`).
  This tree never writes it either, so multilingual models always auto-detect.
- `provider` — the per-request provider override, written when a model is picked from the
  gateway's model list (android/app/src/main/java/com/hermesvox/MainActivity.kt:1878 `putString("provider"`).
- `agent_name` — the label above the being; read with the model id as its fallback
  (android/app/src/main/java/com/hermesvox/MainActivity.kt:1487 `agent_name`). This tree never
  writes it, so in practice the label is always the model id.
- `er_filler_cap` — a legacy slider, read only to migrate an upgraded install into
  `er_presence_density` (android/app/src/main/java/com/hermesvox/PresenceCadence.kt:36 `LEGACY_PREF`).
  Nothing writes it any more.
- `presence` — a second narration gate read alongside `er_presence` when a tool call wants a
  spoken line (android/app/src/main/java/com/hermesvox/MainActivity.kt:1685 `"presence", true`).
  Read with a default of true and never written by this tree, so treat it as permanently on.

---

## 4. The log vocabulary

### Where the log is, and what you will actually see

- Logcat tag `HermesVox`, plus a rolling file at `filesDir/logs/hermes-vox.log`
  (android/app/src/main/java/com/hermesvox/VoxLog.kt:50 `logs/hermes-vox.log`).
- The file is capped at 5 MB and rotates once to `hermes-vox.log.1`; the Debug screen's
  export ships the merged pair, so an exported log normally spans two generations
  (android/app/src/main/java/com/hermesvox/VoxLog.kt:44 `LOG_CAP_BYTES`).
- **`VoxLog.dd()` lines only reach the file when "Verbose file log" is on**; `d()`, `w()`,
  `e()` and `er()` always do (android/app/src/main/java/com/hermesvox/VoxLog.kt:71 `fun dd`).
  In the tables below, `†` marks a line that is `dd()`-only in the shipped default. If a
  user's log lacks it, that is the setting, not a bug.
- Transcript text is hidden unless "Log spoken transcript" is on; lines show `<hidden>`
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:825 `logTranscripts()`).
- Timing lines: `event=turn` is one line per settled turn with `stt=`, `firstByte=`,
  `firstText=`, `firstAudio=`, `fullReply=` and `session_turns=`
  (android/app/src/main/java/com/hermesvox/LatencyStats.kt:84 `event=turn label=`); every
  eighth turn adds `event=lat` p50/p95 summaries
  (android/app/src/main/java/com/hermesvox/LatencyStats.kt:92 `metric=first-byte`).
- The same line carries the **conversational** fields, measured from the END OF SPEECH
  rather than from the turn's launch: `speechEndToFirstText=`, `speechEndToFirstAudio=`,
  `speechEndToRenderDone=` and `sttDecodeMs=` (the recogniser's own work for the segment).
  They are `-` on a typed turn, which has no speech to measure from. `stt=` is unchanged and
  is still `now - segStart`, so on an idle segment it reports idle time — use `sttDecodeMs=`
  or `event=stt-live` for decode cost
  (android/app/src/main/java/com/hermesvox/LatencyStats.kt:86 `speechEndToFirstText=`).
- `event=er-soul-first-token` is the presence render's first token, on the same stream as
  `event=turn`, so a soul render and the turn it belongs to can be compared directly
  (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:524 `er-soul-first-token`).

Every `event=` name the app emits is listed below, grouped by subsystem. If you meet one
that is not here, this list is stale: add it with a reference.

### A. Call lifecycle, audio focus and routing

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=call-end` | The call closed; `callLive` says whether it was live | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt:676 `call-end`) |
| `event=activity-stop` | The activity stopped; `reason=no-live-call controller-stopped` is normal | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt:2162 `activity-stop`) |
| `event=fg-service` | Foreground service created/started/destroyed | `startForeground-failed` | (android/app/src/main/java/com/hermesvox/VoiceService.kt:33 `fg-service start`) |
| `event=fg-service-start` | The mic foreground service was requested | `event=fg-service-start-failed err=` | (android/app/src/main/java/com/hermesvox/VoiceService.kt:87 `fg-service-start ok`) |
| `event=wake` | Wake lock acquired/released for the open line | `event=wake-acquire-failed` | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1186 `wake acquired`) |
| `event=screen-alive` | The keep-screen-on window flag was armed | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1194 `screen-alive armed`) |
| `event=focus-acquire` | Audio focus requested; `result=granted` or `denied` | `event=focus-acquire-failed` | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1258 `focus-acquire result=`) |
| `event=focus-change` | Another app took/returned focus; `state=loss kind=loss / transient / can-duck`, or `state=resume` | `action=hangup reason=over-…` means focus stayed away too long and the call ended | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1296 `focus-change state=loss`) |
| `event=focus` | Focus released | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1272 `focus released`) |
| `event=audio-route` | Capture/playback re-attached for a device change; `devices=` lists the route | `event=audio-route-register-failed`, `event=audio-route-unregister-failed` | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1363 `audio-route devices=`) |
| `event=comm-route` | Headset/communication-route selection (`action=set ok=…`, `action=none`, `action=cleared`) | `action=select-failed`, `ok=false` | (android/app/src/main/java/com/hermesvox/CommRoute.kt:92 `comm-route action=set`) |
| `event=harness` | Debug builds only: a test intent set ringer mode | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt:389 `harness ringer=`) |
| `event=res` | Every 10 s of a call: the app's resident memory, the phone's available memory, low-memory flag, thermal state | `low=true`, `availMb` under ~1000, or `thermal=severe` and above — the phone is close to killing something | (android/app/src/main/java/com/hermesvox/ResourceProbe.kt:35 `event=res rssMb=`) |
| `event=res-summary` | At hang-up: the call's peak RSS, lowest available memory, thermal start → end | A peak that grows release over release (docs/BASELINE.md) | (android/app/src/main/java/com/hermesvox/ResourceProbe.kt:12 `event=res-summary`) |

### B. Mic, VAD, STT

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=endpoint-extended` | The utterance passed `vad_max_ms` and stayed open until a real pause (logged once per utterance) | Fires on every turn = the speaker keeps going; not itself an error | (android/app/src/main/java/com/hermesvox/VoiceController.kt:784 `endpoint-extended`) |
| `event=stt-windowed` | Audio longer than the model window was split into windows before decoding | — | (android/app/src/main/java/com/hermesvox/OfflineStt.kt:41 `stt-windowed`) |
| `event=stt-reject` | A transcript was refused before it became a turn; `reason=` is `noise-tag`, `low-speech-ratio`, `runaway-repetition`, `implausible-rate` or `non-speech-phrase` | Any of them on every turn: the mic is hearing noise, or the threshold is wrong | (android/app/src/main/java/com/hermesvox/TranscriptValidator.kt:85 `noise-tag`) |
| `event=stt-collapse` | Runaway repetition was collapsed to one phrase instead of sent | Frequent = recogniser looping on echo | (android/app/src/main/java/com/hermesvox/VoiceController.kt:926 `stt-collapse`) |
| `event=stt-fallback` | The selected STT leg could not be used; `from=` → `to=` names the swap | `reason=init-failed` (selected model did not load), `reason=absent` (files not installed), `reason=downloading`; a bare `from=remote` = the STT server failed | (android/app/src/main/java/com/hermesvox/VoiceController.kt:484 `stt-fallback`) |
| `event=echo-tail-drop` | A short segment that ended inside the post-reply echo tail was discarded | Frequent drops of real short answers ("yes", "no") = tail too long | (android/app/src/main/java/com/hermesvox/VoiceController.kt:862 `echo-tail-drop`) |
| `event=stt-live` | The streaming arm finished an utterance on its live stream: `fedMs` (audio decoded — equal to the utterance when nothing was decoded twice), `restMs` (fed after the speech ended), `finishMs` (the wait for the text) | `finishMs` that grows with the utterance's length | (android/app/src/main/java/com/hermesvox/StreamingStt.kt:286 `event=stt-live seg`) |
| `event=stt-live-fallback` | The live stream could not finish, so the clip was decoded whole: `reason=worker-busy` or `finish-failed` | Any — the turn's text was right but slow | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2386 `stt-live-fallback`) |
| `event=stt-remote-init` | The remote STT availability probe finished; `ok=` and `api=` prefix | `ok=false` = the server is not usable | (android/app/src/main/java/com/hermesvox/RemoteStt.kt:187 `stt-remote-init`) |
| `event=stt-remote` | One remote transcription; `ms=`/`textLen=` on success (that success line is `dd`) | `err=http-…`, `err=empty-or-unparseable`, `err=` transport | (android/app/src/main/java/com/hermesvox/RemoteStt.kt:202 `stt-remote err=`) |
| `event=stt-remote-test` † | The Settings "Test STT server connection" result | `ok=false reason=no-url`; `ok=false` | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:911 `stt-remote-test`) |

### C. The turn over the wire

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=start-stream` † | A streamed turn opened; carries the routed model and provider | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1437 `start-stream`) |
| `event=stream-wait` | `waitStream` raised (retired stream or transport) | Any `err=` while the turn should be live | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1487 `stream-wait`) |
| `event=stream-poll-error` | One poll failed; retried | Repetition up to 200 = dead stream | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1496 `stream-poll-error`) |
| `event=stream-poll-abort` | 200 consecutive poll errors: the turn was abandoned | Always bad — the gateway or network died mid-turn | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1499 `stream-poll-abort`) |
| `event=stream-poll` † | One poll snapshot: event count, done, text length | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1535 `stream-poll gen=`) |
| `event=stream-stall` | No SSE bytes for 5 s / 15 s (keepalives included) | `no-events=15s` = the gateway has gone quiet | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1540 `no-events=15s`) |
| `event=stream-turn-failed` | The turn threw; `err=timeout` means the 120 s wall clock | Any; `timeout` on a short reply is the gateway | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1594 `stream-turn-failed`) |
| `event=turn-dropped` | A completed turn was dropped because the generation was stopped | `reason=stopped` after a hangup is expected | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1626 `turn-dropped`) |
| `event=settle-drop` | A settled reply was not spoken because it was cancelled or already voiced | Both reasons are protective; a drop with no cut means a double-settle bug | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1727 `settle-drop`) |
| `event=stream-fallback` | The stream failed **after** assembling text; the text is spoken non-streamed instead of lost | Any — the reply is degraded | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1745 `stream-fallback`) |
| `event=compress-failed` | `/compress` (context compression) failed | Always worth reporting with `err=` | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1933 `compress-failed`) |
| `event=command` | A command from the commands screen changed state: `name=`, `state=running / done / failed` | `failed` names the command that did not complete | (android/app/src/main/java/com/hermesvox/CommandsSheet.kt:151 `event=command name=`) |
| `event=compress-failed` | `/compress` (context compression) failed | Always worth reporting with `err=` | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1933 `compress-failed`) |
| `event=turn` | One line per settled turn with latency fields and `outcome=` | Cut outcomes: `reply-error`, `stream-lost`, `empty-reply`, `stopped`, `hush`, `barge-in`, `focus-loss`, `suppressed-inflight`; healthy: `stream-done`, `speak-done`, `text-only` | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1892 `stream-done`) |
| `event=turn-skip` † | A second settle for the same epoch was suppressed | Repeats = a double-settle path | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2405 `turn-skip`) |
| `event=lat` | Rolling p50/p95 per metric every 8 turns | Compare `first-audio` against the cadence target, p50 700 ms / p95 1.4 s (docs/DECISIONS.md:322 `p50 ≤ 700 ms`) | (android/app/src/main/java/com/hermesvox/LatencyStats.kt:92 `event=lat`) |

### D. Barge-in and the turn gate

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=barge-watch` | The playback barge watcher armed for this generation | `vad=off` = no Silero model, so only level can fire | (android/app/src/main/java/com/hermesvox/VoiceController.kt:950 `barge-watch`) |
| `event=barge-in` | A real interruption fired; `mode=playback / generation`, with level and VAD agreement | **Good** — this is barge-in working | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1116 `barge-in source=`) |
| `event=barge-in ignored` | An interruption was seen but refused because of state | `reason=state` with `turnInFlight=false speaking=false` is contradictory — report it | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2274 `barge-in ignored`) |
| `event=barge-nearmiss` † | Level approached the floor without firing | Repeat clusters = floor too high, or the caller is quiet/ducked | (android/app/src/main/java/com/hermesvox/VoiceController.kt:994 `barge-nearmiss`) |
| `event=barge-gap` † | The mic drain was starved >500 ms | Any — the capture loop is blocked | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1040 `barge-gap`) |
| `event=barge-skipcheck` † | The barge check has been skipped in this state >3 s | `why=state` = a deaf window | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1071 `barge-skipcheck`) |
| `event=barge-stat` | Per-second witness: peak/sustained level, echo floor, `sustainedMs`, `levelSustain` | A `sustainedMs` that keeps climbing without `barge-in` = the cut is not firing | (android/app/src/main/java/com/hermesvox/VoiceController.kt:999 `barge-stat`) |
| `event=barge-carry` | The interrupting audio was carried into the next turn | `seeded=false reason=hold / stopped / gate-timeout` = the words were not kept | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1145 `barge-carry`) |
| `event=gate-ceiling` | The mic reopened at the gate's ceiling | Every long reply = the reply outlived the ceiling | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1137 `gate-ceiling`) |
| `event=gate-timeout` | The gate stayed locked 60 s with no activity for 30 s | **Always bad** — a wedged turn; include `still-locked`, `streamed`, `sRunning` | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1139 `gate-timeout`) |
| `event=gate-release` | The gate released; `reason=` becomes `event=turn`'s outcome | `result=stale` (a cancel for an old generation) / `result=duplicate` (expected on hangup) | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2391 `gate-release result=stale`) |
| `event=tts-stop` | Speech was cut; `reason=` is `barge-in`, `hush`, `stop`, `focus-loss`, or an ER cancel; `msSinceBarge=` is the cut latency | `msSinceBarge` in the hundreds = slow cut | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2372 `tts-stop reason=`) |

### E. Voice out, cues and the transcript

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=tts-retire` | The streaming synthesis worker retired after the last chunk | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1881 `tts-retire`) |
| `event=reply-end` | A reply's one end event: `reason=done` or `reason=cancelled cause=<barge-in / hush / stop / …>`. Exactly one per `gen` | A second line for the same `gen` is a bug | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2371 `reason=cancelled cause`) |
| `event=tts-drop` | A sentence of a reply that already ended was discarded instead of played: `reason=reply-ended` (queued) or `reason=stale-epoch` (it was synthesizing across the cut) | Normal after a barge; it is the fix for audio carried into the next turn | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1801 `reason=reply-ended`) (android/app/src/main/java/com/hermesvox/SherpaTts.kt:413 `reason=stale-epoch`) |
| `event=tts-stop … cancelToSilenceMs=` | On a cut: `msSinceBarge` is the barge decision to the cut; `cancelToSilenceMs` is the cut to the track paused | `cancelToSilenceMs` over ~50 | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2372 `cancelToSilenceMs=`) |
| `event=tts-speak-drop` | Synthesis finished after the reply was stopped, so the audio was dropped | Normal after a barge; on its own = a stop raced the synth | (android/app/src/main/java/com/hermesvox/SherpaTts.kt:308 `tts-speak-drop`) |
| `event=tts-speak-cut` | Playback was cut at sample `at=` of `of=` | — | (android/app/src/main/java/com/hermesvox/SherpaTts.kt:328 `tts-speak-cut`) |
| `event=tts-teardown` | The engine was torn down; `result=timeout` means a writer was still active | `result=timeout` = a stuck writer | (android/app/src/main/java/com/hermesvox/SherpaTts.kt:503 `tts-teardown result=timeout`) |
| `event=voice-preview` | A Settings voice preview finished; `engine=` and `ms=` | — | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1462 `voice-preview`) |
| `event=cue` | A sound cue decision: `cue=`, `action=audio / haptic / silent`, `reason=` | `action=silent` with `reason=off / dnd / silent` explains "I hear nothing" | (android/app/src/main/java/com/hermesvox/SoundCuePlayer.kt:68 `event=cue cue=`) |
| `event=cue-fail` | A cue could not be played | Any | (android/app/src/main/java/com/hermesvox/SoundCuePlayer.kt:80 `cue-fail`) |
| `event=reveal-hold` | The speech-locked transcript held position through a synthesis gap | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1969 `reveal-hold`) |
| `event=reveal-freeze` | The crawl was frozen at the spoken position when speech was cut | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2075 `reveal-freeze`) |
| `event=reveal-fallback` | The crawl lost its cursor and fell back to plain reveal | Any — text may run ahead of the voice | (android/app/src/main/java/com/hermesvox/MainActivity.kt:104 `reveal-fallback`) |

### F. Presence (Enhanced Realtime)

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=er-soul` | The soul decided for a turn: `kind=` (turn/greeting/working), `decision=`, `chars=`, `heard=`, `mood=`, `ms=` | `decision=escalate` on every turn = the layer is doing nothing; high `ms=` = slow render | (android/app/src/main/java/com/hermesvox/MainActivity.kt:1152 `er-soul kind=`) |
| `event=presence-unload` | The presence Engine was released: `reason=` one of `call-end` (immediate arm, dev pref), `idle` (the delayed arm's timer), `mode-leave`, `released=`, `availMb=` after the close | Never fires on a shipped build — both release arms are off by default | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:273 `event=presence-unload reason=`) |
| `event=presence-unload-arm` | The delayed arm was armed at hangup: `trigger=call-end`/`mode-leave`, `minutes=` | Fires and is never followed by `-due` or `-cancel` = the timer died | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:322 `event=presence-unload-arm trigger=`) |
| `event=memory-budget` | The dev memory dialog was applied: `kv=` (2048/4096/8192, applies on the next presence load), `unload=`, `idleMin=` | Any `kv=` other than the arm a run believes it is in = the measurement is misattributed | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:686 `event=memory-budget`) |
| `event=presence-unload-cancel` | A pending release was called off: `reason=call-start` (a redial inside the window, the case the arm exists for), `presence-load`, `policy-keep`, `policy-on-hangup` | — | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:334 `event=presence-unload-cancel reason=`) |
| `event=er-soul-drop` | A soul line was rendered but not delivered; `reason=late` (too late to the turn), `undeliverable`, `already-answered`, `mind-already-replying`, `greeting-already-said`, `greeting-reply-live` | All are drops; `late` is the measured common one (see the note under this table) | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1370 `er-soul-drop reason=`) |
| `event=er-mood` | The soul tagged the caller's tone; the mood steers delivery | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1362 `er-mood`) |
| `event=er-glue` | A Presence line was accepted for playback; `source=`, `critical=`, `chars=` | `critical=true` lines pre-empt fillers by design | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2170 `er-glue source=`) |
| `event=er-glue-reject` | A Presence line was refused | `reason=repeat` (same line twice) and `reason=streaming-reply-live` (the reply already has the floor) | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2179 `er-glue-reject reason=repeat`) |
| `event=er-arbiter` | Priority arbitration: P0 stop · P1 mind · P2 critical soul · P3 filler | `outcome=` naming a lower-priority winner over a higher request | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2207 `er-arbiter current=`) |
| `event=er-fence-reopen` | The audio fence reopened after a glue line | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2232 `er-fence-reopen`) |
| `event=er-barge` | Semantic-barge decision on an interruption (e.g. `verdict=cancel_mind reason=semantic-toggle-off`) | A cancel when the user said "go on" = the toggle is off | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2300 `er-barge verdict=`) |
| `event=er-barge-fire` | A staged barge was fired; `hold=staged frames=` | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2311 `er-barge-fire`) |
| `event=er-barge-verdict` | What the verdict classifier decided about the interrupting words | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt:2322 `er-barge-verdict`) |
| `event=er-mind-skip` | The mind's reply was `<<SKIP>>` because the voice already answered | Frequent = the soul is answering things it should escalate | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1552 `er-mind-skip`) |
| `event=er-handoff` | The mind's reply cut in over the soul; `waitedMs=`, `cut=` | Large `waitedMs` = the turn waited on a slow render | (android/app/src/main/java/com/hermesvox/VoiceController.kt:1828 `er-handoff`) |
| `event=er-presence line=` | A mid-run line was voiced; `line=`, `interval=`, `density=`, `kind=lag / soul` | — | (android/app/src/main/java/com/hermesvox/ErPresence.kt:284 `er-presence line=`) |
| `event=er-presence skip` | A cadence slot passed in silence; `reason=silent / late / glue-playing / undeliverable / blocked / no-soul / lag-not-said` | `late` = the line arrived after the reply started; `blocked`/`no-soul` = the layer is not wired | (android/app/src/main/java/com/hermesvox/ErPresence.kt:288 `er-presence skip`) |
| `event=er-presence detail` | Which line was thrown away and why (`why=`, `deltaMs=`, text hidden unless transcript logging is on) | Any `why=` other than `silent` is a refusal worth reading | (android/app/src/main/java/com/hermesvox/VoiceController.kt:352 `er-presence detail`) |
| `event=er-presence stop` | The cadence ended; `reason=cap / reply / barge / hush / call-end` | `reason=cap` = the run used every line and stalled | (android/app/src/main/java/com/hermesvox/ErPresence.kt:274 `er-presence stop`) |
| `event=er-render-skip` | A render was skipped by the spacing floor (1.2 s) | Clusters = renders requested too often | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:366 `er-render-skip`) |
| `event=er-render-drop` | A render was dropped because one was in flight | Frequent = the queue is too small for the turn rate | (android/app/src/main/java/com/hermesvox/VoxExpress.kt:106 `er-render-drop`) |
| `event=er-render-preempt` | An urgent render pre-empted the one in flight | — | (android/app/src/main/java/com/hermesvox/VoxExpress.kt:118 `er-render-preempt`) |
| `event=soul-heard` | The soul processed the caller's audio; `audioBytes=` | — | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:425 `soul-heard`) |
| `event=soul-conv-rotate` | The rolling LiteRT-LM conversation was rebuilt; `tokens=`, `personaChanged=` | Frequent rotations = the conversation keeps filling | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:444 `soul-conv-rotate`) |
| `event=vox-resync` | The VOX.md pull finished; `doc=` length and `result=ok / ok repaired=… / invalid: …` | `result=invalid:` = the entity's reply was not a usable document | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:431 `vox-resync`) |
| `event=vox-resync-failed` | The pull threw | Any | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:431 `vox-resync-failed`) |

**Two `late` sites, two meanings.** `event=er-soul-drop reason=late` is a *turn or beat* render
that lost the race to the mind's first text — the documented common case, and not the working
lane (docs/PRESENCE-LATE-DIAGNOSIS.md:3 `er-soul-drop reason=late`). `event=er-presence skip
reason=late` is a *mid-run* line that arrived after the reply started; that one was addressed by
pre-rendering the line before the slot opened (docs/PRESENCE-LATE-DIAGNOSIS.md:7 `two`). When a
user says "it went quiet", look at `er-presence skip`; when they say "it didn't say anything
before answering", look at `er-soul-drop`.

### G. Models

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=model-dl-start` | A download (or resume) started; `resumeFrom=` bytes | — | (android/app/src/main/java/com/hermesvox/ModelDownloads.kt:110 `model-dl-start`) |
| `event=model-dl-attempt` | One HTTP attempt; `have=`, `code=`, `plan=` | `code=403/404` = the source URL or asset moved | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:135 `model-dl-attempt`) |
| `event=model-dl-offline` | No network: the download waits instead of failing | Expected in a tunnel/lift | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:89 `model-dl-offline`) |
| `event=model-dl-online` | Network returned; the download resumes | — | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:92 `model-dl-online`) |
| `event=model-dl-retry` | A failed attempt is retried after backoff | Repeats up to the retry cap = a flaky source | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:97 `model-dl-retry`) |
| `event=model-dl-end` | The download ended; `result=installed` or an error string | Anything but `installed`; a sha256 mismatch returns "sha256 mismatch" | (android/app/src/main/java/com/hermesvox/ModelDownloads.kt:122 `model-dl-end`) |
| `event=model-dl-service` | The download foreground service failed to start/foreground | Any — the download will not survive leaving the Models screen | (android/app/src/main/java/com/hermesvox/ModelDownloadService.kt:46 `model-dl-service`) |

---

## 5. The model catalogue

Model archives are **never bundled in the APK**: they are downloaded on demand into
app-private storage, sha256-verified against a pinned hash, and unpacked
(android/app/src/main/java/com/hermesvox/ModelCatalog.kt:35 `never bundled`). A download is
resumable over HTTP `Range` and refuses to install on a hash mismatch
(android/app/src/main/java/com/hermesvox/ModelDownloader.kt:108 `sha256 mismatch`).

"Required" and "recommended" are the **same set** everywhere — the Settings badge, the store
header and the install-all button all count the same three models
(android/app/src/main/java/com/hermesvox/ModelCatalog.kt:116 `val required`). That set is
Silero VAD, Supertonic and Whisper base.en: the offline voice needs all three
(android/app/src/main/java/com/hermesvox/ModelsActivity.kt:74 `ModelCatalog.required`).

| Model id | Kind | Download | Required? | Notes | Reference |
|---|---|---|---|---|---|
| `silero-vad` | VAD | 0.6 MB | **yes** | Speech start/stop; without it barge-in is level-only | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:54 `silero-vad`) |
| `supertonic` | TTS | 80.3 MB | **yes** | Ten voices at 44.1 kHz; the recommended voice | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:63 `supertonic`) |
| `whisper-base` | STT | 208.6 MB | **yes** | The blessed default recogniser | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:72 `whisper-base`) |
| `whisper-tiny` | STT | 118.1 MB | no | Fastest, least accurate | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:68 `whisper-tiny`) |
| `whisper-small` | STT | 635.7 MB | no | Most accurate Whisper, heaviest | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:76 `whisper-small`) |
| `parakeet-v2` | STT | 482.5 MB | no | Measured best accuracy+speed on the host bench; larger resident set, so not the default | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:86 `parakeet-v2`) |
| `nemotron-stream-160ms` | STT (streaming) | 463.9 MB | no | Opt-in streaming arm; ~900 MB resident. **Licence gate** | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:97 `Nemotron Speech Streaming`) |
| `piper-lessac` | TTS | 82.0 MB | no | Lighter alternative voice (904 speakers, 8 curated in the picker) | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:58 `piper-lessac`) |
| `gemma-e2b` | Presence | 2588.1 MB | no | The on-device expression layer; needs a working GPU/CPU backend | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:105 `gemma-e2b`) |

### Licences that need acceptance

- **Nemotron** is the only model with a licence gate: the NVIDIA Open Model License, which
  its licensor can revoke. The store shows the name, the attribution and the model card, and
  the downloader refuses to fetch it without acceptance
  (android/app/src/main/java/com/hermesvox/ModelLicences.kt:33 `refusal`). Acceptance is
  stored by licence **name**, so a changed licence asks again
  (android/app/src/main/java/com/hermesvox/ModelLicences.kt:25 `stored == spec.licence.name`).
- **Gemma 4 E2B** carries no `ModelLicence` in its catalogue entry, and a null licence is
  treated as already accepted, so it downloads **without** an acceptance dialog
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:105 `gemma-e2b`)
  (android/app/src/main/java/com/hermesvox/ModelLicences.kt:25 `spec?.licence == null`). Do
  not tell a user they accepted a Gemma licence in this app — they did not.
- **Supertonic**'s catalogue description names its upstream licence (OpenRAIL-M), but that is
  the model card's note, not a download gate
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:64 `OpenRAIL-M`).
- Everything else is permissively licensed upstream; the app itself is GPL-3.0 because
  sherpa-onnx's TTS statically links espeak-ng (docs/RELEASE-0.9.0.md:105 `GPL-3.0`).

### What cannot co-exist comfortably on a small device

- **Gemma (2.6 GB) + the streaming arm (~900 MB resident)** is the worst pairing; Gemma also
  wants the GPU, and on a device it cannot use it runs 5× slower on CPU
  (docs/DECISIONS.md:35 `The GPU engine does not fit`).
- The app warns explicitly on low-RAM devices when the streaming arm is selected: it "needs
  ~900 MB RAM; may be slow or close other apps on this phone"
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:160 `needs ~900 MB RAM`). The
  threshold is an ActivityManager heap class below 256 MB
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:154 `SMALL_MEMORY_CLASS_MB`).
- Whisper small (636 MB) is the heaviest offline recogniser offered; whisper-base is the
  shipped default, and the catalogue says the bigger models stay opt-in until they are
  measured on a phone (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:137 `DEFAULT_STT_MODEL`).
- Two TTS engines do not conflict — only the selected one loads — but the speaker choice is
  stored per engine, so switching engines changes the voice you hear
  (android/app/src/main/java/com/hermesvox/VoiceSpeakers.kt:33 `tts_speaker_`).

---

## 6. Failure modes → log signature → cause → what to do

Each entry is a real, documented field issue. Log lines are literal; the reference proves the
line exists and what it says. Start from the exported log (Settings → About & Diagnostics →
Debug / crash log → Share full log).

### 6.1 The gateway stopped answering after upgrading — bare-IP HTTP is now refused

- **Signature:** the status pill sits on "Can't reach the gateway"
  (android/app/src/main/java/com/hermesvox/ConnectionPhase.kt:117 `Can't reach the gateway`); a turn
  fails fast with `event=stream-turn-failed` (android/app/src/main/java/com/hermesvox/VoiceController.kt:1594 `stream-turn-failed`).
- **Cause:** 0.9 moved turns onto Android's HTTP stack, so the declared network-security
  policy applies: cleartext is refused for every host except tailnet MagicDNS names
  (docs/RELEASE-0.9.0.md:15 `plain HTTP is refused`) (android/app/src/main/res/xml/network_security_config.xml:16 `cleartextTrafficPermitted="false"`).
- **Do:** serve the gateway over HTTPS (`tailscale serve`, Caddy, or any TLS terminator) and
  enter the HTTPS URL. For a tailnet certificate, install the CA and use the HTTPS name
  (android/app/src/main/res/xml/network_security_config.xml:22 `certificates src="user"`). Do
  not suggest uninstalling or downgrading.

### 6.2 The selected STT model "isn't working" — it never loaded

- **Signature:** `event=stt-fallback reason=init-failed from=nemotron-stream-160ms to=whisper-base`
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:516 `reason=init-failed`).
- **Cause:** the streaming arm could not load (memory pressure or a damaged file); the app
  falls back to the shipped model for the rest of that session only.
- **Do:** check the model is installed and the device has headroom; a restart retries the arm.
  If it fails again on a small device, recommend whisper-base instead
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:160 `~900 MB RAM`).

### 6.3 STT is silently degraded to the platform recogniser

- **Signature:** `event=stt-fallback reason=absent` when a streaming model is selected but its
  files are missing (android/app/src/main/java/com/hermesvox/VoiceController.kt:484 `stt-fallback`).
  When **no** on-device model is installed at all there is no fallback line, because nothing was
  selected to fail — the chain simply posts to `SpeechRecognizer`.
- **Cause:** the configured offline model is not installed, so the chain falls through to
  `SpeechRecognizer`; that leg may send audio to the device's provider
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:1265 `createSpeechRecognizer`).
- **Do:** install the model in Voice models, or switch the STT backend to on-device
  explicitly. Tell the user the privacy consequence of staying on platform STT.

### 6.4 Barge-in stopped working after the voice changed

- **Signature:** `event=barge-stat` shows `sustainedMs`/`levelSustain` climbing with no
  `event=barge-in` (android/app/src/main/java/com/hermesvox/VoiceController.kt:999 `barge-stat`).
- **Cause:** the interruption floor is derived from the reply's own echo; a much quieter
  voice (Supertonic is ~6.5 dB quieter than the Piper it replaced) leaves almost no echo bed,
  so the caller's voice alone must clear the floor, and the platform echo canceller ducks it
  during playback (docs/RELEASE-0.9.0.md:50 `measured from the reply's own echo`).
- **Do:** confirm the build includes the measured floor (`EchoFloor`,
  android/app/src/main/java/com/hermesvox/EchoFloor.kt:82 `0.030`), then lower Barge
  sensitivity a step and/or enable Level-only barge
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1161 `barge_level_only_ms`). A
  headset changes the room; retest there.

### 6.5 A long reply cannot be interrupted for its last minute

- **Signature:** `event=gate-ceiling` at ~60 s into a turn, then no barge
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:1137 `gate-ceiling`).
- **Cause:** the barge watchdog used to give up 60 s after the gate closed regardless of
  whether the reply was still speaking; fixed in 0.9.1 to count from the turn's last sign of
  life with a ten-minute backstop (docs/RELEASE-0.9.0.md:59 `60 seconds`).
- **Do:** confirm the version — the ceiling is the turn's own last-activity clock now
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:1137 `gate-ceiling`). There is no
  setting for the old 60 s ceiling; the fix is the build.

### 6.6 The user's sentence is chopped into fragments after ~15 s of talking

- **Signature:** `event=endpoint-extended` directly followed by a short turn in `event=turn`
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:784 `endpoint-extended`).
- **Cause:** the utterance clock used to run from mic-open instead of speech onset, so the
  first >100 ms pause closed a sentence the caller was still in the middle of
  (docs/RELEASE-0.9.0.md:63 `utterance clock starts when you start speaking`).
- **Do:** on 0.9.1+ this should be gone; if it persists, raise "Max speech per turn" and
  check VAD sensitivity. Note the ceiling is a pause rule now, not a hard cut
  (android/app/src/main/java/com/hermesvox/EndpointRule.kt:44 `shouldStop`).

### 6.7 The entity answers its own greeting ("hello, hello")

- **Signature:** `event=echo-tail-drop` immediately after a call opens with Presence on
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:862 `echo-tail-drop`).
- **Cause:** the greeting plays before any turn exists, so the half-duplex gate had not closed
  and the mic transcribed the being's own voice; the fix excludes glue and keeps the tail
  quiet for the echo-guard window (docs/RELEASE-0.9.0.md:66 `own greeting`).
- **Do:** confirm the build; if it recurs, raise the Echo guard
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:348 `er_echo_skip_ms`).

### 6.8 The connection test says FAILED against a gateway that works

- **Signature:** the dialog reports FAILED while turns stream normally; the probe's stream
  leg returned HTTP 400.
- **Cause:** the probe deliberately sends no `input`, and the gateway answers 400; a 400 from
  that probe means the route exists and the key was accepted
  (android/app/src/main/java/com/hermesvox/ConnectionPhase.kt:158 `400`). A false 401 could
  also come from an encrypted key being sent as the credential — the live channel now
  overrules the one-shot probe (android/app/src/main/java/com/hermesvox/ConnectionPhase.kt:182 `reconcile`).
- **Do:** trust the live turns, not the probe; update to a build with the 400 fix
  (docs/RELEASE-0.9.0.md:94 `connection test tells the truth`).

### 6.9 Presence lines are dropped as `late`

- **Signature:** `event=er-presence skip line=N reason=late` and/or
  `event=er-soul-drop reason=late` (android/app/src/main/java/com/hermesvox/VoiceController.kt:1370 `er-soul-drop reason=`).
- **Cause:** a working slot's line used to be rendered only after the slot came due, so a
  ~2.5 s render sat between "due" and "delivered" and the mind's first text landed inside it;
  turn renders are dropped for the same reason but by design (the 900 ms gate)
  (docs/PRESENCE-LATE-DIAGNOSIS.md:7 `two`).
- **Do:** on a fixed build the working lane pre-renders. If turn renders are still dropped,
  that is the documented trade, not a fault; slowing "Presence while working" reduces the
  number of slots, not the drops (docs/PRESENCE-LATE-DIAGNOSIS.md:73 `too slow, not too late`).

### 6.10 Presence is silent through a long tool call

- **Signature:** `event=er-presence line=1 … kind=lag`, then `event=er-presence stop
  reason=cap lines=` or simply nothing for a minute
  (android/app/src/main/java/com/hermesvox/ErPresence.kt:227 `reason=cap`).
- **Cause:** the cadence has a per-run cap; a 76-second tool call produced one line, one
  canned "let me think", then 71 seconds of silence (docs/RELEASE-0.9.0.md:118 `76-second tool call`).
- **Do:** set Presence while working to Chatty for a higher cap and shorter interval
  (android/app/src/main/java/com/hermesvox/PresenceCadence.kt:29 `CHATTY`), and confirm the
  build has the mid-run cadence at all.

### 6.11 The presence model installs but never speaks

- **Signature:** the main screen pill reads "⚠ The presence model could not start on this
  device — Enhanced Realtime is running as Realtime"
  (android/app/src/main/java/com/hermesvox/MainActivity.kt:2076 `could not start on this device`).
- **Cause:** the LiteRT-LM engine could not initialise on this GPU, and the CPU path is the
  documented slow one — ~1.8 s to first token on CPU versus ~0.3 s on GPU
  (android/app/src/main/java/com/hermesvox/GemmaExpress.kt:76 `1.8s`); there is also a recorded
  **intermittent** start hang on the same build
  (docs/RELEASE-0.9.0.md:136 `intermittent rather than fixed`). A double-load of the model
  was fixed earlier (docs/RELEASE-0.6.3.md:8 `Gemma double-load`).
- **Do:** treat it as "Presence unavailable" rather than "broken call"; restarting the app once
  often clears the hang. There is no setting that fixes a GPU the engine cannot use.

### 6.11b An Enhanced call sits on "Loading Presence" for most of a minute

- **Signature:** the pill reads "Loading Presence · N s"; `event=presence-load phase=start availMb=…` with a low
  number, then `phase=end ok=true ms=` tens of thousands; around it, the system log shows the low-memory killer
  clearing other apps.
- **Cause:** the phone is short of memory, so loading Presence's model (about 2.5 GB) waits while Android frees
  room — measured at ~50 s on an 8 GB Pixel 7 whose other apps held a 2.5 GB model of their own (#191). It is not
  a hang. After 20 s the pill adds "tap to start without it" (android/app/src/main/java/com/hermesvox/PresenceWait.kt:22 `OFFER_SKIP_MS`),
  which opens the call with Presence joining when it is ready
  (android/app/src/main/java/com/hermesvox/MainActivity.kt:619 `event=presence-skip`).
- **What to do:** close other heavy apps (on-device AI apps especially), or use Realtime on that phone.

### 6.12 The text on screen runs ahead of (or behind) the voice

- **Signature:** `event=reveal-fallback` (the crawl lost its cursor) — bad
  (android/app/src/main/java/com/hermesvox/MainActivity.kt:104 `reveal-fallback`).
  `event=reveal-hold reason=synthesis-gap` is the **fix working**: the crawl held its last
  honest position through a synthesis gap
  (android/app/src/main/java/com/hermesvox/VoiceController.kt:1969 `reveal-hold`).
- **Cause:** the crawl is paced by the playback head; a synthesis gap used to look like
  "everything has been said" and pinned the crawl to the end of the reply
  (docs/RELEASE-0.9.0.md:98 `spoken transcript keeps its place`).
- **Do:** if only `reveal-hold` appears, nothing is wrong. `reveal-fallback` means the cursor
  was lost; include the surrounding `tts-` lines when reporting it.

### 6.13 A call wedges and the mic never reopens

- **Signature:** `event=gate-timeout await=turn-gate … still-locked` with no
  `event=gate-release` for the generation (android/app/src/main/java/com/hermesvox/VoiceController.kt:1139 `gate-timeout`).
- **Cause:** the turn gate stayed locked 60 s after the gate closed and 30 s with no delta,
  chunk or read — a wedged turn (dead stream that never errored, or a TTS worker that
  never retired).
- **Do:** end the call and reopen it; export the log because `streamed=`, `sRunning=` and
  `still-locked` identify which leg wedged. Do not advise clearing app data first — that
  destroys the evidence.

### 6.14 A download never finishes

- **Signature:** `event=model-dl-retry` repeating, or `event=model-dl-offline` with no
  `model-dl-online` (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:97 `model-dl-retry`).
- **Cause:** the source host is unreachable (offline, captive portal, or an overridden
  `model_source`), or the artifact moved (`code=403/404` in `model-dl-attempt`)
  (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:135 `model-dl-attempt`).
- **Do:** restore the default source in Voice models, check the URL scheme (a scheme-less
  override silently falls back to the default)
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt:196 `resolveSource`); the partial
  file is kept and resumes (android/app/src/main/java/com/hermesvox/ModelDownloader.kt:22 `resuming`).

---

## 7. What the app does not do

These prevent confident wrong advice. None of them is a bug report; they are the product.

- **No cloud, no account, no vendor server.** There is no hosted service, no sign-up and
  nothing operated by the maintainers (README.md:29 `Bring your own gateway`); the gateway
  key goes only to the gateway (PRIVACY.md:112 `only to the gateway`). The only *default*
  network peer is your gateway plus the model host you install from (PRIVACY.md:70 `your server`).
- **No Play Store, no AAB.** Google Play is dropped as a rail; distribution is GitHub,
  Obtainium and F-Droid, always with our own signature (docs/DECISIONS.md:16 `Google Play is dropped`).
- **No iOS, no desktop.** Android only; the reusable core that a port would have needed was
  removed (docs/DECISIONS.md:9 `Desktop and iOS are out of scope`).
- **No wake word that listens.** The wake word is a **preview**: an engine seam whose only
  implementation never detects anything, plus an enrolment screen that records takes into an
  encrypted store. Nothing is trained and nothing listens in the background
  (docs/WAKE-WORD.md:6 `Nothing is trained and nothing listens`) (android/app/src/main/java/com/hermesvox/wake/WakeWordEngine.kt:55 `available = false`). If a
  user says "it doesn't wake on Hey Hermes", the answer is "it cannot, by design yet".
- **No local mind.** The app never reasons, calls tools or stores long-term memory; Gemma
  only expresses (android/app/src/main/java/com/hermesvox/VoxExpress.kt:7 `Gemma NEVER runs the agentic stream`).
- **No full duplex.** It listens *or* speaks, with barge-in; it does not hear you while it is
  talking (docs/ARCHITECTURE.md:203 `Half-duplex`).
- **No streaming partials by default.** Recognition is batch: a turn is transcribed after it
  ends. Partials only trigger an early end, and the streaming arm is opt-in
  (docs/ARCHITECTURE.md:84 `Recognition is *batch*`).
- **No semantic end-of-turn.** Endpointing is silence plus a conservative finished-sentence
  check (docs/ARCHITECTURE.md:188 `not semantic`).
- **No emotion in the voice.** Mood moves speed and pauses only; Supertonic has no emotion
  control (docs/ARCHITECTURE.md:200 `no emotion control`).
- **No transcript editing or resend.** The words on screen track the voice; the text field sends
  a **new** turn rather than editing history (android/app/src/main/java/com/hermesvox/VoiceController.kt:1246 `A text turn`).
- **No remote relay through the maintainers, no telemetry dashboard, no server-side logs.**
  Logs are local-only, transcript logging is opt-in, and the export is a file the user
  chooses to share (android/app/src/main/java/com/hermesvox/VoiceController.kt:825 `logTranscripts()`).

---

## 8. A 60-second triage recipe

1. Ask for Settings → About & Diagnostics → Debug / crash log → Share full log. It ships both
   log generations (android/app/src/main/java/com/hermesvox/SettingsActivity.kt:1013 `mergedRuntimeLog`).
2. Search `event=gate-timeout`, `event=stream-poll-abort`, `event=stream-turn-failed`,
   `event=stt-fallback`, `event=barge-nearmiss`, `event=er-presence skip`,
   `event=model-dl-end`. Those seven are the fastest route into 6.1–6.14.
3. Read one `event=turn`: `outcome=` says how the turn ended, and the latency fields say which
   leg was slow (android/app/src/main/java/com/hermesvox/LatencyStats.kt:84 `outcome=`).
4. Check the version and the mode: `voice_mode` decides whether Presence exists at all
   (android/app/src/main/java/com/hermesvox/MainActivity.kt:2087 `MODE_ENHANCED`).
5. If the log has no `dd()` lines, the user's "Verbose file log" is off — ask them to turn it
   on and reproduce (android/app/src/main/java/com/hermesvox/VoxLog.kt:71 `fun dd`).
