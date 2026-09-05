#!/usr/bin/env python3
"""vox_predict — Hermes Vox endpointing as a TurnBench submission predictor.

Simulates the deployed Hermes Vox turn-end (EOT) and barge-in (interruption)
decision rules on the benchmark's two-channel audio, with Silero VAD v4 as the
per-window speech detector — the same model the Vox capture loop feeds through
`sherpa-onnx` (`SileroVadGate`, OfflineStt.kt:131-156) at the deployed
threshold of 0.5 (VoiceController.kt:112).

Pipeline
--------
1. Each channel is decoded at its native rate and (if needed) resampled to
   16 kHz. Silero v4 runs over non-overlapping 512-sample (32 ms) windows with
   carried (h, c) state; a window is *speaking* iff its prob > 0.5. This is a
   deliberate approximation of sherpa-onnx's Vad: sherpa additionally applies
   its own segment smoothing (min_speech 0.25 s / min_silence 0.5 s defaults)
   inside isSpeechDetected(); here only the raw per-window prob > threshold is
   used, and all endpointing latency/smoothing lives in the Vox rules below.
   Every window contributes exactly 32 ms of clock time (512/16000 s).
2. Per-speaker event rules (the Vox capture loop, VoiceController.kt):

   EOT -- FINAL rule. A channel's *run* opens on the first speaking window and
   stays open while intra-run silence stays <= silenceMs. Run speech duration
   is accumulated speaking windows. When the run has >= 300 ms of speech
   (deployment `vad_min_speech_ms 300`, VC:214/216: a shorter segment is
   discarded, VC:300) and the silent run exceeds silenceMs (VC:267:
   `if (inSpeech && silentMs > silenceMs) break`), the turn ends: an EOT is
   committed at the end of the window that made the decision known (the
   TurnBench causality rule -- mirror baselines/rms_vad/predict.py:71-76).
   Runs whose speech never reaches 300 ms close silently at silenceMs.

   EOT -- final_450 variant (--mode final_450). Same machine with the
   early-start silence bound: the deployed early path commits a turn once the
   normalized partial hypothesis is unchanged AND silentMs >= 450 ms
   (VoiceLoopState.kt:31-36, earlySilenceMs 450). With no streaming ASR in the
   benchmark loop we cannot know partial-text stability, so `final_450` keeps
   only the silence bound (>= 450 ms) and drops the stability condition: it is
   the honest low-latency corner of the early rule, and over-fires on pauses
   that the deployed stability check would veto. The full early rule
   (stability-gated) is NOT faked: see --mode early-with-stability (stub).

   INT -- barge-in rule. While the OTHER channel holds an open, confirmed
   (>= 300 ms speech), not-yet-EOT-committed run, a run opening on this
   channel is an interruption candidate once its own onset is confirmed by
   >= 300 ms of speech; the INT is committed at the end of the window where
   that 300 ms confirmation completes. Direction guard: this channel's run
   must have opened strictly after the other's (the other must hold the floor
   when this speaker starts). This mirrors the deployment barge-in watch that
   arms against the other party's voice once the agent is speaking
   (VoiceController.kt:633-634, 766-768), simplified to the VAD path; the
   deployed arm delay (~0.7 s playback-side) is approximated by the 300 ms
   onset confirmation. INT and EOT are independent per run: a barge-in run can
   later EOT.

Operating points are documented in vox_predict/README.md with file:line
citations into /tmp/vox-work.

    uv run --with onnxruntime python vox_predict/predict.py --mode final \
        --dataset <hf repo|local parquet dir> --out vox_predict/predictions.json
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import numpy as np

# onnxruntime is imported lazily inside SileroVad so that the
# early-with-stability stub (which never touches the model) can run without it.

# turnbench/ is one level up (vox_predict/predict.py).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from turnbench.data import (  # noqa: E402
    DEV_DATASET,
    Conversation,
    conversation,
    conversation_ids,
    resolve_dataset,
)
from turnbench.score import score_submission, task_cells  # noqa: E402
from turnbench.submission import (  # noqa: E402
    SCHEMA_VERSION,
    ConversationPrediction,
    SpeakerEvents,
    Submission,
)

# --------------------------------------------------------------------------
# Model + operating points (deployed config; see README.md for citations)
# --------------------------------------------------------------------------

VAD_SAMPLE_RATE = 16000
WINDOW_SAMPLES = 512
WINDOW_S = WINDOW_SAMPLES / VAD_SAMPLE_RATE  # 0.032 s (32 ms per window)

VAD_THRESHOLD = 0.5          # VoiceController.kt:112 micFloat("vad_threshold", 0.5)
MIN_SPEECH_MS = 300          # VoiceController.kt:216 vad_min_speech_ms 300
FINAL_SILENCE_MS = 800       # VoiceController.kt:214 vad_silence_ms 800 (final rule)
EARLY_SILENCE_MS = 450       # VoiceLoopState.kt:16 earlySilenceMs 450 (early rule bound)

MODEL_DEFAULT = str(Path(__file__).resolve().parent / "models" / "silero_vad.onnx")


class SileroVad:
    """Raw per-window Silero VAD v4: (x[1,512], h, c) -> prob, carried state.

    State is carried across windows of one channel and reset per channel, so a
    conversation's two channels are scored as two independent 16 kHz streams on
    a shared clock.
    """

    def __init__(self, model_path: str):
        import onnxruntime as ort

        self._ort = ort
        if not os.path.exists(model_path):
            raise FileNotFoundError(
                f"silero VAD model not found at {model_path}; set --model or VOX_SILERO_MODEL"
            )
        self.sess = self._ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        self._h = np.zeros((2, 1, 64), dtype=np.float32)
        self._c = np.zeros((2, 1, 64), dtype=np.float32)

    def reset(self) -> None:
        self._h[:] = 0.0
        self._c[:] = 0.0

    def window_probs(self, audio: np.ndarray) -> np.ndarray:
        """Per-32 ms-window speech probs over a 16 kHz float32 mono channel."""
        n_windows = len(audio) // WINDOW_SAMPLES
        probs = np.empty(n_windows, dtype=np.float32)
        for w in range(n_windows):
            x = audio[w * WINDOW_SAMPLES:(w + 1) * WINDOW_SAMPLES].reshape(1, WINDOW_SAMPLES)
            (prob,), self._h, self._c = self.sess.run(
                ["prob", "new_h", "new_c"], {"x": x, "h": self._h, "c": self._c}
            )
            probs[w] = prob[0]
        return probs


def to_16k(audio: np.ndarray, sample_rate: int) -> np.ndarray:
    """Resample a mono float32 channel to 16 kHz (linear interp) if needed."""
    if sample_rate == VAD_SAMPLE_RATE:
        return audio.astype(np.float32, copy=False)
    n = int(len(audio) * VAD_SAMPLE_RATE / sample_rate)
    t_new = np.arange(n) / VAD_SAMPLE_RATE
    t_old = np.arange(len(audio)) / sample_rate
    return np.interp(t_new, t_old, audio).astype(np.float32)


class _ChannelState:
    __slots__ = ("open_at", "speech_ms", "silent_ms", "int_decided", "eot_fired")

    def __init__(self) -> None:
        self.open_at: int | None = None  # window index the current run opened on
        self.speech_ms: float = 0.0      # accumulated speaking ms in the current run
        self.silent_ms: float = 0.0      # consecutive non-speaking ms in the current run
        self.int_decided: bool = False   # onset-confirmation window consumed (fire/no-fire)
        self.eot_fired: bool = False     # EOT already committed for the current run


def predict_conversation(
    conv: Conversation,
    vad: SileroVad,
    mode: str,
) -> ConversationPrediction:
    """Run the Vox endpointing rules over both channels of one conversation."""
    if mode == "final":
        eot_gt_ms, eot_ge = FINAL_SILENCE_MS, False   # silentMs > 800 ms (VC:267)
    else:  # final_450
        eot_gt_ms, eot_ge = EARLY_SILENCE_MS, True    # silentMs >= 450 ms (VoiceLoopState.kt:33)

    streams: list[tuple[np.ndarray, np.ndarray]] = []  # (speaking bools, probs)
    for speaker in (1, 2):
        audio, sample_rate = conv.audio(speaker)
        x16 = to_16k(audio, sample_rate)
        vad.reset()
        probs = vad.window_probs(x16)
        streams.append((probs > VAD_THRESHOLD, probs))

    n_windows = min(len(s[0]) for s in streams)  # shared clock; channels same length

    st = [_ChannelState(), _ChannelState()]
    eot: list[list[float]] = [[], []]
    intr: list[list[float]] = [[], []]

    def close_run(i: int) -> None:
        st[i].open_at = None
        st[i].speech_ms = 0.0
        st[i].silent_ms = 0.0
        st[i].int_decided = False
        st[i].eot_fired = False

    for w in range(n_windows):
        # 1) advance per-channel run state on this window's speaking flag
        for i in range(2):
            s = st[i]
            if streams[i][0][w]:
                if s.open_at is None:
                    s.open_at = w
                s.speech_ms += WINDOW_S * 1000
                s.silent_ms = 0
            elif s.open_at is not None:
                s.silent_ms += WINDOW_S * 1000

        # 2) EOT / run close: decision known once this window is heard -> commit
        #    at the window's END ((w + 1) * WINDOW_S), mirroring rms_vad.
        for i in range(2):
            s = st[i]
            if s.open_at is None or s.eot_fired:
                continue
            crossed = s.silent_ms > eot_gt_ms if not eot_ge else s.silent_ms >= eot_gt_ms
            if crossed:
                if s.speech_ms >= MIN_SPEECH_MS:
                    eot[i].append((w + 1) * WINDOW_S)
                close_run(i)  # confirmed -> EOT; unconfirmed -> silent discard (VC:300)

        # 3) INT: fires on the window that completes the interrupter's >= 300 ms
        #    onset confirmation, against the other channel's still-open run.
        for i in range(2):
            s = st[i]
            if s.open_at is None or s.int_decided:
                continue
            confirmed_now = (
                s.speech_ms >= MIN_SPEECH_MS and s.speech_ms - WINDOW_S * 1000 < MIN_SPEECH_MS
            )
            if not confirmed_now:
                continue
            s.int_decided = True
            o = st[1 - i]
            other_holds_floor = (
                o.open_at is not None          # other still in an open run
                and o.open_at < s.open_at      # ... that started before this speaker
                and o.speech_ms >= MIN_SPEECH_MS      # ... confirmed (>= 300 ms speech)
                and not o.eot_fired             # ... and no EOT committed for it yet
            )
            if other_holds_floor:
                intr[i].append((w + 1) * WINDOW_S)

    return ConversationPrediction(
        conversation_id=conv.conversation_id,
        speaker_1=SpeakerEvents(eot=eot[0], interruption=intr[0]),
        speaker_2=SpeakerEvents(eot=eot[1], interruption=intr[1]),
    )


def predict(dataset, vad: SileroVad, mode: str) -> Submission:
    return Submission(
        schema_version=SCHEMA_VERSION,
        predictions=[
            predict_conversation(conversation(dataset, task_id), vad, mode)
            for task_id in conversation_ids(dataset)
        ],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--dataset",
        default=DEV_DATASET,
        help="HF dataset repo id, or a local directory of parquet shards",
    )
    parser.add_argument("--out", default=None, help="write a predictions JSON here")
    parser.add_argument(
        "--mode",
        default="final",
        choices=["final", "final_450", "early-with-stability"],
        help=(
            "final: deployed 800 ms silent-run EOT. final_450: 450 ms silence bound of the "
            "early rule, stability condition dropped (honest low-latency corner). "
            "early-with-stability: TODO stub - needs streaming partial ASR to judge partial "
            "stability; emits nothing (never fakes stability)."
        ),
    )
    parser.add_argument(
        "--model",
        default=os.environ.get("VOX_SILERO_MODEL", MODEL_DEFAULT),
        help="path to silero_vad.onnx (default: vox_predict/models/silero_vad.onnx)",
    )
    args = parser.parse_args()

    if args.mode == "early-with-stability":
        # The deployed early rule requires the normalized partial hypothesis to be
        # unchanged across two >= 900 ms-apart partial snapshots (VoiceController.kt:272-274,
        # VoiceLoopState.kt:31-36) while the speaker is paused >= 450 ms. Judging partial
        # stability needs a streaming ASR in the loop; this benchmark harness has none, so
        # this mode is an honest TODO that emits nothing rather than a fake stability proxy.
        print(
            "early-with-stability: TODO stub - no streaming ASR in the loop, partial-stability "
            "cannot be judged; emitting no events (empty predictions).",
            file=sys.stderr,
        )
        dataset = resolve_dataset(source=args.dataset, skip_audio=True)
        submission = Submission(
            schema_version=SCHEMA_VERSION,
            predictions=[
                ConversationPrediction(
                    conversation_id=task_id,
                    speaker_1=SpeakerEvents(eot=[], interruption=[]),
                    speaker_2=SpeakerEvents(eot=[], interruption=[]),
                )
                for task_id in conversation_ids(dataset)
            ],
        )
    else:
        vad = SileroVad(args.model)
        dataset = resolve_dataset(source=args.dataset)
        submission = predict(dataset, vad, args.mode)

    if args.out is not None:
        Path(args.out).write_text(submission.model_dump_json(indent=2), encoding="utf-8")
        print(f"Wrote {len(submission.predictions)} predictions to {args.out}", file=sys.stderr)
        return 0

    scores = score_submission(submission, dataset)
    print(f"vox_predict[{args.mode}] — {len(submission.predictions)} conversations")
    for task_name, score in (("EOT", scores.task_eot), ("INT", scores.task_int)):
        recall, fp_rate, latency = task_cells(score)
        print(f"  {task_name}: recall={recall} fp_rate={fp_rate} latency_ms={latency}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
