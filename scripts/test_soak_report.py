#!/usr/bin/env python3
"""Tests for scripts/soak-report.py — the verdict on a thirty-minute soak.

Same rule as the other instruments: a checker whose failures are never exercised is a table.
Each case below is one of the ways a soak could be run and misread — the four-minute desk run, the
phone on the charger, the window where the call never happened — and the exit code is the assertion.

Run in the build gate (`scripts/gate.sh`) and in CI.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "scripts" / "soak-report.py"

PASS, FAIL, USAGE, UNKNOWN = 0, 1, 2, 3

failures: list[str] = []
checks = 0


def write(records: list[dict]) -> str:
    fh = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
    with fh:
        for rec in records:
            fh.write(json.dumps(rec) + "\n")
    return fh.name


def run(records: list[dict], *extra: str) -> tuple[int, str]:
    path = write(records)
    try:
        proc = subprocess.run([sys.executable, str(REPORT), path, *extra],
                              capture_output=True, text=True)
        return proc.returncode, proc.stdout + proc.stderr
    finally:
        os.unlink(path)


def samples(minutes: float, *, battery_drop: int = 2, pss: list[int] | None = None,
            thermal: list[str] | None = None, charging: bool = False) -> list[dict]:
    """One sample per 30 s across `minutes`, with the series a real soak produces."""
    n = int(minutes * 2) + 1
    step = minutes * 60 / (n - 1)
    pss = pss or [700_000] * n
    out = []
    for i in range(n):
        out.append({
            "t": round(i * step, 1),
            "pss_kb": pss[i % len(pss)],
            "avail_kb": 1_500_000,
            "thermal": (thermal[i % len(thermal)] if thermal else "none"),
            "battery": 100 - int(battery_drop * i / (n - 1)),
            "charging": charging,
        })
    return out


def body(samples_: list[dict], *, call: bool = True, label: str = "test soak") -> list[dict]:
    recs = [{"soak": {"label": label, "minutes": 30}}]
    recs.append({"call": {"detected": call, "opened_iso": "2026-11-19T09:00:00-05:00"}})
    recs.extend(samples_)
    return recs


def expect(name: str, records: list[dict], want: int, needle: str = "", *extra: str) -> None:
    global checks
    checks += 1
    got, output = run(records, *extra)
    if got == want and (needle == "" or needle.lower() in output.lower()):
        return
    first = output.strip().splitlines()[0] if output.strip() else "<no output>"
    reason = f"exit {got} (wanted {want})"
    if needle and needle.lower() not in output.lower():
        reason += f", output missing {needle!r}"
    failures.append(f"{name}: {reason}\n    {first}")


def main() -> int:
    # 1. A clean half-hour: level memory, no thermal move, 2 points in 31 min = 3.9 %/h.
    expect("healthy soak passes", body(samples(31)), PASS, "SOAK PASS")

    # 2. Battery over budget: 4 points in 31 min = 7.7 %/h.
    expect("battery over budget fails", body(samples(31, battery_drop=4)), FAIL, "battery")

    # 3. Thermal: any rise is a failure.
    hot = ["none"] * 30 + ["light"] * 20 + ["moderate"] * 13
    expect("thermal rise fails", body(samples(31, thermal=hot)), FAIL, "thermal")

    # 4. Cooling is not a failure — the commonest false red a naive check produces.
    cooling = ["light"] * 20 + ["none"] * 43
    expect("thermal cooling is not a failure", body(samples(31, thermal=cooling)), PASS, "SOAK PASS")

    # 5. Memory ceiling: 1.6 GB peak.
    over = [700_000] * 60 + [1_640_000] + [700_000] * 2
    expect("memory ceiling fails", body(samples(31, pss=over)), FAIL, "peak PSS")

    # 6. Growth over the soak, level in absolute terms: 700 MB → 860 MB is a leak, not a ceiling.
    rising = [700_000 + int(300_000 * i / 62) for i in range(63)]
    expect("memory slope fails", body(samples(31, pss=rising)), FAIL, "slope")

    # 7. A four-minute desk run cannot judge a soak. This is the failure the script exists for.
    expect("short window is UNKNOWN", body(samples(4)), UNKNOWN, "SOAK UNKNOWN")

    # 8. A phone on the charger cannot measure battery drain — the row is unknown, so is the soak.
    expect("charging is UNKNOWN", body(samples(31, charging=True)), UNKNOWN, "charging")

    # 9. No call in the window: nothing was soaked.
    expect("no call is UNKNOWN", body(samples(31), call=False), UNKNOWN, "no live call")

    # 10. --min-minutes judges a deliberate short trial for what a short trial can see — and
    #     battery is not one of those things, so the verdict is still unknown, not green.
    expect("short trial is still UNKNOWN on drain", body(samples(4)), UNKNOWN,
           "too short to measure drain", "--min-minutes", "4")

    # 11. Not a soak file at all.
    expect("empty file is USAGE", [], USAGE, "USAGE")

    # 12. Something that is not JSON.
    path = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
    with path:
        path.write("this is a log, not a soak\n")
    try:
        proc = subprocess.run([sys.executable, str(REPORT), path.name], capture_output=True, text=True)
        global checks
        checks += 1
        if proc.returncode != USAGE or "not JSON" not in (proc.stdout + proc.stderr):
            failures.append(f"non-JSON file is USAGE: exit {proc.returncode}")
    finally:
        os.unlink(path.name)

    if failures:
        print(f"test_soak_report: FAIL — {len(failures)} of {checks} cases")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print(f"test_soak_report: PASS — {checks} cases (exit codes 0/1/2/3 all exercised)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
