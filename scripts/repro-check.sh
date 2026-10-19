#!/usr/bin/env bash
# Hermes Vox — reproducible APK check (#166).
#
# Copies this tree to two different absolute paths, builds an UNSIGNED release
# APK in each, and compares the payloads byte for byte. Nothing is stripped or
# normalised: the only things ignored are META-INF/* (signature metadata) and the
# APK signing block, because this check deliberately builds unsigned artifacts.
#
# Usage:
#   scripts/repro-check.sh                 # copy twice, build twice, compare
#   scripts/repro-check.sh --compare A B   # compare two existing APKs
#
# Exit 0 only when every compared entry matches. On a difference it prints the
# first differing entry and exits 1. The comparator self-test (a one-byte tamper)
# runs after a successful build comparison so a vacuous comparator cannot pass.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK_ROOT="${REPRO_WORK_ROOT:-/tmp/opencode/vox-repro}"
JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"
# The release guard must not trip on a developer's shell; it is about shipping
# keys, not about this unsigned build.
unset HERMES_VOX_API_KEY || true

die() { echo "repro: $*" >&2; exit 2; }
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java"
[ -d "$ANDROID_HOME" ] || die "ANDROID_HOME=$ANDROID_HOME does not exist"

# ---------------------------------------------------------------------------
# Comparator: ordered ZIP entries + CRC-32 + raw compressed bytes.
#
# Python's zipfile reads the central directory, so the APK signing block (which
# sits outside it) is naturally ignored. META-INF/* is skipped by name. For each
# remaining entry we compare the metadata that affects the bytes (name, method,
# flags, DOS timestamp, CRC, sizes, extra/comment/attributes) and the raw
# compressed bytes read from the local file header.
# ---------------------------------------------------------------------------
compare_zip() {
  python3 - "$1" "$2" <<'PY'
import hashlib
import struct
import sys
import zipfile

a_path, b_path = sys.argv[1], sys.argv[2]

def load(path):
    zf = zipfile.ZipFile(path)
    entries = []
    for zi in zf.infolist():
        if zi.filename.startswith("META-INF/"):
            continue
        with open(path, "rb") as f:
            f.seek(zi.header_offset)
            lh = f.read(30)
            if len(lh) != 30 or lh[:4] != b"PK\x03\x04":
                raise SystemExit(f"repro: bad local header for {zi.filename} in {path}")
            name_len, extra_len = struct.unpack("<HH", lh[26:30])
            data_off = zi.header_offset + 30 + name_len + extra_len
            f.seek(data_off)
            raw = f.read(zi.compress_size)
        if len(raw) != zi.compress_size:
            raise SystemExit(f"repro: short read for {zi.filename} in {path}")
        entries.append((zi, raw))
    zf.close()
    return entries

def describe(zi):
    return (
        zi.filename,
        zi.compress_type,
        zi.flag_bits,
        zi.date_time,
        zi.CRC,
        zi.compress_size,
        zi.file_size,
        zi.external_attr,
        zi.internal_attr,
        zi.create_system,
        zi.create_version,
        zi.extract_version,
        zi.extra,
        zi.comment,
    )

def first_diff(path_a, path_b):
    ea = load(path_a)
    eb = load(path_b)
    for i in range(max(len(ea), len(eb))):
        if i >= len(ea):
            return f"only in B: {eb[i][0].filename}"
        if i >= len(eb):
            return f"only in A: {ea[i][0].filename}"
        za, ra = ea[i]
        zb, rb = eb[i]
        if za.filename != zb.filename:
            return f"entry order/name differs at #{i}: {za.filename!r} != {zb.filename!r}"
        da, db = describe(za), describe(zb)
        if da != db:
            fields = ("name", "method", "flags", "time", "crc", "csize", "size",
                      "extattr", "intattr", "createsys", "createver", "extractver",
                      "extra", "comment")
            for name, va, vb in zip(fields, da, db):
                if va != vb:
                    return f"{za.filename}: {name} differs: {va!r} != {vb!r}"
        if ra != rb:
            return (f"{za.filename}: compressed bytes differ "
                    f"(sha256 {hashlib.sha256(ra).hexdigest()[:16]} != "
                    f"{hashlib.sha256(rb).hexdigest()[:16]}, {len(ra)} bytes)")
    return None

diff = first_diff(a_path, b_path)
if diff:
    print(f"repro: FIRST DIFFERENCE: {diff}", file=sys.stderr)
    sys.exit(1)
print(f"repro: payloads identical ({len(load(a_path))} entries compared)")
PY
}

compare_apks() {
  local a="$1" b="$2"
  [ -f "$a" ] || die "missing APK: $a"
  [ -f "$b" ] || die "missing APK: $b"
  if command -v apksigcopier >/dev/null 2>&1; then
    echo "repro: comparing with apksigcopier"
    # --unsigned: neither APK carries a signature by design.
    if apksigcopier compare --unsigned "$a" "$b"; then
      echo "repro: payloads identical (apksigcopier)"
    else
      echo "repro: APK payloads differ (apksigcopier)" >&2
      return 1
    fi
  else
    compare_zip "$a" "$b"
  fi
}

# Flip one byte in the compressed data of the first non-META-INF entry. Used to
# prove the comparator is not vacuous.
tamper_apk() {
  local src="$1" dst="$2"
  python3 - "$src" "$dst" <<'PY'
import struct
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
with open(src, "rb") as f:
    data = bytearray(f.read())
with zipfile.ZipFile(src) as zf:
    target = next(z for z in zf.infolist()
                  if not z.filename.startswith("META-INF/") and z.compress_size > 0)
    fh = open(src, "rb")
    fh.seek(target.header_offset)
    lh = fh.read(30)
    name_len, extra_len = struct.unpack("<HH", lh[26:30])
    off = target.header_offset + 30 + name_len + extra_len + target.compress_size - 1
    fh.close()
data[off] ^= 0xFF
with open(dst, "wb") as f:
    f.write(data)
print(target.filename)
PY
}

if [ "${1:-}" = "--compare" ]; then
  [ $# -eq 3 ] || die "usage: $0 --compare APK_A APK_B"
  if ! compare_apks "$2" "$3"; then exit 1; fi
  exit 0
fi
[ $# -eq 0 ] || die "usage: $0 [--compare APK_A APK_B]"

avail_kb="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
[ -n "$avail_kb" ] || die "cannot determine free space on $ROOT"
if [ "$avail_kb" -lt 3145728 ]; then
  die "less than 3 GB free on $ROOT (${avail_kb} KiB); refusing to build twice"
fi

echo "== repro: preparing two checkouts under $WORK_ROOT =="
rm -rf "$WORK_ROOT"
mkdir -p "$WORK_ROOT/a" "$WORK_ROOT/b"

# Fetch the pinned runtime once here; the copies inherit the verified AAR.
bash "$ROOT/scripts/fetch-runtime.sh"

copy_tree() {
  local dest="$1"
  # Preserve modes/mtimes (the build reads source files), but never copy prior
  # build output or VCS metadata into the checkouts.
  tar -C "$ROOT" -cf - \
    --exclude='./.git' \
    --exclude='./.gradle' \
    --exclude='./build' \
    --exclude='./android/.gradle' \
    --exclude='./android/build' \
    --exclude='./android/app/build' \
    --exclude='./.tools' \
    --exclude='*.apk' \
    . | tar -C "$dest" -xf -
}

build_apk() {
  local dir="$1" log="$2"
  ( cd "$dir/android" && ./gradlew --no-daemon --no-build-cache clean assembleRelease \
      -Phermesvox.unsigned ) >"$log" 2>&1 \
    || { tail -n 60 "$log" >&2; die "release build failed in $dir"; }
  find "$dir/android/app/build/outputs/apk/release" -maxdepth 1 -name '*.apk' -print -quit
}

copy_tree "$WORK_ROOT/a"
copy_tree "$WORK_ROOT/b"

echo "== repro: build A ($WORK_ROOT/a) =="
APK_A="$(build_apk "$WORK_ROOT/a" "$WORK_ROOT/a.log")"
[ -n "$APK_A" ] || die "build A produced no APK"

echo "== repro: build B ($WORK_ROOT/b) =="
APK_B="$(build_apk "$WORK_ROOT/b" "$WORK_ROOT/b.log")"
[ -n "$APK_B" ] || die "build B produced no APK"

sha256sum "$APK_A" "$APK_B" | tee "$WORK_ROOT/hashes.txt"
echo "repro: APK_A=$APK_A"
echo "repro: APK_B=$APK_B"

echo "== repro: compare payloads =="
compare_apks "$APK_A" "$APK_B"

if [ "${REPRO_SKIP_SELFTEST:-0}" != "1" ]; then
  echo "== repro: comparator self-test (one-byte tamper must be detected) =="
  TAMPERED="$WORK_ROOT/tampered.apk"
  entry="$(tamper_apk "$APK_A" "$TAMPERED")"
  if compare_apks "$APK_A" "$TAMPERED" >/dev/null 2>&1; then
    die "comparator self-test FAILED: a one-byte change in $entry was not detected"
  fi
  rm -f "$TAMPERED"
  echo "repro: comparator self-test OK (detected the tampered $entry)"
fi

echo "REPRO-OK"
