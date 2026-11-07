#!/usr/bin/env python3
"""presence-reports.py — turn `presence-report` issues into something countable (#417).

Every report carries a `vox-diag` block the app copies from Settings → Presence → Advanced.
This script pulls the labelled issues with `gh`, parses every block it finds, and prints an
aggregate grouped by preset and then device, with the number of reports behind every row.
It also writes that aggregate as CSV to `--out`.

  python3 scripts/presence-reports.py --out /tmp/presence.csv
  python3 scripts/presence-reports.py --fixture testdata/presence-reports.json --out /tmp/x.csv
  python3 scripts/presence-reports.py --json --out /tmp/x.csv

Malformed and missing blocks are counted and listed; they are never silently dropped. A
metric the block omits stays out of the aggregate (never read as zero) and is reported in the
"missing" section, because a report is a sample, not a schema.

Stdlib only. The only network call is `gh` as a subprocess, and only when --fixture is not
given. `gh` must already be authenticated; this script never handles a token itself.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from typing import Any, Iterable

MARKER = "vox-diag"
KNOWN_PRESETS = ("companion", "beat-only", "voice-smalltalk", "voice-max", "custom")
PRESET_ORDER = {name: i for i, name in enumerate(KNOWN_PRESETS)}
REQUIRED_FIELDS = ("build", "preset", "device", "mind")
FIELD_RE = re.compile(r"^([a-z0-9_]+):\s*(.*)$")
# A counters line is `turns: 12  lost: 0  …`, but a hand-pasted report may use one space.
# Split before any whitespace followed by another `key:`; device and preset_detail are prose.
KEY_BOUNDARY_RE = re.compile(r"\s+(?=[a-z0-9_]+:)")
PROSE_FIELDS = ("device", "preset_detail")

# The numeric fields the block may carry. `preset_detail` and `device` are prose (but still
# from closed vocabularies) and never split on whitespace.
NUMERIC = (
    "e1_p50_ms",
    "e2_p50_ms",
    "turns",
    "lost",
    "answered_by_voice",
    "escalated",
)


class GhError(RuntimeError):
    """`gh` is missing, unauthenticated, or refused the query."""


@dataclass
class Issue:
    number: int
    title: str
    body: str
    url: str = ""
    state: str = "open"
    author: str = ""
    comments: list[Any] = field(default_factory=list)


@dataclass
class Report:
    issue: Issue
    fields: dict[str, str]
    notes: list[str] = field(default_factory=list)

    def number(self, key: str) -> int | None:
        """The field as an int, or None when absent/unparseable (never 0 for absent)."""
        raw = self.fields.get(key)
        if raw is None or raw.strip() == "":
            return None
        try:
            return int(raw.strip())
        except ValueError:
            return None


@dataclass
class Malformed:
    issue: Issue
    reason: str


@dataclass
class Group:
    preset: str
    device: str
    reports: list[Report] = field(default_factory=list)

    def values(self, key: str) -> list[int]:
        out = []
        for r in self.reports:
            v = r.number(key)
            if v is not None:
                out.append(v)
        return out

    @property
    def e1_p50(self) -> int | None:
        return median(self.values("e1_p50_ms"))

    @property
    def e2_p50(self) -> int | None:
        return median(self.values("e2_p50_ms"))

    @property
    def models(self) -> str:
        return ",".join(sorted({r.fields.get("model", "none") for r in self.reports}))


def median(values: Iterable[int]) -> int | None:
    vals = sorted(values)
    if not vals:
        return None
    # Nearest-rank p50, the lower middle for an even count — the same convention as the
    # app's own percentile helper, so the CSV and the in-app number cannot disagree.
    return vals[(len(vals) - 1) // 2]


def _login(value: Any) -> str:
    if isinstance(value, dict):
        return str(value.get("login") or "").strip()
    return str(value or "").strip()


def _normalise_issue(raw: dict[str, Any]) -> Issue:
    return Issue(
        number=int(raw.get("number") or 0),
        title=str(raw.get("title") or ""),
        body=str(raw.get("body") or ""),
        url=str(raw.get("url") or ""),
        state=str(raw.get("state") or "open"),
        author=_login(raw.get("author")),
        comments=list(raw.get("comments") or []),
    )


def fetch_issues(fixture: str | None = None, repo: str | None = None) -> list[Issue]:
    """Read the labelled issues from gh, or from a JSON fixture for tests."""
    if fixture:
        with open(fixture, encoding="utf-8") as fh:
            data = json.load(fh)
        rows = data.get("issues", data) if isinstance(data, dict) else data
        if not isinstance(rows, list):
            raise GhError(f"{fixture}: expected a JSON list of issues")
        return [_normalise_issue(r) for r in rows]

    cmd = [
        "gh", "issue", "list",
        "--label", "presence-report",
        # Closed reports are still data: a configuration that was abandoned is evidence.
        "--state", "all",
        "--limit", "1000",
        "--json", "number,title,body,url,state,author,comments",
    ]
    if repo:
        cmd += ["--repo", repo]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    except FileNotFoundError as exc:
        raise GhError("gh is not installed — install it, or use --fixture") from exc
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()
        raise GhError(f"gh issue list failed: {detail or 'no output'}")
    try:
        rows = json.loads(proc.stdout or "[]")
    except json.JSONDecodeError as exc:
        raise GhError("gh returned something that is not JSON") from exc
    return [_normalise_issue(r) for r in rows]


def extract_block(body: str) -> tuple[list[str] | None, str | None]:
    """The lines under the first `vox-diag` marker, or (None, why it is missing)."""
    lines = (body or "").splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip().strip("`").strip() == MARKER:
            start = i
            break
    if start is None:
        return None, "no vox-diag block"
    block: list[str] = []
    for line in lines[start + 1:]:
        s = line.strip()
        if not s:
            break
        if s.startswith("```"):
            break
        if s.strip("`").strip() == MARKER:
            break
        block.append(line)
    if not block:
        return None, "empty vox-diag block"
    return block, None


def parse_block(lines: list[str]) -> tuple[dict[str, str], list[str]]:
    """Parse `key: value` lines, including the counters line's `a: 1  b: 2` shape."""
    fields: dict[str, str] = {}
    notes: list[str] = []
    for raw in lines:
        line = raw.strip()
        m = FIELD_RE.match(line)
        if not m:
            notes.append(f"unparsed line: {line[:40]!r}")
            continue
        key, rest = m.group(1), m.group(2).strip()
        if key in PROSE_FIELDS:
            # Prose from a closed vocabulary; spaces (and device punctuation) are not
            # separators here.
            fields.setdefault(key, rest)
            continue
        parts = KEY_BOUNDARY_RE.split(rest) if rest else [""]
        for idx, part in enumerate(parts):
            part = part.strip()
            if not part:
                continue
            mm = FIELD_RE.match(part)
            if idx == 0:
                value = mm.group(2).strip() if (mm and mm.group(1) == key) else part
                fields.setdefault(key, value)
            elif mm:
                fields.setdefault(mm.group(1), mm.group(2).strip())
            else:
                fields[key] = (fields.get(key, "") + "  " + part).strip()
    return fields, notes


def parse_issue(issue: Issue) -> tuple[Report | None, Malformed | None]:
    block, reason = extract_block(issue.body)
    if block is None:
        return None, Malformed(issue, reason or "no block")
    fields, notes = parse_block(block)
    if not fields:
        return None, Malformed(issue, "the block has no key: value lines")
    missing = [k for k in REQUIRED_FIELDS if not fields.get(k, "").strip()]
    if missing:
        return None, Malformed(issue, "missing " + ", ".join(missing))
    preset = fields["preset"].strip()
    if preset not in KNOWN_PRESETS:
        notes.append(f"unknown preset {preset!r}")
    report = Report(issue=issue, fields=fields, notes=notes)
    # Note an unparseable number once, here, instead of every time it is read for a group.
    for key in NUMERIC:
        raw = fields.get(key)
        if raw is not None and raw.strip() and report.number(key) is None:
            report.notes.append(f"{key} is not a number: {raw[:40]!r}")
    return report, None


def acknowledged(issue: Issue) -> bool:
    """A report is acknowledged when someone other than its author has replied.

    Bots are ignored: an automated "thanks for the report" is not a human acknowledgement,
    and the daily cadence exists to make that one line a person writes.
    """
    author = issue.author
    for c in issue.comments:
        login = _login(c.get("author") if isinstance(c, dict) else c)
        if not login or login.endswith("[bot]"):
            continue
        if author and login == author:
            continue
        return True
    return False


def aggregate(reports: list[Report]) -> list[Group]:
    groups: dict[tuple[str, str], Group] = {}
    for r in reports:
        preset = r.fields.get("preset", "custom").strip() or "custom"
        device = r.fields.get("device", "unknown").strip() or "unknown"
        groups.setdefault((preset, device), Group(preset, device)).reports.append(r)
    return sorted(
        groups.values(),
        key=lambda g: (PRESET_ORDER.get(g.preset, len(PRESET_ORDER)), g.preset.lower(), g.device.lower()),
    )


def missing_fields(reports: list[Report]) -> dict[str, int]:
    """How many parsed reports omit each optional metric. A missing metric is not a zero."""
    counts = {}
    for key in ("e1_p50_ms", "e2_p50_ms", *NUMERIC[2:], "mind", "model"):
        counts[key] = sum(1 for r in reports if not r.fields.get(key, "").strip())
    return {k: v for k, v in counts.items() if v}


def _cell(value: Any) -> Any:
    return "" if value is None else value


def write_csv(path: str, groups: list[Group]) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow([
            "preset", "device", "reports",
            "e1_p50_ms", "e1_n", "e2_p50_ms", "e2_n",
            "turns_sum", "turns_n", "lost_sum", "lost_n",
            "answered_by_voice_sum", "answered_by_voice_n",
            "escalated_sum", "escalated_n",
            "models", "issues",
        ])
        for g in groups:
            e1 = g.values("e1_p50_ms")
            e2 = g.values("e2_p50_ms")
            turns = g.values("turns")
            lost = g.values("lost")
            answered = g.values("answered_by_voice")
            escalated = g.values("escalated")
            w.writerow([
                g.preset, g.device, len(g.reports),
                _cell(g.e1_p50), len(e1), _cell(g.e2_p50), len(e2),
                _cell(sum(turns) if turns else None), len(turns),
                _cell(sum(lost) if lost else None), len(lost),
                _cell(sum(answered) if answered else None), len(answered),
                _cell(sum(escalated) if escalated else None), len(escalated),
                g.models,
                " ".join(f"#{r.issue.number}" for r in g.reports),
            ])


def write_malformed_csv(path: str, malformed: list[Malformed]) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        # Number + reason only: the digest hash covers this file, and a title edit is not a
        # number changing. The human summary still carries the title.
        w.writerow(["number", "reason"])
        for m in malformed:
            w.writerow([m.issue.number, m.reason])


def _metric(value: int | None, n: int) -> str:
    return f"{value} (n={n})" if value is not None else f"- (n={n})"


def _sum(values: list[int]) -> str:
    """A sum with no samples is '-' — never '0', which would claim a measurement."""
    return str(sum(values)) if values else "-"


def render_text(
    issues: list[Issue],
    reports: list[Report],
    malformed: list[Malformed],
    groups: list[Group],
    missing: dict[str, int],
    unacknowledged: list[Issue],
    out: str,
) -> str:
    lines = [
        f"Presence reports — {len(issues)} issue(s), {len(reports)} parsed, "
        f"{len(malformed)} malformed/missing",
        "",
    ]
    if not groups:
        lines.append("No parsable reports.")
    by_preset: dict[str, list[Group]] = {}
    for g in groups:
        by_preset.setdefault(g.preset, []).append(g)
    for preset, rows in by_preset.items():
        total = sum(len(g.reports) for g in rows)
        lines.append(f"{preset} — {total} report(s) across {len(rows)} device(s)")
        for g in rows:
            e2 = g.values("e2_p50_ms")
            e1 = g.values("e1_p50_ms")
            turns = g.values("turns")
            lost = g.values("lost")
            answered = g.values("answered_by_voice")
            escalated = g.values("escalated")
            lines.append(
                f"  {g.device} — {len(g.reports)} report(s) · "
                f"e2 {_metric(g.e2_p50, len(e2))} ms · "
                f"e1 {_metric(g.e1_p50, len(e1))} ms · "
                f"turns {_sum(turns)} (n={len(turns)}) · "
                f"lost {_sum(lost)} (n={len(lost)}) · "
                f"voice {_sum(answered)} · escalated {_sum(escalated)} · "
                f"model {g.models}"
            )
        lines.append("")

    noted = [(r.issue, r.notes) for r in reports if r.notes]
    if noted:
        lines.append("Warnings (the report parsed, but not cleanly):")
        for issue, notes in noted:
            loc = f"#{issue.number}" if issue.number else "(no number)"
            lines.append(f"  {loc} — {'; '.join(notes[:4])}")
        lines.append("")

    if malformed:
        lines.append(f"Malformed or missing blocks ({len(malformed)}) — counted, not dropped:")
        for m in malformed:
            loc = f"#{m.issue.number}" if m.issue.number else "(no number)"
            lines.append(f"  {loc} {m.issue.title[:80]} — {m.reason}")
        lines.append("")

    if missing:
        lines.append("Missing metrics (omitted by the block, never read as zero):")
        for key, count in missing.items():
            total = len(reports)
            lines.append(f"  {key}: {count}/{total} parsed report(s) omit it")
        lines.append("")

    if unacknowledged:
        lines.append(f"Unacknowledged reports ({len(unacknowledged)}) — needs a human reply:")
        for issue in unacknowledged:
            loc = f"#{issue.number}" if issue.number else "(no number)"
            lines.append(f"  {loc} {issue.title[:90]}")
    else:
        lines.append("Unacknowledged reports: none — every open report has a reply.")
    lines.append("")
    lines.append(f"CSV: {out}")
    return "\n".join(lines)


def build(
    issues: list[Issue],
    out: str,
    malformed_out: str | None = None,
) -> tuple[list[Report], list[Malformed], list[Group], dict[str, int], list[Issue]]:
    reports: list[Report] = []
    malformed: list[Malformed] = []
    for issue in issues:
        report, bad = parse_issue(issue)
        if report is not None:
            reports.append(report)
        elif bad is not None:
            malformed.append(bad)
    groups = aggregate(reports)
    missing = missing_fields(reports)
    unacknowledged = [
        i for i in issues
        if i.state.lower() != "closed" and not acknowledged(i)
    ]
    write_csv(out, groups)
    write_malformed_csv(malformed_out or out + ".malformed.csv", malformed)
    return reports, malformed, groups, missing, unacknowledged


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Aggregate presence-report issues (#417).")
    ap.add_argument("--out", required=True, help="write the aggregate CSV here")
    ap.add_argument("--malformed-out", default=None,
                    help="write malformed/missing reports here (default: <out>.malformed.csv)")
    ap.add_argument("--fixture", default=None, help="read issues from this JSON instead of gh")
    ap.add_argument("--repo", default=None, help="OWNER/NAME for gh (default: the current repo)")
    ap.add_argument("--json", action="store_true", help="print the aggregate as JSON")
    args = ap.parse_args(argv)

    try:
        issues = fetch_issues(fixture=args.fixture, repo=args.repo)
    except (GhError, OSError, json.JSONDecodeError) as exc:
        print(f"presence-reports: {exc}", file=sys.stderr)
        return 2

    reports, malformed, groups, missing, unacknowledged = build(
        issues, args.out, args.malformed_out)

    if args.json:
        print(json.dumps({
            "issues": len(issues),
            "parsed": len(reports),
            "malformed": [
                {"number": m.issue.number, "title": m.issue.title,
                 "url": m.issue.url, "reason": m.reason}
                for m in malformed
            ],
            "groups": [
                {
                    "preset": g.preset,
                    "device": g.device,
                    "reports": len(g.reports),
                    "issues": [r.issue.number for r in g.reports],
                    "e1_p50_ms": g.e1_p50,
                    "e1_n": len(g.values("e1_p50_ms")),
                    "e2_p50_ms": g.e2_p50,
                    "e2_n": len(g.values("e2_p50_ms")),
                    "turns_sum": sum(g.values("turns")),
                    "turns_n": len(g.values("turns")),
                    "lost_sum": sum(g.values("lost")),
                    "lost_n": len(g.values("lost")),
                    "answered_by_voice_sum": sum(g.values("answered_by_voice")),
                    "escalated_sum": sum(g.values("escalated")),
                    "models": g.models,
                }
                for g in groups
            ],
            "missing": missing,
            "unacknowledged": [
                {"number": i.number, "title": i.title} for i in unacknowledged
            ],
            "notes": [
                {"number": r.issue.number, "notes": r.notes}
                for r in reports if r.notes
            ],
            "csv": args.out,
        }, indent=2, sort_keys=False))
    else:
        print(render_text(issues, reports, malformed, groups, missing,
                          unacknowledged, args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
