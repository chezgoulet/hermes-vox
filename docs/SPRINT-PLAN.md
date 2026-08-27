# Hermes Vox — Sprint 1 Plan Brief

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/sprint1-model-downloader
**Model (plan + build):** `deepseek/deepseek-v4-flash-vision-exp` (DeepSeek API, vision) · **Owner/approver:** Torc
**Review gate:** Torc adversarial code review + compile + emulator test (`emulator-5554`). **Merge target:** `main` (Vox releases by tag-on-main).

## Sprint theme
**Model downloader integrity + fix the fresh-install regression.** Make a fresh install download **verified, loadable** on-device models from the canonical k2-fsa upstream. This is a **single file family**: `android/app/src/main/java/com/hermesvox/ModelCatalog.kt` + `ModelDownloader.kt`, plus the loader paths they feed (`OfflineStt.kt`, `SherpaTts.kt`), the model-store scripts (`scripts/model-store.sh`, `scripts/finalize-store.sh`), and `GemmaExpress.kt`.

## Issues in this sprint

### #2 — CRITICAL: canonical-upstream change makes downloaded models unloadable (release-breaking)
- `ModelDownloader.untarBz2` + `hoist()` (≈ lines 96-108, 154-164) extract the k2-fsa tarball — which stores PREFIXED names in a wrapping dir (`sherpa-onnx-whisper-base.en/base.en-encoder.onnx`, `base.en-decoder.onnx`, `base.en-tokens.txt`) — but only lift the dir; they do **not** rename to the loader-expected plain names.
- `OfflineStt.kt:44` requires `encoder.onnx` / `decoder.onnx` / `tokens.txt`. After hoist the model root holds `base.en-*` → STT never loads (`onReady(false)`) → 90s stall → "Voice models failed to load."
- **Piper worse:** `ModelCatalog.kt:40` has `file=""` + a full `url`; `ModelDownloader.kt:90` calls `unpkg(tmp, dir, spec.file)` with `spec.file=""` → dispatch on `""` matches neither `.tar.bz2` nor `.zip` → the `else` branch `File(dir,"").writeBytes(...)` (writes to the dir itself → throws). And `SherpaTts.kt:37-39` loads `voz.onnx`/`voz.txt` which the canonical tarball doesn't contain.
- **Fix:** dispatch `unpkg` on the actual downloaded artifact (`spec.url`), normalize names on-device (rename `*.en-encoder.onnx`→`encoder.onnx`, `*.en-decoder.onnx`→`decoder.onnx`, `*.en-tokens.txt`→`tokens.txt`), and reconcile the Piper loader path (`voz.onnx`/`voz.txt`/`espeak-ng-data`) with the canonical tarball's real contents.

### #3 — CRITICAL: model integrity decorative; source unvalidated; "no protocol" not fully fixed
- Only gemma-e2b has a non-empty `sha256`; `ModelDownloader.kt:83-86` skips verification when blank.
- `ModelCatalog.source()` (lines 76-82) only handles blank. A user typing `models.house.lan` or `10.0.2.2:8899` still reaches `URL(urlStr)` at `ModelDownloader.kt:56` → `MalformedURLException: no protocol` / `unknown protocol`.
- **Fix:** scheme validation + allowlist at the input (require `https?://`, reject scheme-less hosts in `source()`/URL build); populate a real pinned `sha256` for EVERY blessed model; verify-on-download AND verify-on-load (re-hash the installed file at init).

### #49 — 5/6 models lack integrity; the Gemma model URL is wrong
- `ModelCatalog.kt:53-55`: gemma-e2b has `url=""` → resolves to `DEFAULT_SOURCE` (the sherpa-onnx ASR host) → 404 (Gemma is not a sherpa-onnx release). `GemmaExpress.kt:29` expects `gemma-4-E2B-it.litertlm` with no defined archive layout.
- **Fix:** give gemma-e2b a real canonical full URL + the expected unpacked filename; align `GemmaExpress`'s expected path with the downloader's output; add pinned `sha256` for all blessed models.

### #12 — HIGH: truncated downloads accepted as complete installs
- `ModelDownloader.kt:69-91`: the copy loop exits on `n < 0` with no `dl == totalL` comparison; a dropped-but-clean-close connection yields a truncated tarball unpacked into a dir `isInstalled()` reports as installed forever. No `.part` cleanup on IOException; `dir.deleteRecursively()` before unpack destroys a previously-working model on a mid-unpack failure.
- **Fix:** verify `dl == totalL` (or a min threshold); clean `.part` on any error; two-phase unpack (unpack to a temp dir, then swap) so a failure never destroys a good model.

## Cross-cutting (consolidate — do not expand scope)
#2, #3, #49, #12 all sit in `ModelDownloader.kt`'s unpack/verify logic. Treat this as **ONE cohesive overhaul** of the download/verify/unpack path: rename normalization, source scheme validation, sha256 enforcement, truncated-download handling. The `hoist` copy-vs-rename disk concern (#26) and concurrent-cancel (#27) are **deferred** to a later polish sprint — do not touch them here.

## Iron rules (build agent)
- Touch ONLY the ModelDownloader / ModelCatalog / OfflineStt loader-path / SherpaTts loader-path / store-script / GemmaExpress-path scope. Do **not** refactor `VoiceController`, `MainActivity`, or the voice loop.
- **Never commit secrets** (API key, model tokens) — env/in-app only.
- Keep the release convention (tag === versionName === APK filename, no leading `v`).
- Do NOT regress: warm Piper TTS, offline Whisper STT, streaming, the working realtime loop (the Do-Not-Regress invariant).
- Run existing Go/unit tests + the device-free JVM tests; **add** a `ModelCatalog.source()` scheme table-test (`""`, `"host:8899"`, `"host.lan"`, `"ftp://x"`) that would have caught the no-protocol bug.
- Commit as you go; one coherent commit per fix (imperative, one concern).

## Deliverable / acceptance
A fresh install → download (from the canonical k2-fsa upstream) → **verified** → **loadable** models: Whisper STT loads (encoder.onnx/decoder.onnx/tokens.txt correctly named), Piper TTS loads, no "no protocol", no 90s "Voice models failed to load", no false "installed" on a truncated download. The pipeline's `isInstalled()` must reflect a genuinely usable model.

## Verification (review phase — Torc runs, do not just trust)
- Build + install + boot on `emulator-5554` (compile gate, no crash).
- Device-free JVM tests incl. the new `ModelCatalog.source()` scheme table-test.
- Confirm loader paths are consistent (model root has the exact files `OfflineStt`/`SherpaTts` read).

## Decisions (approved by owner, 2026-08-27)

- **D1 — Piper loader path:** use the **canonical** names. Change `SherpaTts.kt` to read `model.onnx` / `tokens.txt` / `espeak-ng-data` (do NOT rename to `voz.*`). Loader logic matches upstream.
- **D2 — espeak-ng-data:** **multi-artifact** (the more canonical path). The piper spec also fetches `espeak-ng-data.tar.bz2` (a separate k2-fsa `tts-models` asset) into the same model dir. **FIRST** inspect the downloaded canonical piper tarball to confirm it does not bundle `espeak-ng-data/`; if it does, just normalize names; if not (expected), add the second-artifact fetch.
- **D3 — `isInstalled` hardening:** make `ModelCatalog.isInstalled` require model-specific marker files (whisper: `encoder.onnx`+`decoder.onnx`+`tokens.txt`; piper: model+tokens+`espeak-ng-data`; silero: `silero_vad.onnx`; gemma: `gemma-4-E2B-it.litertlm`), so a corrupt/partial dir reports not-installed.
- **D4 — sources (CORRECTED 2026-08-27):** `whisper`/`piper`/`silero`/`vad` download from the **canonical k2-fsa sherpa-onnx upstream** (per the canonical-upstream decision). **gemma-e2b ALSO has a public canonical source — verified via the HuggingFace API:** `litert-community/gemma-4-E2B-it-litert-lm`, which is **non-gated, apache-2.0, no token**, and ships `gemma-4-E2B-it.litertlm` (1M+ downloads). So gemma downloads from **`https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`** — NOT the house store. Its pinned sha256 = the canonical litertlm artifact's hash (derive from the HF LFS sha256 metadata, not the house ZIP). *(The earlier "no canonical source / house-store exception" was wrong: the plan agent checked only k2-fsa, which doesn't carry Gemma; the Google LiteRT-LM model zoo does. Optional alternative: Android AI Core / Gemini Nano is Google's production-recommended route but needs supported hardware — default to canonical HF unless the owner opts into AI Core.)*
