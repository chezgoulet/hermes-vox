#!/usr/bin/env bash
# Fetch the pinned sherpa-onnx Android runtime AAR into app/libs (reproducible).
# The AAR is gitignored (49MB third-party native lib); this fetches it on demand
# from the upstream k2-fsa release and verifies it against a pinned SHA-256 so
# a tampered or swapped artifact never reaches the APK.
#
# DEVELOPMENT ONLY (#165). This is the fast path for the gate, CI and nightlies. Releases and
# the F-Droid build use scripts/build-sherpa-from-source.sh (sherpa-onnx from the pinned
# third_party/sherpa-onnx submodule, onnxruntime from Maven Central): F-Droid rejects a prebuilt
# native library fetched at build time, and its rebuild must match the APK we publish.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "fetch-runtime: DEVELOPMENT runtime (prebuilt sherpa-onnx AAR) — not for releases or F-Droid;"
echo "fetch-runtime: those build it from source: bash scripts/build-sherpa-from-source.sh (docs/FDROID.md)"
AAR=android/app/libs/sherpa-onnx-1.13.6.aar
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar"
SHA256="0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698"

verify() { echo "$SHA256  $1" | sha256sum -c --quiet - ; }

if [ -f "$AAR" ]; then
  verify "$AAR" || { echo "sha256 mismatch for existing $AAR — delete it and re-run"; exit 1; }
  echo "have $AAR (verified)"; exit 0
fi
mkdir -p android/app/libs
echo "fetching sherpa-onnx runtime…"
tmp="$AAR.partial"
curl -fsSL -m 600 -o "$tmp" "$URL" || { echo "FAILED to fetch sherpa AAR"; rm -f "$tmp"; exit 1; }
verify "$tmp" || { echo "sha256 mismatch for downloaded sherpa AAR — refusing to use it"; rm -f "$tmp"; exit 1; }
mv "$tmp" "$AAR"
ls -la "$AAR"
echo "runtime ready (verified)"
