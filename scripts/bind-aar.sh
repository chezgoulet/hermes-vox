#!/usr/bin/env bash
# Reproducible `gomobile bind` -> mobile.aar (hermes-vox #138).
#
# The bind is NOT byte-reproducible with gomobile's default flags. Two
# independent sources of nondeterminism, both measured on arm32/arm64/x86/x86_64:
#
#   1. gomobile builds in a fresh `$TMPDIR/gomobile-work-XXXXXXXX` directory and,
#      without -trimpath, Go records absolute source paths. Even two runs from
#      the SAME checkout differ (.debug_*, .gopclntab, .symtab, .rodata).
#      -> `-trimpath` removes all of it, including the random work dir.
#
#   2. gomobile generates a temp module containing
#      `replace github.com/chezgoulet/hermes-vox => <absolute checkout dir>`.
#      Go records that directory in `.go.buildinfo` (mirrored in `.rodata`), and
#      -trimpath does NOT rewrite module replace paths. Two checkouts at
#      different paths therefore differ by exactly the path bytes (and the
#      build-id notes derived from them).
#      -> every bind must run from the same logical directory, so we bind
#      through a canonical symlink and cd -L into it. Go's os.Getwd returns
#      $PWD when it names the same directory, so the recorded path is constant
#      regardless of where the checkout actually lives.
#
# Both mechanisms are required. scripts/repro-check.sh proves the pair and fails
# if either one regresses. See docs/REPRODUCIBLE.md.
#
# Usage: scripts/bind-aar.sh [output.aar] [extra gomobile flags...]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Keep the toolchain pin from scripts/gate.sh; gomobile's temp module otherwise
# triggers a toolchain download mid-bind on a cold host.
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.4}"
OUT="${1:-$ROOT/mobile.aar}"
shift || true
case "$OUT" in
  /*) ;;
  *) OUT="$PWD/$OUT" ;;
esac

# Fixed name (not mktemp): the path is baked into the shipped build info, so it
# must be identical on every builder. Override only if /tmp is unusable.
BIND_ROOT="${HERMES_VOX_BIND_ROOT:-/tmp/hermes-vox-build}"

if [ -e "$BIND_ROOT" ] && [ ! -L "$BIND_ROOT" ]; then
  echo "bind-aar: $BIND_ROOT exists and is not a symlink; remove it or set HERMES_VOX_BIND_ROOT" >&2
  exit 1
fi

# Serialize concurrent binds on the same host. This is load-bearing, not just
# tidiness: if the symlink were swapped while a build was running, $PWD would no
# longer name the process's directory and Go would fall back to the real path.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$BIND_ROOT.lock"
  flock 9
fi

ln -sfn "$ROOT" "$BIND_ROOT"
cd -L "$BIND_ROOT"

# 16 KB page size (#158). NDK r25 links LOAD segments at 4 KB, and a library built
# that way cannot be loaded on a device running 16 KB pages (Android 15+). The NDK's
# clang honours -z max-page-size on the external link gomobile performs; the sherpa
# AAR's own libraries already report 0x4000, so this closes the last gap.
# Verified on the 16 KB emulator image and by llvm-readelf on all four ABIs.
export CGO_LDFLAGS="${CGO_LDFLAGS:-} -Wl,-z,max-page-size=16384"

gomobile bind -trimpath \
  -target android -androidapi 23 -javapkg com.hermesvox \
  "$@" -o "$OUT" github.com/chezgoulet/hermes-vox/mobile
