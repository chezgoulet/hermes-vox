#!/usr/bin/env python3
"""Calibrate synthetic speech-like bursts against real silero_vad.onnx (v4).

Goal: find a deterministic signal whose per-window silero prob is reliably > 0.5
across a burst and ~0 in digital-silence gaps, so a synthetic smoke dataset has
crisp VAD boundaries (no reliance on downloading real speech).
"""
import sys
import numpy as np
import onnxruntime as ort

SR = 16000
WIN = 512

sess = ort.InferenceSession(
    "/tmp/turnbench/vox_predict/models/silero_vad.onnx",
    providers=["CPUExecutionProvider"],
)
h0 = np.zeros((2, 1, 64), dtype=np.float32)
c0 = np.zeros((2, 1, 64), dtype=np.float32)


def per_window_prob(x: np.ndarray, h=None, c=None):
    """Run silero over x (float32 mono 16k), return (probs, final_h, final_c)."""
    if h is None:
        h = h0.copy()
        c = c0.copy()
    n_win = len(x) // WIN
    probs = np.empty(n_win, dtype=np.float32)
    for i in range(n_win):
        chunk = x[i * WIN:(i + 1) * WIN].reshape(1, WIN)
        (prob,), h, c = sess.run(
            ["prob", "new_h", "new_c"],
            {"x": chunk, "h": h, "c": c},
        )
        probs[i] = prob[0]
    return probs, h, c


def speechlike(dur_s: float, rms: float, seed: int = 0, f0: float = 120.0):
    """Speech-shaped burst: voiced harmonics (saw-ish, f0 with jitter) +
    band-limited frication noise + syllable-rate AM envelope."""
    rng = np.random.default_rng(seed)
    n = int(dur_s * SR)
    t = np.arange(n) / SR
    f0 = f0 * (1 + 0.08 * np.sin(2 * np.pi * 3.7 * t))  # prosodic pitch movement
    phase = 2 * np.pi * np.cumsum(f0) / SR
    voiced = np.zeros(n)
    for k in range(1, 8):
        voiced += np.sin(k * phase) / k  # harmonic stack, saw-ish
    noise = rng.standard_normal(n)
    # crude bandpass: cumulative moving difference ~ 300-3500 Hz emphasis
    b = np.convolve(noise, np.ones(8) / 8, mode="same")
    bb = np.diff(np.concatenate([[0.0], b]))  # highpass-ish
    fric = np.convolve(bb, np.ones(24) / 24, mode="same")  # lowpass-ish
    am = 0.55 + 0.45 * np.sin(2 * np.pi * 5.2 * t + 1.0) ** 2  # syllabic 10.4 Hz
    sig = (0.65 * voiced + 0.35 * fric) * am
    sig *= rms / (np.sqrt(np.mean(sig**2)) + 1e-9)
    return sig.astype(np.float32)


def report(name, x):
    p, _, _ = per_window_prob(x)
    mid = p[len(p) // 4: 3 * len(p) // 4]
    print(f"{name:28s} rms={np.sqrt(np.mean(x**2)):.3f} "
          f"min_mid={mid.min():.3f} mean_mid={mid.mean():.3f} frac>0.5={np.mean(p > 0.5):.3f}")


if __name__ == "__main__":
    print("silero v4 calibration @16k, 512-sample windows")
    # silence check
    sil = np.zeros(SR, dtype=np.float32)
    p, _, _ = per_window_prob(sil)
    print(f"{'digital-silence':28s} max_prob={p.max():.4f}")
    for rms in (0.02, 0.05, 0.1, 0.2, 0.35):
        for seed in (0, 1, 2):
            report(f"speechlike rms={rms} seed={seed}", speechlike(1.0, rms, seed))
