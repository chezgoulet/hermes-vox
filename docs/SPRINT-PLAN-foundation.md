# Hermes Vox — Sprint 5 Plan Brief: Polished Foundation (before Enhanced Realtime)

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/foundation
**Plan + build model:** `deepseek/deepseek-v4-flash-vision-exp` · **Owner/approver:** Torc
**Review gate:** Torc adversarial read + compile + unit tests + emulator smoke; on-device for audio paths.

## Sprint theme
**"Make the base rock-solid before the most complicated part."** Sprint 1–4 shipped a working realtime phone-call flow + model downloads + release signing + security. This sprint closes the **foundation/hardening set** the Enhanced Realtime (Gemma presence) layer inherits — correctness/race/leak, privacy, latency/"alive", and settings-honesty — so we write the presence layer on verified ground, not on unvalidated edges. (Triage source: `gh issue list` — 61 open; the code-complete ones are being closed separately.)

## Workstreams (prioritized — do in this order)

### WORKSTREAM 1 — Tier-1 correctness / race / leak (the concurrency the enhanced layer amplifies)  ⭐
- **#16 (HIGH, Go data race)** — `voice/stream.go:140,150,154,168,175,179` dispatch writes `st.respID`/`st.text`(strings.Builder)/`st.done` with NO lock; `PollStreamJSON` reads them under `st.mu`. A builder grown concurrently with `String()` can hand the app a torn `text` → wrong TTS input. **Fix:** write `text`/`respID`/`done` under `st.mu` (or atomics for flags + append to a guarded builder); add `-race` to `scripts/gate.sh` + `go test -race`.
- **#19 (MEDIUM, leak)** — `VoiceController.kt:143-145,207-211,232-238,47` — walkie one-shot exit `stop()`s but never `release()`s `record`; `loopActive=false` → next `start()` overwrites `record` (one leaked native audio session per PTT press → eventually `AudioRecord` stops constructing → degrades). `stop()` never calls `exec.shutdown()`. **Fix:** release `record` on the no-repeat exit + `stop()` calls `exec.shutdown()` (bounded).
- **#24 (MEDIUM, leak)** — `MainActivity.kt:256,300,322` vs `:148` — `startCall` passes `applicationContext` but `send()`/`talk()` pass `this`; the `@Volatile` companion field then holds an Activity (Settings `recreate()` is a reachable trigger). **Fix:** pass `applicationContext` consistently (all three).
- **#35 (LOW, loaded gun)** — `RealtimeActivity.kt:61,68` constructs a **second** `VoiceController` (2nd `AudioRecord` owner), unreachable now that the Realtime button toggles a mode. **Fix:** delete it (single-capture discipline).
- **#28 (MEDIUM, latent)** — `VoiceController.kt:32` claims "all dispatched on main thread"; `:134,163,214,257` call `onState`/`onLog` from the executor. Latent today but the contract is false. **Fix:** wrap the listener callbacks in `main.post`/`runOnUiThread` at the call sites (make the doc true).
- **Verify:** `go test -race ./voice/...` and `:app:test` green; a rapid PTT start/stop loop on the emulator leaks no native sessions; no double `AudioRecord`.

### WORKSTREAM 2 — Tier-2 privacy / security
- **#29 (MEDIUM)** — `android:allowBackup` not disabled → plaintext prefs recoverable from backup. **Fix:** `android:allowBackup="false"` (+ `fullBackupContent="false"`) in the manifest.
- **#13 (HIGH)** — indefinite wake lock never released on background (mic loop keeps running). **Fix:** release the PARTIAL_WAKE_LOCK on `onStop`/`stop()` (bounded to the active call), like the model-dl wake lock.

### WORKSTREAM 3 — Tier-3 "feels alive" / latency (directly the Sesame goal)
- **#39 (MEDIUM)** — SSE is `poll`ed every 240ms, not a push stream → no true realtime. **Fix direction:** switch the stream consumption to a push/SSE-reader (a reader goroutine pushing events to a channel) so deltas render as they arrive, not on a 240ms tick. Bigger change; the foundation for "feels live."
- **#44 (LOW)** — streaming TTS waits for a full sentence terminator before the first audio (no partial-audio). **Fix:** emit partial audio on chunk boundaries (sentence/word) rather than only at `.` — earlier first-audio.
- **#17 (#18 / #25 / #30, MEDIUM/LOW)** — audio correctness: `silentMs` assumes fixed 64ms frame (cuts mid-sentence); pre-roll is quadratic; streaming track sample rate hardcoded (pitch/speed shift for other-rate models); `indexOfSentenceEnd` splits on any `.` (`71.5%`, `Dr.`). **Fix:** time-slice `silentMs` from the actual frame; linear pre-roll; use `audio.sampleRate`; sentence seam only on `.` followed by a space + letter.

### WORKSTREAM 4 — Tier-4 settings honesty (the "pick any config" foundation)
- **#48 / #31** — settings selectors don't change behavior; `buildTts` ignores its `prefer` arg (TTS picker is dead). **Fix:** wire the STT/TTS/voice pickers to actually select the model/voice at build time (so the config-chooser is real before we ship the Enhanced Realtime backend chooser).

## Deferred (after Enhanced Realtime — roadmap/feature)
- #45 (wake-word/always-on), #46 (voice-gating), #47 (Go single-turn backend), #52 (wire Gemma into the Realtime view — the ER work), #54 (ER gate), #55/#56 (particles, mic settings), #59 (selectable warm TTS voices), #33/#34 (dead code, dev tool), #22/#23/#26/#27/#21/#36 (perf/leaks — fold the real ones in as they surface), #42 (audio barge-in test — covered by the emulator harness instead).
- Code-complete issues (Sprint 1–4) are being **closed** separately: #1,#2,#3,#4,#5,#7,#8,#9,#10,#11,#12,#14,#15,#20,#32,#38,#40,#41,#43,#50,#51,#53,#58,#60,#61.

## Definition of done
- Tier-1: `go test -race` + `:app:test` green; no AudioRecord leak / no double-capture / no Activity-context leak; Listener main-thread contract true; no Go streamState race.
- Tier-2: allowBackup off; wake lock released on background.
- Tier-3: the "feels alive" changes land (partial-audio TTS; push-or-close-to-push stream; frame-accurate silence; correct sample rate / sentence seam).
- Tier-4: settings selectors actually change STT/TTS/voice.
- **No regression:** realtime loop, session chaining (resp advances per turn), model downloads, warm voice, barge-in. Verified on-device + via the emulator stress harness (walkie/text against the real Odroid gateway).
- One coherent commit per fix; each compiles + its tests pass.

## Iron rules (build agent)
- Never commit secrets; semver release convention; verify-against-real-log before shipping.
- Do NOT touch Enhanced Realtime/Gemma, Google Play, or the Hermes gateway (the host) — scope is app-side + `voice/` Go only.
- Do not regress the model downloader (parent-dir + logging) or the chaining (resp advancing per turn).
- `voice/` Go changes must pass `go test -race ./...` and regen `mobile.aar` if the Go bind changes.
