#!/usr/bin/env python3
"""Tests for scripts/check-cut.py — the gate that refuses a cut whose tracker is unanswered.

A checker whose failures are never exercised is a table, not an instrument (the same sentence
that put scripts/test_baseline_diff.py in the gate). Every case below is one of the ways 0.9.1
or 0.9.1.3 got past a green gate, and the exit code is the assertion.

The last case is a drift guard: if someone edits the scope questions out of
docs/templates/RELEASE-CUT.md, this fails until check-cut.py's anchors are updated — so the
template cannot quietly stop asking a question the checker still believes it asks.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECK = ROOT / "scripts" / "check-cut.py"
TEMPLATE = ROOT / "docs" / "templates" / "RELEASE-CUT.md"

PASS, REFUSE, USAGE, UNKNOWN = 0, 1, 2, 3

failures: list[str] = []
checks = 0


def run(body: str, *extra: str) -> tuple[int, str]:
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as fh:
        fh.write(body)
        path = fh.name
    try:
        proc = subprocess.run(
            [sys.executable, str(CHECK), "--file", path, *extra],
            capture_output=True,
            text=True,
        )
        return proc.returncode, (proc.stdout + proc.stderr)
    finally:
        os.unlink(path)


def expect(name: str, body: str, want: int, needle: str = "", *extra: str) -> None:
    global checks
    checks += 1
    got, output = run(body, *extra)
    ok = got == want and (needle == "" or needle.lower() in output.lower())
    if ok:
        return
    first = output.strip().splitlines()[0] if output.strip() else "<no output>"
    reason = f"exit {got} (wanted {want})"
    if needle and needle.lower() not in output.lower():
        reason += f", output missing {needle!r}"
    failures.append(f"{name}: {reason}\n    {first}")


def template_body() -> str:
    return TEMPLATE.read_text(encoding="utf-8")


def answer_all(body: str) -> str:
    return re.sub(r"^(\s*[-*]\s+)\[ \]", r"\1[x]", body, flags=re.MULTILINE)


def main() -> int:
    global checks
    if not TEMPLATE.exists():
        print(f"test_check_cut: FAIL — {TEMPLATE} is missing")
        return 1
    tpl = template_body()

    # 1. A fresh copy of the template is not an answered tracker. This is the 0.9.1 shape: the
    #    document existed, the boxes were not answered, and nothing read them.
    expect("fresh template is refused", tpl, REFUSE, "CUT-CHECK REFUSE")

    # 2. Every box answered: the only state that may be tagged.
    expect("fully answered passes", answer_all(tpl), PASS, "CUT-CHECK PASS")

    # 3. One unanswered scope box, everything else answered. The G0 question is the one that
    #    would have caught #517, so an unanswered one is a refusal even at the tag.
    one_open = answer_all(tpl).replace(
        "- [x] **What does this touch that a phone can see?**",
        "- [ ] **What does this touch that a phone can see?**",
    )
    expect("one open scope box refuses", one_open, REFUSE, "phone can see")

    # 4. The scope section deleted altogether. Absence must not read as an answer — that is
    #    failure 3 in docs/REVIEW-0.9.1.md, where a document asserted a state nothing held.
    kept, skipping = [], False
    for line in tpl.splitlines():
        if re.match(r"^\s*#{1,4}\s*0\s*[.)]", line):
            skipping = True
            continue
        if skipping and re.match(r"^\s*#{1,4}\s", line):
            skipping = False
        if not skipping:
            kept.append(line)
    expect("deleted scope section is UNKNOWN", "\n".join(kept), UNKNOWN, "CUT-CHECK UNKNOWN")

    # 5. The scope section present but one required question removed. Same rule, narrower: the
    #    section survives as a heading and still says nothing about the phone.
    trimmed = re.sub(
        r"- \[x\] \*\*What does this touch that a phone can see\?\*\*.*?(?=\n- \[x\])",
        "",
        answer_all(tpl),
        flags=re.DOTALL,
    )
    expect("removed scope question is UNKNOWN", trimmed, UNKNOWN, "missing")

    # 6. Deferred in writing with a linked issue is an answer (docs/REGRESSION.md).
    deferred = answer_all(tpl).replace(
        "- [x] **F5 — the thirty-minute soak.**",
        "- [ ] **F5 — the thirty-minute soak.** deferred: no second handset until #208 lands. #208",
    )
    expect("written deferral passes", deferred, PASS, "deferred, line")

    # 7. "deferred" with no issue reference is not in writing; it is a hope.
    vague = answer_all(tpl).replace(
        "- [x] **F5 — the thirty-minute soak.**",
        "- [ ] **F5 — the thirty-minute soak.** deferred: later",
    )
    expect("deferral without an issue refuses", vague, REFUSE, "unanswered")

    # 7b. The template's own word for it is "waived, with a reason and a linked issue" (§4).
    #     Both words must work, because the checker must read the template's promise, not mine.
    waived = answer_all(tpl).replace(
        "- [x] **F8 — the ten-utterance verdict.**",
        "- [ ] **F8 — the ten-utterance verdict.** waived: no voice in the room this cut, #569. #569",
    )
    expect("waived with an issue passes", waived, PASS, "deferred, line")

    # 8. An empty body is a usage error, not a pass.
    expect("empty body is USAGE", "   \n\n", USAGE, "USAGE")

    # 9. --stage scope reads only §0, for the start of a cut where later sections are empty.
    scope_only = answer_all(tpl).replace(
        "- [x] `bash scripts/gate.sh` prints `GATE-GREEN`",
        "- [ ] `bash scripts/gate.sh` prints `GATE-GREEN`",
    )
    expect("stage scope ignores later sections", scope_only, PASS, "", "--stage", "scope")
    expect("stage all catches later sections", scope_only, REFUSE)

    # 10. A question wrapped across lines is read as one sentence (the template writes them that
    #     way; a parser that only looked at the first line would find none of them).
    wrapped = (
        "## 0. Scope — when the cut begins (2026-11-19)\n\n"
        "- [x] Scope published: what is in,\n      and what is explicitly **not** in.\n"
        "- [x] **What does this touch that a phone\n      can see?** audio: path 4. UI: matrix row.\n"
        "- [x] No transport, media or lifecycle change\n      inside a release whose subject is something else.\n"
    )
    expect("wrapped questions parse", wrapped, PASS, "", "--stage", "scope")

    # 11. Drift guard: the template must still contain every anchor this checker relies on.
    checks += 1
    folded = " ".join(tpl.lower().split())
    for anchor in ("scope published", "phone can see", "no transport"):
        if anchor not in folded:
            failures.append(
                f"template drift: docs/templates/RELEASE-CUT.md no longer contains {anchor!r} — "
                "update REQUIRED_SCOPE_ANCHORS in scripts/check-cut.py in the same commit"
            )

    if failures:
        print(f"test_check_cut: FAIL — {len(failures)} of {checks} cases")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print(f"test_check_cut: PASS — {checks} cases (exit codes 0/1/2/3 all exercised)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
