#!/usr/bin/env python3
"""sttbench — WER / real-time-factor / hallucination regression for the on-device STT.

Transcribes every clip in corpus/ with the SAME model files the app downloads
(the sha256-pinned sherpa-onnx release artifacts in ModelCatalog.kt) through the
SAME runtime version the app ships (sherpa-onnx 1.13.6, pip), and reports:

  * WER per clip and per category vs the reference transcript (normalized: case,
    punctuation, simple number words — see normalize()),
  * RTF (decode seconds / audio seconds) on this host's CPU,
  * hallucinations on the non-speech (silence_*) clips: `raw` = any text at all,
    `kept` = text that survives the app's bracketed-noise-tag strip ("(buzzing)",
    "[BLANK_AUDIO]") — i.e. what would reach the transcript validator.

Whisper is decoded with the app's windowing rule (SttWindows.kt): audio longer
than one 25 s window is cut at the quietest 20 ms frame in the last 4 s of each window
and the pieces are joined — Whisper's encoder sees at most 30 s, and sherpa-onnx
silently truncates anything past it.

    python tools/sttbench/run_bench.py --fetch                 # download pinned models
    python tools/sttbench/run_bench.py --model whisper-base    # one model
    python tools/sttbench/run_bench.py --model all --json out.json

Not part of PR CI (the models are hundreds of MB); see README.md.
"""
import argparse
import hashlib
import json
import re
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
CORPUS = HERE / "corpus"
SR = 16000
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

# id -> (tarball, sha256, kind). The whisper/parakeet pins are the ModelCatalog pins.
MODELS = {
    "whisper-tiny": ("sherpa-onnx-whisper-tiny.en.tar.bz2",
                     "2bd6cf965c8bb3e068ef9fa2191387ee63a9dfa2a4e37582a8109641c20005dd", "whisper"),
    "whisper-base": ("sherpa-onnx-whisper-base.en.tar.bz2",
                     "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542", "whisper"),
    "whisper-small": ("sherpa-onnx-whisper-small.en.tar.bz2",
                      "0cdba2b8aaab69e04847f3427cc9709574112e67913a1a84b7fec3a8729faa9a", "whisper"),
    "parakeet-v2": ("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
                    "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad", "nemo"),
    "parakeet-v3": ("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
                    "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf", "nemo"),
    "zipformer-stream": ("sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
                         "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282", "online"),
}
DEFAULT_SET = ["whisper-base", "whisper-small", "parakeet-v2", "parakeet-v3", "zipformer-stream"]


# ---------------------------------------------------------------- models
def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch(model_dir, ids):
    model_dir.mkdir(parents=True, exist_ok=True)
    for mid in ids:
        tarball, pin, _ = MODELS[mid]
        dst = model_dir / tarball
        if not dst.exists():
            print(f"fetch {tarball}")
            urllib.request.urlretrieve(RELEASE + tarball, dst.with_suffix(".part"))
            dst.with_suffix(".part").rename(dst)
        got = sha256(dst)
        if got != pin:
            dst.unlink()
            raise SystemExit(f"sha256 mismatch for {tarball}: {got}")
        if not (model_dir / tarball.replace(".tar.bz2", "")).exists():
            with tarfile.open(dst) as t:
                t.extractall(model_dir, filter="data")
        print(f"ok {mid} (sha256 verified)")


def build(mid, model_dir, threads, tail_paddings):
    import sherpa_onnx as so
    tarball, _, kind = MODELS[mid]
    d = model_dir / tarball.replace(".tar.bz2", "")
    if not d.exists():
        raise SystemExit(f"{d} missing — run with --fetch")
    if kind == "whisper":
        stem = mid.split("-")[1] + ".en"
        # The app uses the fp32 encoder/decoder (ModelDownloader renames
        # "*-encoder.onnx", which the int8 files do not match).
        return kind, so.OfflineRecognizer.from_whisper(
            encoder=str(d / f"{stem}-encoder.onnx"), decoder=str(d / f"{stem}-decoder.onnx"),
            tokens=str(d / f"{stem}-tokens.txt"), language="en", task="transcribe",
            num_threads=threads, tail_paddings=tail_paddings, decoding_method="greedy_search")
    if kind == "nemo":
        return kind, so.OfflineRecognizer.from_transducer(
            encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"),
            joiner=str(d / "joiner.int8.onnx"), tokens=str(d / "tokens.txt"),
            model_type="nemo_transducer", num_threads=threads, decoding_method="greedy_search")
    p = "epoch-99-avg-1-chunk-16-left-128.int8.onnx"
    return kind, so.OnlineRecognizer.from_transducer(
        encoder=str(d / f"encoder-{p}"), decoder=str(d / f"decoder-{p}"), joiner=str(d / f"joiner-{p}"),
        tokens=str(d / "tokens.txt"), num_threads=threads, decoding_method="greedy_search")


# ---------------------------------------------------------------- windowing (mirror of SttWindows.kt)
WINDOW_S = 25.0   # SttWindows.WHISPER_WINDOW_MS
SEARCH_S = 4.0    # SttWindows.SEARCH_MS
HOP = SR // 50    # 20 ms


def windows(x, window_s=WINDOW_S):
    n, win, search = len(x), int(window_s * SR), int(SEARCH_S * SR)
    if n <= win:
        return [(0, n)]
    out, start = [], 0
    while n - start > win:
        lo, hi = start + win - search, start + win
        best, cut = None, hi
        for i in range(lo, hi - HOP + 1, HOP):
            e = float(np.sum(np.square(x[i:i + HOP], dtype=np.float64)))
            if best is None or e < best:
                best, cut = e, i + HOP // 2
        out.append((start, cut))
        start = cut
    out.append((start, n))
    return out


def transcribe(kind, rec, x, window_s=WINDOW_S):
    if kind == "online":
        s = rec.create_stream()
        s.accept_waveform(SR, x)
        s.accept_waveform(SR, np.zeros(int(0.66 * SR), np.float32))  # flush the right context
        s.input_finished()
        while rec.is_ready(s):
            rec.decode_stream(s)
        return rec.get_result(s)
    parts = []
    spans = windows(x, window_s) if kind == "whisper" and window_s > 0 else [(0, len(x))]
    for a, b in spans:
        s = rec.create_stream()
        s.accept_waveform(SR, x[a:b])
        rec.decode_stream(s)
        parts.append(s.result.text.strip())
    return " ".join(p for p in parts if p)


# ---------------------------------------------------------------- WER
ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def num_words(n):
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        return ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + num_words(n % 100))
    if 1100 <= n <= 1999 and n % 100 != 0:          # a year: eighteen fifty five
        return num_words(n // 100) + " " + num_words(n % 100)
    if n < 1_000_000:
        return num_words(n // 1000) + " thousand" + ("" if n % 1000 == 0 else " " + num_words(n % 1000))
    return str(n)


# The app's bracketed non-speech strip (TranscriptValidator.BRACKETS).
BRACKETS = re.compile(r"\[[^\]]{0,30}\]|\([^)]{0,30}\)|\*[^*]{0,30}\*")

ABBR = {"mr": "mister", "mrs": "missus", "dr": "doctor", "st": "saint", "ok": "okay"}


def normalize(s):
    s = s.lower().replace("-", " ")
    s = re.sub(r"(\d),(\d)", r"\1\2", s)
    s = re.sub(r"\d+", lambda m: " " + num_words(int(m.group())) + " ", s)
    s = re.sub(r"[^a-z' ]", " ", s)
    words = [ABBR.get(w.strip("'"), w.strip("'")) for w in s.split()]
    return [w for w in words if w]


def edit_distance(r, h):
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)]


# ---------------------------------------------------------------- run
def run(mid, args):
    kind, rec = build(mid, args.models, args.threads, args.tail_paddings)
    manifest = [json.loads(l) for l in open(CORPUS / "manifest.jsonl")]
    rows, errs, words, dec, aud = [], 0, 0, 0.0, 0.0
    cat = {}
    for m in manifest:
        x, sr = sf.read(CORPUS / f"{m['id']}.wav", dtype="float32")
        assert sr == SR
        t0 = time.perf_counter()
        # --old-early-start: what the pre-fix early start committed — a decode of
        # only the last 6 s of the segment (the partial snapshot became the turn).
        hyp = transcribe(kind, rec, x[-6 * SR:] if args.old_early_start else x, args.window_s)
        dt = time.perf_counter() - t0
        ref_w, hyp_w = normalize(m["reference"]), normalize(hyp)
        e = edit_distance(ref_w, hyp_w)
        c = cat.setdefault(m["category"], [0, 0, 0, 0])
        if m["category"] == "silence":
            c[2] += 1 if hyp_w else 0
            c[3] += 1 if normalize(BRACKETS.sub(" ", hyp)) else 0
        else:
            errs += e; words += len(ref_w); c[0] += e; c[1] += len(ref_w)
        dec += dt; aud += len(x) / SR
        rows.append(dict(id=m["id"], category=m["category"], wer=(e / len(ref_w)) if ref_w else None,
                         rtf=dt / (len(x) / SR), hyp=hyp))
        if args.verbose:
            w = f"{e / len(ref_w):.3f}" if ref_w else ("HALLUCINATED" if hyp_w else "clean")
            print(f"  {m['id']:18s} {w:>12s}  rtf={dt / (len(x) / SR):.3f}  {hyp[:90]!r}")
    summary = dict(model=mid, threads=args.threads, wer=errs / words, rtf=dec / aud,
                   halluc_raw=cat.get("silence", [0, 0, 0, 0])[2], halluc_kept=cat.get("silence", [0, 0, 0, 0])[3],
                   by_category={k: (v[0] / v[1] if v[1] else None) for k, v in cat.items() if k != "silence"})
    if kind == "whisper":
        summary["tail_paddings"] = args.tail_paddings
    return summary, rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="whisper-base", help=f"one of {', '.join(MODELS)} or 'all'")
    ap.add_argument("--models", type=Path, default=HERE / ".models", help="model cache dir (gitignored)")
    ap.add_argument("--fetch", action="store_true", help="download + sha256-verify the selected models")
    ap.add_argument("--threads", type=int, default=4, help="decoder threads (the app: half the cores, max 4)")
    ap.add_argument("--tail-paddings", type=int, default=1000, help="whisper tail padding frames (OfflineStt.kt)")
    ap.add_argument("--json", type=Path, help="write per-clip results here")
    ap.add_argument("--window-s", type=float, default=WINDOW_S,
                    help="whisper window seconds (SttWindows); 0 = one decode call, no windowing")
    ap.add_argument("--old-early-start", action="store_true",
                    help="decode only each clip's last 6 s (the pre-fix early-start text) for comparison")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    ids = DEFAULT_SET if args.model == "all" else [args.model]
    if args.fetch:
        fetch(args.models, ids)
    results = []
    print(f"{'model':18s} {'WER':>7s} {'RTF':>7s} {'halluc raw/kept':>15s}  per-category WER")
    for mid in ids:
        if args.verbose:
            print(mid)
        s, rows = run(mid, args)
        results.append(dict(summary=s, clips=rows))
        cats = "  ".join(f"{k}={v:.3f}" for k, v in s["by_category"].items())
        print(f"{mid:18s} {s['wer']:7.3f} {s['rtf']:7.3f} {s['halluc_raw']:>13d}/{s['halluc_kept']}  {cats}")
        sys.stdout.flush()
    if args.json:
        args.json.write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
