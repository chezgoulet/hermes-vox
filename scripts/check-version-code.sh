#!/usr/bin/env bash
# check-version-code.sh — versionCode discipline, enforced instead of remembered.
#
# Nightlies carry the same versionCode as the release they follow, and Android refuses
# a downgrade. So if `testing` ever falls behind a shipped release, every nightly becomes
# uninstallable over that release — which is exactly what happened in 0.8.0 (`33786fa`:
# the bump landed on `main` only, `testing` stayed at 140, and phones on the release could
# not take a nightly without uninstalling first).
#
#   scripts/check-version-code.sh             # development: fail if testing is BEHIND main's release
#   scripts/check-version-code.sh --release   # cut: fail unless this code is ABOVE every shipped code
#
# Reads tags, so it needs history: `git fetch --tags --force`.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

GRADLE=android/app/build.gradle
current="$(grep -m1 'versionCode' "$GRADLE" | awk '{print $2}')"
[ -n "$current" ] || { echo "check-version-code: no versionCode in $GRADLE" >&2; exit 2; }

# Every shipped release tag (vX.Y or vX.Y.Z), newest first. Nightly tags are excluded by
# shape: they never ship as releases.
mapfile -t tags < <(git tag -l 'v[0-9]*' --sort=-v:refname)
if [ "${#tags[@]}" -eq 0 ]; then
  echo "check-version-code: no release tags in this checkout — skipping (fetch tags to enforce)"
  exit 0
fi

latest="${tags[0]}"
shipped="$(git show "$latest:$GRADLE" 2>/dev/null | grep -m1 'versionCode' | awk '{print $2}')"
shipped="${shipped:-0}"

mode="${1:-}"
if [ "$mode" = "--release" ]; then
  # A cut must be strictly above every shipped code, or a phone on the previous release
  # cannot install it. Equality is a re-cut of the same version and is always wrong.
  if [ "$current" -le "$shipped" ]; then
    echo "check-version-code: versionCode $current is not above the latest release ($latest = $shipped)." >&2
    echo "  A cut below or equal to a shipped code cannot install over it. Bump, then cut." >&2
    exit 1
  fi
  echo "check-version-code: $current > $latest ($shipped) — a release may be cut"
  exit 0
fi

# Development: `testing` must not lag the release, or its nightlies are downgrades.
if [ "$current" -lt "$shipped" ]; then
  echo "check-version-code: versionCode $current is BELOW the latest release ($latest = $shipped)." >&2
  echo "  Every nightly from this branch is a downgrade and cannot install over the release." >&2
  echo "  Bring the bump forward onto this branch (never bump on main alone)." >&2
  exit 1
fi
if [ "$current" -eq "$shipped" ]; then
  echo "check-version-code: $current == $latest — nightlies from this branch install over the release,"
  echo "  and the next cut must bump. OK for development."
else
  echo "check-version-code: $current > $latest ($shipped) — ahead of the release, as intended"
fi
