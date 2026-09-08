# Hermes Vox — Roadmap

Hermes Vox is an open-source **voice client for the Hermes agent**: a particle-being
you talk to on your phone, with on-device speech processing and a hands-free,
barge-in conversation line.

**Status keys:** `[x]` shipped · `[ ]` in progress / planned · `[?]` open question · `[-]` deferred.

---

## Shipped (`[x]`) — the MVP + hardening line (0.3 → 0.5.6.x)

**M1 — Core completeness (0.3.x).** A working end-to-end voice loop: STT/VAD/TTS on-device,
streaming to the Hermes gateway, barge-in, and the particle-being rendered on OLED black.

**M2 — Modern sci-fi UI overhaul.** A designed (not skeleton) interface on true OLED black,
the particle-being as the central "presence."

**M3 — SSE + streaming.** The entity's stream consumed as a live SSE push (not a poll), and
the reply rendered in real time.

**M4 — Voice pipeline (sherpa-onnx).** The sherpa-onnx runtime bundled (pinned k2-fsa AAR);
real warm on-device Silero VAD (silero-vad), Whisper STT, and Piper TTS.

**M5 — In-app model downloader.** The user downloads the blessed models from inside the app
(no sideload). Models are **stayed in-app downloadable** (not bundled) to keep the APK small
and Play-store-friendly; only the Gemma expression model is a separate, license-required
download.

**M6 — Realtime + Enhanced Realtime.** Two hands-free modes. Realtime = the open line;
**Enhanced Realtime (alpha)** adds the on-device Gemma expression layer (see below).

**Reliability + hardening (0.5.x point releases).** Crash-guard, wall-clock streaming deadline,
barge-in leak fixes, /compress chain unify, Kotlin 2.2.21 toolchain, 7 new visual archetypes
(soundwave, arc, nucleus, eye, water, radar, octopus) + the full ball (sphere), and the
**settings-UX** pass (voice-mode promotion, reset scopes, clear-vs-reset, model-count truth,
bootstrapping, Basic/Advanced split, onboarding + learnability).

---

## In progress / planned (`[ ]`)

**Enhanced Realtime (alpha) — finish.** The Gemma 4 E2B presence layer downloads in-app
(sha256-verified) and `GemmaExpress` loads it via LiteRT-LM. **Not yet wired into the
immersive view** — the presence layer is still Main-only (see issue #52). This is the
biggest open item on the path to a "full ER" line.

**Barge-in interrupt reliability.** The at-rest/glance behavior is in (eye archetype now
reads as an eyeball that pivots); the immersive-view barge/glance integration under a
sustained conversation needs a field pass.

**Desktop / cross-platform (open question).** The portable `voice/` Go core + `mobile/session.go`
are the reusable surface. A future desktop frontend would be a NEW renderer (Compose Desktop,
web/Electron, or a native window) — the current Ebitengine `game/` shell is boilerplate
coupled to the OLD VoiceBackend architecture and is NOT the desktop path.

---

## Deferred (`[-]`)

- **Bundling the voice models** — kept in-app downloadable (Play-friendly; Gemma stays a
  separate license download). A future "offline out of the box" could bundle the ~240MB
  required set, but it's a deliberate APK-size tradeoff.
- **Walkie Talkie / PTT** — removed in C2 (0.4.0). Every mode is hands-free; there is no
  push-to-talk button. (Stripped deliberately — see docs/PLAN-c2-walkie-strip.md.)
- **Cloud voice processing + full-duplex realtime** — after-MVP. The app is local / self-hosted
  (you vs. your own Hermes gateway); cloud is not a mode today.

---

## The being

The identity the whole thing is built around: the particle-being — a luminous swarm that
*is* the agent, present and alive. The shapes it can be are one of its defining features
(the 20-archetype vocabulary from the visual passes). Design north star: "the AI agent exists
within the forms and likes to play with them."
