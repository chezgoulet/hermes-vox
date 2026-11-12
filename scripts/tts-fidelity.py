#!/usr/bin/env python3
"""tts-fidelity.py — do Supertonic's chunks drop words, or does Supertonic? (#559)

The field report is that Supertonic is intelligible but **lossy**: words go missing, while
Piper on the same device and the same call keeps them. The log shaped the diagnosis — Piper
cut 19–29 characters per chunk, Supertonic 56–126 — and left two hypotheses:

  1. **the chunk boundary** — a chunk that is cut or replaced mid-synthesis loses what was in
     it, and the drain still reports `played`, so nothing is logged as lost;
  2. **the model** — Supertonic elides or mispronounces words at its own rate.

The bench separates them the way the issue says, with no phone: synthesise the same sentence
three ways and count the words that come back.

  whole   one chunk: the sentence as written
  comma   two chunks, split after the first comma (a boundary the chunker would choose)
  mid     two chunks, split at the word boundary nearest the middle (a boundary it would not)

If only `comma`/`mid` lose words, it is (1) and the fix is in chunk handling
(`StreamTextChunker`, `SherpaTts.streamChunk`). If all three lose the same words, it is (2):
the model, and Supertonic stays experimental — which is where the owner has already put it.

**Words are counted, not judged.** Each rendering is transcribed back with Whisper base.en and
compared to the known transcript word by word, so "dropped" means a word the recogniser did not
find where it should be — not a listener's impression. That also means the bench has to hold
the recogniser still: a **control voice** (Piper, by default) runs the same three renderings, so
a word missing from *both* voices is the recogniser's limit, not Supertonic's.

Usage (host bench — sherpa-onnx + the models, no device):

  python3 scripts/tts-fidelity.py --engine supertonic --model-dir ~/vox-models/supertonic-bench/sherpa-onnx-supertonic-tts-int8-2026-03-06 \\
      --whisper-dir ~/vox-models/git_..._sttbench_.models/sherpa-onnx-whisper-base.en \\
      --control-dir ~/vox-models/supertonic-bench/vits-piper-en_US-libritts_r-medium \\
      --json results/tts-fidelity.json

Output: one row per sentence × way × voice, plus a per-way total. A word the control loses and
the engine loses is counted separately (`shared`), so the engine is never blamed for the
recogniser.
"""
from __future__ import annotations

import argparse
import difflib
import glob
import json
import os
import re
import sys
import wave

# Sentences from the app's own register — short replies, a comma near the middle, and enough
# ordinary words that a dropped one is visible. The comma matters: it is the boundary the
# chunker prefers, so it is the one the field's failures would sit on.
SENTENCES = [
    "The timer is set, and it will ring in ten minutes.",
    "I looked it up, and the answer is simpler than you think.",
    "Your first meeting starts at nine, so leave by a quarter to.",
    "That file is on the shared drive, under the projects folder.",
    "I can hear you clearly, and the line sounds steady.",
    "The weather tomorrow is cold, with a chance of snow by evening.",
    "I have added it to your list, and I will remind you tonight.",
    "No, I did not send that message, and I will not without asking.",
    "The kitchen light is still on, and the back door is unlocked.",
    "Two of the three files opened, and the last one is empty.",
]

# Scoring is on words, not punctuation or spelling. Lowercase, strip everything that is not a
# letter, digit, apostrophe or space — so "nine," and "nine" are the same word and a full stop is
# never a difference. Numerals are normalised too: a recogniser writes what it hears as "10" where
# the transcript says "ten", and counting that as a dropped word would blame the voice for the
# recogniser's house style. (It did, on the first smoke run.)
_NORM = re.compile(r"[^a-z0-9' ]+")
_NUM = {0: "zero", 1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven",
        8: "eight", 9: "nine", 10: "ten", 11: "eleven", 12: "twelve", 13: "thirteen",
        14: "fourteen", 15: "fifteen", 16: "sixteen", 17: "seventeen", 18: "eighteen",
        19: "nineteen", 20: "twenty", 30: "thirty", 40: "forty", 50: "fifty", 60: "sixty",
        70: "seventy", 80: "eighty", 90: "ninety", 100: "hundred"}


def words(text: str) -> list[str]:
    """The comparable word sequence of [text]."""
    out = []
    for w in _NORM.sub(" ", text.lower().replace("’", "'")).split():
        out.append(_NUM[int(w)] if w.isdigit() and int(w) in _NUM else w)
    return out


def compare(expected: str, heard: str) -> dict:
    """Word-level difference of [heard] against [expected].

    `missing` is what the engine owed and did not deliver, in expected order; `extra` is what
    came back that was never written. A substitution counts as one missing and one extra,
    which is the honest reading: the word that should have been there was not.
    """
    exp, got = words(expected), words(heard)
    sm = difflib.SequenceMatcher(a=exp, b=got, autojunk=False)
    missing, extra = [], []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("delete", "replace"):
            missing.extend(exp[i1:i2])
        if tag in ("insert", "replace"):
            extra.extend(got[j1:j2])
    return {
        "expected": len(exp),
        "heard": len(got),
        "missing": missing,
        "extra": extra,
        "kept": len(exp) - len(missing),
    }


def split_ways(text: str) -> dict[str, list[str]]:
    """The three renderings of one sentence, as chunk lists.

    `comma` splits after the first comma (keeping it, as a chunker would); a sentence with no
    comma has no `comma` rendering — invented punctuation would test something else.
    `mid` splits at the word boundary whose character offset is nearest the middle, so the
    split lands between words and never inside one.
    """
    ways: dict[str, list[str]] = {"whole": [text]}
    if "," in text:
        head, tail = text.split(",", 1)
        ways["comma"] = [head + ",", tail.strip()]
    mid = len(text) // 2
    best = None
    for m in re.finditer(r" ", text):
        d = abs(m.start() - mid)
        if best is None or d < best[0]:
            best = (d, m.start())
    if best is not None:
        cut = best[1]
        left, right = text[:cut].strip(), text[cut:].strip()
        if left and right:
            ways["mid"] = [left, right]
    return ways


def equal_splits(text: str, n: int) -> list[str]:
    """[text] cut into [n] near-equal pieces at word boundaries — the fragment-size sweep.

    The comma and mid arms each cut the sentence **once**, so they conflate "one cut" with
    "shorter pieces". This sweep holds the text and the number of words constant and varies only
    how many syntheses it is fed in, which is the question the gap experiment left open: does
    Supertonic lose words because a *join* is bad, or because each short piece is?
    """
    if n <= 1:
        return [text]
    words = text.split(" ")
    if n >= len(words):
        return [w for w in words]
    pieces, per = [], len(words) / n
    for i in range(n):
        lo, hi = round(i * per), round((i + 1) * per)
        pieces.append(" ".join(words[lo:hi]))
    return [p for p in pieces if p]


def resample(samples: list[float], sr_in: int, sr_out: int = 16_000) -> list[float]:
    """Linear resample. The recogniser wants 16 kHz; the voices are 22.05/44.1 kHz."""
    if sr_in == sr_out or not samples:
        return list(samples)
    n_out = max(1, int(len(samples) * sr_out / sr_in))
    step = sr_in / sr_out
    out = []
    for i in range(n_out):
        pos = i * step
        i0 = int(pos)
        i1 = min(i0 + 1, len(samples) - 1)
        frac = pos - i0
        out.append(samples[i0] * (1 - frac) + samples[i1] * frac)
    return out


def write_wav(path: str, samples: list[float], sr: int) -> None:
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(b"".join(
            int(max(-1.0, min(1.0, s)) * 32767).to_bytes(2, "little", signed=True) for s in samples))


def build_engine(kind: str, model_dir: str):
    """A sherpa-onnx TTS engine. `supertonic` needs seven files; a VITS/Piper voice needs
    one `.onnx`, its tokens and optionally espeak-ng-data."""
    import sherpa_onnx

    if kind == "supertonic":
        def f(name: str) -> str:
            p = os.path.join(model_dir, name)
            if not os.path.isfile(p):
                raise SystemExit(f"tts-fidelity: {p} is missing — is --model-dir the unpacked "
                                 f"sherpa-onnx-supertonic-* directory?")
            return p
        st = sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=f("duration_predictor.int8.onnx"),
            text_encoder=f("text_encoder.int8.onnx"),
            vector_estimator=f("vector_estimator.int8.onnx"),
            vocoder=f("vocoder.int8.onnx"),
            tts_json=f("tts.json"),
            unicode_indexer=f("unicode_indexer.bin"),
            voice_style=f("voice.bin"),
        )
        cfg = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(supertonic=st, num_threads=4, provider="cpu"))
    elif kind == "vits":
        onnx = sorted(glob.glob(os.path.join(model_dir, "*.onnx")))
        tok = sorted(glob.glob(os.path.join(model_dir, "*tokens*.txt")))
        if not onnx or not tok:
            raise SystemExit(f"tts-fidelity: no .onnx/*tokens*.txt under {model_dir}")
        data = os.path.join(model_dir, "espeak-ng-data")
        v = sherpa_onnx.OfflineTtsVitsModelConfig(
            model=onnx[0], tokens=tok[0], data_dir=data if os.path.isdir(data) else "")
        cfg = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(vits=v, num_threads=4, provider="cpu"))
    else:
        raise SystemExit(f"tts-fidelity: unknown --engine {kind} (supertonic|vits)")
    return sherpa_onnx.OfflineTts(cfg)


def build_asr(whisper_dir: str):
    import sherpa_onnx

    def pick(*patterns: str) -> str:
        for pat in patterns:
            hits = sorted(glob.glob(os.path.join(whisper_dir, pat)))
            if hits:
                return hits[0]
        raise SystemExit(f"tts-fidelity: none of {patterns} under {whisper_dir}")
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=pick("*encoder.int8.onnx", "*encoder.onnx"),
        decoder=pick("*decoder.int8.onnx", "*decoder.onnx"),
        tokens=pick("*tokens.txt"),
        num_threads=4, language="en", task="transcribe",
    )
    return rec


def transcribe(rec, samples: list[float], sr: int) -> str:
    """Whisper wants 16 kHz. Resample and SAY 16 kHz — telling it the original rate while
    handing it resampled audio plays the sentence back at the wrong speed, which transcribes
    to nothing and reads, in the table, as every word missing. (It did, once.)"""
    s = rec.create_stream()
    s.accept_waveform(16_000, resample(samples, sr))
    rec.decode_stream(s)
    return s.result.text.strip()


def render(tts, chunks: list[str], sid: int = 0, gap_ms: int = 0):
    """Synthesise each chunk and concatenate — what the app's stream does with a chunk list.

    [gap_ms] inserts silence between chunks. It exists for one experiment: if a gap makes the
    words come back, the loss is at the *seam* — where one synthesis ends and the next begins,
    and how the two are joined — and not in the text that reached the engine.
    """
    samples: list[float] = []
    rate = 0
    for i, c in enumerate([c for c in chunks if c.strip()]):
        a = tts.generate(c, sid=sid, speed=1.0)
        rate = a.sample_rate
        if i and gap_ms > 0:
            samples.extend([0.0] * (rate * gap_ms // 1000))
        samples.extend(a.samples)
    return samples, rate


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("--engine", default="supertonic", choices=["supertonic", "vits"])
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--control-dir", default="", help="a second voice to hold the recogniser still")
    ap.add_argument("--whisper-dir", required=True)
    ap.add_argument("--sentences", default="", help="a file, one sentence a line")
    ap.add_argument("--json", default="", help="write the table as JSON here")
    ap.add_argument("--wav-dir", default="", help="keep the rendered wavs here")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--sweep", action="store_true",
                    help="render each sentence as 1..4 equal pieces (fragment-size sweep)")
    ap.add_argument("--gap-ms", type=int, default=0,
                    help="silence between chunks — the seam experiment")
    args = ap.parse_args(argv)

    sentences = ([l.strip() for l in open(args.sentences) if l.strip()]
                 if args.sentences else list(SENTENCES))
    if args.limit:
        sentences = sentences[:args.limit]
    if args.wav_dir:
        os.makedirs(args.wav_dir, exist_ok=True)

    asr = build_asr(args.whisper_dir)
    voices = [(args.engine, build_engine(args.engine, args.model_dir))]
    if args.control_dir:
        voices.append(("control-vits", build_engine("vits", args.control_dir)))

    rows = []
    for sentence in sentences:
        ways = ({f"{n}piece": equal_splits(sentence, n) for n in (1, 2, 3, 4)}
                if args.sweep else split_ways(sentence))
        for way, chunks in ways.items():
            for vname, tts in voices:
                samples, sr = render(tts, chunks, gap_ms=args.gap_ms)
                if not samples:
                    rows.append({"sentence": sentence, "way": way, "voice": vname,
                                 "error": "no audio"})
                    continue
                if args.wav_dir:
                    write_wav(os.path.join(args.wav_dir, f"{len(rows):02d}-{way}-{vname}.wav"),
                              samples, sr)
                heard = transcribe(asr, samples, sr)
                r = compare(sentence, heard)
                r.update({"sentence": sentence, "way": way, "voice": vname,
                          "gap_ms": args.gap_ms,
                          "seconds": round(len(samples) / sr, 2), "heard_text": heard})
                rows.append(r)
                print(f"[{way:5}] {vname:12} {len(chunks)} chunk(s) {r['seconds']:5.2f}s  "
                      f"kept {r['kept']:2}/{r['expected']:2}  missing={r['missing']}", flush=True)

    # Per way × voice: the total a reader can act on. `shared` is what the control ALSO lost —
    # a word missing from both voices is the recogniser's limit, not this voice's defect.
    totals = {}
    for r in rows:
        if "error" in r:
            continue
        k = (r["way"], r["voice"])
        t = totals.setdefault(k, {"expected": 0, "missing": 0, "rows": 0})
        t["expected"] += r["expected"]
        t["missing"] += len(r["missing"])
        t["rows"] += 1
    if len(voices) > 1:
        eng, ctl = args.engine, "control-vits"
        for r in rows:
            if r["voice"] != eng:
                continue
            mate = next((c for c in rows if c["voice"] == ctl and c["way"] == r["way"]
                         and c["sentence"] == r["sentence"]), None)
            r["shared_with_control"] = sorted(set(r["missing"]) & set(mate["missing"])) if mate else []

    print("\n| way | voice | rows | words | missing | rate |")
    print("|---|---|---|---|---|---|")
    for (way, voice), t in totals.items():
        rate = (t["missing"] / t["expected"] * 100) if t["expected"] else 0
        print(f"| {way} | {voice} | {t['rows']} | {t['expected']} | {t['missing']} | {rate:.1f}% |")

    if args.json:
        os.makedirs(os.path.dirname(args.json) or ".", exist_ok=True)
        with open(args.json, "w") as f:
            json.dump({"engine": args.engine, "rows": rows,
                       "totals": [{"way": w, "voice": v, **t} for (w, v), t in totals.items()]},
                      f, indent=2)
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
