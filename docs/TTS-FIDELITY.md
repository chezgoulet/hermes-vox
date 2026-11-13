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

## Supertonic 3 — the defect belongs to v1.6, and it is fixed upstream

The bench above ran on `sherpa-onnx-supertonic-tts-int8-2026-03-06` (bundle `tts_version`
v1.6.0). The next thing to try was the newer model, because Supertonic 3's release note says
*"improved reading accuracy … fewer repeat/skip failures"* — which is a description of this
defect. Same script, same ten sentences, same control, same method:

| Pieces | Supertonic v1.6 | Supertonic 3 | Piper (control, v3 run) |
|---|---|---|---|
| 1 | 3.4 % | **1.7 %** | 3.4 % |
| 2 | 11.0 % | **0.8 %** | 4.2 % |
| 3 | 22.9 % | **0.8 %** | 11.0 % |
| 4 | 15.3 % | **5.1 %** | 2.5 % |

**The fragment sensitivity is gone.** v1.6 went from 4 lost words on one piece to 27 on three;
v3 loses one word at two pieces and one at three. At the sizes the app actually feeds it, the
newer model is *better than the control voice*, and better than its own one-piece rate — which is
what "fewer skip failures" means in numbers.

**So the fix is a model bump, not a chunker change.** The per-engine chunk cap that D48 proposed
is not needed, and neither is a larger playback track: the 30-character cap is fine when the
engine keeps its words at 30 characters. What was a chunking problem turns out to have been a
model-version problem, and the honest thing is to say so rather than ship the workaround.

What a bump touches, and what is still open:

- **the bundle**: `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`, 128,774,318 bytes,
  sha256 `82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427` — 122.8 MB against the
  current 84.7 MB, so the download row in Settings grows;
- **the runtime**: the bundle is dated 2026-05-11 and the app pins `sherpa-onnx-1.13.6`
  (published 2026-08-18), so the pinned runtime post-dates the model and should load it. That is
  an argument from dates, not a test on the device — the test is one render on the phone;
- **the voices**: `voice.bin` is byte-size identical between the two bundles (517,168 bytes), so
  the ten-voice surface should carry over — also to be confirmed on device;
- **the licence**: the bundle ships an **MIT** LICENSE (Supertone Inc.), while `ModelCatalog.kt`
  describes this entry as *"model: OpenRAIL-M"*. One of the two is wrong and neither should be
  asserted until the upstream model card is read;
- **French**: v3 supports 31 languages including `fr`. That is a first-party French voice in the
  same engine as the ten-voice surface, and it belongs in 0.10's localisation plan (#518).

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

**D48 — Supertonic stays an experimental option; the word loss is v1.6's, and v3 fixes it.** The owner's ruling of 2026-11-12 is that Supertonic remains available and experimental
for the foreseeable future and is not under pressure to be faultless: the ten voices are worth
having. This bench does not change that — it changes what the label can honestly say and what the
next piece of work is:

- the catalogue and Settings copy may say *"experimental: drops words when the reply is spoken in
  short pieces"* rather than the vaguer "may drop words";
- **the fix is the model, not the cap: ship Supertonic 3** (#589). The sweep above shows v1.6 losing
  words per synthesis and v3 not losing them, so the per-engine chunk cap this document first
  proposed is **not needed and should not be built**. The measurement stands as the reason;
- **if a cap is ever revisited for a future engine,** the arithmetic is: Supertonic speaks at
  14.5 chars/s against Piper's 20.5, so 30 characters is 2.1 s of its audio and 45 is 3.1 s. The
  playback track is 4 s, so 60 characters would be 4.1 s and would bring back the #515 class. That
  is a constraint for a future experiment, not a task;
- **not a 0.9.2 blocker.** Nothing here moves the release; it removes the excuse for guessing.

The owner has already put voice quality where it belongs for this series — the recommendation is
Piper, and Supertonic is the option for the ten voices.
