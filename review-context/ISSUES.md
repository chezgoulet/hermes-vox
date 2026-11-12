# The 47 open issues — triage index

Read from GitHub on 2026-11-11, before the re-labelling proposed in PLACEMENT.md. Each entry is the
title, labels and a 300-character hook — enough to triage, not enough to judge. Pull the full text
and comments with `gh issue view <n>` for every issue you actually examine; the bodies carry log
excerpts, measurements and decisions that the hook does not.

**#45 — No wake-word / continuous always-on loop — the full-ambience metamode is not implemented**
  enhancement, milestone/future, review/deepseek, roadmap, sev/low · 1 comments · updated 2026-10-21
  Priority P2 file:line whole repo; nearest ModelCatalog.kt:38 (silero-vad described as "wake trigger") but VoiceController.kt:132-221 has no wake-word — the loop is VAD-gated only after the user manually taps Call (MainActivity.kt:142-180), and the mode enum (ModelCatalog.kt:72-74) only has realtime/…

**#46 — No speaker-ID / voice-gated security — any detected voice triggers the agent**
  enhancement, milestone/future, privacy, review/deepseek, security, sev/low · 1 comments · updated 2026-10-21
  Priority P2 file:line VoiceController.kt:158-211 (any VAD speech → runStreamedTurn); ModelCatalog.kt:36-56 (no speaker-embedding model); no speaker-verification path anywhere in the repo. Best practice violated Pattern 8 — "Speaker ID / voice-gated security (only respond to owner) — privacy/sovereig…

**#179 — Co-residency run: VAD + Nemotron STT + TTS + presence in one live call**
  enhancement, milestone/0.10 · 2 comments · updated 2026-11-11
  The gate on adopting the streaming STT arm (D5, amended 2026-10-19). Nemotron 0.6B at the 160 ms chunk won the A/B on accuracy and accent, but its cost was measured alone: RTF 0.68–0.76, 903 MB RSS, against the Zipformer's 0.19 and 291 MB. A live call runs VAD, STT, TTS and the Gemma presence render…

**#202 — Speech stops before the sentence ends: report what you hear**
  bug, milestone/0.9.1, sev/medium · 1 comments · updated 2026-11-03
  The report so far. On 0.9.0, an utterance — a reply, or one of the entity's own mid-run presence lines — sometimes stops partway through while the on-screen text continues to the end. The owner hears the audio end mid-sentence; the transcript shows the whole thing. Two candidate causes, not yet sepa…

**#208 — Behaviour matrix on two devices, recorded per release**
  enhancement, milestone/0.10 · 1 comments · updated 2026-11-11
  A 1.0 gate (D3) and currently the weakest kind of evidence: we have runs on a Pixel 7 and a Pixel 9, but no artifact anyone can read. What to record, per release candidate, as a table rather than prose: - device + Android version, mode (Realtime / Enhanced Realtime) × endpoint state (HTTPS gateway, …

**#209 — Performance baseline per release**
  enhancement, milestone/0.10 · 5 comments · updated 2026-11-11
  A 1.0 gate (D3: performance within the measured baseline) with no baseline to be within. Record on the release APK, unchanged settings, two devices: - cold start to first frame; model warm (STT, TTS, presence) from cold and from cache; - a short turn: first token, first text, first audio, full reply…

**#217 — Presence tuning: an instrument and a bar**
  awaiting-reporter, enhancement, milestone/0.10, ux · 6 comments · updated 2026-11-11
  Presence is the part users actually hear, and it has been judged by ear on one session. The log already carries the parts (er-presence line/skip/stop, er-soul kind=working, er-render-skip, the soul(...) counters) but nothing summarises them. Build the instrument: from a log, a per-session summary — …

**#228 — Continuous capture: the mic stays open for the call**
  awaiting-reporter, enhancement, milestone/0.10 · 6 comments · updated 2026-11-11
  Today the capture loop opens for a turn and closes when the turn ends. Continuous capture keeps the stream open for the whole call and consumes the partials the streaming recogniser produces (207). Precondition, not a footnote: the battery and thermal budget, measured on a real long call. A recognis…

**#229 — Semantic endpointing: commit early, and be walkable back**
  awaiting-reporter, enhancement, milestone/0.10 · 5 comments · updated 2026-11-11
  With continuous capture in place, the end of a turn stops being a silence threshold and becomes a judgement: a probability that the user has finished, plus a timeout, with an eagerness preset replacing the hand-tuned constants. Two behaviours come with it: - backchannels — presence can react to spee…

**#230 — First run: an onboarding path for the whole app**
  awaiting-reporter, enhancement, milestone/0.10, onboarding, ux · 1 comments · updated 2026-11-11
  OnboardingActivity exists, but it does not walk a new user through what this app now is: a gateway, a voice, a hearing model, presence, and a first call. Build the path in order of what actually blocks: 1. the gateway (with the connection test telling the truth); 2. microphone permission, framed as …

**#232 — It reaches you: routing and a BYO push endpoint**
  enhancement, milestone/future, roadmap · 0 comments · updated 2026-10-21
  D12's outbound half, unnumbered by D21: per-event-class routing (voice call / text / none / delegated) with a user-supplied push endpoint and a per-class rate ceiling. This is what turns the armed session from 1.x into a proactive presence. Constraint from the design: the entity may only initiate on…

**#233 — It shows you: MCP surface and a closed scene vocabulary**
  enhancement, milestone/future, roadmap · 0 comments · updated 2026-10-21
  The agent controls the application through an MCP surface in the app, so it can draw meaning rather than only say it: a shape, a label, an arrow, a timeline, from a closed vocabulary that can be described to a screen reader and replayed deterministically. Per-tool grants, a visible "the agent has co…

**#234 — The household: identity, per-person relationships**
  enhancement, milestone/future, roadmap, security · 0 comments · updated 2026-10-21
  Speaker ID and per-person relationships: who is talking, and how the entity addresses a family rather than a user. Multi-profile (174) becomes identity rather than connection bookkeeping. This is where 46 stops being sev/low: any detected voice currently triggers the agent, and in a proactive, multi…

**#237 — Watch: Ming-3.0-tts 0.5B — MIT, 44.1 kHz, paralinguistic tags**
  enhancement, milestone/future, roadmap, watch · 0 comments · updated 2026-10-21
  A voice model worth following, not adoptable now. Logged on the House watch list 2026-10-20; tracked here so it survives. What it is: inclusionAI/Ming-3.0-tts-0.5B — dense Qwen2.5-0.5B backbone + DiT head + audio tokenizer + speaker encoder, 44.1 kHz, seven languages (en, zh, de, es, fr, ko, ja) plu…

**#238 — Watch: VoiceManager — one socket carrying streaming STT and TTS (protocol prior art)**
  enhancement, milestone/future, roadmap, watch · 1 comments · updated 2026-10-21
  A local speech server that solves the same problem Vox is solving on-device, from the other end. MIT, Python 3.12, GPU host; created 2026-10-17, ~19 stars — young, one author, not a dependency. The value is the protocol, not the code. What it does: one WebSocket carries STT and TTS at the same time.…

**#242 — Barge-in should classify the overlap, not just measure its level**
  awaiting-reporter, enhancement, ux · 2 comments · updated 2026-11-06
  Research checkpoint docs/RESEARCH-2026-10-voice-agents.md §3. The barge gate is a duty-cycle level test against a calibrated floor. The field has a name for that approach and a name for its failure: trigger-happy VAD that misinterprets backchannels (SID-Bench, arXiv 2603.24144). Full-Duplex-Bench v1…

**#268 — What should 1.0 be? Direction from the people using it**
  roadmap · 0 comments · updated 2026-10-21
  1.0 is the general release, and it has no date. It gets cut when the feature set is complete and stable — and the only honest way to learn that is from people using the app, not from a calendar. Where things stand. The next two releases are fixed. 0.9.1 makes the mechanics excellent with or without …

**#279 — The loop, measured: a stranger's real task, end to end**
  enhancement, milestone/0.10, ux · 0 comments · updated 2026-11-11
  The goal of 0.9.1 is not a list of fixes; it is a closed loop. This issue is the test that decides whether it closed. The run. A person who has never used the app, with no tutorial and no help, does this by voice: 1. "Pull my calendar and tell me what's on today." 2. "I don't want to go to that meet…

**#281 — Operations: show what is running, and on what**
  awaiting-reporter, enhancement, milestone/0.10, ux · 2 comments · updated 2026-11-11
  Today a user can hand any command to the agent, but cannot see the work. A tool call is a spinner and a hope. Show, while it runs: which tool is running, in plain language, and what it is acting on. When a step fails, what failed and why. When a job is long, that it is still moving. Do not build: a …

**#282 — Approvals on the phone: sign-off when Hermes hits a barricade**
  awaiting-reporter, enhancement, milestone/0.10, security, surfaced, ux · 2 comments · updated 2026-11-11
  Hermes sometimes stops and asks its owner to approve something — a dangerous command, an action with consequences. On the phone that barricade is invisible today: the turn simply stalls. Surface the request: what is being approved, why it was asked, what happens on yes and on no, and the exact actio…

**#292 — Watch: KittenTTS — Apache-2.0, 15–80M ONNX voices (and a 1.7B cloner)**
  enhancement, milestone/future, watch · 0 comments · updated 2026-10-22
  Logged on the House watch list 2026-10-21. Tracked here so it survives. What it is: KittenML/KittenTTS — Apache-2.0, ~16k stars, active. Two families under one constructor: - Kitten-TTS 2: 1.7B parameters, 1-bit, 506 MiB; zero-shot cloning from 5–30 s; 47 voices; 20 languages; emotion and event tags…

**#293 — Watch: Phonon (gradium) — 31M streaming TTS with an Android path**
  enhancement, milestone/future, watch · 0 comments · updated 2026-10-22
  Logged on the House watch list 2026-10-21. Tracked here so it survives. What it is: gradium's Phonon — TTS built on Kyutai's Pocket TTS and the Mimi codec. The usable model, phonon-31m-en, is 31M parameters, a 64.5 MB q8 GGUF, 24 kHz, and streams audio in 80 ms frames. The runtime gradium-ai/xn-ptts…

**#326 — [feature] Add audio environment modes for indoor and outdoor use**
  enhancement, question · 4 comments · updated 2026-11-08
  What are you trying to do? Add one-touch audio modes, such as Indoor and Outdoor, that automatically adjust the app’s audio behavior for the current environment. This would allow users to quickly shift between optimized behavior for a quiet indoor room and a louder outdoor or external environment. O…

**#373 — [bug] TTS — the system engine speaks once then goes silent, and a missing voice model still lets a call start**
  awaiting-reporter, bug, milestone/0.10, sev/high · 1 comments · updated 2026-11-11
  Owner's result on card T23: fail. Verbatim, two cases: I set it to System Fallback. It said, one greeting, and then never spoke again. I set it to piper, and while it did have a warning that Piper model was not installed, it let me place a call anyway... and did not return any tts or signal from Her…

**#374 — [evidence] First captured STT mishearing — "it's good to be with you" parsed as "it it it"**
  bug, milestone/0.10, sev/medium · 2 comments · updated 2026-11-11
  Card T16 was marked can't judge, but it contains the first real transcript of a mishearing, and that is worth keeping: It struggled to properly parse my first sentence, which was, it's good to be with you. All that was parsed was "it it it" However, my Hermes agent read that as interference, and so …

**#375 — [tracker] 0.9.1 acceptance — findings and the order they land**
  milestone/0.9.1, ux · 2 comments · updated 2026-11-04
  0.9.1 — acceptance findings, and the order they land The release is in acceptance: testing is 0.9.1 until the owner signs it off, and everything the run turned up lands in this version. This issue is the index. Run complete. 52 cards: 24 pass · 7 fail · 11 can't judge · 10 untouched. Run sheet (gene…

**#376 — [bug] Approvals were never exercised: no approval request reached the phone in the whole run**
  awaiting-reporter, bug, milestone/0.10, sev/high · 1 comments · updated 2026-11-11
  Card T41 in the 0.9.1 acceptance run: fail, with T42b, T43, T44 and T45 all blocked behind it. Owner, verbatim: I saw nothing that looked like an approval UI It sounded like it may have tried to ask but I didn't catch it nor did I see any text or UI elements that would suggest it was asking me a que…

**#388 — [feature] Expose TTS Playback Speed Control in App Settings**
  enhancement, good first issue, milestone/future · 1 comments · updated 2026-11-06
  What are you trying to do? Expose the existing speech rate controls in Supersonic through the Hermes Vox Settings UI. Supersonic already supports playback speed adjustment, but this functionality isn't currently exposed to users. Add a Speech Rate slider under the TTS / Voice settings so users can a…

**#404 — Flip the turn route to /v1/runs, and pass the device checks — the cut gate (PLAN §10)**
  awaiting-reporter, enhancement, milestone/0.10 · 1 comments · updated 2026-11-11
  The reader is in behind a default-off switch (PR 393); the flip is not. - Flip StreamRoute.active to RUNS and prove it on a device: approve and deny by touch, deny by voice, a timeout that resolves to no, a barge-in that reaches the run as POST /v1/runs/{id}/stop, and a dropped connection that reatt…

**#405 — Re-measure the budgets with continuous capture on**
  enhancement, milestone/0.10, perf · 2 comments · updated 2026-11-11
  Continuous capture and semantic endpointing default on, behind the Alpha gate (D29), which changes the memory, thermal and battery picture docs/BUDGETS-0.9.1.md was written against. - Re-run the RSS, thermal and battery measurements with the continuous lane live in a call. - The cut holds to the re-…

**#421 — Presence and tool use: the mind directs, the phone acts, and the mind can veto**
  milestone/0.10 · 2 comments · updated 2026-11-11
  Presence today has no tool use at all: the soul has no tools and never has, and the only tool-related instruction on the voice path tells the mind not to reach for them (ResponsesClient.kt:125: "Answer from what you know; do NOT do exhaustive tool work … unless the caller explicitly asks"). So a fac…

**#428 — [tracker] 0.9.1 — the twelve gaps between testing and the cut**
  milestone/0.9.1 · 0 comments · updated 2026-11-04
  Twelve gaps stand between testing and a signed 0.9.1. This is the tracker; each has an owner and a definition of done. Can hold the cut G1 — F-Droid listing (165). Recipe, source build and reproducibility are done; nothing has been submitted. D29 makes the release wait for the listing, and that is s…

**#429 — Phone control: a comprehensive capability set, granted by the user group by group**
  milestone/future · 2 comments · updated 2026-11-06
  Comprehensive phone control for the Hermes agent, with the user deciding how much control it has. Today the agent gets one phone tool with six actions and no user-facing grant beyond enabling it. The owner's direction: a comprehensive capability set, governed by granular permissions — the user choos…

**#442 — Android TV: a thin client on the biggest screen in the house**
  enhancement, milestone/future, roadmap · 2 comments · updated 2026-11-05
  Feature request (owner), not 0.9.1. Android TV support for Hermes Vox. The constraint that decides the architecture Most TV boxes carry 1.5–3 GB of RAM. The Presence model alone is 2.6 GB and holds ~3 GB resident during a call. So Presence cannot run on the television, and no amount of optimisation …

**#443 — A starship-console theme — Jean-Luc (palette from gt-ui, chrome drawn by us)**
  enhancement, milestone/future, ux · 1 comments · updated 2026-11-05
  Feature request (owner), not 0.9.1. A starship-console theme for Hermes Vox — the LCARS look. What LCARS actually is Not a palette. A design language: black ground, elbow-shaped frames, blocky segmented bars, right-aligned labels, rounded caps, condensed uppercase type, deliberate asymmetry, and a f…

**#444 — Generated UI: the agent asks with shapes, the user answers with hands**
  enhancement, milestone/future, roadmap, ux · 0 comments · updated 2026-11-05
  Feature request (owner), future. The phone is a touchscreen that already animates pixels into shapes. So let the agent generate UI for the moment it is in — and let the user answer with their hands. The owner's example: "Maybe the agent asks the user to draw something during a session, for whatever …

**#445 — The phone as a surface: beside, on, in, around**
  milestone/future, roadmap, ux · 0 comments · updated 2026-11-05
  Programme issue. Not tagged to a release: this is the umbrella for the work that turns the phone from a device the agent speaks through into a surface the agent shares. Each child issue is independently shippable; this one exists so the shape does not get lost. The four surfaces Beside. The agent ke…

**#446 — Screen access in two tiers: assist structure first, accessibility behind a marked section**
  enhancement, milestone/future, privacy, security, ux · 0 comments · updated 2026-11-05
  Owner's direction: everything should work with assist structure where possible. Extend into accessibility only if testing shows it is necessary — keep it as clean as we can before reaching for that. This is an alpha feature precisely so we can find out. Tier one — reading what is on screen Assist st…

**#447 — Hermes API: audio in and audio out, so weak devices can borrow the host voice**
  enhancement, milestone/future, roadmap · 0 comments · updated 2026-11-05
  Upstream Hermes feature, not a Vox patch. This is the piece that lets weak devices borrow the host's voice. What Hermes has today Transcription and synthesis are first-class inside the agent — a provider registry with fallbacks (agent/transcriptionregistry.py, agent/transcriptionprovider.py, gateway…

**#448 — Local vision: read the screen with the on-device Gemma, frame never leaves the phone**
  enhancement, milestone/future, privacy · 0 comments · updated 2026-11-05
  Future. The on-device model can look at the screen, and nothing in the app uses that yet. What is already true - The app loads through LiteRT-LM 0.16.1 with gemma-4-E2B-it.litertlm, and GemmaExpress already imports Content / Contents — the multimodal content type — but only ever puts text in the con…

**#496 — [bug] After the agent replies, the call stops taking voice input until it is redialled**
  awaiting-reporter, bug · 0 comments · updated 2026-11-06
  Reported by @aiquiral against 0.9.0.3, in the thread on 330, after the system-TTS silence there was fixed: Now, the voice is working, but after the agent finishes speaking, it does not take further voice input. Even with Barge-in (interrupt) enabled, it just stops taking input. I have to press the r…

**#514 — feat(android): per-profile mTLS client certificate selection**
  milestone/0.10 · 0 comments · updated 2026-11-11
  Following the merge of 510 (originally 468), I'd like to work on the per-profile client certificate selection feature you mentioned in your review. Use case Hermes Vox connection profiles have their own gateway URLs. I'd like to select an Android KeyChain client certificate for each profile, includi…

**#528 — Localisation: the contract, before the code**
  milestone/0.10 · 2 comments · updated 2026-11-11
  Why this exists This issue is the contract for localisation in Hermes Vox, opened at the suggestion of @PierreCsn so that the rules are agreed before code is written. Nothing here is implemented yet. The goal is that adding a language is a resource change, not a conversation-layer change, and that a…

**#538 — [bug] Regression in v0.9.1: Supersonic / TTS failures, intermittent STT issues and possible agent execution problems**
  bug · 6 comments · updated 2026-11-11
  Device Pixel 8 Pro (GrapheneOS) Android version 17 Vox version 0.9.1 How it was installed Obtainium Gateway Local network Steps to reproduce Hi, I've noticed a regression in version 0.9.1 of Hermes Vox, primarily relating to text-to-speech (TTS) and voice output. The behaviour has been somewhat diff…

**#559 — Supertonic drops words; Piper is more consistent on the same device**
  bug, milestone/0.9.1 · 0 comments · updated 2026-11-11
  Field report from the owner, Pixel 9, nightly cbbf45f, same call as the PiP and mood-marker findings. "Supertonic: it's nice to have the different voices, but it drops words on the text to speech quite a lot. Piper performs way more consistently." So: Supertonic is intelligible but lossy — words go …

**#562 — Presence model: bench a smaller one, or no model for most lines**
  milestone/0.10 · 0 comments · updated 2026-11-11
  The owner's call on 2026-11-11 exposed the cost plainly: the presence model is a 2,588 MB file, ~2,646 MB PSS, 5.6 s median load (13 s observed under load), and it produces lines of 11–31 characters — "I see the issue", "Checking the file". Its render measured p50 1.4 s / p95 3.5 s on the Pixel 9, a…

**#569 — The recogniser runs slower than real time on the field device (RTF 1.0–1.8 against the bench's 0.7)**
  bug, milestone/0.10, perf · 0 comments · updated 2026-11-11
  Field log, owner's Pixel 9, 0.9.1.2-nightly-6633013, every Alpha feature on. The live recogniser is running slower than real time: rtf 1 means the decoder cannot keep pace with speech: a 7.8 s utterance took 22 s to finalise, and stt-live-fallback reason=worker-busy fired three times in the same cal…
