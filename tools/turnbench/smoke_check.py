#!/usr/bin/env python3
"""SMOKE-ONLY validation of vox_predict predictions against the synthetic dataset.

The synthetic dataset has no gold annotations, so scoring is impossible by
construction; this exercises the same validation path turnbench.score runs:
coverage (every conversation exactly once), per-speaker schema, strictly
increasing times, and times within each conversation's audio duration.
"""
import sys
from pathlib import Path

sys.path.insert(0, "/tmp/turnbench")

from turnbench.data import conversation, conversation_ids, resolve_dataset  # noqa: E402
from turnbench.submission import load_submission, validate_coverage, validate_event_times  # noqa: E402

dataset = resolve_dataset(source="/tmp/turnbench/vox_predict/synthetic")

for pred_file in ("predictions.json", "predictions_450.json"):
    sub = load_submission(Path("/tmp/turnbench/vox_predict") / pred_file)
    ids = conversation_ids(dataset)
    validate_coverage(sub, ids)
    for conv_id in ids:
        conv = conversation(dataset, conv_id)
        pred = sub.by_conversation()[conv_id]
        validate_event_times(pred, conv.duration_s)
    # strict increasing is enforced by the pydantic model inside load_submission
    print(f"{pred_file}: VALID — {len(sub.predictions)} conversations, "
          f"EOTs={sum(len(p.speaker_1.eot) + len(p.speaker_2.eot) for p in sub.predictions)}, "
          f"INTs={sum(len(p.speaker_1.interruption) + len(p.speaker_2.interruption) for p in sub.predictions)}")
print("schema validation OK (SMOKE-ONLY — no gold labels exist for synthetic audio)")
