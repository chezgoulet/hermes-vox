#!/usr/bin/env python3
"""make_traces.py — frame traces of the sttbench corpus for the endpointer bench (#229).

For every clip in tools/sttbench/corpus, walks the audio in 100 ms frames, as the capture loop
does, and records per frame: whether Silero calls it speech (the app's VAD) and the streaming
recogniser's partial text so far (a streaming Zipformer — Apache-2.0, see README). Writes
android/app/src/test/resources/endpointbench/traces.json, which EndpointBenchTest replays
against the endpointer so its early-commit accuracy is measured on every build, offline.

    python3 -I tools/endpointbench/make_traces.py --zipformer <dir> --silero <silero_vad.onnx>

Pinned in requirements.txt. Deterministic: same corpus + models -> same traces.
"""
import argparse, json, wave
from pathlib import Path
import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "tools/sttbench/corpus"
OUT = ROOT / "android/app/src/test/resources/endpointbench/traces.json"
FRAME = 1600            # 100 ms at 16 kHz
TAIL_S = 3.0            # trailing silence after each clip: past every preset's ceiling


def read(p):
    with wave.open(str(p)) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zipformer", required=True)
    ap.add_argument("--silero", required=True)
    a = ap.parse_args()
    z = Path(a.zipformer)
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(z / "tokens.txt"),
        encoder=str(z / "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
        decoder=str(z / "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
        joiner=str(z / "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
        num_threads=4, sample_rate=16000, feature_dim=80, decoding_method="greedy_search")
    vcfg = sherpa_onnx.VadModelConfig()
    vcfg.silero_vad.model = a.silero
    vcfg.silero_vad.threshold = 0.5
    vcfg.silero_vad.min_silence_duration = 0.1   # a frame-level trace: the endpointer decides pauses, not the VAD
    vcfg.silero_vad.min_speech_duration = 0.1
    vcfg.sample_rate = 16000
    manifest = [json.loads(l) for l in (CORPUS / "manifest.jsonl").read_text().splitlines() if l.strip()]
    clips = []
    for m in manifest:
        if m["category"] == "silence":
            continue    # no utterance to end
        x = read(CORPUS / f"{m['id']}.wav")
        x = np.concatenate([x, np.zeros(int(16000 * TAIL_S), dtype=np.float32)])
        # Speech = inside a Silero segment. (The binding's per-window is_speech() latches and
        # stays true through trailing silence; segment boundaries do not.)
        det = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=60)
        win = vcfg.silero_vad.window_size   # fed a window at a time, as the app feeds it
        for i in range(0, len(x), win):
            det.accept_waveform(x[i:i + win].tolist())
        det.flush()
        segs = []
        while not det.empty():
            segs.append((det.front.start, det.front.start + len(det.front.samples))); det.pop()
        st = rec.create_stream()
        frames = []
        for at in range(0, len(x) - FRAME + 1, FRAME):
            f = x[at:at + FRAME]
            mid = at + FRAME // 2
            speech = any(a <= mid < b for a, b in segs)
            st.accept_waveform(16000, f)
            while rec.is_ready(st):
                rec.decode_stream(st)
            frames.append({"s": 1 if speech else 0, "p": rec.get_result(st).strip().lower()})
        clips.append({"id": m["id"], "category": m["category"], "seconds": m["seconds"],
                      "pause_at": float(m["note"].split(" at ")[1].rstrip(" s")) if m["category"] == "pause" else None,
                      "frames": frames})
        print(m["id"], len(frames), "frames;", frames[-1]["p"][:60])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"frame_ms": 100, "tail_s": TAIL_S, "clips": clips}, separators=(",", ":")))
    print("wrote", OUT.relative_to(ROOT), OUT.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
