# Demo Note — Sherpa-onnx Runtime + Real Warm On-Device TTS (GREEN)

Date: 2026-08-25 · Emulator-5554 (x86_64, Android 15)

## What shipped
The **sherpa-onnx runtime** is now bundled (a pinned, canonical k2-fsa AAR) and
the downloaded Piper model actually **synthesizes warm speech on-device** —
fully offline, no cloud, no sideload beyond the (downloadable) model.

- `SherpaTts` (VoxTts impl) — Piper via sherpa-onnx: loads the model the app
  downloaded into `filesDir/models/piper-lessac/`, `generate()` → float PCM →
  `AudioTrack` (ENCODING_PCM_FLOAT). `isWarm=true` when loaded; seamless fallback
  to system TTS otherwise (never a broken turn).
- Runtime AAR = `sherpa-onnx-1.13.6.aar` in `app/libs/` (all ABIs incl x86_64 +
  arm64-v8a). **Gitignored + fetched by `scripts/fetch-runtime.sh`** (pinned URL);
  `scripts/gate.sh` fetches it before the build.
- Blessed default = warm: `buildTts` returns `SherpaTts` automatically once the
  Piper model is installed (ModelCatalog.isInstalled), else SystemTts.
- VoiceController now initializes TTS at construction (so TEXT turns are voiced,
  not just the mic path).

## Verified live (emulator x86_64)
```
SherpaTts loaded: piper model
piper generated 25344 samples @ 22050Hz (text 15 chars)
piper played 25344 samples
reply: warm voice test
```
The entity replied → Piper synthesized ~1.15s of 22 kHz speech → AudioTrack.
The reply rendered (`warm voice test`), the app stayed healthy (graceful path).

## sherpa-onnx Android gotchas (the ones that cost time — keep)
1. **assetManager MUST be null** when loading a model from an absolute
   filesystem path (`filesDir`). Else sherpa reads it as an asset and aborts:
   `Read binary file: Load '...' failed`. `new OfflineTts(null, cfg)`.
2. **Piper models are phoneme-based** — the `libritts_r-medium` voice needs a G2P
   lexer. sherpa wants `data_dir` pointing at the **espeak-ng-data dir ITSELF**
   (which holds `phontab` + the dicts). Two errors you'll hit if wrong:
   - data_dir=""  → `Not a model using characters... provide --vits-lexicon`
   - data_dir=<model dir> → `'.../phontab' does not exist`
   Correct: `data_dir = <modelDir>/espeak-ng-data`.
3. Use the **k2-fsa sherpa model-zoo** model (`vits-piper-en_US-libritts_r-medium
   .tar.bz2`), NOT an arbitrary HF piper export — the HF one lacked the
   `sample_rate` metadata sherpa reads (`'sample_rate' does not exist in the
   metadata`).
4. The canonical model URL is `.../download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2`
   (NOT prefixed `sherpa-onnx-`) — that prefix 404s. The per-version
   `-wasm-simd-...` tarballs are browser demos WITHOUT the model.
5. `OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir, dictDir,
   noiseScale, noiseScaleW, lengthScale)`; `tokens` = `tokens.txt`.
6. Emulator is **x86_64** — the fat AAR carries x86_64; a real arm64 device
   needs the arm64-v8a libs (both are in the AAR).
