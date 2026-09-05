#!/usr/bin/env python3
"""Build a synthetic two-channel smoke dataset for vox_predict.

SMOKE-ONLY: this is NOT the TurnBench dev set and carries NO gold annotations,
so it can never be scored -- it exists purely to prove the vox_predict pipeline
(VAD -> endpointing rules -> commit times -> predictions.json -> schema
validation) runs end to end.

Audio: mono 16 kHz crops of the public-domain JFK sample (whisper test asset),
each crop pre-verified to make Silero VAD v4 fire (prob > 0.5) across it.
Utterances are placed per an event script with digital-silence gaps (prob ~0),
so the designed pause lengths (0.2 / 0.3 / 0.75 / 1.4+ s), the > 800 ms
trailing silences, the double-talk barge-in, and the < 300 ms "discarded"
utterance all behave deterministically under the VAD.

Script (seconds):

  conv 9001 (21.0 s):
    S1: U_a @0.00 (1.92 s) --0.2 gap-- U_b @2.12 (1.15 s)   # one run, 0.2 s pause ignored
        U_b @6.90 (1.15 s)                                  # barges into S2's turn A
        short @12.00 (0.19 s)                               # < 300 ms -> discarded, no EOT
    S2: U_c @4.40 --0.3 gap-- U_d @6.91 (2.24 s)  ends 9.15 # turn A (S1 barges at 6.90)
        U_c @14.30 (2.21 s) --0.75 gap-- U_d @17.26         # turn B: 0.75 s pause: final_450
                                                            #   fires (>= 450 ms), final does not
  conv 9002 (9.5 s):
    S1: U_a @0.50, U_d @5.20                                # two clean turns
    S2: silent
"""
from __future__ import annotations

import io
import shutil
import sys
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

SR = 16000
WIN = 512
MODEL = Path(__file__).resolve().parent / "models" / "silero_vad.onnx"
OUT_DIR = Path(__file__).resolve().parent / "synthetic"


def window_probs(x: np.ndarray) -> np.ndarray:
    """Silero v4 per-window probs (reused by both chunk selection and the smoke report)."""
    import onnxruntime as ort

    sess = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    h = np.zeros((2, 1, 64), np.float32)
    c = np.zeros((2, 1, 64), np.float32)
    probs = np.empty(len(x) // WIN, np.float32)
    for i in range(len(probs)):
        (p,), h, c = sess.run(
            ["prob", "new_h", "new_c"],
            {"x": x[i * WIN:(i + 1) * WIN].reshape(1, WIN), "h": h, "c": c},
        )
        probs[i] = float(p[0])
    return probs


def slice_seconds(x: np.ndarray, start_s: float, end_s: float) -> np.ndarray:
    a = int(start_s * SR)
    return x[a:int(end_s * SR)].copy()


def place(buf: np.ndarray, at_s: float, chunk: np.ndarray) -> None:
    a = int(at_s * SR)
    buf[a:a + len(chunk)] = chunk


def flac_bytes(x: np.ndarray) -> dict:
    b = io.BytesIO()
    sf.write(b, x, SR, format="FLAC", subtype="PCM_16")
    return {"bytes": b.getvalue(), "path": ""}


def write_parquet(path: Path, rows: list[dict]) -> None:
    n = len(rows)
    ann_type = pa.list_(pa.struct([
        pa.field("start_s", pa.float64()),
        pa.field("end_s", pa.float64()),
        pa.field("label", pa.string()),
        pa.field("text", pa.string()),
    ]))
    audio_type = pa.struct([pa.field("bytes", pa.binary()), pa.field("path", pa.string())])
    table = pa.table({
        "conversation_id": pa.array([r["conversation_id"] for r in rows], pa.string()),
        "speaker_1_audio": pa.array([r["speaker_1_audio"] for r in rows], audio_type),
        "speaker_2_audio": pa.array([r["speaker_2_audio"] for r in rows], audio_type),
        **{
            f"speaker_{s}_annotation_{a}": pa.array([[] for _ in range(n)], ann_type)
            for s in (1, 2) for a in ("a", "b", "c")
        },
    })
    pq.write_table(table, path)


def main() -> None:
    x = np.load("/tmp/jfk16k.npy")  # mono 16 kHz JFK speech
    probs = window_probs(x)

    # Fixed speech chunks (window ranges taken from the silero firing profile of
    # the raw file; every range verified > 0.5 across its interior).
    def chunk(w0: int, w1: int, name: str) -> np.ndarray:
        # The chunk must contain >= ~0.4 s of VAD-active windows so that >= 300 ms
        # onset confirmation is guaranteed; brief interior dips are acceptable.
        active = (probs[w0:w1] > 0.5).sum() * WIN / SR
        assert active >= 0.4, f"chunk {name} not speech-active enough ({active:.2f}s)"
        return x[w0 * WIN:w1 * WIN].copy()

    U_a = chunk(10, 70, "U_a")    # 1.92 s
    U_b = chunk(102, 138, "U_b")  # 1.15 s (contains a natural ~96 ms VAD dip)
    U_c = chunk(169, 238, "U_c")  # 2.21 s
    U_d = chunk(256, 326, "U_d")  # 2.24 s

    # short chunk: densest 6-window (192 ms) slice of U_d -> always < 300 ms speech
    base = 256
    means = [probs[base + k:base + k + 6].mean() for k in range(len(U_d) // WIN - 6)]
    k0 = int(np.argmax(means))
    assert probs[base + k0:base + k0 + 6].min() > 0.5
    U_short = x[(base + k0) * WIN:(base + k0 + 6) * WIN].copy()

    lengths = {n: len(c) / SR for n, c in (("U_a", U_a), ("U_b", U_b), ("U_c", U_c),
                                           ("U_d", U_d), ("short", U_short))}
    print("chunks:", {k: round(v, 3) for k, v in lengths.items()})

    # --- conv 9001 -----------------------------------------------------------------
    L1 = 21.0
    s1 = np.zeros(int(L1 * SR), np.float32)
    s2 = np.zeros(int(L1 * SR), np.float32)
    place(s1, 0.00, U_a); place(s1, 2.12, U_b)     # run 1 (0.2 s internal pause)
    place(s2, 4.40, U_c); place(s2, 6.91, U_d)     # S2 turn A (0.3 s internal pause)
    place(s1, 6.90, U_b)                           # S1 barges into S2's turn A
    place(s1, 12.00, U_short)                      # < 300 ms -> discarded
    place(s2, 14.30, U_c); place(s2, 17.26, U_d)   # S2 turn B (0.75 s internal pause)
    conv_9001 = {"conversation_id": "9001",
                 "speaker_1_audio": flac_bytes(s1), "speaker_2_audio": flac_bytes(s2)}

    # --- conv 9002 -----------------------------------------------------------------
    L2 = 9.5
    t1 = np.zeros(int(L2 * SR), np.float32)
    t2 = np.zeros(int(L2 * SR), np.float32)
    place(t1, 0.50, U_a); place(t1, 5.20, U_d)     # two clean S1 turns
    conv_9002 = {"conversation_id": "9002",
                 "speaker_1_audio": flac_bytes(t1), "speaker_2_audio": flac_bytes(t2)}

    shutil.rmtree(OUT_DIR, ignore_errors=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    write_parquet(OUT_DIR / "data-00000-of-00001.parquet", [conv_9001, conv_9002])

    # --- smoke report: actual VAD speech runs per channel ---------------------------
    print("VAD speech runs (start_s, end_s) per channel:")
    for cid, (a, b) in (("9001", (s1, s2)), ("9002", (t1, t2))):
        for spk, ch in ((1, a), (2, b)):
            p = window_probs(ch)
            sp = p > 0.5
            runs, inr = [], False
            for i, v in enumerate(sp):
                if v and not inr:
                    st = i; inr = True
                elif not v and inr:
                    runs.append((st, i)); inr = False
            if inr:
                runs.append((st, len(sp)))
            print(f"  conv {cid} S{spk}: " + ", ".join(
                f"({st * WIN / SR:.2f}-{en * WIN / SR:.2f})" for st, en in runs) or "  (silent)")
    print(f"wrote {OUT_DIR}")


if __name__ == "__main__":
    main()
