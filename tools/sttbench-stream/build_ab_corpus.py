#!/usr/bin/env python3
"""Build the streaming-STT A/B corpus: three noise conditions + word onsets.

Inputs are the existing sttbench corpus (tools/sttbench/corpus, 25 clips, CC BY /
CC BY-SA — see its README) so both benches speak about the same speech.

Outputs (tools/sttbench-stream/corpus/, gitignored):

    quiet/<id>.wav        the clean clip, unchanged
    tv/<id>.wav           clean clip + 3-talker babble (TV-dialogue *proxy*), 10 dB SNR
    kitchen/<id>.wav      clean clip + pink noise 8 dB SNR + clatter bursts
                          (kitchen-*proxy*: broadband appliance/dish noise)
    manifest.jsonl        one line per (clip, condition): reference, duration,
                          and `words` = [{text, onset_ms, end_ms, how}]

Word onsets are the ground truth the latency metric divides by, so they are
produced OFF the device by forced alignment with faster-whisper small.en
(word_timestamps=True), then aligned to the reference transcript by DP.
Unmatched reference words are linearly interpolated and marked how="interp";
matched ones are how="asr". Whisper's word timestamps are attention-derived and
carry roughly +/-50-100 ms of error — this is stated as a limitation in
docs/STT-AB.md, and onsets are cross-checked against an energy-based speech
onset per clip.

    python3 tools/sttbench-stream/build_ab_corpus.py --align
    python3 tools/sttbench-stream/build_ab_corpus.py            # reuse cached alignment
"""
import argparse
import json
import re
import unicodedata
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SRC = ROOT / "tools" / "sttbench" / "corpus"
OUT = HERE / "corpus"
CACHE = HERE / ".cache"

SPEECH_CATS = ("short", "long", "pause", "verylong", "accent")
# Silence probes: no speech, so any text is a hallucination. Quiet only — noise
# conditions already carry their own non-speech.
SILENCE_IDS = ("silence_roomtone", "silence_fan")
BABBLE_IDS = ("short_02", "short_04", "accent_01")   # three other talkers
SR = 16000


# --------------------------------------------------------------- text utilities
ABBR = {"mr": "mister", "mrs": "missus", "dr": "doctor", "st": "saint", "ok": "okay"}


def norm_words(s):
    """Lowercase, strip punctuation, split hyphens — the WER normalization used by
    sttbench (run_bench.py) so both benches score the same way."""
    s = unicodedata.normalize("NFKC", s).lower().replace("-", " ")
    s = re.sub(r"[^a-z' ]", " ", s)
    return [ABBR.get(w.strip("'"), w.strip("'")) for w in s.split() if w.strip("'")]


def align(a, b):
    """Needleman-Wunsch alignment of token lists; returns pairs (i, j) for matches."""
    n, m = len(a), len(b)
    d = np.zeros((n + 1, m + 1), dtype=np.int32)
    d[:, 0] = np.arange(n + 1)
    d[0, :] = np.arange(m + 1)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1,
                          d[i - 1, j - 1] + (a[i - 1] != b[j - 1]))
    i, j, pairs = n, m, []
    while i > 0 and j > 0:
        if d[i, j] == d[i - 1, j - 1] + (a[i - 1] != b[j - 1]):
            if a[i - 1] == b[j - 1]:
                pairs.append((i - 1, j - 1))
            i -= 1
            j -= 1
        elif d[i, j] == d[i - 1, j] + 1:
            i -= 1
        else:
            j -= 1
    return list(reversed(pairs))


# --------------------------------------------------------------- alignment
def align_clip(clip_id, wav, reference, model, cache_dir):
    """Word onsets for one clean clip, cached in .cache/align/<id>.json."""
    cache = cache_dir / f"{clip_id}.json"
    if cache.exists():
        return json.loads(cache.read_text())
    ref = norm_words(reference)
    x, _sr = sf.read(str(wav), dtype="float32")   # no PyAV: feed samples directly
    segments, _ = model.transcribe(x, language="en", beam_size=5,
                                   word_timestamps=True, vad_filter=False,
                                   condition_on_previous_text=False)
    hyp = []
    for seg in segments:
        for w in seg.words or []:
            t = norm_words(w.word)
            if t:
                hyp.append({"w": t[0], "start": float(w.start), "end": float(w.end)})
    hy = [h["w"] for h in hyp]
    pairs = align(ref, hy)
    by_ref = {i: j for i, j in pairs}
    words = []
    for i, w in enumerate(ref):
        if i in by_ref:
            h = hyp[by_ref[i]]
            words.append({"text": w, "onset_ms": round(h["start"] * 1000),
                          "end_ms": round(h["end"] * 1000), "how": "asr"})
        else:
            words.append({"text": w, "onset_ms": None, "end_ms": None, "how": "interp"})
    # interpolate the gaps between matched neighbours
    known = [i for i, w in enumerate(words) if w["onset_ms"] is not None]
    if not known:
        raise SystemExit(f"{clip_id}: alignment matched zero words")
    if known[0] > 0:
        step = 300
        for i in range(known[0] - 1, -1, -1):
            words[i]["onset_ms"] = max(0, words[i + 1]["onset_ms"] - step)
            words[i]["end_ms"] = words[i + 1]["onset_ms"]
    for a, b in zip(known, known[1:]):
        if b - a > 1:
            span = words[b]["onset_ms"] - words[a]["end_ms"]
            step = span / (b - a)
            for k in range(1, b - a):
                words[a + k]["onset_ms"] = round(words[a]["end_ms"] + step * (k - 1) + step * 0.0)
                words[a + k]["end_ms"] = round(words[a]["end_ms"] + step * k)
    if known[-1] < len(words) - 1:
        for i in range(known[-1] + 1, len(words)):
            words[i]["onset_ms"] = words[i - 1]["end_ms"]
            words[i]["end_ms"] = words[i]["onset_ms"] + 300
    for w in words:
        w["onset_ms"] = int(w["onset_ms"])
        w["end_ms"] = int(w["end_ms"])
    # faster-whisper pins the FIRST word of a segment to the segment start (0.0),
    # which is wrong by the clip's leading silence. The energy onset is exact
    # there, so use it for word 0.
    eo = energy_onset(x)
    if eo is not None and words[0]["onset_ms"] < eo <= words[0]["end_ms"]:
        words[0]["onset_ms"] = eo
        words[0]["how"] = "energy"
    # enforce monotonicity after the fix
    for a, b in zip(words, words[1:]):
        if b["onset_ms"] <= a["onset_ms"]:
            b["onset_ms"] = a["onset_ms"] + 20
        if b["end_ms"] <= b["onset_ms"]:
            b["end_ms"] = b["onset_ms"] + 20
    matched = sum(1 for w in words if w["how"] in ("asr", "energy"))
    result = {"id": clip_id, "words": words, "matched": matched,
              "total": len(words), "energy_onset_ms": eo}
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(result, indent=1))
    return result


def energy_onset(x, thr_db=-35.0):
    """First 20 ms frame above thr_db re full scale — a sanity check on alignment."""
    hop = SR // 50
    for i in range(0, len(x) - hop, hop):
        rms = float(np.sqrt(np.mean(np.square(x[i:i + hop]))))
        if 20 * np.log10(max(rms, 1e-9)) > thr_db:
            return i * 1000 // SR
    return None


def sharpest_edge_ms(x, at_ms, window_ms=150, hop_ms=10):
    """Time of the steepest rise in short-time RMS around `at_ms` (0 if none)."""
    hop = SR * hop_ms // 1000
    lo = max(0, (at_ms - window_ms) * SR // 1000)
    hi = min(len(x) - hop, (at_ms + window_ms) * SR // 1000)
    best, best_i = -1e9, None
    frames = {}
    for i in range(lo, hi, hop):
        frames[i] = float(np.sqrt(np.mean(np.square(x[i:i + hop]))))
    keys = sorted(frames)
    for i in keys:
        prev = frames.get(i - hop, 0.0)
        d = frames[i] - prev
        if d > best:
            best, best_i = d, i
    return None if best_i is None else best_i * 1000 // SR


# --------------------------------------------------------------- noise beds
def rms(x):
    return float(np.sqrt(np.mean(np.square(x))) + 1e-12)


def mix_at_snr(speech, noise, snr_db):
    noise = noise[: len(speech)]
    if len(noise) < len(speech):
        noise = np.resize(noise, len(speech))
    gain = rms(speech) / (rms(noise) * 10 ** (snr_db / 20))
    return speech + noise * gain


def pink(n, rng):
    """Pink (1/f) noise via spectral shaping, unit RMS — same recipe as sttbench."""
    spec = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1 / SR)
    freqs[0] = freqs[1]
    y = np.fft.irfft(spec / np.sqrt(freqs), n)
    return (y / rms(y)).astype(np.float32)


def babble(n, sources, rng):
    """Three-talker babble: the three other clips, cycled and mixed to equal RMS."""
    out = np.zeros(n, np.float32)
    for s in sources:
        v = np.resize(s, n)
        out += v / rms(v)
    return (out / rms(out)).astype(np.float32)


def clatter(n, rng, level=0.6):
    """Kitchen proxy: short broadband bursts (dish/cup clatter) every ~1.3 s."""
    out = np.zeros(n, np.float32)
    burst = int(0.12 * SR)
    i = int(0.4 * SR)
    while i < n - burst:
        b = rng.standard_normal(burst).astype(np.float32)
        env = np.hanning(burst).astype(np.float32)
        out[i:i + burst] += b * env * level
        i += int((1.1 + rng.random() * 0.5) * SR)
    return out


# --------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--align", action="store_true", help="(re)run faster-whisper alignment")
    ap.add_argument("--model", default="small.en", help="faster-whisper model for alignment")
    ap.add_argument("--no-cache", action="store_true", help="ignore .cache/align")
    args = ap.parse_args()

    man = [json.loads(l) for l in open(SRC / "manifest.jsonl")]
    clips = [m for m in man if m["category"] in SPEECH_CATS]
    silence = [m for m in man if m["id"] in SILENCE_IDS]
    if args.no_cache:
        for f in (CACHE / "align").glob("*.json"):
            f.unlink()

    align_model = None
    need_align = args.align or any(
        not (CACHE / "align" / f"{m['id']}.json").exists() for m in clips)
    if need_align:
        from faster_whisper import WhisperModel
        align_model = WhisperModel(args.model, device="cpu", compute_type="int8",
                                   cpu_threads=8)
        print(f"alignment model: faster-whisper {args.model} int8")

    audio = {}
    for m in man:
        x, sr = sf.read(SRC / f"{m['id']}.wav", dtype="float32")
        assert sr == SR, (m["id"], sr)
        audio[m["id"]] = x

    sources = [audio[i] for i in BABBLE_IDS]
    rng = np.random.default_rng(20261019)

    rows = []
    report = []
    for m in clips:
        x = audio[m["id"]]
        al = align_clip(m["id"], SRC / f"{m['id']}.wav", m["reference"], align_model, CACHE / "align")
        onsets = [w["onset_ms"] for w in al["words"]]
        assert all(b > a for a, b in zip(onsets, onsets[1:])), f"{m['id']}: non-monotonic onsets"
        eo = energy_onset(x)
        near = 0
        for w in al["words"]:
            e = sharpest_edge_ms(x, w["onset_ms"])
            if e is not None and abs(e - w["onset_ms"]) <= 60:
                near += 1
        report.append((m["id"], al["matched"], al["total"], onsets[0], eo, near))
        for cond in ("quiet", "tv", "kitchen"):
            if cond == "quiet":
                y = x
            elif cond == "tv":
                y = mix_at_snr(x, babble(len(x), sources, rng), 10)
            else:
                y = mix_at_snr(x, pink(len(x), rng), 8) + clatter(len(x), rng) * rms(x) * 0.5
            peak = float(np.max(np.abs(y)))
            if peak > 0.99:
                y = y * (0.99 / peak)
            d = OUT / cond
            d.mkdir(parents=True, exist_ok=True)
            sf.write(d / f"{m['id']}.wav", y.astype(np.float32), SR, subtype="PCM_16")
            rows.append({"id": m["id"], "condition": cond, "category": m["category"],
                         "wav": f"{cond}/{m['id']}.wav", "seconds": round(len(y) / SR, 3),
                         "reference": m["reference"], "words": al["words"],
                         "matched": al["matched"], "total": al["total"]})
            # Accented speech is where a small model is most likely to fall over, so
            # it gets its own condition (the clean audio) rather than being averaged
            # into "quiet".
            if cond == "quiet" and m["category"] == "accent":
                d = OUT / "accent"
                d.mkdir(parents=True, exist_ok=True)
                sf.write(d / f"{m['id']}.wav", y.astype(np.float32), SR, subtype="PCM_16")
                rows.append({"id": m["id"], "condition": "accent", "category": "accent",
                             "wav": f"accent/{m['id']}.wav",
                             "seconds": round(len(y) / SR, 3),
                             "reference": m["reference"], "words": al["words"],
                             "matched": al["matched"], "total": al["total"]})

    for m in silence:
        x = audio[m["id"]]
        d = OUT / "quiet"
        d.mkdir(parents=True, exist_ok=True)
        sf.write(d / f"{m['id']}.wav", x, SR, subtype="PCM_16")
        rows.append({"id": m["id"], "condition": "quiet", "category": "silence",
                     "wav": f"quiet/{m['id']}.wav", "seconds": round(len(x) / SR, 3),
                     "reference": "", "words": [], "matched": 0, "total": 0})

    with open(OUT / "manifest.jsonl", "w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")

    print(f"{'clip':16s} {'matched':>8s} {'onset':>6s} {'energy':>7s} {'edge<60ms':>10s}")
    for cid, mt, tt, o0, eo, near in report:
        print(f"{cid:16s} {mt:4d}/{tt:<3d} {o0:6d} {str(eo):>7s} {near:5d}/{tt:<3d}")
    total_m = sum(r[1] for r in report)
    total_t = sum(r[2] for r in report)
    edge = sum(r[5] for r in report)
    print(f"\n{len(rows)} manifest rows, {total_m}/{total_t} words aligned "
          f"({100 * total_m / total_t:.1f}%), {sum(r['seconds'] for r in rows):.1f} s audio")
    print(f"onset check: {edge}/{total_t} onsets ({100 * edge / total_t:.1f}%) within "
          f"60 ms of a rising energy edge")


if __name__ == "__main__":
    main()
