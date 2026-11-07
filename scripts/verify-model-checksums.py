#!/usr/bin/env python3
"""Verify the pinned model checksums against the artifacts they name.

The app refuses a download whose sha256 does not match its pin, which protects a user from a
corrupted file — but it cannot tell a *wrong* pin from a tampered artifact. This script can: it
fetches each artifact (or its size) and compares the hash with what `ModelCatalog.kt` pins.

Upstream publishes no `.sha256` sidecar for these releases, so verification means downloading the
bytes. That is slow for the big models and the script says so rather than pretending:

    python3 scripts/verify-model-checksums.py            # models under --max-mb (default 40)
    python3 scripts/verify-model-checksums.py --all      # everything, including the 600 MB ones
    python3 scripts/verify-model-checksums.py --id silero-vad

Exit status: 0 when every model it checked matched, 1 when any mismatched, 2 when it could not
check anything (network, parse failure).
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import tempfile
import urllib.request
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "android/app/src/main/java/com/hermesvox/ModelCatalog.kt"

# ModelSpec("id", "Name", "kind", "file", sizeMB, "desc", blessed, order, "sha256", ...
SPEC = re.compile(
    r'ModelSpec\(\s*"(?P<id>[^"]+)"\s*,\s*"[^"]*"\s*,\s*"(?P<kind>[^"]+)"\s*,\s*"(?P<file>[^"]+)"\s*,'
    r'\s*(?P<size>[0-9.]+)\s*,\s*"(?:[^"\\]|\\.)*"\s*,\s*(?P<blessed>true|false)\s*,\s*\d+\s*,'
    r'\s*"(?P<sha>[^"]+)"',
    re.S,
)
DEFAULT_SOURCE = re.compile(r'DEFAULT_SOURCE\s*=\s*"([^"]+)"')
URL_OVERRIDE = re.compile(r'ModelSpec\((?P<body>(?:[^()]|\([^()]*\))*)\)', re.S)


def models(text: str) -> list[dict]:
    source = DEFAULT_SOURCE.search(text)
    base = source.group(1) if source else ""
    out = []
    for m in SPEC.finditer(text):
        g = m.groupdict()
        # A ModelSpec may carry a trailing url override as the 11th positional argument.
        body = URL_OVERRIDE.search(text, m.start())
        url = ""
        if body:
            trailing = re.findall(r'"(https?://[^"]+)"', body.group("body"))
            if trailing:
                url = trailing[-1]
        out.append({
            "id": g["id"],
            "file": g["file"],
            "size_mb": float(g["size"]),
            "sha256": g["sha"].strip().lower(),
            "url": url or (f"{base}/{g['file']}" if base else ""),
        })
    return out


def digest_of(url: str, max_bytes: int | None) -> tuple[str, int]:
    h = hashlib.sha256()
    total = 0
    req = urllib.request.Request(url, headers={"User-Agent": "hermes-vox-checksum-verify"})
    with urllib.request.urlopen(req, timeout=60) as r, tempfile.SpooledTemporaryFile(max_size=8 << 20) as _:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            total += len(chunk)
            if max_bytes is not None and total > max_bytes:
                raise ValueError(f"artifact is larger than the {max_bytes >> 20} MB limit")
    return h.hexdigest(), total


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="check every model, however large")
    ap.add_argument("--max-mb", type=float, default=40.0, help="skip artifacts above this size (default 40)")
    ap.add_argument("--id", action="append", help="check only this model id (repeatable)")
    args = ap.parse_args()

    if not CATALOG.exists():
        print(f"catalog not found: {CATALOG}", file=sys.stderr)
        return 2
    specs = models(CATALOG.read_text())
    if not specs:
        print("could not parse any ModelSpec from the catalog", file=sys.stderr)
        return 2
    if args.id:
        specs = [s for s in specs if s["id"] in set(args.id)]

    checked = skipped = failed = 0
    for s in specs:
        if not s["url"]:
            print(f"SKIP  {s['id']}: no artifact URL")
            skipped += 1
            continue
        if not args.all and s["size_mb"] > args.max_mb:
            print(f"SKIP  {s['id']}: {s['size_mb']:.1f} MB is above --max-mb {args.max_mb:.0f} (use --all)")
            skipped += 1
            continue
        try:
            got, n = digest_of(s["url"], None if args.all else int(args.max_mb * 1024 * 1024 * 2))
        except Exception as e:  # noqa: BLE001 - the report is the point
            print(f"ERROR {s['id']}: {type(e).__name__}: {e}")
            skipped += 1
            continue
        checked += 1
        if got == s["sha256"]:
            print(f"OK    {s['id']}: {n} bytes, {got[:16]}…")
        else:
            failed += 1
            print(f"FAIL  {s['id']}: pinned {s['sha256'][:16]}… but the artifact hashes to {got[:16]}…")

    print(f"\n{checked} checked, {failed} mismatched, {skipped} skipped")
    if failed:
        return 1
    return 0 if checked else 2


if __name__ == "__main__":
    sys.exit(main())
