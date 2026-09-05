#!/usr/bin/env python3
"""Test which parquet column encodings survive turnbench.data.resolve_dataset on a
local directory (audio struct + empty annotation list columns)."""
import io
import sys
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import soundfile as sf

sys.path.insert(0, "/tmp/turnbench")
from turnbench.data import resolve_dataset, conversation, conversation_ids  # noqa: E402

SR = 16000
dur = 1.0
sig = (0.05 * np.sin(2 * np.pi * 440 * np.arange(int(SR * dur)) / SR)).astype(np.float32)


def flac_bytes(x: np.ndarray) -> bytes:
    buf = io.BytesIO()
    sf.write(buf, x, SR, format="FLAC", subtype="PCM_16")
    return buf.getvalue()


def make_parquet(path: str, audio_pylist) -> None:
    n = len(audio_pylist)
    ids = [str(100 + i) for i in range(n)]
    ann_type = pa.list_(pa.struct([
        pa.field("start_s", pa.float64()),
        pa.field("end_s", pa.float64()),
        pa.field("label", pa.string()),
        pa.field("text", pa.string()),
    ]))
    table = pa.table({
        "conversation_id": pa.array(ids, pa.string()),
        "speaker_1_audio": audio_pylist,
        "speaker_2_audio": audio_pylist,
        **{f"speaker_{s}_annotation_{a}": pa.array([[] for _ in range(n)], ann_type)
           for s in (1, 2) for a in ("a", "b", "c")},
    })
    pq.write_table(table, path)


for label, audio_pylist in [
    ("struct-bytes-path", pa.array([{"bytes": flac_bytes(sig), "path": ""} for _ in range(1)],
                                   pa.struct([pa.field("bytes", pa.binary()), pa.field("path", pa.string())]))),
    ("dict-list", [{"bytes": flac_bytes(sig), "path": ""}]),
    ("raw-bytes", pa.array([flac_bytes(sig)], pa.binary())),
]:
    d = f"/tmp/synth_test_{label.replace('-', '_')}"
    import os, shutil
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    make_parquet(f"{d}/data-00000-of-00001.parquet", audio_pylist)
    try:
        ds = resolve_dataset(source=d)
        conv = conversation(ds, conversation_ids(ds)[0])
        x, sr = conv.audio(1)
        ok = x.shape[0] == int(SR * dur) and sr == SR
        print(f"{label}: OK conv={conv.conversation_id} dur={conv.duration_s:.2f} audio_ok={ok}")
    except Exception as e:
        print(f"{label}: FAIL {type(e).__name__}: {str(e)[:200]}")
