#!/usr/bin/env bash
# #165 (D8, F-Droid path B): build the sherpa-onnx runtime from source instead of fetching
# the prebuilt AAR (scripts/fetch-runtime.sh) — the pattern F-Droid rejects. onnxruntime comes
# from Maven Central (com.microsoft.onnxruntime:onnxruntime-android), which F-Droid's
# inclusion policy allows: its headers and libonnxruntime.so are what the JNI is linked
# against here, and the same artifact is the app's Gradle dependency at runtime.
#
# Output (gitignored): android/sherpa-src/{jniLibs/<abi>/libsherpa-onnx-jni.so, kotlin/*.kt}
# Use it:  cd android && ./gradlew -Phermesvox.sherpaSource=../sherpa-src assembleRelease
#
# Needs: git, cmake (3.22+), the Android NDK (ANDROID_NDK, default: the newest under
# $ANDROID_HOME/ndk). One ABI takes a few minutes on an 8-core host.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SHERPA_TAG=v1.13.6
SHERPA_COMMIT=1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911
ORT_VERSION=1.27.0   # must match the Gradle dependency in android/app/build.gradle
ORT_SHA256=077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb
ABIS="${SHERPA_ABIS:-arm64-v8a armeabi-v7a}"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK="${ANDROID_NDK:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n1)}"
[ -d "$ANDROID_NDK" ] || { echo "build-sherpa: no NDK (set ANDROID_NDK)" >&2; exit 2; }

WORK="${SHERPA_WORK:-$ROOT/android/.sherpa-build}"
OUT="$ROOT/android/sherpa-src"
mkdir -p "$WORK"

# 1. sherpa-onnx at the pinned commit — a tag can move, a commit cannot.
if [ ! -d "$WORK/src/.git" ]; then
  git clone -q --depth 1 -b "$SHERPA_TAG" https://github.com/k2-fsa/sherpa-onnx.git "$WORK/src"
fi
got="$(git -C "$WORK/src" rev-parse HEAD)"
[ "$got" = "$SHERPA_COMMIT" ] || { echo "build-sherpa: $SHERPA_TAG is $got, pinned $SHERPA_COMMIT — refusing" >&2; exit 1; }

# 2. onnxruntime from Maven Central, checksum-pinned.
ORT="$WORK/ort-$ORT_VERSION"
if [ ! -f "$ORT/ort.aar" ]; then
  mkdir -p "$ORT"
  curl -fsSL -o "$ORT/ort.aar.partial" \
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar"
  echo "$ORT_SHA256  $ORT/ort.aar.partial" | sha256sum -c --quiet - || { rm -f "$ORT/ort.aar.partial"; echo "build-sherpa: onnxruntime sha256 mismatch" >&2; exit 1; }
  mv "$ORT/ort.aar.partial" "$ORT/ort.aar"
  (cd "$ORT" && unzip -q -o ort.aar 'jni/*' 'headers/*')
fi

# 3. The JNI library per ABI, linked against that onnxruntime. Source paths are mapped to a
#    fixed prefix: without it the library embeds this checkout's absolute path (every
#    __FILE__ in a log line), so a rebuild elsewhere — F-Droid's — could never match our
#    APK byte for byte (#166, D15), and the build host's path would ship in the binary.
#    The NDK toolchain ignores CFLAGS/CXXFLAGS from the environment, so the flag is handed to
#    CMake on the command line, through a patched copy of upstream's build script; each ABI
#    starts from a clean build directory so it is a fresh configure.
# The whole work directory is mapped, not only the source: the onnxruntime headers live
# beside it. And the library is linked without a build-id: lld hashes the unstripped output,
# debug info included, so two paths gave libraries identical but for those 20 bytes.
MAP="-ffile-prefix-map=$WORK=/sherpa-build"
rm -rf "$OUT"; mkdir -p "$OUT/kotlin"
for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) script=build-android-arm64-v8a.sh; dir=build-android-arm64-v8a ;;
    armeabi-v7a) script=build-android-armv7-eabi.sh; dir=build-android-armv7-eabi ;;
    *) echo "build-sherpa: unsupported ABI $abi" >&2; exit 2 ;;
  esac
  echo "== sherpa-onnx $SHERPA_TAG for $abi =="
  rm -rf "$WORK/src/$dir"
  sed "s|^cmake -DCMAKE_TOOLCHAIN_FILE=|cmake -DCMAKE_C_FLAGS=\"$MAP\" -DCMAKE_CXX_FLAGS=\"$MAP\" -DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=none -DCMAKE_TOOLCHAIN_FILE=|" \
    "$WORK/src/$script" > "$WORK/src/vox-$script"
  grep -q "file-prefix-map" "$WORK/src/vox-$script" || { echo "build-sherpa: could not patch $script" >&2; exit 1; }
  (cd "$WORK/src" && SHERPA_ONNXRUNTIME_LIB_DIR="$ORT/jni/$abi" SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT/headers" \
     SHERPA_ONNX_ENABLE_TTS=ON SHERPA_ONNX_ENABLE_C_API=ON bash "vox-$script" > "$WORK/build-$abi.log" 2>&1) \
     || { echo "build-sherpa: $abi failed — see $WORK/build-$abi.log" >&2; exit 1; }
  mkdir -p "$OUT/jniLibs/$abi"
  cp "$WORK/src/$dir/install/lib/libsherpa-onnx-jni.so" "$OUT/jniLibs/$abi/"
  leaks=$(strings "$OUT/jniLibs/$abi/libsherpa-onnx-jni.so" | grep -cF "$WORK" || true)
  if [ "$leaks" != "0" ]; then
    echo "build-sherpa: $abi library still embeds $WORK — the prefix map did not apply" >&2; exit 1
  fi
done

# 4. The Kotlin API the app compiles against (the prebuilt AAR's classes.jar, as source).
cp "$WORK/src/sherpa-onnx/kotlin-api/"*.kt "$OUT/kotlin/"
echo "sherpa-onnx $SHERPA_TAG ($SHERPA_COMMIT) built from source for: $ABIS"
echo "onnxruntime-android $ORT_VERSION (Maven Central, sha256 $ORT_SHA256)"
ls -la "$OUT"/jniLibs/*/
