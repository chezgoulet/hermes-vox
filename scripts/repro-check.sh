#!/usr/bin/env bash
# Prove the gomobile bind is reproducible (hermes-vox #138).
#
# Copies the same source tree to two different absolute paths (different
# lengths on purpose), binds each with scripts/bind-aar.sh, and requires every
# architecture in the two AARs to be byte-identical. Exits non-zero on any
# difference, printing the per-arch sizes/hashes so the mismatch is diagnosable.
#
# Usage: scripts/repro-check.sh [/path/to/workdir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Same defaults as scripts/gate.sh so the check is runnable on its own.
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17.0.12+7}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.2.9519653}"
# Same toolchain pin as scripts/gate.sh; without it a cold host tries to
# download a toolchain mid-bind.
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.4}"
WORK="${1:-$(mktemp -d "${TMPDIR:-/tmp}/hermes-vox-repro-XXXXXX")}"
mkdir -p "$WORK"

A="$WORK/a"
B="$WORK/b-with-a-much-longer-checkout-path"

copy_tree() {
  mkdir -p "$2"
  tar -C "$1" \
      --exclude=./.git --exclude=./.tools --exclude=./.cache \
      --exclude=./mobile.aar --exclude='./*.aar' \
      -cf - . | tar -C "$2" -xf -
}

echo "== repro-check: staging two checkouts under $WORK =="
rm -rf "$A" "$B"
copy_tree "$ROOT" "$A"
copy_tree "$ROOT" "$B"

echo "== repro-check: bind #1 ($A) =="
(cd "$A" && bash scripts/bind-aar.sh "$WORK/one.aar")

echo "== repro-check: bind #2 ($B) =="
(cd "$B" && bash scripts/bind-aar.sh "$WORK/two.aar")

echo "== repro-check: comparing =="
extract() {
  rm -rf "$2"
  mkdir -p "$2"
  unzip -q -o "$1" -d "$2"
  for f in "$2"/jni/*/libgojni.so; do
    arch="$(basename "$(dirname "$f")")"
    printf '%-12s %10d %s\n' "$arch" "$(wc -c < "$f")" "$(sha256sum "$f" | cut -c1-16)" | tee -a "$3"
  done
  sort -o "$3" "$3"
}

: > "$WORK/one.txt"
: > "$WORK/two.txt"
extract "$WORK/one.aar" "$WORK/x-one" "$WORK/one.txt"
extract "$WORK/two.aar" "$WORK/x-two" "$WORK/two.txt"

printf '\n%-12s %10s %10s\n' arch bind-1 bind-2
paste "$WORK/one.txt" "$WORK/two.txt" | awk '{printf "%-12s %10s %10s  %s\n", $1, $2, $5, ($2==$5 && $3==$6 ? "same" : "DIFF")}'

aar1="$(sha256sum "$WORK/one.aar" | cut -d' ' -f1)"
aar2="$(sha256sum "$WORK/two.aar" | cut -d' ' -f1)"

if [ "$aar1" != "$aar2" ] || ! cmp -s "$WORK/one.txt" "$WORK/two.txt"; then
  echo
  echo "REPRO-FAIL: mobile.aar differs between checkouts" >&2
  echo "  one.aar sha256 $aar1" >&2
  echo "  two.aar sha256 $aar2" >&2
  echo "  workdir kept for inspection: $WORK" >&2
  exit 1
fi

echo
echo "REPRO-OK: all arches byte-identical; mobile.aar sha256 $aar1"
rm -rf "$WORK"
