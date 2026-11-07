#!/usr/bin/env python3
"""Replay OnlineNemotronStt.transcribe()'s feed protocol on the host and compare it with
the phone bench.

The app's streaming leg decodes a finished utterance as StreamingTranscriber does: 20 ms
chunks, each drained, then 500 ms of silence, input_finished(), a final drain. This runs
the same protocol through the sherpa-onnx Python wheel over the corpus clips and checks the
text against the on-device bench's final_text for the same clips (and against a
single-feed decode). Host CPU numbers are not phone numbers.

    python3 tools/sttbench-stream/check_wholeclip.py <model-dir> tools/sttbench/corpus \\
        tools/sttbench-stream/results/raw/nemotron160-quiet-p0.json
"""
import json, sys, time, wave, re
import numpy as np, sherpa_onnx
mdir, corpus, bench = sys.argv[1], sys.argv[2], sys.argv[3]
rec = sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens=f"{mdir}/tokens.txt", encoder=f"{mdir}/encoder.int8.onnx",
    decoder=f"{mdir}/decoder.int8.onnx", joiner=f"{mdir}/joiner.int8.onnx",
    num_threads=4, sample_rate=16000, feature_dim=80, decoding_method="greedy_search",
    enable_endpoint_detection=False)
def read(p):
    with wave.open(p) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
def run(x, chunked=True):
    s = rec.create_stream()
    if chunked:
        for i in range(0, len(x), 320):
            s.accept_waveform(16000, x[i:i+320])
            while rec.is_ready(s): rec.decode_stream(s)
    else:
        s.accept_waveform(16000, x)
    s.accept_waveform(16000, np.zeros(8000, dtype=np.float32)); s.input_finished()
    while rec.is_ready(s): rec.decode_stream(s)
    return rec.get_result(s).strip()
norm = lambda t: re.sub(r"[^a-z0-9' ]", " ", t.lower()).split()
def wer(ref, hyp):
    r, h = norm(ref), norm(hyp); d = list(range(len(h)+1))
    for i in range(1, len(r)+1):
        p, d[0] = d[0], i
        for j in range(1, len(h)+1):
            p, d[j] = d[j], min(d[j]+1, d[j-1]+1, p + (r[i-1] != h[j-1]))
    return d[len(h)], len(r)
b = {c["id"]: c for c in json.load(open(bench))["clips"] if not c.get("warmup")}
same = 0; tot = 0; E = [0,0,0]; N = 0; dec = 0.0; aud = 0.0; agree_whole = 0
for cid, c in b.items():
    x = read(f"{corpus}/{cid}.wav"); t0 = time.time(); hyp = run(x); dec += time.time() - t0; aud += len(x)/16000
    whole = run(x, chunked=False); agree_whole += (whole == hyp)
    dev = c["final_text"].strip(); tot += 1; same += (norm(hyp) == norm(dev))
    if c.get("reference"):
        e, n = wer(c["reference"], hyp); e2, _ = wer(c["reference"], dev); E[0] += e; E[1] += e2; N += n
    print(f"{cid:18s} same_as_phone={norm(hyp)==norm(dev)!s:5s} chunked==whole={whole==hyp!s:5s}")
print(f"clips={tot} identical_to_phone_bench={same} chunked_equals_single_feed={agree_whole}")
print(f"WER host_wholeclip={E[0]/max(N,1):.3f} phone_bench={E[1]/max(N,1):.3f} words={N}")
print(f"host RTF={dec/aud:.3f} (x86 host, 4 threads; not a phone number)")
