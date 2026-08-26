#!/bin/bash
set -uo pipefail
cd "$(dirname "$0")/.."
STORE="$PWD/models-store"; W="$STORE/work"
set -e
cd "$W"
# Repackage whisper with standard loader names.
rm -rf zi_whisper; mkdir zi_whisper
SRC="tw/sherpa-onnx-whisper-tiny.en"
cp "$SRC/tiny.en-encoder.onnx" zi_whisper/encoder.onnx
cp "$SRC/tiny.en-decoder.onnx"  zi_whisper/decoder.onnx
cp "$SRC/tiny.en-tokens.txt"    zi_whisper/tokens.txt
( cd zi_whisper && rm -f ../whisper-tiny.zip && zip -q ../whisper-tiny.zip encoder.onnx decoder.onnx tokens.txt )
# Move all zips + manifest to the store root.
for z in silero-vad piper-lessac whisper-tiny; do cp -f "$W/$z.zip" "$STORE/$z.zip"; done
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
echo "=== store root ==="; ls -la "$STORE"/*.zip; echo "=== manifest ==="; cat "$STORE/manifest.json"
