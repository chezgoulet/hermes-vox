# TTS fidelity — why Supertonic drops words (#559)

The owner's field report: *"Supertonic … drops words on the text to speech quite a lot. Piper
performs way more consistently."* The log from that call shaped the diagnosis — Piper was cutting
19–29 characters per chunk, Supertonic 56–126 — and left two hypotheses nobody had separated:

1. **the chunk boundary.** A chunk cut or replaced mid-synthesis loses what was in it, and the
   drain still reports `played`, so nothing is logged as lost.
2. **the model.** Supertonic elides or mispronounces words at its own rate.

This is the bench that separates them. It needs no phone: `scripts/tts-fidelity.py`, one committed
script, one table.

## Method

Ten sentences from the app's own register, a comma near the middle of each. Every sentence is
synthesised **three ways**:

- **whole** — one chunk, the sentence as written;
- **comma** — two chunks, split after the first comma (the boundary a chunker prefers);
- **mid** — two chunks, split at the word boundary nearest the middle (one it would not choose).

Each rendering is written to a wav, transcribed back with **Whisper base.en**, and compared to the
known transcript word by word. "Dropped" means a word the recogniser did not find where it should
be — not a listener's impression. Numerals are normalised (`ten` = `10`) and punctuation and case
are ignored, so the table measures words, not house style.

**The control is the point.** A recogniser is not a perfect listener, so a word missing from the
engine might be the recogniser's failure. The same three renderings therefore run through a second
voice — Piper en-US LibriTTS-R, the recommended one — and a word both voices lose is the
recogniser's floor, not this voice's defect.

```sh
python3 scripts/tts-fidelity.py --engine supertonic \
  --model-dir ~/vox-models/supertonic-bench/sherpa-onnx-supertonic-tts-int8-2026-03-06 \
  --control-dir ~/vox-models/supertonic-bench/vits-piper-en_US-libritts_r-medium \
  --whisper-dir ~/vox-models/git_chezgoulet_hermes-vox_tools_sttbench_.models/sherpa-onnx-whisper-base.en \
  --json results/tts-fidelity.json
```

## The table

Ten sentences, 118 words, run 2026-11-12 on sasquatch (CPU, 4 threads, sherpa-onnx 1.13.8).

| Way | Voice | Words | Lost | Rate |
|---|---|---|---|---|
| whole | Supertonic | 118 | 4 | **3.4 %** |
| whole | Piper (control) | 118 | 9 | 7.6 % |
| comma | Supertonic | 118 | 15 | **12.7 %** |
| comma | Piper (control) | 118 | 5 | 4.2 % |
| mid | Supertonic | 118 | 13 | 11.0 % |
| mid | Piper (control) | 118 | 11 | 9.3 % |

Two-sided Fisher exact:

- Supertonic, one chunk vs split at a comma: **p = 0.015** (4/118 → 15/118).
- Supertonic vs Piper at the comma split: **p = 0.033** (15/118 vs 5/118).

## What it says

**The evidence changed the conclusion twice, and the second answer is the one that holds.**

*First run.* Handed a whole sentence, Supertonic loses fewer words than the control (4 against 9);
handed the same sentence as two chunks split at a comma, it loses fifteen. That pointed at the
comma boundary.

*The gap experiment refuted the join.* Re-running with **250 ms of silence inserted between the
two chunks** — the test that would distinguish "the two pieces are joined badly" from "each piece
is bad" — changed nothing: Supertonic lost 17 words instead of 15, and the control got worse
(11 against 5, which is the extra silence fragmenting the prosody). If truncation or overlap at
the seam were the mechanism, silence would have recovered the words. It did not.

*The fragment sweep found it.* Same sentences, same words, split into **1, 2, 3 and 4 equal pieces**
at word boundaries — no punctuation involved, so only the number of synthesis calls varies:

| Pieces | Supertonic | Piper (control) |
|---|---|---|
| 1 | 4 / 118 (3.4 %) | 5 / 118 (4.2 %) |
| 2 | 13 / 118 (11.0 %) | 7 / 118 (5.9 %) |
| 3 | **27 / 118 (22.9 %)** | 7 / 118 (5.9 %) |
| 4 | 18 / 118 (15.3 %) | 9 / 118 (7.6 %) |

Supertonic's loss rises with the number of pieces — roughly seven times its one-piece rate at
three pieces — while Piper stays flat. The words are identical in every arm; only how many times
the engine is asked to speak changes. **So the loss is per synthesis, not per join**: it is
Supertonic's behaviour on short fragments, and the app's 30-character chunk cap — right for Piper,
which does not care — hands it about the worst input it can get.

## What it does not say

- Ten sentences, 118 words a side. The effect is large enough to be worth acting on and small
  enough that a second run is cheap — the script is committed, and a wider sentence set (say 40)
  would settle the size rather than the direction.
- The app's real chunks are 56–126 characters; these comma splits are 25–45. Larger chunks are a
  *different* test, and if the defect is at the boundary the size matters less than the boundary
  itself — but that is an inference, not a measurement.
- Nothing here says where in `StreamTextChunker`/`SherpaTts.streamChunk` the loss happens. The
  bench says the boundary is the place to look; it does not name the line.

## The decision

**D48 — Supertonic stays an experimental option; the word loss is a chunking defect, not the
model.** The owner's ruling of 2026-11-12 is that Supertonic remains available and experimental
for the foreseeable future and is not under pressure to be faultless: the ten voices are worth
having. This bench does not change that — it changes what the label can honestly say and what the
next piece of work is:

- the catalogue and Settings copy may say *"experimental: drops words when the reply is spoken in
  short pieces"* rather than the vaguer "may drop words";
- the concrete candidate fix is a **per-engine chunk cap**: the app feeds every voice 30 characters
  (`StreamTextChunker.CHUNK_CAP_CHARS`), which is tuned for Piper and is near the worst size for
  Supertonic. One chunker with a cap per voice — not a second delivery path;
- **45 characters is the ceiling today, and the reason is arithmetic.** Supertonic speaks at
  14.5 chars/s against Piper's 20.5, so 30 characters is 2.1 s of its audio and 45 is 3.1 s. The
  playback track is 4 s; 60 characters would be 4.1 s and would bring back the #515 defect where
  the next chunk waits for the last one to finish playing. Measure 45 first; larger caps need a
  larger track in the same change, and the gaps measured as well as the words;
- **not a 0.9.2 blocker.** Nothing here moves the release; it removes the excuse for guessing.

The owner has already put voice quality where it belongs for this series — the recommendation is
Piper, and Supertonic is the option for the ten voices.
