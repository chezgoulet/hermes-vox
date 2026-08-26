#!/bin/bash
# Fetch the pinned sherpa-onnx Android runtime AAR into app/libs (reproducible).
# The AAR is gitignored (49MB third-party native lib); this fetches it on demand.
set -uo pipefail
cd "$(dirname "$0")/.."
AAR=android/app/libs/sherpa-onnx-1.13.6.aar
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar"
if [ -f "$AAR" ]; then echo "have $AAR"; exit 0; fi
mkdir -p android/app/libs
echo "fetching sherpa-onnx runtime…"
curl -sSL -m 600 -o "$AAR" "$URL" || { echo "FAILED to fetch sherpa AAR"; exit 1; }
ls -la "$AAR"
echo "runtime ready"
