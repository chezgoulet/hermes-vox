#!/usr/bin/env python3
"""check-skill-claims.py — keep the app skill honest (#221).

Every factual claim in `skills/hermes-vox-app/SKILL.md` cites a file and a token:

    (path/to/file.ext `token`)

The token is a literal string that must appear in that file, and it is the whole
claim: no line number is stored, so adding code above the cited token changes
nothing. A legacy `path/to/file.ext:LINE `token`` is still accepted on the way in
with the number ignored, so one commit can convert every claim without a broken
window.

Three outcomes, one of them good:

  * the token appears exactly once in the file -> verified;
  * it appears nowhere -> stale: the claim no longer matches the code and must be
    corrected (never repointed);
  * it appears more than once -> ambiguous: the token does not identify one
    place, so the claim must quote the whole line. Proximity is deliberately not
    used to pick a match — a checker that resolves a repeated token to the
    nearest line is how a wrong anchor survives review.

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

# Reference: (path[ `token`]) — a path with a known source extension, then one
# space and a backtick-delimited token. A run of backticks long enough to contain
# the token's own backticks is the delimiter, so a whole quoted line is writable.
# The optional `:LINE` (and a `:LINE-END` range) is legacy: accepted, then ignored.
REF_RE = re.compile(
    r"\((?P<path>[A-Za-z0-9_][A-Za-z0-9_./-]*\.(?:kt|xml|md|py|sh|yml|yaml|json|gradle|properties|txt))"
    r"(?::\d+(?:-\d+)?)?"
    r"[ \t]+(?P<quote>`+)(?P<token>[^\n]+?)(?P=quote)\)"
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
    ap = argparse.ArgumentParser(description="Verify the app skill's file+token claims.")
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
        errors.append("no file references found — every claim must be checkable")

    verified = 0
    unique: set[tuple[str, str]] = set()
    for m in refs:
        rel = m.group("path")
        token = norm(m.group("token").strip())
        if (rel, token) in unique:
            continue
        unique.add((rel, token))

        if not token:
            errors.append(f"{rel} — reference has no token to check")
            continue
        path = resolve(root, rel)
        if path is None:
            errors.append(f"{rel} — file not found")
            continue
        try:
            source = path.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            errors.append(f"{rel} — unreadable: {e}")
            continue

        # Count occurrences in the file's text with whitespace collapsed, so a
        # quoted line that wraps in the source still matches as written. The
        # token must identify exactly one place; two is not a tie to break.
        body = norm(source)
        hits = body.count(token)
        if hits == 0:
            errors.append(
                f"{rel} — stale claim: token not in this file: {token!r}"
            )
            continue
        if hits > 1:
            # Line numbers here are a diagnostic, not a resolution: the claim
            # stays wrong until its token is quoted from the whole line.
            where = [
                str(i + 1)
                for i, line in enumerate(source.splitlines())
                if token in norm(line)
            ]
            at = f" (lines {', '.join(where[:6])})" if where else ""
            errors.append(
                f"{rel} — token appears {hits} times{at}; "
                f"ambiguous — quote the whole line: {token!r}"
            )
            continue
        verified += 1
        if args.verbose:
            print(f"ok  {rel}  {token}")

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
