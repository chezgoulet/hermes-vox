#!/usr/bin/env python3
"""check-doc-truth.py — the design documents against the code.

The app skill has had mechanically verified claims since #221; the design
documents had nothing. That is how `docs/DECISIONS.md` came to say "Presence
remains 0.9.2" while the milestone said otherwise, and how a whole release got
described wrongly to a reviewer.

Prose cannot be verified in general, but a *specific claim about the code* can.
`docs/DOC-TRUTH.yml` lists those claims: each names the document that makes it,
the source file that settles it, and a pattern that must match there. This script
checks both directions — the phrase still exists in the document, and the pattern
still exists in the code — so either side drifting fails the gate.

Stdlib only, no device, no network, no build.

    python3 scripts/check-doc-truth.py
    python3 scripts/check-doc-truth.py --verbose

Exit codes: 0 all invariants hold, 1 an invariant is broken, 2 the ledger is
missing or unreadable.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

LEDGER = Path("docs/DOC-TRUTH.yml")


def parse_ledger(text: str) -> list[dict[str, str]]:
    """A deliberately small YAML subset: a list of flat `key: value` maps.

    Keeps the check dependency-free (no PyYAML on the build host), and the
    ledger is written to fit it exactly.
    """
    entries: list[dict[str, str]] = []
    cur: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip() if not raw.lstrip().startswith("#") else ""
        if not line.strip():
            continue
        if line.startswith("- "):
            if cur:
                entries.append(cur)
            cur = {}
            line = line[2:]
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        v = v.strip().strip('"').strip("'")
        cur[k.strip()] = v
    if cur:
        entries.append(cur)
    return entries


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if not LEDGER.is_file():
        print(f"check-doc-truth: {LEDGER} is missing", file=sys.stderr)
        return 2

    entries = parse_ledger(LEDGER.read_text())
    if not entries:
        print("check-doc-truth: the ledger is empty", file=sys.stderr)
        return 2

    failures: list[str] = []
    for e in entries:
        ident = e.get("id", "<unnamed>")
        doc = Path(e.get("doc", ""))
        src = Path(e.get("file", ""))
        says = e.get("says", "")
        match = e.get("match", "")
        absent = e.get("absent", "")

        if not doc.is_file():
            failures.append(f"{ident}: document {doc} is missing")
            continue
        if not src.is_file():
            failures.append(f"{ident}: source {src} is missing")
            continue

        dtext = doc.read_text(errors="replace")
        stext = src.read_text(errors="replace")

        if says and says not in dtext:
            failures.append(f"{ident}: {doc} no longer says '{says}'")
        if match and not re.search(match, stext):
            failures.append(f"{ident}: {src} no longer matches /{match}/")
        if absent and re.search(absent, stext):
            failures.append(f"{ident}: {src} still matches the forbidden /{absent}/")
        if args.verbose and says in dtext and (not match or re.search(match, stext)):
            print(f"ok {ident}: {doc} and {src} agree")

    if failures:
        print(f"check-doc-truth: {len(entries)} invariant(s), {len(failures)} broken", file=sys.stderr)
        for f in failures:
            print("  - " + f, file=sys.stderr)
        return 1

    print(f"check-doc-truth: {len(entries)} invariant(s) hold")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
