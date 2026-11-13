#!/usr/bin/env python3
"""check-docs-tree.py — historical documents belong in docs/archive, and the count is enforced.

A document that says it is historical but sits in docs/ is how a session re-derives what the tree
already settled: 126 files were in that state on 2026-11-13. Two rules, both cheap:

  1. a file under docs/ (not docs/archive/) may not carry the historical banner;
  2. the count of current documents is printed, so growth is visible in the gate output.

Escape hatch: none. If a document becomes historical, move it — that is the whole point.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
ARCHIVE = DOCS / "archive"
BANNER = "Historical — kept as a record"

def main() -> int:
    current = [p for p in DOCS.glob("*.md") if p.parent == DOCS]
    archived = list(ARCHIVE.glob("*.md")) if ARCHIVE.is_dir() else []
    strays = [p for p in current if BANNER in p.read_text(errors="replace")[:600]]
    for p in strays:
        print(f"check-docs-tree: {p.relative_to(ROOT)} is marked historical but is not archived", file=sys.stderr)
    print(f"check-docs-tree: {len(current)} current, {len(archived)} archived, {len(strays)} stray")
    return 1 if strays else 0

if __name__ == "__main__":
    sys.exit(main())
