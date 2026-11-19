#!/usr/bin/env python3
"""Read a cut tracker and refuse the tag when it is not actually answered.

Why this exists
---------------
0.9.1 was cut with every mechanical fact about it sound and two device defects in it. The
review (docs/REVIEW-0.9.1.md, failures 1 and 3) named the reason: the gate that was supposed
to ask *what does this touch that a phone can see?* (G0) and the gate that was supposed to
prove the record agreed with the release (G6) were both satisfied by a person ticking a box.
A tick is a claim, and a claim is the thing that failed.

This script turns the two of them into an exit code. It reads the cut tracker — the issue body
built by copying docs/templates/RELEASE-CUT.md — and answers three questions:

  * is the talked-about scope section present at all, with its required questions intact?
  * is every box in it answered?
  * is every box in the rest of the tracker either answered or *explicitly deferred* with a
    reason and a linked issue?

The rule it enforces is the one docs/REGRESSION.md already states: "Any red is fixed, or
deferred in writing with a reason and a linked issue. No tag with an unexplained red."

Exit codes (the same shape as scripts/baseline-diff.py, because a reader should not have to
learn a second vocabulary for "not green"):

  0  CUT-CHECK PASS      every required line present and every box answered or deferred
  1  CUT-CHECK REFUSE    an answered-looking tracker that is not answered; the offenders are named
  2  CUT-CHECK USAGE     the input could not be read as a tracker at all
  3  CUT-CHECK UNKNOWN   the scope section or one of its required questions is *absent*.
                         Absence is not a pass: a tracker that deleted the question looks
                         exactly like one that answered it, which is failure 3 in the review.

Exit 3 is deliberately not a softer 1. UNKNOWN is not healthy (D44): a cut either restores the
line or says in writing why it is gone, and that sentence belongs in the tracker.

Usage
-----
  scripts/check-cut.py --issue 548              # read the tracker from GitHub
  scripts/check-cut.py --file tracker.md        # read it from a file
  gh issue view 548 --json body -q .body | scripts/check-cut.py --file -
  scripts/check-cut.py --issue 548 --stage scope    # only §0, for the start of a cut

Nothing here judges whether the answers are *good*. It judges whether they exist. A person
still has to read the sentence that names the regression path.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field

# The three lines §0 of docs/templates/RELEASE-CUT.md requires. Each is an anchor phrase, kept
# short enough to survive rewording but specific enough that a deleted question is noticed.
# scripts/test_check_cut.py asserts each of these still occurs in the template, so editing the
# template without editing this list fails the build gate rather than silently lowering the bar.
REQUIRED_SCOPE_ANCHORS = (
    ("scope published", "what is in, and what is explicitly not in"),
    ("phone can see", "the regression path for every audio / lifecycle / network / UI change"),
    ("no transport", "no transport, media or lifecycle change inside a release whose subject is something else"),
)

SCOPE_HEADING = re.compile(r"^\s{0,3}#{1,4}\s*0\s*[.)]\s*(.*)$", re.IGNORECASE)
ANY_HEADING = re.compile(r"^\s{0,3}#{1,4}\s")
CHECKBOX = re.compile(r"^(?P<indent>\s*)[-*]\s+\[(?P<mark>[ xX])\]\s*(?P<text>.*)$")
DEFERRAL = re.compile(r"\b(deferred|waived)\b", re.IGNORECASE)
ISSUE_REF = re.compile(r"(?<![\w/])#\d+\b")


@dataclass
class Box:
    line_no: int
    checked: bool
    text: str
    section: str


@dataclass
class Report:
    boxes: list[Box] = field(default_factory=list)
    sections: list[str] = field(default_factory=list)
    scope_found: bool = False
    scope_text: str = ""
    missing_anchors: list[str] = field(default_factory=list)

    @property
    def scope_boxes(self) -> list[Box]:
        return [b for b in self.boxes if b.section == "0"]

    def unaccounted(self, boxes: list[Box]) -> list[Box]:
        """Unchecked, and not deferred in writing with a linked issue."""
        out = []
        for b in boxes:
            if b.checked:
                continue
            deferred = DEFERRAL.search(b.text) and ISSUE_REF.search(b.text)
            if not deferred:
                out.append(b)
        return out


def parse(body: str) -> Report:
    """Read the tracker. A checkbox owns every non-checkbox, non-heading line under it, so a
    question wrapped across four lines is read as one sentence — which is how the template's
    scope questions are written."""
    rep = Report()
    lines = body.splitlines()
    current_section = ""
    current_box: Box | None = None
    for i, raw in enumerate(lines, start=1):
        heading = SCOPE_HEADING.match(raw)
        if heading:
            current_section = "0"
            rep.scope_found = True
            rep.sections.append(raw.strip())
            current_box = None
            continue
        if ANY_HEADING.match(raw):
            current_section = raw.strip().lstrip("#").strip()
            rep.sections.append(current_section)
            current_box = None
            continue
        box = CHECKBOX.match(raw)
        if box:
            current_box = Box(
                line_no=i,
                checked=box.group("mark").lower() == "x",
                text=box.group("text").strip(),
                section=current_section,
            )
            rep.boxes.append(current_box)
            if current_section == "0":
                rep.scope_text += " " + current_box.text
            continue
        if current_box is not None and raw.strip() and not raw.strip().startswith("</"):
            # continuation of the box above it
            current_box.text += " " + raw.strip()
            if current_box.section == "0":
                rep.scope_text += " " + raw.strip()
    folded = " ".join(rep.scope_text.lower().split())
    rep.missing_anchors = [label for anchor, label in REQUIRED_SCOPE_ANCHORS if anchor not in folded]
    return rep


def read_body(args) -> str | None:
    if args.file == "-":
        return sys.stdin.read()
    if args.file:
        try:
            with open(args.file, encoding="utf-8") as fh:
                return fh.read()
        except OSError as exc:
            print(f"cut-check: cannot read {args.file}: {exc}", file=sys.stderr)
            return None
    if args.issue is None:
        return None
    if shutil.which("gh") is None:
        print("cut-check: gh is not installed; pass --file instead", file=sys.stderr)
        return None
    proc = subprocess.run(
        ["gh", "issue", "view", str(args.issue), "--json", "body,title,state"],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        print(f"cut-check: gh could not read #{args.issue}: {proc.stderr.strip()}", file=sys.stderr)
        return None
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        print("cut-check: gh returned something that is not JSON", file=sys.stderr)
        return None
    if data.get("state", "").lower() == "closed" and data.get("body"):
        # A closed tracker is still readable; closure alone is not a verdict here.
        pass
    return data.get("body") or ""


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Refuse a cut whose tracker is not answered (G0 scope, G6 record).",
        epilog="Exit 0 pass, 1 refuse, 2 usage, 3 unknown — UNKNOWN is not a green.",
    )
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--issue", type=int, help="cut tracker issue number")
    src.add_argument("--file", help="path to a tracker body, or - for stdin")
    ap.add_argument(
        "--stage",
        choices=("scope", "all"),
        default="all",
        help="scope = only §0 (the start of a cut); all = the whole tracker (the tag)",
    )
    args = ap.parse_args(argv)

    body = read_body(args)
    if body is None:
        ap.print_usage(sys.stderr)
        print("cut-check: CUT-CHECK USAGE — no tracker body was read", file=sys.stderr)
        return 2
    if not body.strip():
        print("cut-check: CUT-CHECK USAGE — the tracker body is empty", file=sys.stderr)
        return 2

    rep = parse(body)
    boxes = rep.scope_boxes if args.stage == "scope" else rep.boxes

    # 1. Absence. A deleted question is the failure that reads as an answer.
    if not rep.scope_found:
        print("cut-check: CUT-CHECK UNKNOWN — no §0 (scope) section in this tracker.", file=sys.stderr)
        print("  The scope section is what makes G0 a gate. Restore it from", file=sys.stderr)
        print("  docs/templates/RELEASE-CUT.md, or say in writing why the cut needs none.", file=sys.stderr)
        return 3
    if rep.missing_anchors:
        print("cut-check: CUT-CHECK UNKNOWN — the scope section is present but incomplete:", file=sys.stderr)
        for label in rep.missing_anchors:
            print(f"  missing: {label}", file=sys.stderr)
        print("  Each of those questions is in docs/templates/RELEASE-CUT.md §0. A cut that", file=sys.stderr)
        print("  removed one has not answered it.", file=sys.stderr)
        return 3
    if not boxes:
        print("cut-check: CUT-CHECK UNKNOWN — no checkboxes found where boxes are expected.", file=sys.stderr)
        return 3

    # 2. Answered, or deferred in writing with a linked issue.
    offenders = rep.unaccounted(boxes)
    answered = len(boxes) - offenders.__len__()
    where = "§0 scope" if args.stage == "scope" else f"the whole tracker ({len(rep.sections)} sections)"
    if offenders:
        print(f"cut-check: CUT-CHECK REFUSE — {len(offenders)} of {len(boxes)} boxes in {where} are unanswered.", file=sys.stderr)
        for b in offenders:
            first = b.text.strip().split(" ")[0:14]
            print(f"  line {b.line_no} [{b.section or '-'}]: {' '.join(first)}…", file=sys.stderr)
        print("  Each one is fixed, or carries `waived: <reason> #<issue>` on the same line", file=sys.stderr)
        print("  (`deferred:` is the same word; docs/REGRESSION.md, 'Any red is fixed, or", file=sys.stderr)
        print("  deferred in writing with a reason and a linked issue').", file=sys.stderr)
        return 1

    deferred = [b for b in boxes if not b.checked]
    print(f"cut-check: CUT-CHECK PASS — {len(boxes)} boxes in {where}: {answered} answered, {len(deferred)} deferred in writing.")
    for b in deferred:
        print(f"  deferred, line {b.line_no}: {b.text.strip()[:110]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
