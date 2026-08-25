#!/usr/bin/env python3
"""Run a built Ebitengine artifact from the releases directory.

Locates a previously built artifact and executes it with any forwarded CLI
arguments.  Supports host-target artifacts (exe on Windows, native binary on
Linux/macOS).  Web (JS/WASM) artifacts are reported as unsupported for
local execution — they require a browser.

Usage:
    python scripts/run_artifact.py                     # auto-detect host target
    python scripts/run_artifact.py --target windows-amd64  # explicit target
    python scripts/run_artifact.py --list              # list available artifacts
    python scripts/run_artifact.py --args -- --some-flag value   # forward args

Exit codes:
    0  — artifact executed successfully
    1  — artifact failed or not found
    2  — bad usage (unknown flags, unsupported target)
"""

from __future__ import annotations

import argparse
import os
import platform
import signal
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

REPO_ROOT = Path(__file__).resolve().parent.parent
RELEASE_DIR = REPO_ROOT / "releases"

# Map (goos, goarch) → (output name, suffix)
TARGET_MAP = {
    ("linux", "amd64"): ("linux/amd64/app", "latest"),
    ("linux", "arm64"): ("linux/arm64/app", "latest"),
    ("linux", "arm"): ("linux/arm/app", "latest"),
    ("darwin", "amd64"): ("darwin/amd64/app", "latest"),
    ("darwin", "arm64"): ("darwin/arm64/app", "latest"),
    ("windows", "amd64"): ("windows/amd64/app", "latest.exe"),
    ("windows", "386"): ("windows/386/app", "latest.exe"),
    ("js", "wasm"): ("js/wasm/app", "latest.wasm"),
}

# Human-readable names for --target
NAME_TO_KEY = {
    "linux-amd64": ("linux", "amd64"),
    "linux-arm64": ("linux", "arm64"),
    "linux-arm": ("linux", "arm"),
    "darwin-amd64": ("darwin", "amd64"),
    "darwin-arm64": ("darwin", "arm64"),
    "windows-amd64": ("windows", "amd64"),
    "windows-386": ("windows", "386"),
    "js-wasm": ("js", "wasm"),
}


def _emit(step: str, detail: str = "") -> None:
    from datetime import datetime, timezone
    prefix = f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] "
    line = f"{prefix}▶ {step}"
    if detail:
        line += f" — {detail}"
    print(line)


def _host_key() -> tuple[str, str]:
    """Return the (goos, goarch) pair for the current host."""
    os_name = platform.system().lower()
    if os_name == "windows":
        goos = "windows"
    elif os_name in ("darwin", "macos"):
        goos = "darwin"
    else:
        goos = "linux"
    arch = platform.machine().lower()
    if arch in ("x86_64", "amd64"):
        goarch = "amd64"
    elif arch in ("aarch64", "arm64"):
        goarch = "arm64"
    elif arch in ("armv7l", "arm"):
        goarch = "arm"
    elif arch in ("i386", "i686"):
        goarch = "386"
    else:
        goarch = "amd64"
    return (goos, goarch)


def _artifact_path(target_name: str | None) -> Path | None:
    """Resolve the artifact path for a target name or host auto-detect."""
    if target_name:
        key = NAME_TO_KEY.get(target_name)
        if not key:
            return None
    else:
        key = _host_key()

    sub, name = TARGET_MAP.get(key, (None, None))
    if sub is None:
        return None
    return RELEASE_DIR / sub / name


def list_artifacts() -> int:
    """List all artifacts found under releases/."""
    _emit("listing artifacts", str(RELEASE_DIR))
    if not RELEASE_DIR.exists():
        print("  No releases directory found. Run a build first.")
        return 1

    found = 0
    for dirpath, _, filenames in os.walk(RELEASE_DIR):
        for fn in sorted(filenames):
            rel = (Path(dirpath) / fn).relative_to(REPO_ROOT)
            size = (Path(dirpath) / fn).stat().st_size
            print(f"  {rel}  ({size:,} bytes)")
            found += 1
    if found == 0:
        print("  No artifacts found.")
        return 1
    print(f"\n  {found} artifact(s) found.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run a built Ebitengine artifact from the releases directory.",
        allow_abbrev=False,
    )
    parser.add_argument("--target", choices=list(NAME_TO_KEY.keys()),
                        help="Explicit target to run (default: auto-detect host).")
    parser.add_argument("--list", action="store_true",
                        help="List available artifacts and exit.")
    # --args separates forwarded CLI arguments from this script's own flags.
    args, forwarded = parser.parse_known_args()
    if forwarded and forwarded[0] == "--":
        forwarded = forwarded[1:]

    if args.list:
        return list_artifacts()

    # Ctrl+C: controlled interruption, exit 130.
    def _sigint(signum, frame):
        _emit("interrupted", "Ctrl+C received — terminating artifact")
        os._exit(130)

    signal.signal(signal.SIGINT, _sigint)

    # Resolve artifact.
    artifact = _artifact_path(args.target)
    if artifact is None:
        if args.target:
            print(f"✗ Unsupported target: {args.target}", file=sys.stderr)
            print(f"  Supported: {', '.join(NAME_TO_KEY.keys())}", file=sys.stderr)
        else:
            host = _host_key()
            print(f"✗ No artifact for host target {host[0]}-{host[1]}", file=sys.stderr)
            print("  Run a build first, or use --target to specify another target.", file=sys.stderr)
        return 1

    if not artifact.exists():
        _emit("not found", str(artifact))
        print(f"✗ Artifact not found: {artifact}", file=sys.stderr)
        print("  Run a build first:  python scripts/build_orchestrator.py", file=sys.stderr)
        return 1

    # Web artifacts cannot be executed locally.
    if artifact.suffix == ".wasm":
        print("✗ Web (JS/WASM) artifacts require a browser and cannot be run locally.", file=sys.stderr)
        print(f"  Artifact: {artifact}", file=sys.stderr)
        print("  Serve it with a local web server and open in a browser.", file=sys.stderr)
        return 2

    _emit("running artifact", str(artifact))
    _emit("working directory", str(REPO_ROOT))

    # Execute the artifact with forwarded args.
    import subprocess
    cmd = [str(artifact)] + forwarded
    try:
        proc = subprocess.run(cmd, cwd=str(REPO_ROOT))
        exit_code = proc.returncode
    except OSError as e:
        print(f"✗ Failed to execute: {e}", file=sys.stderr)
        return 1

    if exit_code == 0:
        _emit("artifact exited cleanly")
    else:
        _emit("artifact exited", f"exit_code={exit_code}")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
