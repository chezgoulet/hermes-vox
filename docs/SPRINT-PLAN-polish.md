# Hermes Vox — Sprint 6: Polish / UX Sprint (before Enhanced Realtime)

**Repo:** chezgoulet/hermes-vox · **Branch:** feature/polish
**Model:** `deepseek/deepseek-v4-flash-vision-exp` · **Owner/approver:** Torc
**Theme:** Make everything more intuitive. Post-hardening polish — the phone-call flow + foundation are solid (0.3.26–0.3.29); this sprint is UX/UI clarity + making actions actually work.

## Concrete bug found on-device (0.3.28.1) — the slash command speaks with no call
Log (14:52:06, no active call): a stream starts with **no `realtime: speech=`** (a slash-command/text turn), gets a reply (`turn done gen=0 resp_2727f272 len=761`), then the app **generated + spoke the audio**:
```
14:52:32.439 piper generated 1040176 samples @ 22050Hz (text 761 chars)
14:52:53...  piper played 1040176 samples
```
**Problem:** speech playback is NOT gated on an active call — a slash-command reply (no call) is voiced aloud. **Root:** the reply/settle `speak()` path doesn't check `callLive`; it speaks whenever a reply arrives. **Fix:** the TTS/speak path must be gated on `callLive` (or the intended "voice channel" being open) — replies that arrive with no active call are rendered as **text only**.

## Workstreams

### WS1 — Settings rework (nested views)  ⭐
Group related settings into their own nested sub-views instead of a flat list. Proposed groups (each a sub-screen with its own **restore-defaults**):
- **Entity / Connection** (url, key, model, connection-test button, voice mode).
- **Speech & Mic** (the VAD dials + partial-STT/AEC/noise-suppressor toggles from Tier-4).
- **STT / Transcription** (backend + model picker).
- **TTS / Voice** (engine + voice register pickers).
- **Models** (download/install view — promoted).
- **About / Diagnostics** (version, log export, dev console).
- Keep the main Settings screen as a clean list of group rows; each rows into a nested view. Restore-defaults in every sub-view.

### WS2 — Models download view prominence
The model download/install view is the first-run blocker — bring it **high** in Settings (near the top, maybe a `row_models` right under Entity/Mode), and make the install flow obvious (which models are needed, progress, install state).

### WS3 — Connection test plain-language
Replace the raw `ping error: null / stream error: null` text with plain language a regular person understands:
- Success: **"Connected to your agent. Everything's working."**
- Failure: **"Couldn't reach the gateway. Check that your network is on and the address is right."** (+ the specific reason in smaller debug text).

### WS4 — Actions actually work
- **Speak gate:** the reply/`speak()` path must only voice a reply when there's an **active call** (`callLive`). With no call, replies are text-only. (Fixes the slash-command-speaks bug.)
- **Slash commands as native mini-UIs (NOT agent strings):** each `/command` should bring up a **native panel** for its capability, not send a string to the agent to talk about. Examples:
  - **`/models` → native model list, organized by provider**, pulled from the gateway `/v1/models` (live data), each row showing provider/name/status.
  - **`/health` → gateway health card.** **`/new` → new-conversation confirm.** **`/reconnect` → reconnect action.**
  - The slash menu shows user-friendly labels; tapping opens the panel. The agent is for *conversation*, not command-UIs.
- Remove/replace the current string-passing slash path.

## Deferred (after Sprint 6)
- Enhanced Realtime (Gemma) spike; voice-quality roadmap (streaming STT, latency, enhancement); Google Play; release-signing polish.

## Definition of done
- Settings regrouped into nested sub-views, each with restore-defaults (no behavior change at defaults).
- Models view prominent + obvious.
- Connection test in plain language.
- **No speech without an active call** (slash-command replies are text-only; the 14:52 bug is gone).
- `/models` (and friends) open native mini-UIs fed by real gateway data (models by provider), not an agent-text reply.
- No regression: realtime loop, chaining, barge-in, model downloads. Emulator + on-device.

## Iron rules (build agent)
- Do NOT touch Enhanced Realtime/Gemma, Google Play, the Hermes gateway, the model downloader logic, or the realtime-loop/chaining.
- Keep behavior identical at defaults/the untouched path. No minify-off. No secrets.
- One coherent commit per workstream; `assembleRelease testReleaseUnitTest` green after each.
- The slash-command mini-UIs fetch REAL data (e.g. `/v1/models`) — do not fake/placeholder the provider list.
- Native panels push data from the gateway; the agent does conversation, not command-UIs.
