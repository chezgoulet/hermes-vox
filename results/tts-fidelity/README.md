# TTS fidelity results — the receipts for `docs/TTS-FIDELITY.md` (#559)

One JSON per run of `scripts/tts-fidelity.py`, as it wrote them — not edited, not summarised.
Every percentage in `docs/TTS-FIDELITY.md` comes from one of these files.

| File | What produced it |
|---|---|
| `sweep-v1.6-ways.json` | v1.6 (`…2026-03-06`), the whole/comma/mid arms — the first run |
| `gap250-v1.6.json` | v1.6, `--gap-ms 250` — the seam experiment that refuted the join |
| `sweep-v1.6.json` | v1.6, `--sweep` — one to four equal pieces |
| `sweep-v3.json` | Supertonic 3 (`…2026-05-11`), `--sweep` — the same sentences, the fix |

Each row carries the sentence, the way, the voice, the words lost, the text the recogniser heard,
the audio length and (where set) the gap. The control voice in every run is
`vits-piper-en_US-libritts_r-medium`; the recogniser is Whisper `base.en`.

Re-run any of them with the command in `docs/TTS-FIDELITY.md`; the numbers will differ slightly in
the last digit because a recogniser is not deterministic across thread counts, which is why these
files are kept rather than regenerated on demand.
