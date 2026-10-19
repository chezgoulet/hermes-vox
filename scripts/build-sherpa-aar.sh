#!/usr/bin/env bash
# Build the sherpa-onnx Android AAR from source for F-Droid (path B, #165).
#
# The runtime the app used to download from a GitHub release bundled a prebuilt
# libonnxruntime.so.  This script instead builds libsherpa-onnx-jni.so from the
# pinned sherpa-onnx tag for arm64-v8a + armeabi-v7a and links it against the
# ONNX Runtime shipped by the Maven Central artifact
# com.microsoft.onnxruntime:onnxruntime-android.  The Maven AAR is used for two
# things only: its version-matched C/C++ headers (headers/) and, at packaging
# time, its jni/<abi>/libonnxruntime.so (Gradle pulls that into the APK through
# the dependency; this AAR does not contain it, so there is exactly one
# onnxruntime in the app).
#
# Everything fetched here is pinned by revision and SHA-256:
#   * sherpa-onnx    v1.13.6  (commit 1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911)
#   * onnxruntime    1.27.0   (Maven Central AAR, sha256 below)
#   * kotlinc        2.2.21   (to compile the Kotlin JNI wrappers from source)
#   * 7 FetchContent source archives (see docs/FDROID.md; each is a tarball with
#     a SHA-256 already pinned in sherpa-onnx's cmake/*.cmake).
#
# Usage:
#   bash scripts/build-sherpa-aar.sh                 # build + write app/libs/sherpa-onnx-1.13.6-src.aar
#   bash scripts/build-sherpa-aar.sh --out /tmp/x.aar
#   SHERPA_SRC=/path/to/sherpa-onnx bash scripts/build-sherpa-aar.sh
#   SHERPA_DEPS_DIR=/path/to/tarballs bash scripts/build-sherpa-aar.sh
#
# A local checkout (SHERPA_SRC or third_party/sherpa-onnx) is used when present;
# otherwise the pinned tag is cloned.  The revision is always verified, so a
# source tree that drifted cannot silently produce the AAR.
#
# This does not replace scripts/fetch-runtime.sh: the upstream release AAR
# remains the default path when no source-built AAR is present.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SHERPA_VERSION="1.13.6"
SHERPA_TAG="v${SHERPA_VERSION}"
SHERPA_COMMIT="1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911"
SHERPA_GIT_URL="https://github.com/k2-fsa/sherpa-onnx.git"

ONNXRUNTIME_VERSION="1.27.0"
ONNXRUNTIME_AAR_SHA256="077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb"
ONNXRUNTIME_AAR_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ONNXRUNTIME_VERSION}/onnxruntime-android-${ONNXRUNTIME_VERSION}.aar"

KOTLIN_VERSION="2.2.21"
KOTLIN_ZIP_SHA256="a623871f1cd9c938946948b70ef9170879f0758043885bbd30c32f024e511714"
KOTLIN_ZIP_URL="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"

ABIS=("arm64-v8a" "armeabi-v7a")
ANDROID_PLATFORM="android-24"   # app minSdk is 24
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
OUT="$ROOT/android/app/libs/sherpa-onnx-${SHERPA_VERSION}-src.aar"
WORK="${SHERPA_AAR_WORK:-$ROOT/.tools/sherpa-aar}"
CACHE="$WORK/cache"
STAGE="$WORK/stage"

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out) mkdir -p "$(dirname "$2")"; OUT="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2 ;;
    --out=*) OUT="${1#--out=}"; shift ;;
    --jobs|-j) JOBS="$2"; shift 2 ;;
    --jobs=*) JOBS="${1#--jobs=}"; shift ;;
    --clean) rm -rf "$WORK/build" "$STAGE"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "build-sherpa-aar: $*" >&2; exit 1; }
say() { echo "== $*"; }

# ---- toolchain --------------------------------------------------------------
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.2.9519653}"
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin"

[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java"
[ -d "$ANDROID_NDK_HOME" ] || die "ANDROID_NDK_HOME=$ANDROID_NDK_HOME does not exist"
[ -f "$ANDROID_HOME/platforms/android-34/android.jar" ] || die "missing $ANDROID_HOME/platforms/android-34/android.jar (compileSdk 34)"

CMAKE="$(command -v cmake || true)"
[ -n "$CMAKE" ] || die "cmake not found"
for candidate in "$ANDROID_HOME/cmake/3.22.1/bin/ninja" "$(command -v ninja || true)" "$(command -v ninja-build || true)"; do
  [ -n "$candidate" ] && [ -x "$candidate" ] && NINJA="$candidate" && break
done
[ -n "${NINJA:-}" ] || die "ninja not found (install ninja-build or the Android SDK cmake package)"
command -v zip >/dev/null || die "zip not found"
command -v unzip >/dev/null || die "unzip not found"
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || die "llvm-strip not found under $ANDROID_NDK_HOME"

# Refuse to start if the build would fill the disk (task rule: < 3 GB free = stop).
mkdir -p "$WORK"
avail_kb="$(df -Pk "$WORK" | awk 'NR==2 {print $4}')"
[ "${avail_kb:-0}" -ge $((3 * 1024 * 1024)) ] || die "only $((avail_kb / 1024)) MB free under $WORK; need >= 3 GB"

echo "build-sherpa-aar: JAVA_HOME=$JAVA_HOME"
echo "build-sherpa-aar: ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
echo "build-sherpa-aar: cmake=$CMAKE ninja=$NINJA jobs=$JOBS"
echo "build-sherpa-aar: out=$OUT"

# ---- pinned downloads -------------------------------------------------------
fetch() { # fetch URL SHA FILE
  local url="$1" sha="$2" file="$3"
  if [ -f "$file" ] && echo "$sha  $file" | sha256sum -c --quiet - 2>/dev/null; then
    echo "have $(basename "$file") (verified)"
    return 0
  fi
  rm -f "$file.partial"
  echo "fetching $(basename "$file")"
  curl -fsSL -m 900 -o "$file.partial" "$url" || { rm -f "$file.partial"; die "failed to fetch $url"; }
  echo "$sha  $file.partial" | sha256sum -c --quiet - || { rm -f "$file.partial"; die "sha256 mismatch for $url"; }
  mv "$file.partial" "$file"
}
mkdir -p "$CACHE"

# ---- onnxruntime (Maven Central) -------------------------------------------
ORT_AAR="$CACHE/onnxruntime-android-${ONNXRUNTIME_VERSION}.aar"
fetch "$ONNXRUNTIME_AAR_URL" "$ONNXRUNTIME_AAR_SHA256" "$ORT_AAR"
ORT_DIR="$WORK/onnxruntime-${ONNXRUNTIME_VERSION}"
if [ ! -f "$ORT_DIR/headers/onnxruntime_cxx_api.h" ]; then
  say "unpacking onnxruntime ${ONNXRUNTIME_VERSION} headers + jni libs"
  rm -rf "$ORT_DIR"; mkdir -p "$ORT_DIR"
  ( cd "$ORT_DIR" && unzip -q "$ORT_AAR" 'headers/*' 'jni/*/libonnxruntime.so' )
fi

# ---- sherpa-onnx source -----------------------------------------------------
if [ -n "${SHERPA_SRC:-}" ]; then
  SRC="$SHERPA_SRC"
elif [ -e "$ROOT/third_party/sherpa-onnx/.git" ]; then
  SRC="$ROOT/third_party/sherpa-onnx"
else
  SRC="$WORK/sherpa-onnx"
  if [ ! -d "$SRC/.git" ]; then
    say "cloning sherpa-onnx $SHERPA_TAG"
    rm -rf "$SRC"
    git clone --filter=blob:none --no-checkout "$SHERPA_GIT_URL" "$SRC"
  fi
  git -C "$SRC" fetch --depth 1 origin "refs/tags/$SHERPA_TAG:refs/tags/$SHERPA_TAG" >/dev/null 2>&1 || true
  git -C "$SRC" checkout --detach "$SHERPA_COMMIT" >/dev/null 2>&1 \
    || git -C "$SRC" fetch --depth 1 origin "$SHERPA_COMMIT" >/dev/null 2>&1
fi
[ -d "$SRC/sherpa-onnx/kotlin-api" ] || die "$SRC does not look like a sherpa-onnx checkout"
actual_commit="$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)"
[ "$actual_commit" = "$SHERPA_COMMIT" ] \
  || die "sherpa-onnx revision mismatch: $actual_commit != $SHERPA_COMMIT (set SHERPA_SRC to the pinned checkout)"
echo "build-sherpa-aar: sherpa-onnx $SHERPA_TAG @ $actual_commit"

# Optional offline dependency cache: the seven FetchContent archives.  sherpa's
# CMake looks for them in ${CMAKE_SOURCE_DIR}/<name>; copy them there so the
# build never reaches the network when the cache is supplied.
if [ -n "${SHERPA_DEPS_DIR:-}" ]; then
  [ -d "$SHERPA_DEPS_DIR" ] || die "SHERPA_DEPS_DIR=$SHERPA_DEPS_DIR does not exist"
  say "using FetchContent archives from $SHERPA_DEPS_DIR"
  for f in "$SHERPA_DEPS_DIR"/*.tar.gz "$SHERPA_DEPS_DIR"/*.zip; do
    [ -e "$f" ] || continue
    cp -f "$f" "$SRC/$(basename "$f")"
  done
fi

# ---- kotlinc (compiles the JNI wrappers, not a prebuilt jar) ----------------
KOTLIN_HOME="$WORK/kotlinc"
if [ ! -x "$KOTLIN_HOME/bin/kotlinc" ]; then
  KOTLIN_ZIP="$CACHE/kotlin-compiler-${KOTLIN_VERSION}.zip"
  fetch "$KOTLIN_ZIP_URL" "$KOTLIN_ZIP_SHA256" "$KOTLIN_ZIP"
  say "unpacking kotlinc ${KOTLIN_VERSION}"
  rm -rf "$KOTLIN_HOME" "$WORK/kotlin-unzip"; mkdir -p "$WORK/kotlin-unzip"
  ( cd "$WORK/kotlin-unzip" && unzip -q "$KOTLIN_ZIP" )
  rm -rf "$KOTLIN_HOME"; mv "$WORK/kotlin-unzip/kotlinc" "$KOTLIN_HOME"
fi

# ---- build both ABIs --------------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE/jni"

for abi in "${ABIS[@]}"; do
  say "building sherpa-onnx-jni for $abi"
  b="$WORK/build/$abi"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT_DIR/jni/$abi"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT_DIR/headers"
  [ -f "$SHERPA_ONNXRUNTIME_LIB_DIR/libonnxruntime.so" ] || die "missing onnxruntime for $abi"

  "$CMAKE" -S "$SRC" -B "$b" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
    -DSHERPA_ONNX_ENABLE_TTS=ON \
    -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON \
    -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
    -DCMAKE_INSTALL_PREFIX="$b/install"
  "$CMAKE" --build "$b" -j "$JOBS"
  "$CMAKE" --install "$b" --strip

  jni="$b/install/lib/libsherpa-onnx-jni.so"
  [ -f "$jni" ] || die "build did not produce $jni"
  mkdir -p "$STAGE/jni/$abi"
  cp "$jni" "$STAGE/jni/$abi/"
  "$STRIP" --strip-unneeded "$STAGE/jni/$abi/libsherpa-onnx-jni.so"
  echo "build-sherpa-aar: $abi $(( $(stat -c%s "$STAGE/jni/$abi/libsherpa-onnx-jni.so") / 1024 )) KiB"
done

# ---- Kotlin JNI wrappers -> classes.jar ------------------------------------
say "compiling com.k2fsa.sherpa.onnx from sherpa-onnx/kotlin-api"
rm -rf "$WORK/classes"
mkdir -p "$WORK/classes"
"$KOTLIN_HOME/bin/kotlinc" \
  -classpath "$ANDROID_HOME/platforms/android-34/android.jar" \
  -jvm-target 1.8 \
  -d "$WORK/classes" \
  "$SRC"/sherpa-onnx/kotlin-api/*.kt
# Deterministic archive: fixed timestamps and a sorted file list. (The JDK's
# `jar` stamps entries with the current time, so it cannot produce a
# reproducible classes.jar; Info-ZIP stores the mtimes we set.)
epoch="${SOURCE_DATE_EPOCH:-1700000000}"
find "$WORK/classes" -exec touch -h -d "@$epoch" {} +
( cd "$WORK/classes" && find . -mindepth 1 -type f -printf '%P\n' | LC_ALL=C sort | zip -q -X "$STAGE/classes.jar" -@ )

# ---- AAR packaging ----------------------------------------------------------
say "packaging AAR"
cat > "$STAGE/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.k2fsa.sherpa.onnx" >

    <uses-sdk android:minSdkVersion="24" />

</manifest>
EOF
: > "$STAGE/R.txt"
: > "$STAGE/proguard.txt"
mkdir -p "$STAGE/META-INF/com/android/build/gradle"
cat > "$STAGE/META-INF/com/android/build/gradle/aar-metadata.properties" <<'EOF'
aarFormatVersion=1.0
aarMetadataVersion=1.0
minCompileSdk=1
minCompileSdkExtension=0
minAndroidGradlePluginVersion=1.0.0
coreLibraryDesugaringEnabled=false
EOF

# Deterministic archive: fixed timestamps + no extra metadata.  SOURCE_DATE_EPOCH
# keeps two builds on different machines byte-identical (assuming the same NDK;
# see docs/FDROID.md for the reproducibility caveat).
find "$STAGE" -exec touch -h -d "@$epoch" {} +
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
( cd "$STAGE" && find . -mindepth 1 -type f -printf '%P\n' | LC_ALL=C sort | zip -q -X "$OUT" -@ )

# ---- self-checks ------------------------------------------------------------
for abi in "${ABIS[@]}"; do
  unzip -l "$OUT" "jni/$abi/libsherpa-onnx-jni.so" >/dev/null 2>&1 \
    || die "AAR missing jni/$abi/libsherpa-onnx-jni.so"
done
unzip -l "$OUT" classes.jar >/dev/null || die "AAR missing classes.jar"
if unzip -l "$OUT" | grep -q 'libonnxruntime.so'; then
  die "AAR must not bundle libonnxruntime.so (Gradle gets it from Maven Central)"
fi

say "OK $OUT"
ls -l "$OUT"
sha256sum "$OUT"
echo "jni symbols: $(unzip -p "$OUT" "jni/arm64-v8a/libsherpa-onnx-jni.so" > "$WORK/check.so" && "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D --defined-only "$WORK/check.so" | wc -l)"
echo
echo "Dependencies fetched (all pinned; see docs/FDROID.md):"
echo "  onnxruntime-android $ONNXRUNTIME_VERSION  Maven Central (headers + libonnxruntime.so)"
echo "  kotlinc $KOTLIN_VERSION                   github.com/JetBrains/kotlin (build-time compiler)"
echo "  kaldi-native-fbank 1.22.3                 FetchContent, sha 9176cc66..."
echo "  kaldi-decoder 0.3.0                       FetchContent, sha b9f34cfb..."
echo "  simple-sentencepiece 0.7                  FetchContent, sha 1748a822..."
echo "  nlohmann/json 3.12.0                      FetchContent, sha 4b92eb0c..."
echo "  espeak-ng ed530aa (csukuangfj fork)       FetchContent, sha e4e262cb..."
echo "  piper-phonemize f3ff95a (csukuangfj fork) FetchContent, sha d9cca4e2..."
echo "  hclust-cpp 2026-02-25                     FetchContent, sha 8f14e024..."
