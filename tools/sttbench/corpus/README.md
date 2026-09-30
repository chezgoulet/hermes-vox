# sttbench corpus — provenance and licences

25 clips, 16 kHz mono 16-bit PCM WAV (the app's capture format), 5.7 MB.
`manifest.jsonl` has one line per clip: id, category, reference transcript,
source, licence, and a note on any processing. Every clip is reproducible:
`../build_corpus.py` fetched and generated them (fixed rows, fixed seed).

| category | clips | what it tests | source |
|---|---|---|---|
| `short` | 6 | 2–4 s commands/questions, six speakers | LibriSpeech test-clean |
| `long` | 4 | 10–20 s single utterances (the old early start kept only the last 6 s) | LibriSpeech test-clean |
| `verylong` | 1 | 31.7 s — past Whisper's 30 s window (windowed decoding) | LibriSpeech test-clean |
| `pause` | 2 | a 1.4 s thinking pause inserted at a mid-sentence word gap | LibriSpeech test-clean + generated gap |
| `noisy` | 3 | white 10 dB, pink 5 dB, 3-talker babble 5 dB | LibriSpeech test-clean + generated / LibriSpeech babble |
| `silence` | 5 | no speech at all (digital silence, room tone, mains hum, fan, clicks) — the reference is empty, so any text is a hallucination | generated |
| `accent` | 4 | conversational English from non-native speakers (L1 Mandarin, Spanish, Romanian, French) | EdAcc |

## Licences

- **LibriSpeech** (Panayotov et al., 2015; openslr.org/12) — **CC BY 4.0**.
  Utterance IDs are in `manifest.jsonl` (`source`). Fetched through the
  Hugging Face datasets-server rows API for `openslr/librispeech_asr`
  (config `clean`, split `test`). Noisy/pause clips are modified versions
  (noise mixed / a silent gap inserted); the modification is in `note`.
- **EdAcc** — the Edinburgh International Accents of English Corpus
  (Sanabria et al., 2023; datashare.ed.ac.uk/handle/10283/4836) —
  **CC BY-SA 4.0**. The four `accent_*` clips are unmodified test-split
  segments (resampled to 16 kHz), redistributed under the same licence;
  the speaker id and L1 are in `manifest.jsonl`. EdAcc references keep the
  corpus's conversational transcription (fillers such as UM/UH included).
- **Generated** clips (`silence_*`, the noise beds, the inserted gap) —
  produced by `build_corpus.py`, Apache-2.0 like the rest of this repo.

Attribution for the CC BY clips: LibriSpeech ASR corpus, Vassil Panayotov,
Guoguo Chen, Daniel Povey, Sanjeev Khudanpur; EdAcc, Ramon Sanabria, Nikolay
Bogoychev, Nina Markl, Andrea Carmantini, Ondrej Klejch, Peter Bell.
