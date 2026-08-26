#!/bin/bash
# Hermes Vox — blessed model store builder + host.
# Fetches the BLESSED on-device model set from verified open-source endpoints,
# repackages each as a flat ZIP (so the Android app unpacks with built-in
# ZipInputStream — no tar.bz2 deps), writes a manifest, and serves over http.
#
# The app's Model Catalog reads /manifest.json; the default source is this store
# (adjustable in Settings for a house/LAN host).
#
# Usage:
#   bash scripts/model-store.sh build   # fetch + repackage into ./models-store
#   bash scripts/model-store.sh serve   # python3 -m http.server on $PORT
set -uo pipefail
cd "$(dirname "$0")/.."
STORE="$PWD/models-store"
PORT="${MODEL_STORE_PORT:-8899}"

mkdir -p "$STORE/work"
cd "$STORE/work"

fetch(){
  local name="$1" url="$2"
  [ -f "$name" ] && { echo "have $name (cached)"; return; }
  echo "fetch $name <- $url"; curl -sSL -m 900 -o "$name" "$url" || { echo "FAIL $name"; return 1; }
}

# ---- Silero VAD (single onnx, ~640KB) ----
fetch silero_vad.onnx "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
mkdir -p zi_silero && cp silero_vad.onnx zi_silero/
( cd zi_silero && rm -f ../silero-vad.zip && zip -q ../silero-vad.zip silero_vad.onnx )

# ---- Piper TTS (onnx + json, en_US-lessac-medium) ----
fetch piper.onnx "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx"
fetch piper.onnx.json "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"
mkdir -p zi_piper && cp piper.onnx zi_piper/voz.onnx && cp piper.onnx.json zi_piper/voz.onnx.json
( cd zi_piper && rm -f ../piper-lessac.zip && zip -q ../piper-lessac.zip voz.onnx voz.onnx.json )

# ---- Whisper tiny.en (k2-fsa asr tarball -> rezip flat) ----
fetch whisper.tar.bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
rm -rf tw && mkdir tw && tar xjf whisper.tar.bz2 -C tw
W=$(find tw -maxdepth 2 -name "encoder*.onnx" | head -1 | xargs dirname)
echo "whisper dir: $W"
mkdir -p zi_whisper && cp "$W/encoder*.onnx" "$W/decoder*.onnx" "$W/tokens.txt" zi_whisper/ 2>/dev/null
( cd zi_whisper && rm -f ../whisper-tiny.zip && zip -q ../whisper-tiny.zip * )

# ---- Manifest ----
for f in silero-vad piper-lessac whisper-tiny; do
  [ -f "$STORE/$f.zip" ] || { echo "MISSING $f.zip"; exit 1; }
done
sha(){ sha256sum "$1" | cut -d' ' -f1; }
cat > "$STORE/manifest.json" <<JSON
{
  "source": "house",
  "models": [
    {"id":"silero-vad","name":"Silero VAD","kind":"vad","file":"silero-vad.zip","size":$(stat -c%s "$STORE/silero-vad.zip"),"sha256":"$(sha "$STORE/silero-vad.zip")","blessed":true,"order":1,"desc":"Barge-in / wake trigger"},
    {"id":"piper-lessac","name":"Piper · en-US (Lessac)","kind":"tts","file":"piper-lessac.zip","size":$(stat -c%s "$STORE/piper-lessac.zip"),"sha256":"$(sha "$STORE/piper-lessac.zip")","blessed":true,"order":2,"desc":"Warm on-device TTS"},
    {"id":"whisper-tiny","name":"Whisper (tiny.en)","kind":"stt","file":"whisper-tiny.zip","size":$(stat -c%s "$STORE/whisper-tiny.zip"),"sha256":"$(sha "$STORE/whisper-tiny.zip")","blessed":true,"order":3,"desc":"Offline speech-to-text"}
  ]
}
JSON
ls -la "$STORE"/*.zip
echo "STORE-READY at $STORE"
if [ "${1:-}" = "serve" ]; then
  echo "serving on :$PORT"; cd "$STORE" && python3 -m http.server "$PORT"
fi
