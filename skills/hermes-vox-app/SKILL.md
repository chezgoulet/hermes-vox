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

**How to read the references.** Every factual claim ends with ``(path `token`)``: the file and a
literal token that must appear in that file. There is no line number to drift — a claim is a file
and a token, so adding code above the cited line changes nothing. The checker
`check-skill-claims.py`, in the repository's `scripts` directory, verifies all of them on every
gate run: a token that is gone from its file is stale and must be corrected, and a token that
appears more than once is ambiguous and must quote the whole line. If you are reading this and a
claim looks wrong, the checker is authoritative — run it from the repository root and fix or
delete the claim. (It lives outside this folder on purpose: it reads repository paths, and a copy
inside the skill would be fetched as a support file on install and then drift.)

**One rule for answering.** Never tell a user a feature exists because this file mentions a
setting; check the row's reference. The most useful answers here are the ones that say what
is absent (section 7) or a preview (the wake word).

---

## 1. What it is, in four lines

1. **Hermes Vox is an Android voice client for Hermes, a self-hosted agent you run yourself.**
   There is no hosted service and nothing to sign up for (README.md `Bring your own gateway`).
2. **By default, hearing and speaking happen on the phone.** Silero VAD, Whisper/Parakeet/Nemotron
   STT and Supertonic/Piper TTS run on-device, fully offline (docs/ARCHITECTURE.md `Speech processing is on-device`);
   the two opt-in exceptions are in the table below.
3. **The thinking happens on your gateway.** The app sends the turn's text to
   `/v1/responses` and streams the reply back; tools, memory and context stay server-side
   (docs/ARCHITECTURE.md `POST /v1/responses`).
4. **By default nothing leaves the phone except that turn** — text to your own gateway, plus
   model files fetched over HTTPS when you install them (PRIVACY.md `your server`). Two
   opt-in paths send audio elsewhere: the remote-STT backend and the platform (Google)
   recogniser; both are described in section 2 and must be named when a user asks about
   privacy.

**Presence** is the layer that gives the agent a body and a voice on the phone — on by
default, and switchable off: an on-device Gemma 4 E2B model, the mirrored `VOX.md`, the
being and its asides
(docs/DECISIONS.md `## D16 — There is one product, and presence is a layer on it`). It is an expression layer, never a second brain
(docs/ARCHITECTURE.md `never a second`).

---

## 2. The pieces and where they run

| # | Leg | Runs | What it is | Reference |
|---|---|---|---|---|
| 1 | Microphone | on-device | `AudioRecord`, 16 kHz mono; `VOICE_COMMUNICATION` by default so the platform AEC/NS applies | (android/app/src/main/java/com/hermesvox/VoiceController.kt `val sourceName = if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMMUNICATION(AEC/NS)" else "MIC"`) |
| 2 | VAD | on-device | Silero VAD via sherpa-onnx decides speech vs silence and gates the open line | (android/app/src/main/java/com/hermesvox/VoiceController.kt `vad = SileroVadGate(context, micFloat("vad_threshold", 0.5f)); vad?.init {}`) |
| 3 | STT | on-device by default | sherpa-onnx offline recogniser (Whisper base.en default) — batch: it sees a finished utterance | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `DEFAULT_STT_MODEL`) |
| 3a | STT alternative | **remote** | optional OpenAI-compatible server; audio is POSTed to the URL the user typed | (android/app/src/main/java/com/hermesvox/RemoteStt.kt `audio never leaves the`) |
| 3b | STT alternative | **remote (platform)** | Android `SpeechRecognizer`; the device's provider may process audio off-device — do not call it local | (android/app/src/main/java/com/hermesvox/VoiceController.kt `createSpeechRecognizer`) |
| 4 | The mind | **remote** | your Hermes gateway: `POST /v1/responses` with `stream: true`, chained by `previous_response_id` | (android/app/src/main/java/com/hermesvox/core/HermesSession.kt `* previous_response_id on the next turn.`) |
| 5 | TTS | on-device | Supertonic (recommended), Piper, or the System voice as fallback | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `defaultTts`) |
| 6 | Presence (optional) | on-device | Gemma 4 E2B via LiteRT-LM on the GPU; speaks asides in the agent's mirrored voice | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("gemma-e2b", "Gemma 4 E2B (presence)", "express", "gemma-4-E2B-it.litertlm", 2588.1,`) |
| 7 | Avatar + words | on-device | GPU-drawn being and the speech-locked crawl | (docs/ARCHITECTURE.md `6,000 stateless particles`) |
| 8 | Phone actions | on-device (plus a tailnet listener while a call is live) | timers, alarms, do-not-disturb, volume, media, device state, clipboard, files, calendar, a contacts lookup by name, and handoffs: a text is composed for the owner's messaging app, the dialer opens with a number ready, apps launch or open. Notifications are a summary of counts per app while a call is live, then one notification's content by id — the listener is open only during a call. Nothing is sent or dialled by Vox. The caller's words are matched locally and never leave the phone, and the mind acts through one MCP tool that returns a result. Every capability group is Off until the owner grants it, and the schema offers only what is granted | (android/app/src/main/java/com/hermesvox/PhoneEngine.kt `fun registry(`) (android/app/src/main/java/com/hermesvox/PhoneGrants.kt `val GROUPS: List<Group> = listOf(`) |

Only leg 4 is a network peer. Barge-in cancels the stream rather than leaving it running
(docs/ARCHITECTURE.md `closing the stream aborts the generation`).

**What the agent is doing** is the pill under the agent's name — the state line, shown whenever
a step is running or a failure is held, not only during a call, and mirrored in the call
notification. While a tool runs it names the tool in plain words and what it acts on
("Searching the web · trail conditions"), says "still working" once the step is long (then an
elapsed count, never a made-up percentage) (android/app/src/main/java/com/hermesvox/Operations.kt `fun running`),
and on a step that reported an error says what failed and why, held until the next turn
(android/app/src/main/java/com/hermesvox/Operations.kt `fun failure`). A failed step gets the
being's failure ending — the hourglass in the waiting colour — so it cannot look like a
successful one. It is the agent's own report of the step, not a check that anything changed in
the world, and the transcript deliberately keeps no operations history: the line is the state
while it is current, and the reply is the record.

### One normal operation, with Presence as a layer

There is **one normal operation**: a hands-free open line with VAD, barge-in and a turn to
your gateway (android/app/src/main/java/com/hermesvox/OnboardingActivity.kt `HardwareCheck.firstRunLine(HardwareCheck.store(prefs))`).
Presence is a **layer on that**, not a second mode (docs/DECISIONS.md `presence is a layer`).
There is no mode selector and no mode label.

What the build does:

- Presence is **alpha** in 0.9.1 and sits behind the Alpha gate (#402): it runs only when
  **Alpha features** is on and the Presence set is selected
  (android/app/src/main/java/com/hermesvox/PresenceGate.kt `AlphaGate.allows(prefs, AlphaGate.FeatureSet.PRESENCE`)`).
- Inside the gate, one boolean, `presence_enabled`, default on
  (android/app/src/main/java/com/hermesvox/PresenceGate.kt `const val KEY_PRESENCE_ENABLED = "presence_enabled"`).
- The old `voice_mode` choice is migrated once: a stored `realtime` becomes off, a stored
  `enhanced` becomes on, an install that never touched it gets on
  (android/app/src/main/java/com/hermesvox/PresenceGate.kt `fun migrated`).
- Presence runs only when the model is downloaded **and** able to start
  (android/app/src/main/java/com/hermesvox/MainActivity.kt `private fun presenceActive(): Boolean = PresenceGate.active(`). When it
  cannot, the app is normal operation: no label, no degraded-mode pill.
- The Settings presence row is the only place the absence is explained
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `set_presence_status`),
  because it is the only place a user can act on it.
- Inside the layer, the narration switch (`er_presence`) defaults to on
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `swPresence.isChecked = prefs.getBoolean("er_presence", true)`).

So: a fresh install has Presence **off**, because Alpha features is off
(android/app/src/main/java/com/hermesvox/AlphaGate.kt `DEFAULT_MASTER = false`). An install
upgraded from a build where Presence was running keeps it: the master is turned on with the
Presence set selected (android/app/src/main/java/com/hermesvox/AlphaGate.kt `fun migrate(s: Store, presenceModelInstalled: Boolean) {`).
Do not tell a user Presence is a mode.

**The hardware check (#403).** At first run, for everyone, the app records total RAM and free
memory once (android/app/src/main/java/com/hermesvox/HardwareCheck.kt `event=hw-check stage=first-run`).
The bar is 8 GB of total RAM, read as the phone's marketed size
(android/app/src/main/java/com/hermesvox/HardwareCheck.kt `BAR_GB = 8`), with a free-memory
floor of 3 GB (android/app/src/main/java/com/hermesvox/HardwareCheck.kt `const val FREE_FLOOR_MB = 3_072L`).
The first call that loads Presence is the second stage: a failed load switches the Presence set
off and says so in the Alpha panel
(android/app/src/main/java/com/hermesvox/MainActivity.kt `event=hw-check stage=first-load`).
It only advises: the verdict appears in the Alpha panel under Presence and, when the phone falls
short, in first run with where to turn it on. It never selects anything.

---

## 3. The settings map

All prefs live in one `SharedPreferences` file, `hv`
(android/app/src/main/java/com/hermesvox/SettingsActivity.kt `getSharedPreferences("hv"`).
"Default" is what an untouched install reads. Settings is a home list of groups with nested
sections (android/app/src/main/res/layout/activity_settings.xml `grp_list`).

**0.9.1 renamed the sections by what the user changes (#224):** *Mic & Barge-in* is now
**Hearing**, *Spoken replies* is **Voice**, *Visuals* is **Being**; the Presence rows moved out of
*Entity & Connection* into their own **Presence** section, and the sound cues moved into **Being**.
Every row carries a one-line statement of what it changes
(android/app/src/main/java/com/hermesvox/SettingsHints.kt `package com.hermesvox`). A user on an
older build still sees the old names.

### Per profile, and what is not

A **profile** is a saved connection — and since the per-profile pass it is also *how that agent
presents*: its **voice**, its **model** and its **look**. The record carries them beside url/key/scope
(android/app/src/main/java/com/hermesvox/Profiles.kt `data class Profile`) as a map of the scoped
keys (android/app/src/main/java/com/hermesvox/ProfileScope.kt `val KEYS`), and a switch applies
all three: the being redraws, the next utterance speaks in the new voice, the next turn thinks with
the new model (android/app/src/main/java/com/hermesvox/MainActivity.kt `private fun switchProfile`).

- **The fallback.** The live `hv` prefs stay the store the app reads, and they are the device-wide
  default: a key a profile has no value for is not written on a switch, so the device-wide value
  stays in force. Nothing breaks for a record written before this pass, which has no values of its own
  (android/app/src/main/java/com/hermesvox/ProfileScope.kt `fun adopt`).
- **The one-time inheritance.** On first load, every profile with no values of its own takes the
  device-wide ones, once, guarded by `profile_scoped`; from then on they are its own, and a later
  device-wide change does not move them (android/app/src/main/java/com/hermesvox/MainActivity.kt `fun migrateScopedProfiles`).
- **A change belongs to who was on.** Leaving a profile records the live values into it, so a setting
  changed in Settings is that profile's (android/app/src/main/java/com/hermesvox/ProfileScope.kt `fun capture`).
- **The rows say so.** Every voice/model/look row carries "· profile <name>" on its second line —
  the same Settings screen, scoped, not a second screen per profile
  (android/app/src/main/java/com/hermesvox/SettingsHints.kt `val SCOPED`).

**Not per profile, and why:** the **connection** (address, key, scope — that *is* the profile); the
**Alpha gate** (it is about the build, not the agent); **Presence's own switches** (its on/off, its
cadence, its asides, its narration voice, its reasoning effort — the layer belongs to the phone the
agent is on); and **anything about the phone itself** — theme, screen layout, keep-screen-on, the
microphone and hearing, the sound cues, the wake word. `docs/PRESENCE.md` states the same list.

**The UI/UX pass re-grouped the home list by the question a person arrives with**, in set-up
order: Alpha features, Entity & Connection, **Hearing** (now also the recogniser — the old
*Transcription (STT)* section is its *Recognition* block), Wake word, **Voice & sounds** (the
voice, then the *Sound cues* block, moved out of Being), **Voice models** (the row opens the
model store itself; the one-row section is gone), Presence, **Appearance** (the old
*Appearance & Layout* rows as a *Screen* block, then the *Being* block), the assistant role,
About & Diagnostics. Each section has one restore row, and restoring asks first. A section's
title is read from the home row that opened it
(android/app/src/main/java/com/hermesvox/SettingsActivity.kt `findViewById<TextView>(R.id.set_title).text = sectionTitle(target)`).

### Alpha features (the first row of Settings, #402)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Alpha features | `alpha_features` | false | The master gate for everything unfinished. Off: every alpha row is dimmed and takes no tap, and nothing alpha runs. Turning it on slides out the panel of feature sets | (android/app/src/main/java/com/hermesvox/AlphaGate.kt `KEY_MASTER = "alpha_features"`) |
| Presence (set) | `alpha_set_presence` | false | Listed first in the panel. Lets Presence run (its own switch then applies). Its description states the cost: about 3 GB of memory while a call is live | (android/app/src/main/java/com/hermesvox/AlphaGate.kt `"alpha_set_presence"`) |
| Continuous listening (set) | `alpha_set_continuous_listening` | false | Lets continuous capture and semantic endpointing run; both of their own switches default on (D29). Requires Presence, because it is how the being hears: selected without Presence it is inert, and the picker refuses the tap with that line. Its description states about 300 MB more memory during a call | (android/app/src/main/java/com/hermesvox/AlphaGate.kt `"alpha_set_continuous_listening"`) |
| Phone tools (set) | `alpha_set_phone_tools` | false | The mind acting on the phone: the MCP `phone` tool, its tailnet server and its grants. Independent of Presence — either can be taken without the other (D38) | (android/app/src/main/java/com/hermesvox/AlphaGate.kt `"alpha_set_phone_tools"`) |
| Sessions (set) | `alpha_set_sessions` | false | Lets `/sessions` exist at all: the card that lists the conversations the host already has and picks one up. With it off the command is not on the sheet, a typed "/sessions" is ordinary conversation, and no path adopts a session. Leaves Alpha once a device pass shows conversation switching works as intended | (android/app/src/main/java/com/hermesvox/AlphaGate.kt `"alpha_set_sessions"`) |

Turning the master off keeps the selections, so turning it back on restores the same sets. The
dimmed rows are the Presence group row and, in Hearing, the tone, continuous-capture and
semantic-endpointing rows (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `gate(R.id.row_grp_presence, presence`)`).

Turning the master **on** also turns **Diagnostic summary** (`diag_summary`) on, because someone
who opts into alpha is a tester and should be able to hand us a bug without hunting for a switch
(android/app/src/main/java/com/hermesvox/SettingsActivity.kt `DiagnosticSummary.alphaTurnedOn(DiagnosticSummary.store(prefs`))`).
It is one-way — turning Alpha off does not turn it off — and it never overrules the user: a flip
of that switch records `diag_summary_chosen`, so an explicit off survives every later Alpha-on.

### Entity & Connection

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Profiles (the commands sheet, under *The agent*; the agent's name on the main screen also opens it) | `profiles`, `profile_active`, `profile_scoped` | the current connection, saved on first open | Saved connections — name, address (`https://host/p/<profile>` for a Hermes profile), key, model, scope, and the profile's own voice and look — one active at a time. It opens as a **sheet on the commands scaffold**: each row is the agent you would be talking to — its name, then the agent name, the model and the voice under it — the active one carries **IN USE**, `Add a profile` / `Edit a profile` / `Refresh from host` are rows of their own, and a switch closes the sheet and ends a live call first. The list is **asked in conversation** (#384) on connect and on an explicit refresh: the agent answers a `[[vox:profiles]]` turn with one `vox-profiles` block, every name is proved with `/v1/models`, and the proven list is cached — opening the switcher is instant and never waits on a model turn, and a refresh inside 60 s is refused. A refresh during a call waits for the call to end. A profile the host stopped serving is marked unavailable, never deleted. The header and notification name the active one once there are two; the header itself is display only | (android/app/src/main/java/com/hermesvox/MainActivity.kt `agentName.setOnClickListener { profiles.showSwitcher() }`)`) |
| Test entity connection | — | — | One probe of `/v1/models` (+ optionally `/v1/responses`), reported as ok / warming / key / FAILED | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `testConnectionAsync`) |
| Entity endpoint | `url` | "" | The gateway base URL; also decides whether the app is "configured" | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `AppSkillInstall.ask(this, s, versionName(), AppSkill.profileOf(prefs.getString("url", "").orEmpty()))`)`) |
| Entity name | `agent_name` | the host's `data[0].id`, else `model` | The name above the being and in Settings → Entity. It is **read-only and host-derived**: the same `GET /v1/models` probe as connect adopts `data[0].id`; a 200 with no id says "connected, but the host did not name itself" and keeps the fallback. It is not the request route | (android/app/src/main/java/com/hermesvox/OnboardingActivity.kt `HostEntity.answer`) |
| Teach your agent this app | (action) | — | Asks the agent to install this skill on its gateway, or copies the `hermes skills install …` command (with `-p <profile>` for a profile address) | (android/app/src/main/java/com/hermesvox/AppSkill.kt `fun command`) |
| Teach your agent this app — first run | `app_skill_offered` | unset | The same install, offered once right after the connection is verified ("Teach your agent this app?", Ask the agent / Copy command / Later). The answer is remembered either way; it is an offer, never an automatic install | (android/app/src/main/java/com/hermesvox/AppSkillInstall.kt `fun offerOnce`) |
| API key | `key` | "" | Bearer credential, Keystore-encrypted at rest | (android/app/src/main/java/com/hermesvox/core/Entity.kt `Bearer`) |
| Model | `model`, `provider` | `hermes-agent` | The model route sent on every request; chosen from the gateway's model list, never typed in Settings → Entity (that field is the host-derived entity name). It is **this profile's** model: the Settings → *Agent model* row opens the same model sheet `/models` does, and says whose it is | (android/app/src/main/java/com/hermesvox/EntitySession.kt `DEFAULT_MODEL = "hermes-agent"`) |
| Agent model | `model` (per profile) | the profile's, else the device-wide one | The Settings row that makes the model visible: it shows the route as words ("DeepSeek V4 Flash") and opens the model sheet. Choosing one persists it, sets it on the live session and re-inits the conversation, so the next turn uses it | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `fun bindAgentModel`) |
| Entity scope | `session_scope` | "" (blank) | Optional `X-Hermes-Session-Key`; blank means "declare no identity" | (android/app/src/main/java/com/hermesvox/SessionScope.kt `session_scope`) |
| New conversation | (action) | — | Clears context and ends any live call | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `// #113: "New conversation" wipes the live conversation (and ends any active`) |

### Hearing (was "Mic & Barge-in")

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Barge-in | `duplex` | true | Talking over the entity interrupts it | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `barge.isChecked = prefs.getBoolean("duplex", true)`) |
| Noise & echo cancel (AEC) | `mic_aec` | true | Uses `VOICE_COMMUNICATION` capture (hardware AEC/NS) instead of raw MIC | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `set_mic_aec`) |
| Extra noise suppression | `ns_extra` | true | Adds a platform `NoiseSuppressor` on the record session | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `set_ns_extra`) |
| Streaming partial transcripts | `partial_stt` | true | Runs partial decoding to end a turn early on a finished-sounding pause | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `set_partial_stt`) |
| Voice-call echo routing | `tts_voice_usage` | **false** | On: playback uses the voice-communication usage so the platform AEC gets an echo reference; off (the field-A/B default) plays on the media stream and measured louder | (android/app/src/main/java/com/hermesvox/SherpaTts.kt `kill-switch`) |
| Continuous capture (alpha) | `continuous_capture` | true, behind Alpha | Keeps the mic open and reads the recogniser every 100 ms; runs only with the continuous-listening set selected | (android/app/src/main/java/com/hermesvox/VoiceController.kt `listeningAlpha && micBool(ContinuousCapture.KEY`) |
| Judge when you finish (alpha) | `semantic_endpoint` | true, behind Alpha | Ends the turn on a judgement of the partial instead of a fixed silence; needs continuous capture | (android/app/src/main/java/com/hermesvox/VoiceController.kt `listeningAlpha && micBool(SemanticEndpoint.KEY`) |
| The soul hears your tone | `er_soul_hears` | true | Gemma's audio encoder reads the caller's tone; audio never leaves the phone. A hearing setting, not a Presence-behaviour one | (android/app/src/main/java/com/hermesvox/SoulAudio.kt `"er_soul_hears"`) |
| Mic sensitivity (VAD) | `vad_threshold` | 0.50 | How sure Silero must be before a sound counts as speech | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindFloatSeekBar(R.id.set_seek_vad, R.id.set_mic_vad_val, "vad_threshold",`) |
| Pause to end your turn | `vad_silence_ms` | 800 ms | Silence that closes an utterance | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_silence, R.id.set_mic_silence_val, "vad_silence_ms",`) |
| Early-start pause | `vad_early_silence_ms` | 450 ms | How final a partial must sound before the turn ends early | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_early, R.id.set_mic_early_val, "vad_early_silence_ms",`) |
| Min speech before a turn | `vad_min_speech_ms` | 300 ms | Shortest speech that becomes a turn | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_min, R.id.set_mic_min_speech_val, "vad_min_speech_ms",`) |
| Max speech per turn | `vad_max_ms` | 15000 ms | Past this, the segment only closes on a real pause — a continuous speaker is not chopped | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_max, R.id.set_mic_max_val, "vad_max_ms",`) |
| Barge sensitivity | `barge_rms_min` | 0.10 | The ceiling for the playback barge floor; the floor itself is measured from the reply's echo | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindFloatSeekBar(R.id.set_seek_barge_rms, R.id.set_mic_barge_rms_val, "barge_rms_min",`) |
| Barge grace | `barge_grace_ms` | 500 ms | Ignore interruption attempts this soon after speech starts | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_barge_grace, R.id.set_mic_barge_grace_val, "barge_grace_ms",`) |
| Level-only barge | `barge_level_only_ms` | 200 ms | Sustained loud voice cuts the entity even when VAD misses it through echo; 0 disables | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_barge_level, R.id.set_mic_barge_level_val, "barge_level_only_ms",`) |

### Hearing › Recognition (was Transcription (STT))

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Speech-to-text backend | `stt_backend` | `on-device` | `on-device`, `platform` (Google), or `remote` (a server you run) | (android/app/src/main/java/com/hermesvox/VoiceController.kt `val backend = prefString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE)`) |
| STT model | `stt_model` | `whisper-base` | Which offline recogniser runs | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `const val DEFAULT_STT_MODEL = "whisper-base"`) |
| (streaming arm) | `stt_stream_model` | unset | Selecting a streaming model stores it here and leaves `stt_model` as the fallback | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `stt_stream_model`) |
| Voice CPU threads | `voice_threads` | Auto (0) | Thread override for STT and TTS; applies on the next model load | (android/app/src/main/java/com/hermesvox/VoxThreads.kt `fun stt`) |
| Remote STT URL | `stt_remote_url` | "" | The server audio is POSTed to; blank means the remote leg is unavailable | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindRemoteTextField(R.id.stt_remote_url, KEY_STT_REMOTE_URL) { it.trim() }`) |
| Remote STT model | `stt_remote_model` | `whisper` | The model name sent to that server | (android/app/src/main/java/com/hermesvox/RemoteStt.kt `internal const val DEFAULT_REMOTE_MODEL = "whisper"`) |
| Remote STT key | `stt_remote_key` | "" | Encrypted bearer key for that server | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindRemoteTextField(R.id.stt_remote_key, KEY_STT_REMOTE_KEY) { SecureStore.encrypt(it) ?: it }`) |
| Test STT server connection | (action) | — | Probes `/v1/models` then `/api/v1/models` and reports ok/FAILED | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `VoxLog.dd("event=stt-remote-test ok=false reason=no-url")`) |

### Voice (TTS; was "Spoken replies")

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Text-to-speech | `tts` | unset → best installed (`supertonic` → `piper` → `system`) | Which engine speaks | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `fun defaultTts`) |
| Delivery | `voice` | `system` (shown as "Natural") | Synthesis register: Natural / Bright / Deep | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `private fun deliveryLabel(): String = when (prefs.getString("voice", "system")) {`) |
| Speaker | `tts_speaker_<model>` | Supertonic F2 (id 1), Piper 0 | The engine's built-in voice; per engine | (android/app/src/main/java/com/hermesvox/VoiceSpeakers.kt `fun defaultId(engine: SherpaVoice): Int = when (engine) {`) |
| Speak responses | `speak_responses` | true | Whether replies are voiced at all | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `speak.isChecked = prefs.getBoolean("speak_responses", true)`) |

**Engine, register and speaker belong to the profile.** All three rows carry "· profile <name>", are
recorded into the profile you are on when you switch away, and are written from the profile you switch
to — so two profiles can speak in different voices on the same phone. `speak_responses` (whether to
voice replies at all) stays device-wide: it is about how you use the phone, not who you are talking to.

### Presence

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Presence | `presence_enabled` | true | The layer itself: the being, its voice and its asides. The switch is on by default but applies only behind the Alpha gate; it runs only when the model is downloaded and able to start | (android/app/src/main/java/com/hermesvox/PresenceGate.kt `const val KEY_PRESENCE_ENABLED = "presence_enabled"`) |
| Soul narration | `er_presence` | true | Presence's spoken asides (mm, breath, fillers); off keeps the layer wired but quiet | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `swPresence.isChecked = prefs.getBoolean("er_presence", true)`) |
| Semantic barge-in | `er_semantic_barge` | true | Lets "take your time" keep the mind working instead of cancelling it | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `swBarge.isChecked = prefs.getBoolean("er_semantic_barge", true)`) |
| Narration voice | `er_presence_voice` | `sounds` | `silent` (motion only), `sounds` (mm/breath clips), `spoken` (sentence fillers) | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `val cur = prefs.getString("er_presence_voice", "sounds") ?: "sounds"`) |
| Presence while working | `er_presence_density` | `conversational` | How often Presence speaks during a long mind run: quiet 30 s / conversational 18 s / chatty 10 s intervals, with caps | (android/app/src/main/java/com/hermesvox/PresenceCadence.kt `val DEFAULT = Density.CONVERSATIONAL`) |
| Voice-call reasoning | `voice_reasoning_effort` | `low` | How hard the mind thinks on a voice turn (`off`, `low`, `medium`, `high`), sent per request as `model_options.reasoning`. Lower answers sooner; a provider that ignores it behaves as before | (android/app/src/main/java/com/hermesvox/VoiceTurn.kt `PREF_REASONING`) |
| Echo guard | `er_echo_skip_ms` | 700 ms | Silences barge capture for this long after Presence speaks, so the phone does not hear itself | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `val curEcho = prefs.getFloat("er_echo_skip_ms", 700f).toInt()`) |
| Copy diagnostic summary | — | — | Puts the `vox-diag` block on the clipboard for a `presence-report`: build, preset, device, mind side, e2, session counters, on-device model. No transcript, key, URL or hostname. A metric the build cannot measure is omitted, never printed as zero; docs/PRESENCE-REPORTS.md lists what is excluded. `/diagnostics` is the second door to the same bytes (`DiagnosticClipboard` copies one block from one collector); this row stays inside Presence | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `row_presence_diag`) |
| Resync VOX.md | (file, not pref) | not mirrored | Pulls the agent-authored voice-export and mirrors it read-only | (android/app/src/main/java/com/hermesvox/VoxSync.kt `event=vox-resync profile=`) |

### Wake word (preview)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Wake word | (no pref) | "0 of 8 takes · not listening" | Records enrolment takes into an encrypted store; nothing is trained and nothing listens | (android/app/src/main/res/layout/activity_settings.xml `Wake word (preview`)`) |

The row's subtitle is computed from the take count and the words "not listening"
(android/app/src/main/java/com/hermesvox/SettingsActivity.kt `not listening`).

### Appearance (Screen + Being), About

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Theme | `theme` | `system` | System / Dark / Light | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `label("theme", "system") + " · " + label("layout_mode", "presence") + " · " +`)`) |
| Screen layout | `layout_mode` | `presence` | "Being" (avatar fills the screen) or "Transcript" | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `label("theme", "system") + " · " + label("layout_mode", "presence") + " · " +`) |
| Keep screen on during calls | `keep_screen_on` | true | A window flag while a call is open; no wake lock | (android/app/src/main/java/com/hermesvox/ScreenAwake.kt `KEEP_SCREEN_ON_DEFAULT = true`) |
| Visual category | `visual_category` | `lumen` | Re-colours/re-lights/re-paces the being in every state | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `DEFAULT = "lumen"`) |
| Cycle all categories | `visual_cycle_all` | false | Rotates through every visual family while idle | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `DEFAULT_CYCLE_ALL = false`) |
| Energy | `visual_energy` | 1.0× | Motion energy multiplier | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `DEFAULT_ENERGY = 1f`) |
| Glow / bloom | `visual_glow` | 1.0× | Bloom multiplier | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `DEFAULT_GLOW = 1f`) |
| Density | `visual_density` | 100 % | How many of the particles are drawn (30–100 %) | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `KEY_DENSITY`) |
| Speed | `visual_speed` | 1.0× | How fast the being's own clock runs — drift, shimmer, breath | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `KEY_SPEED`) |
| Trail | `visual_trail` | the category's | Light trails behind each particle; 0 keeps the category's own | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `KEY_TRAIL`) |
| Hue | `visual_hue` | 0° | Turns the whole palette around the colour wheel | (android/app/src/main/java/com/hermesvox/VisualStyle.kt `KEY_HUE`) |
| Speaking / Listening / Thinking shape | `visual_shape_speaking` / `_listening` / `_thinking` | `soundwave` / `eye` / `radar` | Which shape fires in each active state | (android/app/src/main/java/com/hermesvox/AvatarView.kt `DEFAULT_SHAPE_SPEAKING = "soundwave"`) |
| Presence shape / theme | `particles_theme` | `aura` | The idle shape family | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `val theme = prefs.getString("particles_theme", "aura") ?: "aura"`) |
| Cycle themes while idle | `particles_cycle` | true | Rotates the idle shape | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `cyc.isChecked = prefs.getBoolean("particles_cycle", true)`) |
| Sound cues | `sound_cues` | true | The being's own thinking/listening/error cues | (android/app/src/main/java/com/hermesvox/CuePolicy.kt `DEFAULT_ENABLED = true`) |
| Cue level | `sound_cue_level` | 70 % | Relative to media volume | (android/app/src/main/java/com/hermesvox/CuePolicy.kt `DEFAULT_LEVEL = 70`) |
| Cues play | `sound_cue_scope` | `presence_off` | **Only when Presence is off** (default) or **Always**. While Presence speaks its asides, cues would double the same channel, so by default they are quiet then — "I hear no cues" with Presence on is this setting, logged as `event=cue … reason=presence` | (android/app/src/main/java/com/hermesvox/CuePolicy.kt `DEFAULT_SCOPE = SCOPE_PRESENCE_OFF`) |
| Soft ping while the agent works | `sound_cue_ping` | true | A quiet single note every 6 s while the agent is working, stopping when the answer arrives | (android/app/src/main/java/com/hermesvox/CuePolicy.kt `DEFAULT_PING = true`) |
| Check in after a silent call | `sound_cue_idle_s` | 120 | After this many seconds of a call with no input and no output, one rising two-note cue (`event=cue-idle`); never / 1 / 2 / 5 min | (android/app/src/main/java/com/hermesvox/CuePolicy.kt `DEFAULT_IDLE_S = 120`) |
| Cues when the ringer is silent | `sound_cues_silent` | false | Opt-in: cues still play (or tap) when the ringer is silent | (android/app/src/main/java/com/hermesvox/CuePolicy.kt `DEFAULT_SILENT = false`) |
| Developer console | `dev_console` | false | Shows the raw SSE drawer on the main screen | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `devc.isChecked = prefs.getBoolean("dev_console", false)`) |
| Log spoken transcript | `log_transcripts` | false | Prints what was heard/decided into logs — privacy sensitive | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `ltr.isChecked = prefs.getBoolean("log_transcripts", false)`) |
| Verbose file log | `debug_log` | false | `dd()` probes reach the log file; also disables log rotation | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `dbl.isChecked = prefs.getBoolean("debug_log", false)`) |
| Diagnostic summary | `diag_summary` | false | Adds the `/diagnostics` command to the commands sheet: it copies the same `vox-diag` block the Presence row copies. The switch gates the command's existence and nothing else — the Presence row keeps its own copy. Turning Alpha features on turns it on once; an explicit off (`diag_summary_chosen`) wins over every later Alpha-on | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `DiagnosticSummary.on`) |

**The being's rows are the profile's; the screen's are the phone's.** Visual category, its five
scalars, cycle-all, the resting shape and its rotation, and the three state shapes carry "· profile
<name>" and travel with the profile — switching profiles redraws the being at once. Theme, screen
layout and keep-screen-on stay device-wide: they are about the phone the agent is on.

### The phone's assistant (#329)

The Settings home has one more row: **Be the phone's assistant**
(android/app/src/main/res/layout/activity_settings.xml `row_grp_assistant`). It is the only
place the app ever asks for the role, and it asks nothing at first launch.

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Be the phone's assistant | — | off | Reads Android's `android.app.role.ASSISTANT` state; the first row asks Android for the role (its consent dialog where the build allows one, then an honest fallback), the second opens Android's digital-assistant screen — the only place the role can be taken back, since there is no relinquish API | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `findViewById<android.view.View>(R.id.row_assistant_request)?.setOnClickListener { requestAssistantRole() }`) |
| What the gesture then does | — | — | A live call is focused (never a second session); an idle configured app opens the call screen ready to listen; a first run goes to onboarding | (android/app/src/main/java/com/hermesvox/AssistRoute.kt `fun target`) |
| The entry point Android calls | — | — | A translucent, no-history bridge over `android.intent.action.ASSIST` that hands `MainActivity` the `EXTRA_ASSIST` extra with `CLEAR_TOP`/`SINGLE_TOP` | (android/app/src/main/java/com/hermesvox/AssistActivity.kt `putExtra(MainActivity.EXTRA_ASSIST`) |

Two facts worth repeating to a user, both measured: on the verification build Android refuses
the in-app role request outright (`E RequestRoleActivity: Role is not requestable`), so the
working path is Android's own screen, where Vox is listed; and several apps can qualify, so the
row cannot name the current holder — it says "another app", not a guess
(android/app/src/main/java/com/hermesvox/AssistantRole.kt `fun summary`). The manifest
declaration is what makes the app eligible
(android/app/src/main/AndroidManifest.xml `<!-- #329: the digital-assistant entry point. Handling android.intent.action.ASSIST`).

### Commands (the `/` button on the main screen)

Grouped by what a person is acting on — **This conversation** and **The agent** — never by the
subsystem that does the work (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `enum class Group`). Each row leads with a mark drawn from
the being's six-verb motion grammar for that kind of command, amber only when something is lost
for good (android/app/src/main/java/com/hermesvox/VerbMark.kt `class VerbMark`). Each row
states its effect in one line and, only when the command loses something, what it loses in a
second line (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `val cost`). `/help` is not listed on the sheet (it would open a copy of the
sheet); a typed "/help" and the search still reach it (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `val listed`).

A command that runs in place shows *queued / running… / done / failed* on the row where it was
tapped, instead of a toast (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `enum class RunState`).
Past two seconds the word becomes "still working…" and never a number that pretends to be
progress (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `fun stateText`). A command that opens its own card shows no state on its row —
the card is its report (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `fun reportsOnRow`).

| Command | Group | What it does | What it loses | Reference |
|---|---|---|---|---|
| `/compress` | This conversation | (also `/compact`) Asks the agent to fold a long thread into a summary | Older detail survives only in the summary | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Fold a long thread`) |
| `/clear` | This conversation | (also `/reset`) Clears the screen; the agent keeps the thread, and a live call is re-dialled | The screen's copy, for good | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Clear the screen`) |
| `/new` | This conversation | A fresh thread; the agent forgets this one. Ends and re-dials a live call | This thread, for good | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Start a fresh thread`) |
| `/sessions` | This conversation (only while the Alpha **Sessions** set is on) | Opens the session card: the conversations the host already has, newest activity first, with the one in use marked. Picking one continues THAT conversation and shows what was said | nothing — the session keeps its history and so does this app | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Pick up a thread`) |
| `/models` | The agent | Opens the model switcher: provider → category → model | Switching starts a fresh thread | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Choose the model`) |
| `/profiles` | The agent | Opens the profile switcher: which agent you are talking to, with its voice, model and look | Switching starts a fresh conversation | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Talk to another`) |
| `/reconnect` | The agent | Reaches the gateway again and re-dials a live call | nothing | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Reach the gateway again`) |
| `/health` | The agent | (also `/status`) Whether the agent is up, and its version | nothing — it only asks | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Whether the agent is up`) |
| `/diagnostics` | The agent (only while `diag_summary` is on) | Copies the `vox-diag` report block — the same one the Presence row copies, from the same collector | nothing — it reads and copies | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `Copy a report block`) |
| `/help` | The agent (not listed on the sheet) | The list, with these same lines | nothing — it only asks | (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `What each command does`) |

Those ten (with the aliases `/status`, `/reset`, `/compact`) are the whole catalogue — `/diagnostics`
only while its switch is on, `/profiles` only while the Alpha *Switching profiles* set is on, and
`/sessions` only while *Sessions* is. A command added without a group, an effect or an alias target fails
`CommandCatalogTest.everyCommandBelongsToALabelledGroup`
(android/app/src/test/java/com/hermesvox/CommandCatalogTest.kt `everyCommandBelongsToALabelledGroup`).
**There is no `/file` command.** The dispatch has exactly the ten names above
(android/app/src/main/java/com/hermesvox/MainActivity.kt `when (c.name) { "/profiles" -> profiles`)`), and no build of
this tree has ever had a handler for one. Files arrive a different way: the **Share with agent**
row inside the commands sheet, or sharing one to Vox from another app, offers it to the agent —
see *Share with agent* below. Do not invent a `/file` command; do not say files are impossible
either.
### Browsing and resuming a session (#437)

The host has always held these: `GET /api/sessions` lists what it has for this key — telegram
sessions, `api_server` sessions and the `vox_…` ones this app wrote — and
`GET /api/sessions/{id}/messages` is their transcript. `/sessions` is the way in, and it is in
the catalogue only while the Alpha **Sessions** set is on
(android/app/src/main/java/com/hermesvox/Sessions.kt `fun refusal`): with it off the row is
absent from the sheet, the search and `/help`, a typed or dictated "/sessions" is ordinary
conversation, and the dispatch and the pick both refuse
(android/app/src/main/java/com/hermesvox/MainActivity.kt `private fun adoptSession`).

**The list is asked at the host, not at the profile.** A profile is addressed at
`https://host/p/<name>`, and on a real gateway the root answers 200 for the same key a profile
path answers 401 for (#436). So the base is
(android/app/src/main/java/com/hermesvox/Sessions.kt `fun rootBase`) — the profile listing's
own `<HostProfiles.rootBase>` rule, not a second copy of it — with the key the profile already
holds and **no** `X-Hermes-Session-Key` (this is not a turn)
(android/app/src/main/java/com/hermesvox/SessionsClient.kt `Sessions.rootBase(profileBase`)`).

**The card.** Rows are newest activity first (end, else start)
(android/app/src/main/java/com/hermesvox/Sessions.kt `fun order`), each with the host's title,
its source, its age and its message count when the host states one (the list route documents no
count, so an absent count is left out rather than printed as zero). The one in use carries
**in use**. A long list pages with the host's own `has_more`/`offset` behind a **More** row, never
a silent cut (android/app/src/main/java/com/hermesvox/SessionsSheet.kt `private fun more`).

**Four states, four lines, and no fifth**
(android/app/src/main/java/com/hermesvox/Sessions.kt `const val KEY_REJECTED = "the key was not accepted for the host's session list"`): a key the host refused
("the key was not accepted for the host's session list"), a host that did not answer, a host with
no sessions ("no sessions on this host"), and a transcript that did not load. A 401 is never
rendered as an empty list.

**Picking one is setting a field.** `POST /v1/runs` ranks an explicit body `session_id` above the
response chain and above the declared key, so adoption is
(android/app/src/main/java/com/hermesvox/core/HermesSession.kt `fun adoptConversation`) and
nothing else: the id then rides every following turn, `/compress` included. It is **not** a
profile switch — the call and the profile stay put.

**The transcript is the host's.** A resumed session may be written by another surface at the same
time, so the app re-reads it on resume, on `/reconnect` and on returning to the app after a gap of
30 s (android/app/src/main/java/com/hermesvox/Sessions.kt `const val GAP_MS = 30_000L`). It never appends what it
believes it has: the block is replaced whole, merged by message identity — the host's `id` when
there is one, else `created_at` plus `role`
(android/app/src/main/java/com/hermesvox/Sessions.kt `fun merge`). The app's own reply is
recorded as an echo with no id and no timestamp, and disappears the moment the host serves the
same words, so a reconnect shows one copy of every line. Both reading surfaces show the same block
— the crawl as its history (android/app/src/main/java/com/hermesvox/MainActivity.kt `private fun paintTrail`)
and the conversation layout before this device's own turns
(android/app/src/main/java/com/hermesvox/Transcript.kt `fun setHistory`).

**Per profile.** Sessions belong to a profile: the adopted id, the transcript read from it and the
listing that offered it are one piece of state
(android/app/src/main/java/com/hermesvox/Sessions.kt `class Adoption`), and a profile switch, a
new conversation or a saved connection clears all three
(android/app/src/main/java/com/hermesvox/MainActivity.kt `private fun clearAdoptedSession`).
A `/reconnect` deliberately does not — it re-dials the same conversation and reads it again
(android/app/src/main/java/com/hermesvox/MainActivity.kt `adoption.adopt(rejoin`)`).

The **Being** section opens with a live preview: the same being the main screen draws, from the
same prefs, walking through resting, listening, working and speaking — so a look is judged in
Settings, not by leaving it (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `private val previewStates = arrayOf("idle" to "resting", "listening" to "listening",`).
Its five look sliders are bound from one table, so a slider can only write the pref it names
(android/app/src/main/java/com/hermesvox/LookControls.kt `object LookControls`), each reads
"current · range" and carries a one-tap reset. The preview renders only while Appearance
is in front: leaving the section, or the screen pausing, releases the render thread and its GL
targets (android/app/src/main/java/com/hermesvox/AvatarView.kt `fun setPaused`).

**How the being shows a command.** Each command's kind maps to one of six motions — sweep
(inspect), pulse (choose), converge (compress), scatter (start over), ring (reconnect), settle
(help) — held while it runs; it ends in a bloom when it worked and in the hourglass, in the
waiting colour, when it failed (android/app/src/main/java/com/hermesvox/CommandMotion.kt `fun verbFor`).
The log says which: `event=command-motion` (android/app/src/main/java/com/hermesvox/MainActivity.kt `event=command-motion`).

**Finding a command.** The commands screen has a search box that matches what a person wants
("it's slow", "can't reach it", "start over"), not only command names
(android/app/src/main/java/com/hermesvox/CommandCatalog.kt `fun search`), and leads with what fits the moment —
*reconnect* and *health* after a failed turn, *compress* in a long conversation
(android/app/src/main/java/com/hermesvox/CommandCatalog.kt `fun suggest`). A failed turn's line on the main screen
ends "tap to reconnect" and does it; on a caller's first three calls, twenty silent seconds bring
the hint "Ask anything — or say "what can you do?"" (android/app/src/main/java/com/hermesvox/Discovery.kt `const val STUCK_AFTER_MS = 20_000L`).

### Share with agent (the `⌘` entry)

The main action row has the call glyph and **one** commands entry, `⌘` (U+2318). It is drawn
as a local vector path, so it never depends on a system font shipping the glyph, and its label
is still "Commands". Typing `/` on the main screen opens the same sheet
(android/app/src/main/java/com/hermesvox/MainActivity.kt `fun dispatchKeyEvent`).

The first row inside that sheet is **Share with agent**. It is an artifact action, not a slash
command: it sits outside the nine-command catalogue, inherits the row grammar and the CHOOSE →
pulse motion (android/app/src/main/java/com/hermesvox/CommandCatalog.kt `val SHARE`). It
opens a chooser — the clipboard, or any file on the device — and then **one confirmation card**
with the file's name, type and size
(android/app/src/main/java/com/hermesvox/ShareSheet.kt `class ShareSheet`). Nothing is sent
until SEND. An incoming `ACTION_SEND`, `ACTION_SEND_MULTIPLE` or `ACTION_VIEW` lands on that
same card; the share target is not a licence to send on arrival
(android/app/src/main/java/com/hermesvox/MainActivity.kt `fun routeShareIntent`).

The clipboard is rendered as a **file**, never as pasted text. A URL or any other string
becomes `clipboard-2026-11-02T14-31.txt`; an image keeps its bytes and becomes
`clipboard-2026-11-02T14-31.png`; the extension comes from one mime table
(android/app/src/main/java/com/hermesvox/ShareSurface.kt `object ShareSurface`). An empty or
unreadable clip is refused in words. There is no PASTE button and no FILE button any more: both
acts are rows inside the sheet, and the card is also the conversation's transcript line —
`You: shared meeting-notes.md · text/markdown · 8.1 KB` — never the bytes.

### Offering a file (SPEC-conversational-bridge §B)

The API refuses `file` parts by design, so the app offers a file instead of uploading one: the
phone copies it into private staging, serves it **once** at a 128-bit token on the **tailnet
address only** for five minutes, and the turn carries the name, type, size, sha256 and URL.
The agent fetches it with `curl`, verifies the hash and keeps it
(android/app/src/main/java/com/hermesvox/FileOffer.kt `object FileOffer`). No bytes go through the
model, nothing is uploaded to the gateway, and the transcript shows only the metadata.

- **Cap 100 MB**, refused above it with the number in the line; **text under 8 KB** travels
  inline in a fenced block labelled with the name, with no server at all
  (android/app/src/main/java/com/hermesvox/FileOffer.kt `const val MAX_BYTES = 100_000_000L`).
- **One GET.** A second request gets `410 Gone`; the listener dies five minutes after the
  offer. Every refusal is logged and the token never is
  (android/app/src/main/java/com/hermesvox/FileServer.kt `class FileServer`).
- **Only the tailnet binds.** No `0.0.0.0`, no Wi-Fi, no cellular: a `100.64.0.0/10` or
  `fd7a:115c:a1e0::/48` address is the only candidate. With Tailscale down the app starts no
  server and says so; a small note is then offered inline instead
  (android/app/src/main/java/com/hermesvox/Tailnet.kt `object Tailnet`).
- **The agent verifies; the app reports.** A reply that says the hash or size did not match is
  shown as `the agent received a different file than we sent · nothing was kept`, never as
  success (android/app/src/main/java/com/hermesvox/FileOffer.kt `const val MISMATCH_LINE = "the agent received a different file than we sent · nothing was kept"`). A reply
  that does not say the file was kept is not a collection either — the app waits rather than
  reading any friendly sentence as one. If the transfer completed and the agent never said
  where it kept it, the expiry line says exactly that instead of inventing a path.
- **The staged copy is deleted however the flow ends** — collected, mismatched, expired,
  cancelled or the screen going away (android/app/src/main/java/com/hermesvox/FileShare.kt `class FileShare`).

### Voice models (the store; the home row opens it)

| UI | Key | Default | What it changes | Reference |
|---|---|---|---|---|
| Model source | `model_source` | the k2-fsa upstream release URL | Where model archives are fetched from; scheme-less input falls back to the default | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `const val DEFAULT_SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"`) |
| Licence acceptance | `model_licence_accepted_<id>` | none | Stores the licence name the user accepted; the name changing asks again | (android/app/src/main/java/com/hermesvox/ModelLicences.kt `model_licence_accepted_`) |

**Profiles, said plainly.** A profile is saved only after the gateway answers `/v1/models`
with its key: "Not served" means that gateway has no such profile, "Key rejected" means each
profile needs its own key (android/app/src/main/java/com/hermesvox/Profiles.kt `NOT_SERVED("Not served — this gateway has no such profile"),`).
The list is asked in conversation (#384), not from a route: the app sends one
`[[vox:profiles]]` chat turn and the agent answers with a `vox-profiles` block, while a switch
still ends a live call and starts a new conversation
(android/app/src/main/java/com/hermesvox/MainActivity.kt `session?.resetConversation() LatencyStats.resetSessionTurns() // C3: a reset conversation is a fresh session`). The answer
is cached; opening the switcher never asks, a refresh inside 60 s is refused, and a refresh
during a call runs when the call ends
(android/app/src/main/java/com/hermesvox/ProfilesAsk.kt `fun decide`). A host that does
not answer with the block falls back to by-hand profiles and says so in one line.

### Answering the app's profile ask (`[[vox:profiles]]`)

This is the one thing this skill asks the agent to do. The switcher asks the agent for the
profiles it serves; there is no `GET /v1/profiles` route to implement and nothing to install.

When a message begins with `[[vox:profiles]]`, run `hermes profile list` (or read the
gateway's profiles directory) and reply with **only** one fenced block labelled
`vox-profiles`:

```vox-profiles
default=home
profile=home
profile=work
```

- `default=<name>` — the default profile, zero or one line. Omit it when there is no default.
- `profile=<name>` — one line per profile, including the default.
- A name matches `[a-z0-9][a-z0-9_-]{0,31}`. Anything else is dropped by the client.
- No prose, no introduction, no closing question, no second block.

**Why the shape matters:** the client parses the block and fills its switcher from it. An
essay — however helpful — makes the feature fail, because prose instead of a block is read as
"this host did not answer with a profile list", and a block whose names all fail the charset
is read as "this host named no usable profiles". The client then probes each name with
`GET /v1/models` (the default at the host, a named profile under `/p/<name>`); only a 200
makes it usable, and a name that does not answer is shown as unavailable rather than saved.
The exact user turn the app sends is
(android/app/src/main/java/com/hermesvox/ProfilesAsk.kt `const val ASK`):

```
[[vox:profiles]] Which Hermes profiles does this gateway serve to this key? Reply with one fenced vox-profiles block and nothing else.
```

The parser accepts one label and one shape
(android/app/src/main/java/com/hermesvox/ProfilesAsk.kt `const val LABEL`); the app never
speaks this reply and never puts it in the call transcript
(android/app/src/main/java/com/hermesvox/ProfileDialogs.kt `fun refreshFromHost`).

### Prefs that have no Settings screen

Do not send a user hunting for these.

- `vad_max_hard_ms` — hard utterance ceiling, read with a default of 0 = disabled
  (android/app/src/main/java/com/hermesvox/EndpointRule.kt `DEFAULT_HARD_MS = 0`). This tree
  never writes it, so the ceiling is always off; do not offer it as an adjustable setting.
- `stt_language` — decode language for a **multilingual** Whisper; the shipped `.en` models
  ignore it and always decode English (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `const val KEY_STT_LANGUAGE = "stt_language"`).
  This tree never writes it either, so multilingual models always auto-detect.
- `provider` — the per-request provider override, written when a model is picked from the
  gateway's model list (android/app/src/main/java/com/hermesvox/MainActivity.kt `putString("provider"`).
- `agent_name` — the label above the being; read with the model id as its fallback
  (android/app/src/main/java/com/hermesvox/MainActivity.kt `val agent = prefs.getString("agent_name", "").orEmpty().ifBlank { ModelChoices.displayName(model) }`). It is written only
  from a verified host answer (`data[0].id`), by onboarding's connect and Settings → Entity's
  on-open refresh (android/app/src/main/java/com/hermesvox/HostEntity.kt `fun parse`), never
  by free text.
- `er_filler_cap` — a legacy slider, read only to migrate an upgraded install into
  `er_presence_density` (android/app/src/main/java/com/hermesvox/PresenceCadence.kt `LEGACY_PREF`).
  Nothing writes it any more.
- `presence` — a second narration gate read alongside `er_presence` when a tool call wants a
  spoken line (android/app/src/main/java/com/hermesvox/MainActivity.kt `"presence", true`).
  Read with a default of true and never written by this tree, so treat it as permanently on.

---

## 4. The log vocabulary

### Where the log is, and what you will actually see

- Logcat tag `HermesVox`, plus a rolling file at `filesDir/logs/hermes-vox.log`
  (android/app/src/main/java/com/hermesvox/VoxLog.kt `file = File(context.filesDir, "logs/hermes-vox.log")`).
- The file is capped at 5 MB and rotates once to `hermes-vox.log.1`; the Debug screen's
  export ships the merged pair, so an exported log normally spans two generations
  (android/app/src/main/java/com/hermesvox/VoxLog.kt `internal const val LOG_CAP_BYTES = 5L * 1024 * 1024`).
- **`VoxLog.dd()` lines only reach the file when "Verbose file log" is on**; `d()`, `w()`,
  `e()` and `er()` always do (android/app/src/main/java/com/hermesvox/VoxLog.kt `fun dd`).
  In the tables below, `†` marks a line that is `dd()`-only in the shipped default. If a
  user's log lacks it, that is the setting, not a bug.
- Transcript text is hidden unless "Log spoken transcript" is on; lines show `<hidden>`
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `"text=${if (logTranscripts()) semantic.text().take(60) else "<hidden>"}")`)`).
- Timing lines: `event=turn` is one line per settled turn with `stt=`, `firstByte=`,
  `firstText=`, `firstAudio=`, `fullReply=` and `session_turns=`
  (android/app/src/main/java/com/hermesvox/LatencyStats.kt `event=turn label=`); every
  eighth turn adds `event=lat` p50/p95 summaries
  (android/app/src/main/java/com/hermesvox/LatencyStats.kt `metric=first-byte`).
- The same line carries the **conversational** fields, measured from the END OF SPEECH
  rather than from the turn's launch: `speechEndToFirstText=`, `speechEndToFirstAudio=`,
  `speechEndToRenderDone=` and `sttDecodeMs=` (the recogniser's own work for the segment).
  They are `-` on a typed turn, which has no speech to measure from. `stt=` is unchanged and
  is still `now - segStart`, so on an idle segment it reports idle time — use `sttDecodeMs=`
  or `event=stt-live` for decode cost
  (android/app/src/main/java/com/hermesvox/LatencyStats.kt `speechEndToFirstText=`).
- `event=er-soul-first-token` is the presence render's first token, on the same stream as
  `event=turn`, so a soul render and the turn it belongs to can be compared directly
  (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `er-soul-first-token`).

Every `event=` name the app emits is listed below, grouped by subsystem. If you meet one
that is not here, this list is stale: add it with a reference.

### A. Call lifecycle, audio focus and routing

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=call-end` | The call closed; `callLive` says whether it was live | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt `VoxLog.d("event=call-end callLive=$callLive")`) |
| `event=activity-stop` | The activity stopped; `reason=no-live-call controller-stopped` is normal | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt `activity-stop`) |
| `event=fg-service` | Foreground service created/started/destroyed | `startForeground-failed` | (android/app/src/main/java/com/hermesvox/VoiceService.kt `VoxLog.d("event=fg-service start id=$startId")`) |
| `event=fg-service-start` | The mic foreground service was requested | `event=fg-service-start-failed err=` | (android/app/src/main/java/com/hermesvox/VoiceService.kt `fg-service-start ok`) |
| `event=wake` | Wake lock acquired/released for the open line | `event=wake-acquire-failed` | (android/app/src/main/java/com/hermesvox/MainActivity.kt `wake acquired`) |
| `event=screen-alive` | The keep-screen-on window flag was armed | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt `screen-alive armed`) |
| `event=focus-acquire` | Audio focus requested; `result=granted` or `denied` | `event=focus-acquire-failed` | (android/app/src/main/java/com/hermesvox/MainActivity.kt `focus-acquire result=`) |
| `event=focus-change` | Another app took/returned focus; `state=loss kind=loss / transient / can-duck`, or `state=resume` | `action=hangup reason=over-…` means focus stayed away too long and the call ended | (android/app/src/main/java/com/hermesvox/MainActivity.kt `VoxLog.d("event=focus-change state=loss kind=$kind")`) |
| `event=focus` | Focus released | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt `focus released`) |
| `event=audio-route` | Capture/playback re-attached for a device change; `devices=` lists the route | `event=audio-route-register-failed`, `event=audio-route-unregister-failed` | (android/app/src/main/java/com/hermesvox/MainActivity.kt `audio-route devices=`) |
| `event=comm-route` | Headset/communication-route selection (`action=set ok=…`, `action=none`, `action=cleared`) | `action=select-failed`, `ok=false` | (android/app/src/main/java/com/hermesvox/CommRoute.kt `"event=comm-route action=set ok=$ok type=${CommRoutePolicy.name(want.type)} " +`) |
| `event=assist` | #329: the assistant gesture reached the app; `route=start_call` (cold, opens ready to listen), `route=focus_live_call` (a call was already running — never a second session) or `route=onboard` (no gateway yet) | `route=start_call` while the user was mid-call | (android/app/src/main/java/com/hermesvox/MainActivity.kt `event=assist route=`) |
| `event=harness` | Debug builds only: a test intent set ringer mode | — | (android/app/src/debug/java/com/hermesvox/DebugHarnessActivity.kt `harness ringer=`) |
| `event=res` | Every 10 s of a call: the app's resident memory, the phone's available memory, low-memory flag, thermal state | `low=true`, `availMb` under ~1000, or `thermal=severe` and above — the phone is close to killing something | (android/app/src/main/java/com/hermesvox/ResourceProbe.kt `fun line(): String = "event=res rssMb=${mb(rssKb)} availMb=${mb(availKb)} totalMb=${mb(totalKb)} " +`) |
| `event=res-summary` | At hang-up: the call's peak RSS, lowest available memory, thermal start → end | A peak that grows release over release (docs/BASELINE.md) | (android/app/src/main/java/com/hermesvox/ResourceProbe.kt `* event=res-summary peakRssMb=… minAvailMb=… thermalStart=… thermalEnd=… samples=… ms=…`) |
| `event=profiles-ask` | #384: the host was asked for its profiles, on connect or on an explicit refresh. `kind=` is `connect`/`refresh`, `source=` is `fast` (the opportunistic `GET /v1/profiles` route), `chat` (the conversational ask) or `none` (it failed), `status=` its HTTP code (`-1` = no answer or the 45 s chat budget expiring), `ms=` the whole ask including probes, `fast=` that route's status, `added=` profiles new to this phone, `unavailable=` saved profiles this host did not prove (kept, marked in the switcher), `dropped=` names the agent wrote outside the charset | A steady `fast=404` is normal — the route does not exist and `source=chat` carried the list; `source=none` means the agent's answer failed (read `status=` and the line in the switcher); `added=0 unavailable=0` after a profile was created on the gateway = the device is on another host or key | (android/app/src/main/java/com/hermesvox/ProfileDialogs.kt `event=profiles-ask`) |

### B. Mic, VAD, STT

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=endpoint-extended` | The utterance passed `vad_max_ms` and stayed open until a real pause (logged once per utterance) | Fires on every turn = the speaker keeps going; not itself an error | (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.d("event=endpoint-extended ms=$elapsedMs")`) |
| `event=stt-windowed` | Audio longer than the model window was split into windows before decoding | — | (android/app/src/main/java/com/hermesvox/OfflineStt.kt `stt-windowed`) |
| `event=stt-reject` | A transcript was refused before it became a turn; `reason=` is `noise-tag`, `low-speech-ratio`, `runaway-repetition`, `implausible-rate` or `non-speech-phrase` | Any of them on every turn: the mic is hearing noise, or the threshold is wrong | (android/app/src/main/java/com/hermesvox/TranscriptValidator.kt `noise-tag`) |
| `event=stt-collapse` | Runaway repetition was collapsed to one phrase instead of sent | Frequent = recogniser looping on echo | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stt-collapse`) |
| `event=stt-fallback` | The selected STT leg could not be used; `from=` → `to=` names the swap | `reason=init-failed` (selected model did not load), `reason=absent` (files not installed), `reason=downloading`; a bare `from=remote` = the STT server failed | (android/app/src/main/java/com/hermesvox/VoiceController.kt ``* and log `event=stt-fallback` once, so the degraded mode is visible. The``) |
| `event=echo-tail-drop` | A short segment that ended inside the post-reply echo tail was discarded | Frequent drops of real short answers ("yes", "no") = tail too long | (android/app/src/main/java/com/hermesvox/VoiceController.kt `echo-tail-drop`) |
| `event=stt-live` | The streaming arm finished an utterance on its live stream: `fedMs` (audio decoded — equal to the utterance when nothing was decoded twice), `restMs` (fed after the speech ended), `finishMs` (the wait for the text) | `finishMs` that grows with the utterance's length | (android/app/src/main/java/com/hermesvox/StreamingStt.kt `event=stt-live seg`) |
| `event=stt-live-fallback` | The live stream could not finish, so the clip was decoded whole: `reason=worker-busy` or `finish-failed` | Any — the turn's text was right but slow | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stt-live-fallback`) |
| `event=stt-remote-init` | The remote STT availability probe finished; `ok=` and `api=` prefix | `ok=false` = the server is not usable | (android/app/src/main/java/com/hermesvox/RemoteStt.kt `stt-remote-init`) |
| `event=stt-remote` | One remote transcription; `ms=`/`textLen=` on success (that success line is `dd`) | `err=http-…`, `err=empty-or-unparseable`, `err=` transport | (android/app/src/main/java/com/hermesvox/RemoteStt.kt `VoxLog.w("event=stt-remote err=${e.message?.take(120)}"); return null`) |
| `event=stt-remote-test` † | The Settings "Test STT server connection" result | `ok=false reason=no-url`; `ok=false` | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `VoxLog.dd("event=stt-remote-test ok=false reason=no-url")`) |

### C. The turn over the wire

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=start-stream` † | A streamed turn opened; carries the routed model and provider | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `start-stream`) |
| `event=stream-wait` | `waitStream` raised (retired stream or transport) | Any `err=` while the turn should be live | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-wait`) |
| `event=stream-poll-error` | One poll failed; retried | Repetition up to 200 = dead stream | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-poll-error`) |
| `event=stream-poll-abort` | 200 consecutive poll errors: the turn was abandoned | Always bad — the gateway or network died mid-turn | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-poll-abort`) |
| `event=stream-poll` † | One poll snapshot: event count, done, text length | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-poll gen=`) |
| `event=stream-stall` | No SSE bytes for 5 s / 15 s (keepalives included) | `no-events=15s` = the gateway has gone quiet | (android/app/src/main/java/com/hermesvox/VoiceController.kt `no-events=15s`) |
| `event=stream-turn-failed` | The turn threw; `err=timeout` means the 120 s wall clock | Any; `timeout` on a short reply is the gateway | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-turn-failed`) |
| `event=turn-dropped` | A completed turn was dropped because the generation was stopped | `reason=stopped` after a hangup is expected | (android/app/src/main/java/com/hermesvox/VoiceController.kt `turn-dropped`) |
| `event=settle-drop` | A settled reply was not spoken because it was cancelled or already voiced | Both reasons are protective; a drop with no cut means a double-settle bug | (android/app/src/main/java/com/hermesvox/VoiceController.kt `settle-drop`) |
| `event=stream-fallback` | The stream failed **after** assembling text; the text is spoken non-streamed instead of lost | Any — the reply is degraded | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-fallback`) |
| `event=compress-failed` | `/compress` (context compression) failed | Always worth reporting with `err=` | (android/app/src/main/java/com/hermesvox/MainActivity.kt `compress-failed`) |
| `event=command` | A command from the commands screen changed state: `name=`, `state=queued / running / done / failed`; `queued` carries `behind=` the command it waits for | `failed` names the command that did not complete | (android/app/src/main/java/com/hermesvox/CommandsSheet.kt `VoxLog.d("event=command name=${c.name} state=running")`) |
| `event=compress-failed` | `/compress` (context compression) failed | Always worth reporting with `err=` | (android/app/src/main/java/com/hermesvox/MainActivity.kt `compress-failed`) |
| `event=turn` | One line per settled turn with latency fields and `outcome=` | Cut outcomes: `reply-error`, `stream-lost`, `empty-reply`, `stopped`, `hush`, `barge-in`, `focus-loss`, `suppressed-inflight`; healthy: `stream-done`, `speak-done`, `text-only` | (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-done`) |
| `event=turn-skip` † | A second settle for the same epoch was suppressed | Repeats = a double-settle path | (android/app/src/main/java/com/hermesvox/VoiceController.kt `turn-skip`) |
| `event=lat` | Rolling p50/p95 per metric every 8 turns | Compare `first-audio` against the cadence target, p50 700 ms / p95 1.4 s (docs/DECISIONS.md `p50 ≤ 700 ms`) | (android/app/src/main/java/com/hermesvox/LatencyStats.kt `out += "event=lat label=$label metric=first-byte p50=${pct(firstByte,0.50)} p95=${pct(firstByte,0.95)} n=${firstByte.size}"`) |
| `event=file-offer route=` | A file was chosen: `route=offer / inline / refused / unreachable`, with `bytes=` | `refused` above 100 MB; `unreachable` = Tailscale down, no server started | (android/app/src/main/java/com/hermesvox/FileShare.kt `event=file-offer route=`) |
| `event=file-offer serving` | The one-shot server bound; `port=`, `expires=` (epoch seconds) | — | (android/app/src/main/java/com/hermesvox/FileShare.kt `event=file-offer serving`) |
| `event=file-offer served` / `transferred` | One complete response was written; `bytes=`, `sha256=` | — | (android/app/src/main/java/com/hermesvox/FileServer.kt `event=file-offer served`) |
| `event=file-offer refused` | A request the server refused; `reason=method / path / traversal / query / range / spent / expired / source`, `status=`, never the token | `spent` after a transfer and `expired` are expected; `traversal` is an attack; `source` (500) means the staged copy no longer matched its hash and was not served | (android/app/src/main/java/com/hermesvox/FileServer.kt `"event=file-offer refused reason=$reason status=$status " +`) |
| `event=file-offer verdict=` | What the agent said about the file: `collected`, `mismatch`, `none` | `mismatch` means the bytes it got were not the bytes we sent | (android/app/src/main/java/com/hermesvox/FileShare.kt `log("event=file-offer verdict=mismatch")`) |

### D. Barge-in and the turn gate

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=barge-watch` | The playback barge watcher armed for this generation | `vad=off` = no Silero model, so only level can fire | (android/app/src/main/java/com/hermesvox/VoiceController.kt `barge-watch`) |
| `event=barge-in` | A real interruption fired; `mode=playback / generation`, with level and VAD agreement | **Good** — this is barge-in working | (android/app/src/main/java/com/hermesvox/VoiceController.kt `barge-in source=`) |
| `event=barge-in ignored` | An interruption was seen but refused because of state | `reason=state` with `turnInFlight=false speaking=false` is contradictory — report it | (android/app/src/main/java/com/hermesvox/VoiceController.kt `barge-in ignored`) |
| `event=barge-nearmiss` † | Level approached the floor without firing | Repeat clusters = floor too high, or the caller is quiet/ducked | (android/app/src/main/java/com/hermesvox/VoiceController.kt `// near-miss calibration; nearMissLoggedAtMs rate-limits event=barge-nearmiss`) |
| `event=barge-gap` † | The mic drain was starved >500 ms | Any — the capture loop is blocked | (android/app/src/main/java/com/hermesvox/VoiceController.kt `barge-gap`) |
| `event=barge-skipcheck` † | The barge check has been skipped in this state >3 s | `why=state` = a deaf window | (android/app/src/main/java/com/hermesvox/VoiceController.kt `barge-skipcheck`) |
| `event=barge-stat` | Per-second witness: peak/sustained level, echo floor, `sustainedMs`, `levelSustain` | A `sustainedMs` that keeps climbing without `barge-in` = the cut is not firing | (android/app/src/main/java/com/hermesvox/VoiceController.kt `// 0.9.1: per-second duty-cycle witness (event=barge-stat) for every`) |
| `event=barge-carry` | The interrupting audio was carried into the next turn | `seeded=false reason=hold` = the audio was not reseeded, because a hold keeps the mind working; the WORDS are still kept (`event=er-pending`). `reason=stopped / gate-timeout` = the carry had nowhere to go | (android/app/src/main/java/com/hermesvox/VoiceController.kt `// and the recorder keeps running. event=barge-carry is the field witness:`) |
| `event=er-pending` | The caller's words were queued as the next turn instead of being dropped: `queued=true reason=hold / suppressed-inflight chars=`, or `submitted=true` when the queue became the turn | `queued=true` with no later `submitted=true` = the caller said something the mind never saw | (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.er("event=er-pending gen=$gen queued=true reason=${ErHandoff.REASON_SUPPRESSED} chars=${text.length}")`) |
| `event=gate-ceiling` | The mic reopened at the gate's ceiling | Every long reply = the reply outlived the ceiling | (android/app/src/main/java/com/hermesvox/VoiceController.kt `gate-ceiling`) |
| `event=gate-timeout` | The gate stayed locked 60 s with no activity for 30 s | **Always bad** — a wedged turn; include `still-locked`, `streamed`, `sRunning` | (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.w("event=gate-timeout await=turn-gate gen=$myGen after=${TURN_GATE_TIMEOUT_MS}ms sinceActivity=${gateLockedNow - lastActivityAt}ms still-locked speaking=$speaking streamed=$streamed sRunning=$sRunning turnInFlight=$turnInFlight")`) |
| `event=gate-release` | The gate released; `reason=` becomes `event=turn`'s outcome | `result=stale` (a cancel for an old generation) / `result=duplicate` (expected on hangup) | (android/app/src/main/java/com/hermesvox/VoiceController.kt `gate-release result=stale`) |
| `event=tts-stop` | Speech was cut; `reason=` is `barge-in`, `hush`, `stop`, `focus-loss`, or an ER cancel; `msSinceBarge=` is the cut latency | `msSinceBarge` in the hundreds = slow cut | (android/app/src/main/java/com/hermesvox/VoiceController.kt `tts-stop reason=`) |

### E. Voice out, cues and the transcript

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=tts-retire` | The streaming synthesis worker retired after the last chunk | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `tts-retire`) |
| `event=reply-end` | A reply's one end event: `reason=done` or `reason=cancelled cause=<barge-in / hush / stop / …>`. Exactly one per `gen` | A second line for the same `gen` is a bug | (android/app/src/main/java/com/hermesvox/VoiceController.kt `reason=cancelled cause`) |
| `event=tts-drop` | A sentence of a reply that already ended was discarded instead of played: `reason=reply-ended` (queued) or `reason=stale-epoch` (it was synthesizing across the cut) | Normal after a barge; it is the fix for audio carried into the next turn | (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.d("event=tts-drop reason=reply-ended gen=$gen source=begin")`) (android/app/src/main/java/com/hermesvox/SherpaTts.kt `reason=stale-epoch`) |
| `event=tts-stop … cancelToSilenceMs=` | On a cut: `msSinceBarge` is the barge decision to the cut; `cancelToSilenceMs` is the cut to the track paused | `cancelToSilenceMs` over ~50 | (android/app/src/main/java/com/hermesvox/VoiceController.kt `cancelToSilenceMs=`) |
| `event=tts-speak-drop` | Synthesis finished after the reply was stopped, so the audio was dropped | Normal after a barge; on its own = a stop raced the synth | (android/app/src/main/java/com/hermesvox/SherpaTts.kt `tts-speak-drop`) |
| `event=tts-speak-cut` | Playback was cut at sample `at=` of `of=` | — | (android/app/src/main/java/com/hermesvox/SherpaTts.kt `tts-speak-cut`) |
| `event=tts-teardown` | The engine was torn down; `result=timeout` means a writer was still active | `result=timeout` = a stuck writer | (android/app/src/main/java/com/hermesvox/SherpaTts.kt `tts-teardown result=timeout`) |
| `event=tts-speak` | #373: one line per spoken utterance, naming the engine that spoke it — `engine=system / piper / supertonic`, `chars=`, `reason=` (`ok`, `one-shot`, `stream`, or the fallback reason) | `engine=none reason=…` = nothing could speak, and why; its absence (pre-#373) is why "it went silent" could not be settled | (android/app/src/main/java/com/hermesvox/TtsEngineHost.kt `"event=tts-speak engine=$engineToken chars=${text.length} " +`) (android/app/src/main/java/com/hermesvox/SherpaTts.kt `VoxLog.d("event=tts-speak engine=${voice.modelId} reason=stream chars=${text.length}")`) |
| `event=tts-fail` | #373: an utterance failed; `engine=` names it and `code=` is the engine's own error (a TextToSpeech error code, a sherpa exception message, `timeout`) | Any — the engine is retired and re-acquired for the next utterance | (android/app/src/main/java/com/hermesvox/TtsEngineHost.kt `event=tts-fail engine=`) (android/app/src/main/java/com/hermesvox/SherpaTts.kt `event=tts-fail engine=`) |
| `event=tts-fallback` | #373: the requested voice cannot speak, so the system voice does — `from=` → `to=system`, `reason=` (`piper model not installed`, `piper could not start (…)`) | Expected when the model was never installed; the status line says the same thing | (android/app/src/main/java/com/hermesvox/TtsEngineHost.kt `warn("event=tts-fallback from=$local to=system reason=$why")`) |
| `event=tts-ready` | #373: an engine loaded and will speak; `engine=`, `reason=` (`ok` or the fallback note) | — | (android/app/src/main/java/com/hermesvox/TtsEngineHost.kt `event=tts-ready engine=`) |
| `event=tts-recover` | #373: a failed engine is being replaced before the next utterance; `reason=`, `attempt=` (at most 3 per call) | `attempt=3` = the voice leg keeps dying | (android/app/src/main/java/com/hermesvox/TtsEngineHost.kt `event=tts-recover reason=`) |
| `event=tts-cut` | #373: an utterance was cut on purpose (barge, hush, hang-up, a newer utterance) — not an engine failure, so nothing is rebuilt | — | (android/app/src/main/java/com/hermesvox/TtsEngineHost.kt `event=tts-cut engine=`) |
| `event=tts-timeout` | #373: the system engine never called back; the utterance ends as failed instead of latching the call | Present = the engine stopped responding | (android/app/src/main/java/com/hermesvox/WarmTts.kt `event=tts-timeout engine=system`) |
| `event=voice-preview` | A Settings voice preview finished; `engine=` and `ms=` | — | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `voice-preview`) |
| `event=cue` | A sound cue decision: `cue=`, `action=audio / haptic / silent`, `reason=` | `action=silent` with `reason=off / dnd / silent` explains "I hear nothing" | (android/app/src/main/java/com/hermesvox/SoundCuePlayer.kt `event=cue cue=`) |
| `event=cue-fail` | A cue could not be played | Any | (android/app/src/main/java/com/hermesvox/SoundCuePlayer.kt `cue-fail`) |
| `event=reveal-hold` | The speech-locked transcript held position through a synthesis gap | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `@Volatile private var gapHoldLogged = false // one reveal-hold line per gap`) |
| `event=reveal-freeze` | The crawl was frozen at the spoken position when speech was cut | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `reveal-freeze`) |
| `event=reveal-fallback` | The crawl lost its cursor and fell back to plain reveal | Any — text may run ahead of the voice | (android/app/src/main/java/com/hermesvox/MainActivity.kt `reveal-fallback`) |

### F. Presence

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=er-soul` | The soul decided for a turn: `kind=` (turn/greeting/working), `decision=`, `chars=`, `heard=`, `mood=`, `ms=` | `decision=escalate` on every turn = the layer is doing nothing; high `ms=` = slow render | (android/app/src/main/java/com/hermesvox/MainActivity.kt `er-soul kind=`) |
| `event=presence-unload` | The presence Engine was released: `reason=` one of `call-end` (immediate arm, dev pref), `idle` (the delayed arm's timer), `presence-off`, `released=`, `availMb=` after the close | Never fires on a shipped build — both release arms are off by default | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `synchronized(loadLock) { if (loading) { VoxLog.d("event=presence-unload reason=$reason skipped=loading"); return } }`) |
| `event=presence-unload-arm` | The delayed arm was armed at hangup: `trigger=call-end`/`presence-off`, `minutes=` | Fires and is never followed by `-due` or `-cancel` = the timer died | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `event=presence-unload-arm trigger=`) |
| `event=memory-budget` | The dev memory dialog was applied: `kv=` (2048/4096/8192, applies on the next presence load), `unload=`, `idleMin=` | Any `kv=` other than the arm a run believes it is in = the measurement is misattributed | (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `event=memory-budget`) |
| `event=presence-unload-cancel` | A pending release was called off: `reason=call-start` (a redial inside the window, the case the arm exists for), `presence-load`, `policy-keep`, `policy-on-hangup` | — | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `event=presence-unload-cancel reason=`) |
| `event=er-soul-drop` | A soul line was rendered but not delivered; `reason=late` (too late to the turn), `undeliverable`, `already-answered`, `mind-already-replying`, `greeting-already-said`, `greeting-reply-live` | All are drops; `late` is the measured common one (see the note under this table) | (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.er("event=er-soul-drop reason=not-a-beat gen=$gen")`) |
| `event=er-mood` | The soul tagged the caller's tone; the mood steers delivery | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-mood`) |
| `event=er-glue` | An aside was accepted for playback; `source=`, `critical=`, `chars=`, and `text=` only with transcript logging on (`<hidden>` otherwise) | `critical=true` asides pre-empt fillers by design | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-glue source=`) |
| `event=er-glue-reject` | An aside was refused | `reason=result-unbacked` (D29's false-action-claim guard, on every lane; `source=` names it, including `mind-commentary`), `reason=repeat` (same line twice), `reason=reply-live` (the mind's answer has the floor) and `reason=streaming-reply-live` | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-glue-reject reason=repeat`) |
| `event=repair` | A failed or empty turn spoke the repair line instead of going silent: `reason=empty-reply / stream-lost / reply-error / turn-timeout` | Any — the caller heard words; check `reason=` for what broke | (android/app/src/main/java/com/hermesvox/VoiceController.kt `event=repair reason=`) |
| `event=er-arbiter` | Priority arbitration: P0 stop · P1 mind · P2 critical soul · P3 filler | `outcome=` naming a lower-priority winner over a higher request | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-arbiter current=`) |
| `event=er-fence-reopen` | The audio fence reopened after a glue line | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-fence-reopen`) |
| `event=er-barge` | Semantic-barge decision on an interruption (e.g. `verdict=cancel_mind reason=semantic-toggle-off`) | A cancel when the user said "go on" = the toggle is off | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-barge verdict=`) |
| `event=er-barge-fire` | A staged barge was fired; `hold=staged frames=` | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-barge-fire`) |
| `event=er-barge-verdict` | What the verdict classifier decided about the interrupting words | — | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-barge-verdict`) |
| `event=local-action` | The phone engine's platform-side line for an action the **mind** asked for: `kind=`/`ok=` on the engine path and `event=local-action ring kind=` when a timer fires. It is no longer a turn path: every utterance goes to the mind, and a local grammar cannot claim one (D38) | `ok=false` = the phone could not do it and said so (a missing DND permission is named). There is no `er-handoff reason=local-action` any more; a timer that did not run is the mind's refusal to report | (android/app/src/main/java/com/hermesvox/LocalActions.kt `event=local-action ring kind=$kind id=$id`) |
| `event=phone-mcp` | The mind acting on the phone: `start url=… port=` when a call opens the listener, `stop served=N` on hangup, `action=<timer/alarm/dnd/volume/note/undo> ok=true id=…` per call, `refused reason=no-token / unknown-tool`, `unavailable reason=no-tailnet / bind`, `token-store=failed session-only=true` | `ok=false` with a `reason=` is the phone refusing truthfully (no DND access, tier 2, bad arguments). `refused reason=no-token` = a client with neither the phone's token nor the connection's API key probed the port | (android/app/src/main/java/com/hermesvox/PhoneMcp.kt `event=phone-mcp start`) |
| `event=er-handoff` | **The receipt**: one line per turn proving the mind received it. `ok=true\|false`, `reason=voice-turn / text-turn / suppressed-inflight / cancelled-before-submit / stopped / no-stream`, `soul=`, `askChars=` | `ok=false` = the mind never saw the turn. `reason=suppressed-inflight` is not a lost utterance: the words are queued (`event=er-pending`) and submitted when the current turn settles. Since D30 the mind is never told the voice answered, so no `soul=answer` response is invited | (android/app/src/main/java/com/hermesvox/ErHandoff.kt `event=$EVENT gen=$gen ok=$ok`) |
| `event=er-audio-handoff` | The mind's reply cut in over the soul; `waitedMs=`, `cut=` | Large `waitedMs` = the turn waited on a slow render | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-audio-handoff`) |
| `event=er-presence line=` | A mid-run aside was voiced; `line=`, `interval=`, `density=`, `kind=lag / soul` | — | (android/app/src/main/java/com/hermesvox/ErPresence.kt `er-presence line=`) |
| `event=er-presence skip` | A cadence slot passed in silence; `reason=silent / late / glue-playing / undeliverable / blocked / no-soul / lag-not-said` | `late` = the line arrived after the reply started; `blocked`/`no-soul` = the layer is not wired | (android/app/src/main/java/com/hermesvox/ErPresence.kt `er-presence skip`) |
| `event=er-presence repair` | A mid-run aside was **spoken after shape repair**, never a refusal; `reason=cut / markup / fence / title / courtesy` | Not an error — the aside survived. Frequent `cut` = the model runs long | (android/app/src/main/java/com/hermesvox/ErPresence.kt `er-presence repair`) |
| `event=er-presence detail` | Which line was thrown away and why (`why=`, `deltaMs=`, text hidden unless transcript logging is on) | Any `why=` other than `silent` is a refusal worth reading | (android/app/src/main/java/com/hermesvox/VoiceController.kt `er-presence detail`) |
| `event=er-presence stop` | The cadence ended; `reason=cap / reply / barge / hush / call-end` | `reason=cap` = the run used every line and stalled | (android/app/src/main/java/com/hermesvox/ErPresence.kt `VoxLog.er("event=er-presence stop reason=cap lines=${run.linesSaid}")`) |
| `event=er-render-skip` | A render was skipped by the spacing floor (1.2 s) | Clusters = renders requested too often | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `er-render-skip`) |
| `event=er-render-drop` | A render was dropped because one was in flight | Frequent = the queue is too small for the turn rate | (android/app/src/main/java/com/hermesvox/VoxExpress.kt `er-render-drop`) |
| `event=er-render-preempt` | An urgent render pre-empted the one in flight | — | (android/app/src/main/java/com/hermesvox/VoxExpress.kt `er-render-preempt`) |
| `event=soul-heard` | The soul processed the caller's audio; `audioBytes=` | — | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `soul-heard`) |
| `event=soul-conv-rotate` | The rolling LiteRT-LM conversation was rebuilt; `tokens=`, `personaChanged=` | Frequent rotations = the conversation keeps filling | (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `soul-conv-rotate`) |
| `event=vox-resync` | The VOX.md pull finished; `doc=` length and `result=ok / ok repaired=… / invalid: …` | `result=invalid:` = the entity's reply was not a usable document | (android/app/src/main/java/com/hermesvox/VoxSync.kt `event=vox-resync profile=`) |
| `event=vox-resync-failed` | The pull threw | Any | (android/app/src/main/java/com/hermesvox/VoxSync.kt `vox-resync-failed`) |

**Two `late` sites, two meanings.** `event=er-soul-drop reason=late` is a *turn or beat* render
that lost the race to the mind's first text — the documented common case, and not the working
lane (docs/PRESENCE-LATE-DIAGNOSIS.md ``Diagnosis for the 2026-10-20 field sessions (17 `er-soul-drop reason=late`, 0 guard``). `event=er-presence skip
reason=late` is a *mid-run* line that arrived after the reply started; that one was addressed by
pre-rendering the line before the slot opened (docs/PRESENCE-LATE-DIAGNOSIS.md ``## 1. There are two `late` sites, and the field's 17 are not the working lane``). When a
user says "it went quiet", look at `er-presence skip`; when they say "it didn't say anything
before answering", look at `er-soul-drop`.

### G. Models

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=model-dl-start` | A download (or resume) started; `resumeFrom=` bytes | — | (android/app/src/main/java/com/hermesvox/ModelDownloads.kt `model-dl-start`) |
| `event=model-dl-attempt` | One HTTP attempt; `have=`, `code=`, `plan=` | `code=403/404` = the source URL or asset moved | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `model-dl-attempt`) |
| `event=model-dl-offline` | No network: the download waits instead of failing | Expected in a tunnel/lift | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `model-dl-offline`) |
| `event=model-dl-online` | Network returned; the download resumes | — | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `model-dl-online`) |
| `event=model-dl-retry` | A failed attempt is retried after backoff | Repeats up to the retry cap = a flaky source | (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `model-dl-retry`) |
| `event=model-dl-end` | The download ended; `result=installed` or an error string | Anything but `installed`; a sha256 mismatch returns "sha256 mismatch" | (android/app/src/main/java/com/hermesvox/ModelDownloads.kt `model-dl-end`) |
| `event=model-dl-service` | The download foreground service failed to start/foreground | Any — the download will not survive leaving the Models screen | (android/app/src/main/java/com/hermesvox/ModelDownloadService.kt `VoxLog.e("event=model-dl-service startForeground-failed err=${e.message}")`) |

### H. Sessions (#437)

| Event | Meaning | Bad values | Reference |
|---|---|---|---|
| `event=session-adopt` | A session was picked up: `id=`, `source=` | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt `event=session-adopt id=`) |
| `event=session-transcript` | One transcript read landed: `id=`, `messages=` served, `shown=` after the merge | `messages=0` on a session the card showed rows for = the id or the key is wrong | (android/app/src/main/java/com/hermesvox/MainActivity.kt `VoxLog.d("event=session-transcript id=$id messages=${answer.messages.size} shown=${adoption.resumed.size}")`) |
| `event=session-transcript … state=key-rejected / no-answer` | The read failed, and which of the two it was | `key-rejected` on the root = the same wall as #436, not a missing session | (android/app/src/main/java/com/hermesvox/MainActivity.kt `state=key-rejected`) |
| `event=session-clear` | The adopted session was let go (`id=`): `/new`, `/clear`, a profile switch, a saved connection | — | (android/app/src/main/java/com/hermesvox/MainActivity.kt `event=session-clear id=`) |
| `event=sessions refused reason=alpha-off` / `event=session-adopt refused reason=alpha-off` | The card or the pick was refused because the Alpha Sessions set is off | Expected with the set off; anything else means two gates disagree | (android/app/src/main/java/com/hermesvox/MainActivity.kt `VoxLog.d("event=sessions refused reason=alpha-off")`) |

---

## 5. The model catalogue

Model archives are **never bundled in the APK**: they are downloaded on demand into
app-private storage, sha256-verified against a pinned hash, and unpacked
(android/app/src/main/java/com/hermesvox/ModelCatalog.kt `never bundled`). A download is
resumable over HTTP `Range` and refuses to install on a hash mismatch
(android/app/src/main/java/com/hermesvox/ModelDownloader.kt `sha256 mismatch`).

"Required" and "recommended" are the **same set** everywhere — the Settings badge, the store
header and the install-all button all count the same three models
(android/app/src/main/java/com/hermesvox/ModelCatalog.kt `val required`). That set is
Silero VAD, Supertonic and Whisper base.en: the offline voice needs all three
(android/app/src/main/java/com/hermesvox/ModelsActivity.kt `ModelCatalog.required.filter { !ModelCatalog.isInstalled(this, it.id) }`).

| Model id | Kind | Download | Required? | Notes | Reference |
|---|---|---|---|---|---|
| `silero-vad` | VAD | 0.6 MB | **yes** | Speech start/stop; without it barge-in is level-only | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("silero-vad", "Silero VAD", "vad", "silero_vad.onnx", 0.6,`) |
| `supertonic` | TTS | 80.3 MB | **yes** | Ten voices at 44.1 kHz; the recommended voice | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("supertonic", "Supertonic · 10 voices (44.1 kHz)", "tts", "sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2", 80.3,`) |
| `whisper-base` | STT | 208.6 MB | **yes** | The blessed default recogniser | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("whisper-base", "Whisper base.en", "stt", "sherpa-onnx-whisper-base.en.tar.bz2", 208.6,`) |
| `whisper-tiny` | STT | 118.1 MB | no | Fastest, least accurate | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("whisper-tiny", "Whisper tiny.en", "stt", "sherpa-onnx-whisper-tiny.en.tar.bz2", 118.1,`) |
| `whisper-small` | STT | 635.7 MB | no | Most accurate Whisper, heaviest | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("whisper-small", "Whisper small.en", "stt", "sherpa-onnx-whisper-small.en.tar.bz2", 635.7,`) |
| `parakeet-v2` | STT | 482.5 MB | no | Measured best accuracy+speed on the host bench; larger resident set, so not the default | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("parakeet-v2", "Parakeet TDT 0.6B v2 (int8)", "stt", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", 482.5,`) |
| `nemotron-stream-160ms` | STT (streaming) | 463.9 MB | no | Opt-in streaming arm; ~900 MB resident. **Licence gate** | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec(NEMOTRON_STREAM_ID, "Nemotron Speech Streaming 0.6B (160 ms, int8)", "stt",`) |
| `piper-lessac` | TTS | 82.0 MB | no | Lighter alternative voice (904 speakers, 8 curated in the picker) | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("piper-lessac", "Piper · en-US (LibriTTS-R, medium)", "tts", "vits-piper-en_US-libritts_r-medium.tar.bz2", 82.0,`) |
| `gemma-e2b` | Presence | 2588.1 MB | no | The on-device expression layer; needs a working GPU/CPU backend | (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("gemma-e2b", "Gemma 4 E2B (presence)", "express", "gemma-4-E2B-it.litertlm", 2588.1,`) |

### Licences that need acceptance

- **Nemotron** is the only model with a licence gate: the NVIDIA Open Model License, which
  its licensor can revoke. The store shows the name, the attribution and the model card, and
  the downloader refuses to fetch it without acceptance
  (android/app/src/main/java/com/hermesvox/ModelLicences.kt `refusal`). Acceptance is
  stored by licence **name**, so a changed licence asks again
  (android/app/src/main/java/com/hermesvox/ModelLicences.kt `stored == spec.licence.name`).
- **Gemma 4 E2B** carries no `ModelLicence` in its catalogue entry, and a null licence is
  treated as already accepted, so it downloads **without** an acceptance dialog
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `ModelSpec("gemma-e2b", "Gemma 4 E2B (presence)", "express", "gemma-4-E2B-it.litertlm", 2588.1,`)
  (android/app/src/main/java/com/hermesvox/ModelLicences.kt `spec?.licence == null`). Do
  not tell a user they accepted a Gemma licence in this app — they did not.
- **Supertonic**'s catalogue description names its upstream licence (OpenRAIL-M), but that is
  the model card's note, not a download gate
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `OpenRAIL-M`).
- Everything else is permissively licensed upstream; the app itself is GPL-3.0 because
  sherpa-onnx's TTS statically links espeak-ng (docs/RELEASE-0.9.0.md `- **GPL-3.0.** The app is GPL-3.0 because sherpa-onnx's TTS statically links`).

### What cannot co-exist comfortably on a small device

- **Gemma (2.6 GB) + the streaming arm (~900 MB resident)** is the worst pairing; Gemma also
  wants the GPU, and on a device it cannot use it runs 5× slower on CPU
  (docs/DECISIONS.md `The GPU engine does not fit`).
- The app warns explicitly on low-RAM devices when the streaming arm is selected: it "needs
  ~900 MB RAM; may be slow or close other apps on this phone"
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `needs ~900 MB RAM`). The
  threshold is an ActivityManager heap class below 256 MB
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `const val SMALL_MEMORY_CLASS_MB = 256`).
- Whisper small (636 MB) is the heaviest offline recogniser offered; whisper-base is the
  shipped default, and the catalogue says the bigger models stay opt-in until they are
  measured on a phone (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `DEFAULT_STT_MODEL`).
- Two TTS engines do not conflict — only the selected one loads — but the speaker choice is
  stored per engine, so switching engines changes the voice you hear
  (android/app/src/main/java/com/hermesvox/VoiceSpeakers.kt `fun key(engine: SherpaVoice): String = "tts_speaker_${engine.modelId}"`).

---

## 6. Failure modes → log signature → cause → what to do

Each entry is a real, documented field issue. Log lines are literal; the reference proves the
line exists and what it says. Start from the exported log (Settings → About & Diagnostics →
Debug / crash log → Share full log).

### 6.1 The gateway stopped answering after upgrading — bare-IP HTTP is now refused

- **Signature:** the status pill sits on "Can't reach the gateway"
  (android/app/src/main/java/com/hermesvox/ConnectionPhase.kt `Can't reach the gateway`); a turn
  fails fast with `event=stream-turn-failed` (android/app/src/main/java/com/hermesvox/VoiceController.kt `stream-turn-failed`).
- **Cause:** 0.9 moved turns onto Android's HTTP stack, so the declared network-security
  policy applies: cleartext is refused for every host except tailnet MagicDNS names
  (docs/RELEASE-0.9.0.md `plain HTTP is refused`) (android/app/src/main/res/xml/network_security_config.xml `cleartextTrafficPermitted="false"`).
- **Do:** serve the gateway over HTTPS (`tailscale serve`, Caddy, or any TLS terminator) and
  enter the HTTPS URL. For a tailnet certificate, install the CA and use the HTTPS name
  (android/app/src/main/res/xml/network_security_config.xml `certificates src="user"`). Do
  not suggest uninstalling or downgrading.

### 6.2 The selected STT model "isn't working" — it never loaded

- **Signature:** `event=stt-fallback reason=init-failed from=nemotron-stream-160ms to=whisper-base`
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `reason=init-failed`).
- **Cause:** the streaming arm could not load (memory pressure or a damaged file); the app
  falls back to the shipped model for the rest of that session only.
- **Do:** check the model is installed and the device has headroom; a restart retries the arm.
  If it fails again on a small device, recommend whisper-base instead
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `~900 MB RAM`).

### 6.3 STT is silently degraded to the platform recogniser

- **Signature:** `event=stt-fallback reason=absent` when a streaming model is selected but its
  files are missing (android/app/src/main/java/com/hermesvox/VoiceController.kt ``* and log `event=stt-fallback` once, so the degraded mode is visible. The``).
  When **no** on-device model is installed at all there is no fallback line, because nothing was
  selected to fail — the chain simply posts to `SpeechRecognizer`.
- **Cause:** the configured offline model is not installed, so the chain falls through to
  `SpeechRecognizer`; that leg may send audio to the device's provider
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `createSpeechRecognizer`).
- **Do:** install the model in Voice models, or switch the STT backend to on-device
  explicitly. Tell the user the privacy consequence of staying on platform STT.

### 6.4 Barge-in stopped working after the voice changed

- **Signature:** `event=barge-stat` shows `sustainedMs`/`levelSustain` climbing with no
  `event=barge-in` (android/app/src/main/java/com/hermesvox/VoiceController.kt `// 0.9.1: per-second duty-cycle witness (event=barge-stat) for every`).
- **Cause:** the interruption floor is derived from the reply's own echo; a much quieter
  voice (Supertonic is ~6.5 dB quieter than the Piper it replaced) leaves almost no echo bed,
  so the caller's voice alone must clear the floor, and the platform echo canceller ducks it
  during playback (docs/RELEASE-0.9.0.md `measured from the reply's own echo`).
- **Do:** confirm the build includes the measured floor `EchoFloor`
  (android/app/src/main/java/com/hermesvox/EchoFloor.kt `0.030`), then lower Barge
  sensitivity a step and/or enable Level-only barge
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `bindIntSeekBar(R.id.set_seek_barge_level, R.id.set_mic_barge_level_val, "barge_level_only_ms",`). A
  headset changes the room; retest there.

### 6.5 A long reply cannot be interrupted for its last minute

- **Signature:** `event=gate-ceiling` at ~60 s into a turn, then no barge
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `gate-ceiling`).
- **Cause:** the barge watchdog used to give up 60 s after the gate closed regardless of
  whether the reply was still speaking; fixed in 0.9.1 to count from the turn's last sign of
  life with a ten-minute backstop (docs/RELEASE-0.9.0.md `60 seconds`).
- **Do:** confirm the version — the ceiling is the turn's own last-activity clock now
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `gate-ceiling`). There is no
  setting for the old 60 s ceiling; the fix is the build.

### 6.6 The user's sentence is chopped into fragments after ~15 s of talking

- **Signature:** `event=endpoint-extended` directly followed by a short turn in `event=turn`
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.d("event=endpoint-extended ms=$elapsedMs")`).
- **Cause:** the utterance clock used to run from mic-open instead of speech onset, so the
  first >100 ms pause closed a sentence the caller was still in the middle of
  (docs/RELEASE-0.9.0.md `utterance clock starts when you start speaking`).
- **Do:** on 0.9.1+ this should be gone; if it persists, raise "Max speech per turn" and
  check VAD sensitivity. Note the ceiling is a pause rule now, not a hard cut
  (android/app/src/main/java/com/hermesvox/EndpointRule.kt `* 0.9.1: the clock [shouldStop]'s elapsedMs must run on — time since the SPEECH`).

### 6.7 The entity answers its own greeting ("hello, hello")

- **Signature:** `event=echo-tail-drop` immediately after a call opens with Presence on
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `echo-tail-drop`).
- **Cause:** the greeting plays before any turn exists, so the half-duplex gate had not closed
  and the mic transcribed the being's own voice; the fix excludes glue and keeps the tail
  quiet for the echo-guard window (docs/RELEASE-0.9.0.md `own greeting`).
- **Do:** confirm the build; if it recurs, raise the Echo guard
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `val curEcho = prefs.getFloat("er_echo_skip_ms", 700f).toInt()`).

### 6.8 The connection test says FAILED against a gateway that works

- **Signature:** the dialog reports FAILED while turns stream normally; the probe's stream
  leg returned HTTP 400.
- **Cause:** the probe deliberately sends no `input`, and the gateway answers 400; a 400 from
  that probe means the route exists and the key was accepted
  (android/app/src/main/java/com/hermesvox/ConnectionPhase.kt `if (it < 200 || it >= 400) return Probe.GATEWAY_ERROR`). A false 401 could
  also come from an encrypted key being sent as the credential — the live channel now
  overrules the one-shot probe (android/app/src/main/java/com/hermesvox/ConnectionPhase.kt `reconcile`).
- **Do:** trust the live turns, not the probe; update to a build with the 400 fix
  (docs/RELEASE-0.9.0.md `connection test tells the truth`).

### 6.9 Asides are dropped as `late`

- **Signature:** `event=er-presence skip line=N reason=late` and/or
  `event=er-soul-drop reason=late` (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.er("event=er-soul-drop reason=not-a-beat gen=$gen")`).
- **Cause:** a working slot's line used to be rendered only after the slot came due, so a
  ~2.5 s render sat between "due" and "delivered" and the mind's first text landed inside it;
  turn renders are dropped for the same reason but by design (the 900 ms gate)
  (docs/PRESENCE-LATE-DIAGNOSIS.md ``## 1. There are two `late` sites, and the field's 17 are not the working lane``).
- **Do:** on a fixed build the working lane pre-renders. If turn renders are still dropped,
  that is the documented trade, not a fault; slowing "Presence while working" reduces the
  number of slots, not the drops (docs/PRESENCE-LATE-DIAGNOSIS.md `too slow, not too late`).

### 6.10 Presence is silent through a long tool call

- **Signature:** `event=er-presence line=1 … kind=lag`, then `event=er-presence stop
  reason=cap lines=` or simply nothing for a minute
  (android/app/src/main/java/com/hermesvox/ErPresence.kt `reason=cap`).
- **Cause:** the cadence has a per-run cap; a 76-second tool call produced one line, one
  canned "let me think", then 71 seconds of silence (docs/RELEASE-0.9.0.md `76-second tool call`).
- **Do:** set Presence while working to Chatty for a higher cap and shorter interval
  (android/app/src/main/java/com/hermesvox/PresenceCadence.kt `CHATTY("chatty", 10_000L, 12, 3),`), and confirm the
  build has the mid-run cadence at all.

### 6.11 The presence model installs but never speaks

- **Signature:** there is **no pill on the main screen** — by design. The Settings presence row
  reads "On, but the model cannot start on this device — Presence stays off here"
  (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `cannot start on this device`),
  and the log carries `event=presence-unavailable reason=start-failed`
  (android/app/src/main/java/com/hermesvox/MainActivity.kt `if (g.loadFailed) VoxLog.w("event=presence-unavailable reason=start-failed")`).
- **Cause:** the LiteRT-LM engine could not initialise on this GPU, and the CPU path is the
  documented slow one — ~1.8 s to first token on CPU versus ~0.3 s on GPU
  (android/app/src/main/java/com/hermesvox/GemmaExpress.kt `1.8s`); there is also a recorded
  **intermittent** start hang on the same build
  (docs/RELEASE-0.9.0.md `intermittent rather than fixed`). A double-load of the model
  was fixed earlier (docs/RELEASE-0.6.3.md `Gemma double-load`).
- **Do:** treat it as "Presence unavailable" rather than "broken call"; the app is normal
  operation. Restarting the app once often clears the hang. There is no setting that fixes a GPU
  the engine cannot use.

### 6.11b A call with Presence on sits on "Loading Presence" for most of a minute

- **Signature:** the pill reads "Loading Presence · N s"; `event=presence-load phase=start availMb=…` with a low
  number, then `phase=end ok=true ms=` tens of thousands; around it, the system log shows the low-memory killer
  clearing other apps.
- **Cause:** the phone is short of memory, so loading Presence's model (about 2.5 GB) waits while Android frees
  room — measured at ~50 s on an 8 GB Pixel 7 whose other apps held a 2.5 GB model of their own (#191). It is not
  a hang. After 20 s the pill adds "tap to start without it" (android/app/src/main/java/com/hermesvox/PresenceWait.kt `const val OFFER_SKIP_MS = 20_000L`),
  which opens the call with Presence joining when it is ready
  (android/app/src/main/java/com/hermesvox/MainActivity.kt `event=presence-skip`).
- **What to do:** close other heavy apps (on-device AI apps especially), or turn Presence off on that phone.

### 6.12 The text on screen runs ahead of (or behind) the voice

- **Signature:** `event=reveal-fallback` (the crawl lost its cursor) — bad
  (android/app/src/main/java/com/hermesvox/MainActivity.kt `reveal-fallback`).
  `event=reveal-hold reason=synthesis-gap` is the **fix working**: the crawl held its last
  honest position through a synthesis gap
  (android/app/src/main/java/com/hermesvox/VoiceController.kt `@Volatile private var gapHoldLogged = false // one reveal-hold line per gap`).
- **Cause:** the crawl is paced by the playback head; a synthesis gap used to look like
  "everything has been said" and pinned the crawl to the end of the reply
  (docs/RELEASE-0.9.0.md `spoken transcript keeps its place`).
- **Do:** if only `reveal-hold` appears, nothing is wrong. `reveal-fallback` means the cursor
  was lost; include the surrounding `tts-` lines when reporting it.

### 6.13 A call wedges and the mic never reopens

- **Signature:** `event=gate-timeout await=turn-gate … still-locked` with no
  `event=gate-release` for the generation (android/app/src/main/java/com/hermesvox/VoiceController.kt `VoxLog.w("event=gate-timeout await=turn-gate gen=$myGen after=${TURN_GATE_TIMEOUT_MS}ms sinceActivity=${gateLockedNow - lastActivityAt}ms still-locked speaking=$speaking streamed=$streamed sRunning=$sRunning turnInFlight=$turnInFlight")`).
- **Cause:** the turn gate stayed locked 60 s after the gate closed and 30 s with no delta,
  chunk or read — a wedged turn (dead stream that never errored, or a TTS worker that
  never retired).
- **Do:** end the call and reopen it; export the log because `streamed=`, `sRunning=` and
  `still-locked` identify which leg wedged. Do not advise clearing app data first — that
  destroys the evidence.

### 6.15 `/sessions` is missing, or the card has no rows

- **Signature:** the commands sheet has no `/sessions` row and a typed "/sessions" reaches the
  agent as a sentence; or the card opens with `no sessions on this host`, `the key was not
  accepted` or `the host did not answer`.
- **Cause:** the first is the Alpha **Sessions** set being off — the row does not exist, by design
  (android/app/src/main/java/com/hermesvox/Sessions.kt `fun refusal`). Of the three card
  lines, only one is about the phone: `no sessions on this host` means the host answered and holds
  nothing for this key; `the key was not accepted` is a 401 at the root (the #436 wall, not a
  missing session); `the host did not answer` is transport, a timeout, or a body that is not a
  list (android/app/src/main/java/com/hermesvox/Sessions.kt `fun listing`).
- **Do:** turn Alpha on and select **Sessions**; then check the gateway's `/api/sessions` with the
  same key from the host. Sessions belong to a profile: after a profile switch the list is the new
  host's, and an empty card may simply be a host that has none.

### 6.14 A download never finishes

- **Signature:** `event=model-dl-retry` repeating, or `event=model-dl-offline` with no
  `model-dl-online` (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `model-dl-retry`).
- **Cause:** the source host is unreachable (offline, captive portal, or an overridden
  `model_source`), or the artifact moved (`code=403/404` in `model-dl-attempt`)
  (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `model-dl-attempt`).
- **Do:** restore the default source in the model store ("Use default"), check the URL scheme (a scheme-less
  override silently falls back to the default)
  (android/app/src/main/java/com/hermesvox/ModelCatalog.kt `fun resolveSource(input: String?): String {`); the partial
  file is kept and resumes (android/app/src/main/java/com/hermesvox/ModelDownloader.kt `* itself with backoff, resuming each time. The sha256 over the COMPLETE file is still the gate`).

---

## 7. What the app does not do

These prevent confident wrong advice. None of them is a bug report; they are the product.

- **No cloud, no account, no vendor server.** There is no hosted service, no sign-up and
  nothing operated by the maintainers (README.md `Bring your own gateway`); the gateway
  key goes only to the gateway (PRIVACY.md `preferences on your device and is transmitted **only to the gateway you configured** —`). The only *default*
  network peer is your gateway plus the model host you install from (PRIVACY.md `your server`).
- **No Play Store, no AAB.** Google Play is dropped as a rail; distribution is GitHub,
  Obtainium and F-Droid, always with our own signature (docs/DECISIONS.md `Google Play is dropped`).
- **No iOS, no desktop.** Android only; the reusable core that a port would have needed was
  removed (docs/DECISIONS.md `Desktop and iOS are out of scope`).
- **No wake word that listens.** The wake word is a **preview**: an engine seam whose only
  implementation never detects anything, plus an enrolment screen that records takes into an
  encrypted store. Nothing is trained and nothing listens in the background
  (docs/WAKE-WORD.md `Nothing is trained and nothing listens`) (android/app/src/main/java/com/hermesvox/wake/WakeWordEngine.kt `available = false`). If a
  user says "it doesn't wake on Hey Hermes", the answer is "it cannot, by design yet".
- **No local mind.** The app never reasons, calls tools or stores long-term memory; Gemma
  only expresses (android/app/src/main/java/com/hermesvox/VoxExpress.kt `Gemma NEVER runs the agentic stream`).
- **No consequential action the owner has not allowed.** The phone's MCP tool offers only the
  capability groups the owner has granted, and every group starts Off. Off refuses (`not_granted`);
  Ask raises the phone's own card on every use, whatever the tier; Allow runs without a card. The
  owner answers by tap or by voice, and the action executes only on a second call carrying that id
  against the same arguments — so an Allow on a consequential action is a real permission, said
  plainly on its row. A call naming an action whose group is Off is absent from the advertised
  `enum` (android/app/src/main/java/com/hermesvox/PhoneTool.kt `PhoneTiers.APPROVAL ->`)
  (android/app/src/main/java/com/hermesvox/PhoneGrants.kt `val DEFAULT: State = State.OFF`).
- **No automatic install on the gateway.** The app offers to ask the agent to install this
  skill once, and the answer is remembered; it never installs anything on the gateway by
  itself, and a skill the owner disabled for the API server stays off
  (android/app/src/main/java/com/hermesvox/AppSkillInstall.kt `PREF_OFFERED, false`).
- **No assistant that binds the side button.** Holding `android.app.role.ASSISTANT` decides
  *which app* the assist gesture reaches; whether a long press of the side button is that
  gesture at all is the phone maker's setting (One UI's own side-key/Bixby controls on a
  Samsung). Never promise a Samsung user that installing Vox rebinds their key
  (docs/ASSISTANT-ROLE.md `Android owns the **role**; the phone maker owns the **button**. Vox asks for the role when`).
- **No full duplex.** It listens *or* speaks, with barge-in; it does not hear you while it is
  talking (docs/ARCHITECTURE.md `Half-duplex`).
- **No streaming partials by default.** Recognition is batch: a turn is transcribed after it
  ends. Partials only trigger an early end, and the streaming arm is opt-in
  (docs/ARCHITECTURE.md `Recognition is *batch*`).
- **No semantic end-of-turn.** Endpointing is silence plus a conservative finished-sentence
  check (docs/ARCHITECTURE.md `not semantic`).
- **No emotion in the voice.** Mood moves speed and pauses only; Supertonic has no emotion
  control (docs/ARCHITECTURE.md `no emotion control`).
- **No session store of its own.** The app keeps no session list on disk: what the card shows is
  what the host answered a moment ago, and the transcript on screen is the host's, re-read rather
  than remembered. Offline it says `the host did not answer` instead of showing a stale copy
  (android/app/src/main/java/com/hermesvox/Sessions.kt `const val NO_ANSWER = "the host did not answer"`).
- **No transcript editing or resend.** The words on screen track the voice; the text field sends
  a **new** turn rather than editing history (android/app/src/main/java/com/hermesvox/VoiceController.kt `A text turn`).
- **No remote relay through the maintainers, no telemetry dashboard, no server-side logs.**
  Logs are local-only, transcript logging is opt-in, and the export is a file the user
  chooses to share (android/app/src/main/java/com/hermesvox/VoiceController.kt `"text=${if (logTranscripts()) semantic.text().take(60) else "<hidden>"}")`)`).

---

## 8. A 60-second triage recipe

1. Ask for Settings → About & Diagnostics → Debug / crash log → Share full log. It ships both
   log generations (android/app/src/main/java/com/hermesvox/SettingsActivity.kt `// prior full generation, older) + hermes-vox.log (current). mergedRuntimeLog`).
2. Search `event=gate-timeout`, `event=stream-poll-abort`, `event=stream-turn-failed`,
   `event=stt-fallback`, `event=barge-nearmiss`, `event=er-presence skip`,
   `event=model-dl-end`. Those seven are the fastest route into 6.1–6.14.
3. Read one `event=turn`: `outcome=` says how the turn ended, and the latency fields say which
   leg was slow (android/app/src/main/java/com/hermesvox/LatencyStats.kt `outcome=`).
4. Check the version and Presence: whether the layer is on and whether its model is installed
   and startable (android/app/src/main/java/com/hermesvox/PresenceGate.kt `fun state`).
5. If the log has no `dd()` lines, the user's "Verbose file log" is off — ask them to turn it
   on and reproduce (android/app/src/main/java/com/hermesvox/VoxLog.kt `fun dd`).
