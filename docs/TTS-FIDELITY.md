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

**Hypothesis 1, the chunk boundary, is the one the evidence supports.** Handed a whole sentence,
Supertonic loses *fewer* words than the control voice (4 against 9) — there is nothing inherently
lossy about one synthesis. Handed the same sentence as two chunks split at a comma, it loses four
times as many, and the control voice does not (it goes from 9 to 5). Four of the ten sentences lost
nothing in any rendering; the losses that matter appear only when the text is chunked, and they
cluster in the middle of sentences — `hear, the, line`, `chance, of`, `kitchen, light`, `leave,
by, to`.

**Hypothesis 2 is not supported at one chunk**, which is the arm that isolates the model from the
chunker. One word — `projects`, from *"under the projects folder"* — was lost in all three
renderings, and the control lost it too at the whole rendering. That is the recogniser, not
Supertonic.

**The mid-phrase split is hard for both voices** (11.0 % vs 9.3 %): cutting between words without
punctuation costs everyone some prosody, and the recogniser punishes it. So the actionable signal
is the **comma** split, where the two voices diverge and the split is one the app's chunker would
actually choose.

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
  chunks; under investigation"* rather than the vaguer "may drop words";
- the next investigation is a **chunker probe**, not a model swap: what happens to a large chunk at
  a boundary, and why the drain reports `played` while a word is missing;
- **not a 0.9.2 blocker.** Nothing here moves the release; it removes the excuse for guessing.

The owner has already put voice quality where it belongs for this series — the recommendation is
Piper, and Supertonic is the option for the ten voices.
