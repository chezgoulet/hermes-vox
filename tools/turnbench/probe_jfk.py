#!/usr/bin/env python3
"""Prepare mono 16k JFK speech and probe silero per-window firing profile."""
import numpy as np
import soundfile as sf

x, sr = sf.read('/tmp/turnbench/vox_predict/jfk.flac')
if x.ndim > 1:
    x = x.mean(axis=1)
n = int(len(x) * 16000 / sr)
t_old = np.arange(len(x)) / sr
t_new = np.arange(n) / 16000
xr = np.interp(t_new, t_old, x).astype(np.float32)
print('mono16k dur', round(len(xr) / 16000, 3), 'rms', round(float(np.sqrt((xr ** 2).mean())), 3))
np.save('/tmp/jfk16k.npy', xr)

import onnxruntime as ort
SR, WIN = 16000, 512
sess = ort.InferenceSession('/tmp/turnbench/vox_predict/models/silero_vad.onnx',
                            providers=['CPUExecutionProvider'])
h = np.zeros((2, 1, 64), np.float32)
c = np.zeros((2, 1, 64), np.float32)
probs = []
for i in range(len(xr) // WIN):
    (p,), h, c = sess.run(['prob', 'new_h', 'new_c'],
                          {'x': xr[i * WIN:(i + 1) * WIN].reshape(1, WIN), 'h': h, 'c': c})
    probs.append(float(p[0]))
p = np.array(probs)
sp = p > 0.5
print('frac speech windows:', round(float(sp.mean()), 3))
runs = []
inr = False
for i, v in enumerate(sp):
    if v and not inr:
        start = i; inr = True
    elif not v and inr:
        runs.append((start, i)); inr = False
if inr:
    runs.append((start, len(sp)))
print('speech runs (s):', [(round(a * WIN / SR, 3), round(b * WIN / SR, 3)) for a, b in runs])
