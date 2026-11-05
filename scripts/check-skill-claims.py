#!/usr/bin/env python3
"""check-skill-claims.py — keep the app skill honest (#221).

Every factual claim in `skills/hermes-vox-app/SKILL.md` carries a reference of the form

    (path/to/file.ext:LINE token)

where `token` is a literal string that must appear on that line (a range
`path:12-18` is also accepted, and the token may be wrapped in backticks).
This script verifies every reference: the file exists, the line exists, and the
named token is on it. Stale references are listed and the process exits 1.

It also guards the file's shape, because a skill that loses its frontmatter or a
required section can no longer be triggered or trusted:

  * Hermes frontmatter: name, description, version, author, license;
  * the description's first sentence is a self-contained trigger (<= 57 chars)
    and names Hermes Vox;
  * sections 1-7 required by the issue are present;
  * a minimum number of verified claims (the file must not be hollowed out);
  * no personal/build-host paths or e-mail addresses in the skill (leak guard).

Stdlib only, no device, no build:

    python3 scripts/check-skill-claims.py            # verify
    python3 scripts/check-skill-claims.py --verbose  # also print each claim

Exit codes: 0 all claims verified, 1 a stale claim or shape error, 2 the skill
file itself is missing/unreadable.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SKILL = Path("skills/hermes-vox-app/SKILL.md")

# Reference: (path:line[+token]) — a path with a known source extension, a line or
# line range, then one space and the token, up to the closing parenthesis.
REF_RE = re.compile(
    r"\(([A-Za-z0-9_][A-Za-z0-9_./-]*\.(?:kt|xml|md|py|sh|yml|yaml|json|gradle|properties|txt))"
    r":(\d+)(?:-(\d+))?"
    r"[ \t]+([^)]+?)\)"
)

MIN_CLAIMS = 150  # the finished skill carries far more; this catches mass deletion
TRIGGER_CHARS = 57
REQUIRED_SECTIONS = [
    "## 1. What it is, in four lines",
    "## 2. The pieces and where they run",
    "## 3. The settings map",
    "## 4. The log vocabulary",
    "## 5. The model catalogue",
    "## 6. Failure modes",
    "## 7. What the app does not do",
]
FRONTMATTER_FIELDS = ["name", "description", "version", "author", "license"]

# Personal details must never appear in a shipped skill. These are the shapes
# that leaked into docs before: absolute build-host paths and e-mail addresses.
LEAK_PATTERNS = [
    (re.compile(r"/home/"), "absolute /home path"),
    (re.compile(r"~/"), "home-relative path"),
    (re.compile(r"\bC:\\\\"), "Windows absolute path"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "e-mail address"),
    (re.compile(r"jdk-\d"), "JDK build-host path"),
    (re.compile(r"Android/Sdk"), "SDK build-host path"),
]


def norm(s: str) -> str:
    """Collapse whitespace so indentation and line wrapping never cause a false miss."""
    return " ".join(s.split())


def resolve(root: Path, rel: str) -> Path | None:
    p = root / rel
    if p.is_file():
        return p
    if "/" not in rel:
        # A bare basename is accepted only when it is unambiguous in the tree.
        hits = [q for q in root.rglob(rel) if q.is_file() and ".git" not in q.parts]
        if len(hits) == 1:
            return hits[0]
    return None


def parse_frontmatter(text: str) -> tuple[dict[str, str], list[str]]:
    errors: list[str] = []
    if not text.startswith("---\n"):
        return {}, ["no YAML frontmatter (the file must start with '---')"]
    end = text.find("\n---\n", 3)
    if end < 0:
        return {}, ["frontmatter is not closed with '---'"]
    fields: dict[str, str] = {}
    for line in text[4:end].splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if ":" not in line:
            errors.append(f"frontmatter line is not 'key: value': {line!r}")
            continue
        k, v = line.split(":", 1)
        fields[k.strip()] = v.strip().strip('"').strip("'")
    for f in FRONTMATTER_FIELDS:
        if not fields.get(f):
            errors.append(f"frontmatter is missing '{f}'")
    return fields, errors


def check_shape(text: str, fields: dict[str, str]) -> list[str]:
    errors: list[str] = []
    for section in REQUIRED_SECTIONS:
        if section not in text:
            errors.append(f"missing required section: {section!r}")
    desc = fields.get("description", "")
    first = re.split(r"(?<=[.!?])\s", desc, maxsplit=1)[0] if desc else ""
    if not first:
        errors.append("description is empty")
    else:
        if len(first) > TRIGGER_CHARS:
            errors.append(
                f"description's first sentence is {len(first)} chars; it must stand alone "
                f"as a trigger in <= {TRIGGER_CHARS}: {first!r}"
            )
        if "Hermes Vox" not in first:
            errors.append(f"description's first sentence does not name Hermes Vox: {first!r}")
    for pattern, label in LEAK_PATTERNS:
        m = pattern.search(text)
        if m:
            line = text[: m.start()].count("\n") + 1
            errors.append(f"leak guard: {label} at line {line}: {m.group(0)!r}")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description="Verify the app skill's file:line claims.")
    ap.add_argument("--verbose", action="store_true", help="print every verified claim")
    ap.add_argument("--skill", default=str(SKILL), help="path to the skill (default: %(default)s)")
    args = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    skill_path = Path(args.skill)
    if not skill_path.is_absolute():
        skill_path = root / skill_path
    try:
        text = skill_path.read_text(encoding="utf-8")
    except OSError as e:
        print(f"check-skill-claims: cannot read {skill_path}: {e}", file=sys.stderr)
        return 2

    fields, errors = parse_frontmatter(text)
    errors += check_shape(text, fields)

    refs = list(REF_RE.finditer(text))
    if not refs:
        errors.append("no file:line references found — every claim must be checkable")

    verified = 0
    unique: set[tuple[str, int, int, str]] = set()
    for m in refs:
        rel, first_s, last_s, raw_token = m.groups()
        first = int(first_s)
        last = int(last_s) if last_s else first
        token = raw_token.strip().strip("`").strip()
        key = (rel, first, last, token)
        if key in unique:
            continue
        unique.add(key)
        line_no = text[: m.start()].count("\n") + 1

        if not token:
            errors.append(f"{rel}:{first_s} — reference has no token to check")
            continue
        path = resolve(root, rel)
        if path is None:
            errors.append(f"{rel}:{first_s} — file not found")
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError as e:
            errors.append(f"{rel}:{first_s} — unreadable: {e}")
            continue
        if first < 1 or last < first or last > len(lines):
            errors.append(
                f"{rel}:{first_s}-{last} — line range outside the file "
                f"({len(lines)} lines)"
            )
            continue
        window = norm(" ".join(lines[first - 1 : last]))
        if norm(token) not in window:
            actual = norm(lines[first - 1])[:150]
            errors.append(
                f"{rel}:{first_s} — token not on line: {token!r}\n"
                f"      actual line: {actual}"
            )
            continue
        verified += 1
        if args.verbose:
            print(f"ok  {rel}:{first_s}  {token}")

    if verified < MIN_CLAIMS:
        errors.append(
            f"only {verified} claims verified; the skill must keep at least {MIN_CLAIMS} "
            f"(did a section get deleted?)"
        )

    if errors:
        print(f"check-skill-claims: {len(errors)} problem(s) in {SKILL}:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        print(
            f"check-skill-claims: {verified} reference(s) verified, {len(errors)} stale/invalid",
            file=sys.stderr,
        )
        return 1

    print(f"check-skill-claims: {verified} claims verified in {SKILL} (0 stale)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
