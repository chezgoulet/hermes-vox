# Hermes Vox — Sprint 4 Plan Brief: 0.4.0 Gates + First-Run Correctness

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/040-gates
**Plan + build model:** `deepseek/deepseek-v4-flash-vision-exp` (DeepSeek API) · **Owner/approver:** Torc
**Review gate:** Torc adversarial read + compile + emulator (`emulator-5554` via adb/uiautomator/screencap) BEFORE shipping, + on-device where the emulator can't cover (arm64-only Gemma, gateway stream).

## Sprint theme
**"Finish the 0.4.0 gates and fix first-run."** Sprint 2 + 3 shipped a working realtime phone-call flow (multi-turn, barge-in, session isolation, release signing + security #1/#14/#50 + #60). This sprint drives the app to the **0.4.0 gate** (per `docs/handoff.md` §3/§6): realtime OK (done), **Enhanced Realtime (Gemma)**, **particles + settings**, **mic settings**, plus the **no-regress** invariant — and closes the first-run blocker **#61** (mic-permission prompt).

## Context / anchor points (verified, not assumed)
- **Phone-call flow is green** (0.3.24.3, on-device): silero VAD + Piper + whisper-base all download + load; multi-turn + barge-in work.
- **Model download path is fixed + observable** (0.3.24.3): `dir.parentFile?.mkdirs()` before the ingest rename; `ModelDownloader` now logs `model <id>: downloading/result/threw` to `VoxLog`. The stale `unpack swap failed` is resolved.
- **Gemma hash corrected** (`main @ 18f4755`): gemma-e2b pinned sha256 updated to `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` (matches HF `litert-community/gemma-4-E2B-it-litert-lm` file). (The old `ee3c29…` was stale → `sha256 mismatch`.)
- **Shrink/minify is currently OFF** (reverted in 0.3.24.1). Defer/drop decision below.
- **gemma-e2b is arm64-only** (the `.litertlm` won't load on the x86_64 emulator → graceful fallback via `RoutedExpress`); the phone loads it.

## In-scope workstreams

### WORKSTREAM 1 — #61 mic-permission prompt (first-run blocker)  ⭐ HIGH
**Bug:** `MainActivity.kt:135-136` (start-call path) checks `RECORD_AUDIO`; if missing it `setStatus("Mic permission needed to start the call", true); return` — **no `requestPermissions()`**, so the system dialog never appears. A sibling path (`MainActivity.kt:333-341`) already does the correct `ActivityCompat.requestPermissions(...)`.
- **Fix:** in the start-call path, when `RECORD_AUDIO` is not granted, call `ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)` (mirror lines 333-341; same REQUEST_CODE), optionally preceded by `shouldShowRequestPermissionRationale`. Add/verify `onRequestPermissionsResult(...)` so the call proceeds once granted; on hard denial keep the status pill + existing behaviour. Consider `POST_NOTIFICATIONS` for the foreground service too.
- **Verify:** fresh install → tap call → system mic dialog appears → grant → call starts. Emulator + on-device.

### WORKSTREAM 2 — Enhanced Realtime (Gemma presence) — 0.4.0 gate
- `GemmaExpress` + `VoiceOrchestrator` are wired; `handleModeUi` loads Gemma when mode=enhanced; degrades to `RoutedExpress` when the `.litertlm` is absent/arm64-incompatible.
- **Path:** (a) the device downloads `gemma-4-E2B-it.litertlm` (~2.4 GB, sha256 now correct) from the HF source; (b) select **Enhanced Realtime** mode; (c) the `.litertlm` loads on-device (arm64-only; emulator falls back gracefully — no crash).
- **Note the house-store alternative** (`docs/handoff.md` §3): `models-store/gemma-e2b.zip` (2.2 GB, sha256 `877db…adc`) is packaged on the Thelio; serving `models-store/` over HTTP + setting the app's `model_source` to the house store is an alternate source if we want to self-host rather than pull from HF. Decide one; the app's `ModelSpec` currently points at the HF `.litertlm` (verified).
- **Verify:** on-device (arm64) Enhanced Realtime loads Gemma + runs the presence layer; emulator shows the graceful fallback (no crash).

### WORKSTREAM 3 — Particles + settings (presence UI) — 0.4.0 gate
Per `docs/handoff.md`: the idle shape/theme + auto-cycle toggle in Settings → Particles/presence sub-menu. **The theme is moved OFF the raw avatar tap (tap = STOP); idle theme visibly changes the avatar (verified vortex spiral).** This is the Christopher UI-taste gate: ALIVE/generative, presence-first, airy particles, avatar may read as an EYE (good, not "avoid"), user-config modes.
- **Verify:** emulator (`uiautomator` + `screencap`) — idle theme changes avatar; tap = STOP; auto-cycle toggles; no crash.

### WORKSTREAM 4 — Mic settings — 0.4.0 gate
The per-piece pipeline settings (STT model picker — the `ModelCatalog` STT model map, TTS voice, source/config). Surface + persist per the Settings model.
- **Verify:** STT/TTS source + model selections apply + persist; the selected model loads; no regression to the default warm voice.

### DECISION — R8/minify + shrink (intentionally deferred from Sprint 3)
The shrink was reverted because the `-keep class org.apache.commons.compress.** { *; }` rule didn't preserve the runtime `BZip2CompressorInputStream`/`TarArchiveInputStream` path that the model downloader needs. Two options for Sprint 4:
- **Re-enable properly** — fix the keep-rules (add `-keepattributes Exceptions,InnerClasses,Signature`, `-dontoptimize`/`-keep` the compressor factory service-loaders, verify the model unpack works against a REAL model download, gated on-device) for a ~2 MB size win. OR
- **Drop it** — keep `minifyEnabled false` (accepted: APK stays ~106 MB, native-heavy), document the trade-off, revisit only if size becomes a real constraint.
**Recommendation:** attempt one focused keep-rule fix + a real model-download gate; if it doesn't hold cleanly, drop it (make that an explicit decision, not a silent revert).

## Deferred (do not expand scope)
- **Voice-quality roadmap:** streaming/partial STT (#38), latency instrumentation (#40), speech enhancement.
- **Google Play publish:** blocked on the Play identity/passport verification (the `hermes-vox` keystore + sideload/Obtainium are ready regardless; Play upload reuses the same key).
- **Crash-hardening #7/#8** (AudioTrack/native use-after-free) — separate sub-track.
- **Voice-loop polish:** the harmless #60 double-release edge (already once-guarded), the `TurnGateReleaseTest` regression assertion (needs Robolectric), DevTools/UX extras.

## Definition of done (0.4.0 gate)
- **#61** closed: mic-permission prompt appears + call starts on grant (emulator + on-device).
- **Enhanced Realtime (Gemma):** on-device (arm64) loads the `.litertlm` + runs the presence layer; emulator degrades gracefully (no crash).
- **Particles + settings:** idle theme changes avatar, tap = STOP, auto-cycle toggles.
- **Mic settings:** STT/TTS source + model selections apply + persist.
- **Shrink decision made** (re-enable-with-fixed-keep-rules OR drop explicitly), not an accidental revert.
- **No-regress:** warm voice, offline STT, streaming, realtime loop, and the model downloader all keep working (re-run the phone-call smoke on-device).
- Unit + instrumented tests green; each workstream fits an Android `testDebugUnitTest`/instrumented gate where possible.

## Iron rules (build agent)
- **Never commit secrets** (keystore gitignored; password from env store / `keystore.properties`).
- **Release convention:** tag === versionName === APK, no leading `v`; proper semver (Obtainium).
- **Verify against a real on-device log before shipping** — instrument the path so it is diagnosable; do NOT iterate builds guessing at a cause (the model-unpack churn was exactly that failure mode).
- One coherent commit per fix; each compiles + its tests pass. Byte-precise edits.
- Do not regress the model downloader (it now logs; keep `dir.parentFile?.mkdirs()` + the logging).
