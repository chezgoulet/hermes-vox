# Voice bake-off — 2026-09-29

The question: which **on-device** voice should Hermes Vox speak with? No remote GPU; it must run
live on a phone. Candidates were the open models supported by the sherpa-onnx runtime Vox already
pins (1.13.6), plus the wider field surveyed first.

## Method

- Every candidate synthesized the same line through `sherpa-onnx==1.13.6` (Python, in a venv): the
  exact runtime and model packages the app downloads.
  > "Hmm, good question. Let me think about that for a second — okay, here's what I found."
- **Speed:** real-time factor (RTF = synthesis time ÷ audio length) on a desktop x86 CPU, two
  threads. A phone is slower, so a phone needs a wide margin under 1.0.
- **Intelligibility:** each output was transcribed back with on-device Whisper base.en. A voice
  that drops or garbles words fails the app's first rule: the user must hear what the mind said.

These are host numbers. Confirm on a device with the log lines below before drawing a
phone-level conclusion.

## Results

| Voice | Package | RTF (2 threads) | Heard back | Verdict |
|---|---|---|---|---|
| **Supertonic** (10 voices) | `sherpa-onnx-supertonic-tts-int8-2026-03-06` (80 MB, OpenRAIL-M) | **0.10–0.18** | All words on 8 of 10 voices | **Chosen: recommended voice** |
| Pocket TTS (voice cloning) | `sherpa-onnx-pocket-tts-int8-2026-01-26` (93 MB, CC BY 4.0) | 0.48–0.54 warm, ~1.0 cold | Dropped "Hmm", cut the ending, "Good"→"Dude" | Rejected for now |
| Kokoro int8 | `kokoro-int8-en-v0_19` (98 MB, Apache-2.0) | **2.36** (slower than real time) | — | Rejected |
| Piper (current) | `vits-piper-en_US-libritts_r-medium` | fast | good | Kept as the lighter option |

Pocket TTS's streaming callback also delivered its first audio only after 1.2–1.6 s (close to the
full synthesis time), so it would not start speaking early either. It stays interesting, for
per-agent voices cloned from a consented clip and for mood by reference audio, if a future
package fixes intelligibility.

Supertonic's ten voices split cleanly by measured pitch: ids 0–4 at 159–240 Hz (F1–F5) and
5–9 at 95–133 Hz (M1–M5). The default is F2 (id 1); every word came through intact on it.

## What shipped

- `SherpaTts` runs any `SherpaVoice` (Piper, Supertonic) on the same streaming, fencing and
  speech-cursor machinery; only engine configuration and per-phrase synthesis differ.
- Supertonic is the recommended TTS download and the default engine once installed
  (`ModelCatalog.defaultTts`); Piper is the lighter optional voice. Settings gains a speaker picker.
  The Kokoro option, whose runtime was a stub that always fell back to the system voice, is gone.
- **Mood → delivery:** Supertonic has no emotion control, but it honours speaking speed (measured:
  the same line at 6.40 s calm vs 5.45 s lively) and pause length. `VoiceMood` maps the caller's
  mood, as the soul heard it, to those two controls (see `docs/ARCHITECTURE.md`).

## Field check

Look for `SherpaTts loaded: supertonic speakers=10` and the per-chunk `supertonic chunk … smp`
lines. `LatencyStats` first-audio shows whether time-to-first-sound improved over Piper.
