#!/usr/bin/env python3
"""Build orchestrator — the no-flag entry point the pipeline and Makefile invoke.

This is the *operator-facing* surface.  It:
  - refuses any target flags (no-flag design principle);
  - detects a BuildPlan (which targets are feasible from this host) via
    build.py's feasibility evaluator;
  - runs the build;
  - handles Ctrl+C as a controlled interruption (visible report, exit 130);
  - persists a structured, non-secret run report (framework evidence
    invariant) so downstream pipeline stages can gate on it.

Usage:
    python scripts/build_orchestrator.py
    python scripts/build_orchestrator.py --verify
    python scripts/build_orchestrator.py --plan      # print BuildPlan and exit
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

# Import the canonical driver's plan/feasibility machinery.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402


def _emit(step: str, detail: str = "") -> None:
    """Operator-visible progress (framework progress invariant)."""
    prefix = f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] "
    line = f"{prefix}▶ {step}"
    if detail:
        line += f" — {detail}"
    print(line)


def detect_build_plan() -> dict:
    """Return a BuildPlan describing feasible and infeasible targets."""
    matrix = build.build_target_matrix()
    plan = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "host": {"os": build._host_os(), "arch": build._host_arch()},
        "go_toolchain": build._go_version(),
        "feasible": [t.name for t in matrix if t.feasible],
        "infeasible": [
            {"name": t.name, "reason": t.infeasible_reason}
            for t in matrix if not t.feasible
        ],
    }
    return plan


def _persist_evidence(plan: dict, result: dict) -> Path:
    """Persist a structured run report (framework evidence invariant)."""
    reports = build.REPORTS_DIR
    reports.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = reports / f"orchestrator-{ts}.json"
    payload = {
        "orchestrator": "build_orchestrator",
        "started": result.get("started"),
        "finished": result.get("finished"),
        "exit_code": result.get("exit_code"),
        "plan": plan,
        "outcome": result.get("outcome"),
    }
    path.write_text(json.dumps(payload, indent=2))
    return path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="No-flag build orchestrator for Ebitengine boilerplate.",
    )
    parser.add_argument("--verify", action="store_true",
                        help="Verify the artifact matrix after building.")
    parser.add_argument("--plan", action="store_true",
                        help="Print the BuildPlan and exit without building.")
    # Unknown flags are rejected (no-flag design).
    args, unknown = parser.parse_known_args()
    if unknown:
        print(f"✗ Unsupported argument(s): {unknown}", file=sys.stderr)
        print("  This entry point is no-flag: it builds every feasible target.", file=sys.stderr)
        return 2

    # Ctrl+C: controlled interruption, visible report, exit 130.
    def _sigint(signum, frame):
        _emit("interrupted", "Ctrl+C received — preserving state, exiting 130")
        os._exit(130)

    signal.signal(signal.SIGINT, _sigint)

    started = datetime.now(timezone.utc).isoformat()
    _emit("orchestrator start")

    plan = detect_build_plan()
    _emit("build plan detected",
          f"{len(plan['feasible'])} feasible, {len(plan['infeasible'])} infeasible")

    if args.plan:
        print(json.dumps(plan, indent=2))
        return 0

    for item in plan["infeasible"]:
        _emit("infeasible target", f"{item['name']}: {item['reason']}")

    # Delegate to the canonical driver (respects --verify).
    cmd = [sys.executable, str(Path(__file__).resolve().parent / "build.py")]
    if args.verify:
        cmd.append("--verify")

    import subprocess
    proc = subprocess.run(cmd, cwd=str(build.REPO_ROOT))
    exit_code = proc.returncode
    finished = datetime.now(timezone.utc).isoformat()

    outcome = "success" if exit_code == 0 else "failure"
    result = {
        "started": started,
        "finished": finished,
        "exit_code": exit_code,
        "outcome": outcome,
    }

    report_path = _persist_evidence(plan, result)
    _emit("evidence persisted", str(report_path))
    _emit("orchestrator done", f"exit_code={exit_code}")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
