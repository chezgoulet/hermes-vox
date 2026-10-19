#!/usr/bin/env python3
"""presence-bench-report.py — turn the bench JSONL into the tables in docs/PRESENCE-BENCH.md.

Reads presence-bench-results/*.jsonl (rows written by PresenceBenchTest.kt) and prints, per
model+backend, the cold init, warm init, first-render TTFT, steady-state TTFT, decode rate,
line time and peak RSS. Medians, with the range in parentheses, over the reps of a row.

  python3 scripts/presence-bench-report.py [results-dir]
"""
import json
import statistics as st
import sys
from pathlib import Path

MODEL_NAMES = {
    "qwen": "Qwen3.5 0.8B int8",
    "smollm": "SmolLM2 360M",
    "gemma": "Gemma 4 E2B",
}
MODEL_SIZES = {"qwen": 965, "smollm": 374, "gemma": 2588}


def med(vals):
    vals = [v for v in vals if v is not None]
    if not vals:
        return None
    return st.median(vals)


def fmt(v, unit="", digits=0):
    if v is None:
        return "—"
    if digits == 0:
        return f"{v:.0f}{unit}"
    return f"{v:.{digits}f}{unit}"


def rng(vals, unit="", digits=0):
    vals = [v for v in vals if v is not None]
    if not vals:
        return "—"
    if len(vals) == 1:
        return fmt(vals[0], unit, digits)
    return f"{fmt(st.median(vals), unit, digits)} ({fmt(min(vals), '', digits)}–{fmt(max(vals), '', digits)})"


def load(path):
    rows = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "presence-bench-results")
    print("| row | cold init | warm init | TTFT 1st | TTFT steady | decode t/s | line time | peak RSS |")
    print("|---|---|---|---|---|---|---|---|")
    for path in sorted(root.glob("*.jsonl")):
        rows = load(path)
        if not rows:
            continue
        model = rows[0].get("model")
        if not model:
            continue
        backend = rows[0].get("backend")
        shape = rows[0].get("shape")
        inits = {r["cold"]: r["init_ms"] for r in rows if "init_ms" in r and r.get("rep") == -1}
        gens = [r for r in rows if r.get("rep", 0) > 0 and "ttft_ms" in r]
        hwm = [r.get("hwm_kb") for r in rows]
        first = gens[0]["ttft_ms"] if gens else None
        steady = med([g["ttft_ms"] for g in gens[1:]]) if len(gens) > 1 else first
        dec = med([g.get("wall_tok_s") for g in gens[1:] or gens])
        line = med([g["total_ms"] for g in gens[1:] or gens])
        print(
            f"| {path.stem} | "
            f"{fmt(inits.get(True), ' ms')} | {fmt(inits.get(False), ' ms')} | "
            f"{fmt(first, ' ms')} | {fmt(steady, ' ms')} | {fmt(dec, '', 1)} | "
            f"{fmt(line, ' ms')} | {fmt(max([h for h in hwm if h and h > 0], default=None) and max([h for h in hwm if h and h > 0]) / 1024, ' MB')} |"
        )


if __name__ == "__main__":
    main()
