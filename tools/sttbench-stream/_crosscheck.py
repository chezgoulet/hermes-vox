#!/usr/bin/env python3
"""Throwaway: how stable are the ground-truth onsets? Compare small.en (the
aligner) with base.en, an independently sized checkpoint, on the same clips."""
import json, sys, statistics
from pathlib import Path
import numpy as np
import soundfile as sf
from faster_whisper import WhisperModel

sys.path.insert(0, "/home/c/vox-stt/tools/sttbench-stream")
from build_ab_corpus import norm_words, align

CORPUS = Path("/home/c/vox-stt/tools/sttbench/corpus")
ALIGN = Path("/home/c/vox-stt/tools/sttbench-stream/.cache/align")
CLIPS = ["short_01", "short_04", "long_02", "pause_01", "long_04"]

man = {json.loads(l)["id"]: json.loads(l) for l in open(CORPUS / "manifest.jsonl")}
m = WhisperModel("base.en", device="cpu", compute_type="int8", cpu_threads=8)

deltas = []
for cid in CLIPS:
    ref = norm_words(man[cid]["reference"])
    x, _ = sf.read(str(CORPUS / f"{cid}.wav"), dtype="float32")
    segs, _ = m.transcribe(x, language="en", beam_size=5, word_timestamps=True,
                           vad_filter=False, condition_on_previous_text=False)
    hyp = []
    for seg in segs:
        for w in seg.words or []:
            t = norm_words(w.word)
            if t:
                hyp.append({"w": t[0], "start": float(w.start)})
    pairs = dict(align(ref, [h["w"] for h in hyp]))
    small = json.loads((ALIGN / f"{cid}.json").read_text())["words"]
    row = []
    for i, w in enumerate(ref):
        if i in pairs and i > 0:   # skip word 0: small.en's is the energy fix
            d = hyp[pairs[i]]["start"] * 1000 - small[i]["onset_ms"]
            row.append(d)
            deltas.append(d)
    if row:
        print(f"{cid:12s} n={len(row):2d} median|d|={statistics.median([abs(d) for d in row]):5.0f} ms "
              f"max|d|={max(abs(d) for d in row):5.0f} ms")

ad = [abs(d) for d in deltas]
print(f"\nbase.en vs small.en onsets: n={len(ad)} median |delta|={statistics.median(ad):.0f} ms, "
      f"p90={sorted(ad)[int(0.9 * (len(ad) - 1))]:.0f} ms, "
      f"within 100 ms={100 * sum(1 for d in ad if d <= 100) / len(ad):.0f}%")
