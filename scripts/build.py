#!/usr/bin/env python3
"""Canonical no-flag cross-platform build driver for Ebitengine boilerplate.

Design principles (per plan and agentic-pipelines invariants):
  - NO FLAG entry: rejects any target-specific flags; builds every
    feasible target from the current host and reports infeasible ones
    with reasons.
  - Deterministic-first: the target matrix is a static table; the
    LLM is never consulted for target selection or build success.
  - Progress reporting: every material stage emits an operator-visible
    line (framework invariant).
  - Evidence preservation: a structured build report is persisted to
    reports/build/ for downstream pipeline stages.
  - Ctrl+C handling: reports the interruption visibly and exits 130.

Usage:
    python scripts/build.py            # build every possible target
    python scripts/build.py --verify   # build then verify artifact matrix
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ── Constants ─────────────────────────────────────────────────────────────────

REPO_ROOT = Path(__file__).resolve().parent.parent
RELEASE_DIR = REPO_ROOT / "releases"
REPORTS_DIR = REPO_ROOT / "reports" / "build"
TOOLS_DIR = REPO_ROOT / ".tools"
CACHE_DIR = TOOLS_DIR / "cache"
GO_TOOLCHAIN = "go1.26.4"

# ── Target matrix ─────────────────────────────────────────────────────────────
# Each entry describes a (goos, goarch) pair that `go build` can cross-compile
# for Ebitengine v2.9.9.  The `feasible` field is set at runtime by
# `_evaluate_feasibility` based on what the current Go toolchain + host can
# actually produce.

@dataclass
class Target:
    goos: str
    goarch: str
    name: str
    output_ext: str          # ".exe" or ""
    output_name: str         # "latest" or "latest.exe"
    target: str = "app"
    feasible: bool = True
    infeasible_reason: str = ""
    build_cmd: list[str] = field(default_factory=list)
    output_path: Path | None = None
    build_seconds: float = 0.0
    build_ok: bool = False
    build_log: str = ""


def _host_os() -> str:
    system = platform.system().lower()
    if system == "windows":
        return "windows"
    if system == "linux":
        return "linux"
    if system == "darwin":
        return "darwin"
    return system


def _host_arch() -> str:
    machine = platform.machine().lower()
    mapping = {
        "amd64": "amd64", "x86_64": "amd64", "x64": "amd64",
        "arm64": "arm64", "aarch64": "arm64",
        "arm": "arm",
        "386": "386", "i386": "386", "i686": "386",
    }
    return mapping.get(machine, "amd64")


def _detect_go_toolchain() -> str:
    """Return a usable Go executable path or an unavailable marker."""
    go = shutil.which("go")
    if go:
        return go

    pinned = shutil.which(GO_TOOLCHAIN)
    if pinned:
        return pinned
    return "unavailable"


def _go_environment() -> dict[str, str]:
    """Return the isolated Go environment used by both probes and builds."""
    go_cache = CACHE_DIR / "go-build"
    go_mod_cache = CACHE_DIR / "go-mod"
    go_env = TOOLS_DIR / "goenv"
    go_cache.mkdir(parents=True, exist_ok=True)
    go_mod_cache.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ)
    environment.update({
        "GOCACHE": str(go_cache),
        "GOMODCACHE": str(go_mod_cache),
        "GOENV": str(go_env),
    })
    return environment


def _go_version() -> str:
    """Return the active Go version for operator-visible reports."""
    go = _detect_go_toolchain()
    if go == "unavailable":
        return go
    try:
        result = subprocess.run(
            [go, "env", "GOVERSION"],
            capture_output=True, text=True, timeout=10, env=_go_environment(),
        )
        if result.returncode == 0:
            version = result.stdout.strip()
            if version:
                return version
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    try:
        result = subprocess.run(
            [go, "version"], capture_output=True, text=True, timeout=10, env=_go_environment(),
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    return "unknown"


def _cgo_compiler() -> str | None:
    """Return the configured local C compiler when it is available on PATH."""
    go = _detect_go_toolchain()
    if go == "unavailable":
        return None
    try:
        result = subprocess.run(
            [go, "env", "CC"], capture_output=True, text=True, timeout=10, env=_go_environment(),
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    compiler = result.stdout.strip().split(maxsplit=1)[0]
    return shutil.which(compiler) if compiler else None


def _android_missing_components() -> list[str]:
    """Return the Android tooling a gomobile .apk build needs but the host lacks."""
    missing: list[str] = []
    if not (os.environ.get("JAVA_HOME") or shutil.which("java")):
        missing.append("JDK")
    if not shutil.which("gomobile"):
        missing.append("gomobile")
    and_home = os.environ.get("ANDROID_HOME") or ""
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.path.join(and_home, "ndk")
    has_ndk = bool(ndk and os.path.isdir(ndk) and any(os.scandir(ndk)))
    if not (and_home and has_ndk):
        missing.append("Android SDK/NDK")
    return missing


def _evaluate_feasibility(target: Target) -> None:
    """Mark a target feasible or infeasible with a reason.

    Ebitengine cross-compilation rules (v2.9.9):
      - CGO is required for most Ebitengine backends (OpenAL, GLFW, etc.).
      - Without a cross-compiler toolchain, only the host platform is
        reliably buildable. The no-flag entry point conservatively reports
        non-host CGO targets as infeasible instead of requiring environment
        variables or per-target configuration.
      - Pure-Go targets (e.g., js/wasm for web) are always feasible.
    """
    host_os = _host_os()
    host_arch = _host_arch()

    if _detect_go_toolchain() == "unavailable":
        target.feasible = False
        target.infeasible_reason = "Go toolchain is not available on PATH"
        return
    # JS/WASM is always feasible (pure Go).
    if target.goos == "js" and target.goarch == "wasm":
        return

    # Android/gomobile requires a JDK, gomobile, and the Android SDK/NDK.
    if target.goos == "android":
        missing = _android_missing_components()
        if missing:
            target.feasible = False
            target.infeasible_reason = (
                f"Android ({target.goos}/{target.goarch}) needs a JDK, gomobile, "
                f"and the Android SDK/NDK; this host lacks: {', '.join(missing)}. "
                "Provision them (gomobile init, ndk, JAVA_HOME, ANDROID_HOME) "
                "and it becomes POSSIBLE."
            )
        return

    if target.goos == host_os and target.goarch == host_arch:
        return  # native target — the actual build is the authoritative capability check

    # Conservatively infeasible without a cross toolchain.
    target.feasible = False
    target.infeasible_reason = (
        f"cross-compile to {target.goos}/{target.goarch} requires a "
        f"CGO cross-compiler toolchain on this host "
        f"({_host_os()}/{_host_arch()}); the no-flag entry point builds "
        "only targets it can establish as locally supported"
    )


# ── Canonical target matrix ───────────────────────────────────────────────────

def build_target_matrix() -> list[Target]:
    """Return the static target matrix for Ebitengine v2.9.9.

    Every target is evaluated for feasibility at runtime.
    """
    matrix = [
        Target(goos="linux",   goarch="amd64", name="linux-amd64",
               output_ext="",     output_name="latest"),
        Target(goos="linux",   goarch="arm64", name="linux-arm64",
               output_ext="",     output_name="latest"),
        Target(goos="linux",   goarch="arm",   name="linux-arm",
               output_ext="",     output_name="latest"),
        Target(goos="darwin",  goarch="amd64", name="darwin-amd64",
               output_ext="",     output_name="latest"),
        Target(goos="darwin",  goarch="arm64", name="darwin-arm64",
               output_ext="",     output_name="latest"),
        Target(goos="windows", goarch="amd64", name="windows-amd64",
               output_ext=".exe", output_name="latest.exe"),
        Target(goos="windows", goarch="386",   name="windows-386",
               output_ext=".exe", output_name="latest.exe"),
        # Hermes Vox: Android via gomobile (ROADMAP Phase 3). Output is an .apk.
        Target(goos="android", goarch="arm64", name="android-arm64",
               output_ext=".apk", output_name="latest.apk"),
        Target(goos="android", goarch="arm", name="android-arm",
               output_ext=".apk", output_name="latest.apk"),
        Target(goos="js",      goarch="wasm",  name="js-wasm",
               output_ext=".wasm", output_name="latest.wasm"),
    ]
    for t in matrix:
        _evaluate_feasibility(t)
    return matrix


def _print_feasibility_report(matrix: list[Target]) -> None:
    """Print every target's local feasibility before build execution."""
    go = _detect_go_toolchain()
    compiler = _cgo_compiler()
    print("▶ Local build environment")
    print(f"  Host platform: {_host_os()}/{_host_arch()}")
    print(f"  Go executable: {go}")
    print(f"  Go version: {_go_version()}")
    print(f"  Configured CGO compiler: {compiler or 'not detected on PATH (advisory only)'}")
    print("  Policy: attempt the native target and JS/WASM; report non-host CGO targets without attempting unsupported cross-compilation. A completed build is the final capability check for native CGO support.")
    print("\n▶ Target feasibility report")
    for target in matrix:
        artifact = RELEASE_DIR / target.goos / target.goarch / target.target / target.output_name
        if target.feasible:
            if target.goos == "js":
                reason = "pure-Go WebAssembly target; browser serving and packaging are outside this build"
            else:
                reason = "native host target; it will be attempted locally and the build result is the authoritative capability check"
            status = "POSSIBLE"
        else:
            reason = target.infeasible_reason
            status = "NOT POSSIBLE"
        print(f"  [{status}] {target.name} ({target.goos}/{target.goarch})")
        print(f"    Output: {artifact.relative_to(REPO_ROOT)}")
        print(f"    Why: {reason}")


# ── Build execution ───────────────────────────────────────────────────────────

def _run_build(target: Target) -> bool:
    """Execute a single `go build` for the given target.

    Returns True on success.
    """
    out_dir = RELEASE_DIR / target.goos / target.goarch / target.target
    out_dir.mkdir(parents=True, exist_ok=True)
    target.output_path = out_dir / target.output_name

    # Determine build command.
    go = _detect_go_toolchain()
    if go == "unavailable":
        target.infeasible_reason = "Go toolchain not found on PATH"
        target.feasible = False
        return False

    # For Android, the build is gomobile (not `go build`), producing an .apk.
    if target.goos == "android":
        gomobile = shutil.which("gomobile")
        if not gomobile:
            target.infeasible_reason = "gomobile not found on PATH (run `go install golang.org/x/mobile/cmd/gomobile@latest`)"
            target.feasible = False
            return False
        out_dir.mkdir(parents=True, exist_ok=True)
        target.output_path = out_dir / target.output_name
        env = _go_environment()
        cmd = [gomobile, "build", "-target", f"{target.goos}/{target.goarch}",
               "-androidapi", "23", "-o", str(target.output_path), "./cmd/app"]
        print(f"  $ gomobile build -target={target.goos}/{target.goarch} -o {target.output_path.name}")
        start = time.monotonic()
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=900,
                                    cwd=str(REPO_ROOT), env=env)
            target.build_seconds = time.monotonic() - start
            target.build_log = (result.stdout or "") + (result.stderr or "")
            if result.returncode == 0:
                target.build_ok = True
                return True
            target.infeasible_reason = result.stderr.strip()[-500:]
            return False
        except subprocess.TimeoutExpired:
            target.build_seconds = time.monotonic() - start
            target.infeasible_reason = "Android build timed out after 900s"
            return False
        except (FileNotFoundError, OSError) as exc:
            target.infeasible_reason = str(exc)
            return False

    # For js/wasm, use GOOS=js GOARCH=wasm with pure-Go.
    env = _go_environment()
    if target.goos == "js" and target.goarch == "wasm":
        env["GOOS"] = "js"
        env["GOARCH"] = "wasm"
        env["CGO_ENABLED"] = "0"
    else:
        env["GOOS"] = target.goos
        env["GOARCH"] = target.goarch
        # Ebitengine requires CGO for most backends.
        env["CGO_ENABLED"] = "1"

    cmd = [go, "build", "-buildvcs=false", "-o", str(target.output_path), "./cmd/app"]

    print(f"  $ GOOS={env['GOOS']} GOARCH={env['GOARCH']} CGO_ENABLED={env['CGO_ENABLED']} "
          f"go build -o {target.output_path.name}")

    start = time.monotonic()
    try:
        result = subprocess.run(
            cmd,
            capture_output=True, text=True, timeout=300,
            cwd=str(REPO_ROOT),
            env=env,
        )
        target.build_seconds = time.monotonic() - start
        target.build_log = (result.stdout or "") + (result.stderr or "")
        if result.returncode == 0:
            target.build_ok = True
            return True
        else:
            target.infeasible_reason = result.stderr.strip()[-500:]
            return False
    except subprocess.TimeoutExpired:
        target.build_seconds = time.monotonic() - start
        target.infeasible_reason = "build timed out after 300s"
        return False
    except (FileNotFoundError, OSError) as exc:
        target.infeasible_reason = str(exc)
        return False


def _verify_artifacts(matrix: list[Target]) -> bool:
    """Verify that every built target produced a non-empty artifact.

    Returns True if all verifiable artifacts pass.
    """
    ok = True
    print(f"\n▶ Verifying artifact matrix")
    for t in matrix:
        if not t.feasible:
            continue
        if not t.build_ok:
            print(f"  ✗ {t.name}: build did not produce an artifact ({t.infeasible_reason})")
            ok = False
            continue
        if t.output_path is None or not t.output_path.exists():
            print(f"  ✗ {t.name}: artifact missing at {t.output_path}")
            ok = False
            continue
        size = t.output_path.stat().st_size
        if size == 0:
            print(f"  ✗ {t.name}: artifact is empty")
            ok = False
            continue
        print(f"  ✓ {t.name}: {t.output_path.name} ({size:,} bytes)")
    if ok:
        print("✓ All built artifacts verified.")
    else:
        print("✗ Some artifacts failed verification.")
    return ok


# ── Report ────────────────────────────────────────────────────────────────────

def _persist_report(matrix: list[Target], verify_ok: bool | None) -> Path:
    """Persist a structured build report to reports/build/."""
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report_path = REPORTS_DIR / f"build-report-{ts}.json"

    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "go_toolchain": _go_version(),
        "host": {"os": _host_os(), "arch": _host_arch()},
        "targets": [
            {
                **asdict(t),
                "output_path": str(t.output_path) if t.output_path else None,
            }
            for t in matrix
        ],
        "built_count": sum(1 for t in matrix if t.build_ok),
        "total_count": len(matrix),
        "verification": "pass" if verify_ok else ("fail" if verify_ok is False else None),
    }
    report_path.write_text(json.dumps(report, indent=2))
    return report_path


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(
        description="No-flag cross-platform build driver for Ebitengine boilerplate.",
    )
    parser.add_argument(
        "--verify", action="store_true",
        help="Build then verify the artifact matrix.",
    )
    # Reject any target flags (no-flag design principle).
    parser.add_argument(
        "--target", action="store_true", default=False,
        help=argparse.SUPPRESS,  # hidden: we reject it
    )
    args = parser.parse_args()

    if args.target:
        print("✗ --target is not supported. This driver builds ALL possible targets.", file=sys.stderr)
        return 2

    # Ctrl+C handler (framework invariant: exit 130).
    def _sigint_handler(signum, frame):
        print("\n⚠ Build interrupted by Ctrl+C — state preserved.", file=sys.stderr)
        os._exit(130)

    signal.signal(signal.SIGINT, _sigint_handler)

    print(f"▶ Ebitengine build driver — {datetime.now(timezone.utc).isoformat()}\n")

    matrix = build_target_matrix()

    feasible = [t for t in matrix if t.feasible]
    _print_feasibility_report(matrix)
    print(f"\n▶ Build decision: {len(feasible)}/{len(matrix)} targets are possible in this local environment.\n")

    # Build each feasible target.
    print("▶ Building targets...")
    for t in feasible:
        print(f"\n  [{t.name}]")
        _run_build(t)
        if t.build_ok:
            print(f"    ✓ Built {t.output_path.name} in {t.build_seconds:.1f}s")
        else:
            print(f"    ✗ Failed: {t.infeasible_reason}")

    # Verify.
    verify_ok = None
    if args.verify:
        verify_ok = _verify_artifacts(matrix)

    # Persist report.
    report_path = _persist_report(matrix, verify_ok)
    print(f"\n▶ Report: {report_path}")

    built = sum(1 for t in matrix if t.build_ok)
    print(f"▶ Summary: {built}/{len(matrix)} targets built successfully.")

    return 0 if (verify_ok is None or verify_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
