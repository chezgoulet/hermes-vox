# F-Droid path B — sherpa-onnx from source, onnxruntime from Maven (#165)

Status: **built and proven locally on a Pixel 7; not submitted to fdroiddata.**
Draft recipe: [`docs/fdroiddata/com.hermesvox.yml`](fdroiddata/com.hermesvox.yml).

## Why this exists

The current release path (`scripts/fetch-runtime.sh`) downloads
`sherpa-onnx-1.13.6.aar` from a GitHub release. That AAR bundles a prebuilt
`libonnxruntime.so`; F-Droid's inclusion policy allows prebuilt binaries only
from trusted sources, and its build workflow wants dependencies built from
source or taken from a trusted Maven repository
([Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) — "All binary
dependencies … must originate either from source compilation or Debian
repository downloads", with Maven Central named as a trusted source;
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)).

D8 chose path B: sherpa-onnx from a pinned revision, built per ABI, with
onnxruntime from Maven Central (`com.microsoft.onnxruntime:onnxruntime-android`).

## What changed

| Path | Change |
|---|---|
| `scripts/build-sherpa-aar.sh` | **new.** Builds `libsherpa-onnx-jni.so` for `arm64-v8a` + `armeabi-v7a` with the NDK, links it against the Maven onnxruntime, compiles the Kotlin JNI wrappers from source, and writes `android/app/libs/sherpa-onnx-1.13.6-src.aar`. |
| `third_party/sherpa-onnx` | **new submodule**, pinned to `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911` (tag `v1.13.6`). This makes the recipe's `submodules: true` meaningful and avoids a clone during `build:`. |
| `scripts/fetch-runtime.sh` | additive precedence rule: if `sherpa-onnx-1.13.6-src.aar` exists, use it and skip the download. The default (upstream AAR) path is unchanged when it does not. It also refuses to run with both AARs present. |
| `android/app/build.gradle` | when the source AAR is present, add `com.microsoft.onnxruntime:onnxruntime-android:1.27.0` (the only `libonnxruntime.so` in the APK). Refuses to build with both AARs. Adds `-PhermesVoxUnsigned` so F-Droid can build an unsigned release. |
| `.gitignore` | ignore the generated `sherpa-onnx-1.13.6-src.aar`. |
| `docs/fdroiddata/com.hermesvox.yml` | draft recipe (see caveats below). |
| `docs/FDROID.md` | this file. |

The AAR has a distinct `-src` suffix on purpose: the upstream and source
artifacts must never be silently interchangeable, and only one may be in
`android/app/libs/`.

## Build

```sh
export JAVA_HOME=/home/c/jdk-17
export ANDROID_HOME=/home/c/Android/Sdk
export ANDROID_NDK_HOME=/home/c/Android/Sdk/ndk/25.2.9519653
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin"
flock /home/c/build.lock -c 'bash scripts/build-sherpa-aar.sh'
```

Pins (all verified by the script):

* **sherpa-onnx** `v1.13.6`, commit `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911`.
  The script accepts `SHERPA_SRC=` or the `third_party/sherpa-onnx` submodule,
  and refuses a checkout whose `HEAD` is not that commit. Otherwise it clones
  the tag.
* **onnxruntime-android 1.27.0**, Maven Central:
  `https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.27.0/onnxruntime-android-1.27.0.aar`,
  SHA-256 `077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb`.
  The AAR usefully contains `headers/` **and** `jni/<abi>/libonnxruntime.so`, so
  the C++ build and the runtime are version-matched from one artifact. Upstream
  sherpa 1.13.6 was built against ORT 1.27.1; 1.27.0 is the closest version on
  Maven Central and exports the same `VERS_1.27.0` symbol version (checked).
* **kotlinc 2.2.21**, SHA-256
  `a623871f1cd9c938946948b70ef9170879f0758043885bbd30c32f024e511714`,
  used to compile `sherpa-onnx/kotlin-api/*.kt` into `classes.jar`. This is a
  build-time compiler, not a shipped binary. (F-Droid's policy permits
  compilers/build tools not packaged in Debian.)
* **NDK 25.2.9519653**, CMake ≥ 3.15 and Ninja. The local machine used CMake
  3.28.3 and the SDK's Ninja 1.10.2.

Build flags: `Release`, `BUILD_SHARED_LIBS=ON`, `SHERPA_ONNX_ENABLE_JNI=ON`,
TTS and speaker diarization ON (parity with the upstream AAR's feature set),
C API/binary/tests/python/portaudio/websocket OFF (the app uses only the JNI
classes), `ANDROID_PLATFORM=android-24` (the app's `minSdk`).
`SHERPA_ONNXRUNTIME_LIB_DIR` / `SHERPA_ONNXRUNTIME_INCLUDE_DIR` point at the
Maven AAR, so sherpa's CMake uses the pre-installed ORT instead of downloading
one.

Result:

```
android/app/libs/sherpa-onnx-1.13.6-src.aar
3,982,662 bytes
sha256 8cce424807d401492e8c9cc73e9806638027fb83cba97b665436b4b200b1c05e
```

Contents: `classes.jar` (compiled from `kotlin-api`),
`jni/arm64-v8a/libsherpa-onnx-jni.so` (4,584,944 B),
`jni/armeabi-v7a/libsherpa-onnx-jni.so` (3,327,164 B), manifest and AAR
metadata. **No `libonnxruntime.so`** — that comes from Gradle/Maven, so there is
exactly one.

### JNI surface

The app calls `OfflineRecognizer`, `Vad`, `OfflineTts` and their config classes.
The source-built library exports **133 defined dynamic symbols, exactly the
same set as the upstream 1.13.6 AAR** (both ABI comparison, `comm -3` empty):

```sh
NDK=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
$NDK/llvm-nm -D --defined-only jni/arm64-v8a/libsherpa-onnx-jni.so | awk '{print $3}' | sort
```

It has `DT_NEEDED libonnxruntime.so` and undefined
`OrtGetApiBase@VERS_1.27.0`, which the Maven library exports with exactly that
version. The upstream AAR's `libsherpa-onnx-c-api.so` /
`libsherpa-onnx-cxx-api.so` are intentionally not built: the app never loads
them.

### Dependencies fetched by the C++ build and whether they can be vendored

sherpa-onnx's CMake uses `FetchContent` for seven source archives. Each already
has a pinned SHA-256 in `cmake/*.cmake`; the build downloads them on first use.

| Dependency | Revision | Vendorable? |
|---|---|---|
| kaldi-native-fbank | v1.22.3 | Yes — source tarball, sha `9176cc66…` |
| kaldi-decoder | v0.3.0 | Yes — source tarball, sha `b9f34cfb…` |
| simple-sentencepiece | v0.7 | Yes — source tarball, sha `1748a822…` |
| nlohmann/json | v3.12.0 | Yes — source tarball, sha `4b92eb0c…` |
| espeak-ng (csukuangfj fork) | `ed530aa…` | Yes — source zip, sha `e4e262cb…`; **GPL-3.0** (see blockers) |
| piper-phonemize (csukuangfj fork) | `f3ff95a…` | Yes — source zip, sha `d9cca4e2…` |
| hclust-cpp | 2026-02-25 | Yes — source tarball, sha `8f14e024…` |

`sherpa-onnx` itself is already vendored as the submodule. The seven archives
are not yet vendored; `SHERPA_DEPS_DIR=<dir>` makes the script copy
pre-downloaded archives into the sherpa source root, where its CMake looks
before reaching the network. This is the intended mechanism for the merge
request if reviewers will not accept build-time downloads. They are source
code, not prebuilt binaries, so the inclusion policy is not the concern — the
buildserver's network/reproducibility is.

onnxruntime is **not** vendored as source: the Maven Central AAR is explicitly
allowed by the inclusion policy, and it carries both the headers and the
runtime. Vendoring a second onnxruntime would be redundant.

## What is proven locally

### Gate (source-built AAR in place)

`bash scripts/gate.sh` with the environment above:

```
gate: have android/app/libs/sherpa-onnx-1.13.6-src.aar (source-built, see docs/FDROID.md)
gate: parity OK over 10 fixtures
GATE-GREEN
```

`StreamParityTest`: `tests="2" skipped="0" failures="0" errors="0"`. The debug
APK packages `lib/arm64-v8a/libsherpa-onnx-jni.so` byte-identical to the AAR's
copy plus `libonnxruntime.so` from the Maven dependency.

The default path was re-tested as well: with the source AAR removed and the
upstream release AAR in `libs/`, `scripts/gate.sh` also prints `GATE-GREEN`
and the APK packages the upstream `libsherpa-onnx-jni.so` + its bundled
`libonnxruntime.so` (no Maven dependency). So the fallback is not broken.

### Device (Pixel 7, Android 17 / SDK 37, arm64-v8a)

Debug APK installed over the existing debug install with `adb install -r`.
Three real model loads went through the source-built JNI + Maven ORT:

* **STT (Whisper):** the app's existing `probe.wav` hook
  (`files/models/<model>/probe.wav`) transcribed a 16 kHz clip:
  `WHISPER PROBE(whisper-base) transcript=<I wonder if I've been changed in the night.>`
  This used tiny.en int8 weights placed in the `whisper-base` directory; it
  proves the recognizer/ORT path, not the base weights themselves.
* **TTS (Supertonic):** Settings → Spoken replies → Supertonic:
  `SherpaTts loaded: supertonic speakers=10 threads=2 warmMs=220`,
  `supertonic generated 126870 samples @ 44100Hz`, `supertonic played 126870 samples`.
* **VAD (Silero):** a throwaway probe APK built against the same AAR + Maven ORT
  reported `VADPROBE: loaded=true silenceSpeech=false` (model loads and runs).

A **full voice call was not exercised**: this install has no gateway
credentials, and the call path requires a live session before it reaches the
pipeline. The model load itself is the same `VoiceController.initPipeline()`
code that TTS/STT/VAD use, but it ran here via the preview/probe entry points.

### Reproducibility

Two clean builds on this machine produced **byte-identical** AARs:
`8cce424807d401492e8c9cc73e9806638027fb83cba97b665436b4b200b1c05e` both
times. The `.so` files were already identical; the only non-determinism found
was `classes.jar`, because the JDK's `jar` tool stamps entries with the current
time. The script now writes `classes.jar` with Info-ZIP from a sorted file list
with `SOURCE_DATE_EPOCH` mtimes (default 1700000000), and the outer AAR the same
way. Even so, F-Droid's APK cannot be byte-identical to our release APK because
of the signing key, and builds on a different NDK/compiler are not guaranteed
to match these hashes.

### Unsigned release (the recipe's Gradle step)

Verified locally with the source AAR present:

```
(cd android && ./gradlew --no-daemon -PhermesVoxUnsigned clean assembleRelease)
android/app/build/outputs/apk/release/hermes-vox-0.8.1.apk
```

`apksigner verify` reports `DOES NOT VERIFY` (expected: unsigned). The APK
contains only `arm64-v8a` + `armeabi-v7a`, with
`libsherpa-onnx-jni.so` byte-identical to the AAR's copy and
`libonnxruntime.so` from the Maven artifact. `libonnxruntime4j_jni.so` also
comes from that artifact; the app does not use the ORT Java API, so it is
unused but harmless. The release APK was not installed on the device (the
on-device proof used the debug APK); the R8 keep rules for
`com.k2fsa.sherpa.onnx` are unchanged and the minified build completed.

## What remains for the merge request

1. **Licensing (blocker).** `libsherpa-onnx-jni.so` statically links espeak-ng,
   which is **GPL-3.0**, into a TTS-capable library. The app's `LICENSE` is
   Apache-2.0. The combined work's license must be resolved (e.g. ship the
   app under GPL-3.0, or build a TTS variant without espeak-ng and drop Piper),
   and `NOTICE`/source-offer obligations added. This is not introduced by
   path B — the upstream AAR already bundles espeak-ng — but compiling from
   source makes the linkage explicit and F-Droid will inspect it.
2. **Toolchain on the F-Droid buildserver.** The recipe pins NDK
   `25.2.9519653`; confirm it is available or rebuild with one that is.
   Confirm CMake/Ninja and outbound network for the seven FetchContent
   archives, or vendor them with `SHERPA_DEPS_DIR`.
3. **`Binaries:` and `AllowedAPKSigningKeys:` are placeholders.** They need:
   the release asset URL and the SHA-256 signing-certificate fingerprint from
   `apksigner verify --print-certs`. If the build cannot be made byte-reproducible
   against our signed APK, `Binaries:` cannot be used for verification.
4. **Unsigned release path.** `-PhermesVoxUnsigned` was added and verified
   locally: Gradle emits an unsigned `hermes-vox-0.8.1.apk` with the expected
   two ABIs. Only its behaviour on the F-Droid buildserver remains unverified.
5. **`prebuild` vs `build`.** Per the metadata reference, `prebuild` must not
   build and runs before scanning; the AAR is therefore built in `build:`.
   Reviewers should confirm this ordering works for a generated AAR that Gradle
   then consumes from `libs/`.
6. **Submodule checkout.** `submodules: true` + `third_party/sherpa-onnx` is
   standard, but this exact combination has not been run on F-Droid's
   buildserver.
7. **Model downloads at runtime.** The app downloads model files from GitHub
   releases on user action (Settings → Voice models). They are data, not
   executable code, and are sha256-verified; the download is explicit. Mention
   it in the submission notes so it is not mistaken for a hidden downloader.

## Reviewer commands

```sh
# 1. get the pinned native source (the repo now has one submodule)
git submodule update --init --recursive third_party/sherpa-onnx
git -C third_party/sherpa-onnx rev-parse HEAD   # 1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911

# 2. build the AAR (JDK 17 + NDK 25.2 + cmake + ninja)
export JAVA_HOME=/home/c/jdk-17
export ANDROID_HOME=/home/c/Android/Sdk
export ANDROID_NDK_HOME=/home/c/Android/Sdk/ndk/25.2.9519653
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin"
flock /home/c/build.lock -c 'bash scripts/build-sherpa-aar.sh'
sha256sum android/app/libs/sherpa-onnx-1.13.6-src.aar
unzip -l android/app/libs/sherpa-onnx-1.13.6-src.aar

# 3. gate: fetch-runtime notices the source AAR, Gradle adds Maven ORT,
#    assembleDebug + unit tests + parity all run
bash scripts/gate.sh        # or: make test

# 4. on-device model load (optional; needs a device with the debug APK)
adb install -r android/app/build/outputs/apk/debug/hermes-vox-0.8.1.apk
# push a Whisper model + a 16 kHz mono probe.wav into files/models/<id>/ via run-as
# (see the WHISPER PROBE hook in MainActivity), then:
adb logcat -s HermesVox:*
# expected: "Whisper-<id> loaded: ..." and "WHISPER PROBE(<id>) transcript=<...>"

# 5. unsigned release (what the recipe asks for)
(cd android && ./gradlew --no-daemon -PhermesVoxUnsigned assembleRelease)
ls -l android/app/build/outputs/apk/release/
```

## The three riskiest assumptions

1. **F-Droid will accept the seven FetchContent source downloads during
   `build:`** (or reviewers will accept the vendored-archive path). If not, the
   merge request must add all seven as vendored sources/submodules before
   submission. Nothing in their docs forbids network builds, but their
   buildserver is not available to test against here.
2. **onnxruntime 1.27.0 from Maven is a safe substitute for the 1.27.1 that
   sherpa 1.13.6's release was built with.** The C API symbol version matches
   (`VERS_1.27.0`) and Whisper, Supertonic and Silero VAD all load, but
   every model family (Parakeet transducer, Piper/VITS with espeak-ng,
   punctuation/denoiser paths) was not exercised on device.
3. **The GPL-3.0 espeak-ng linkage can be reconciled with the app's
   Apache-2.0 license** without dropping Piper/VITS. If it cannot, path B still
   builds, but the shipped feature set/license changes and this design needs a
   decision before the F-Droid MR.

## Unverified F-Droid behaviour (explicitly marked, not guessed)

* That the buildserver has NDK 25.2.9519653 and the CMake/Ninja versions the
  script needs.
* That `build:` runs before the Gradle step for a generated `libs/*.aar` and is
  not re-scanned/deleted.
* That `submodules: true` materialises `third_party/sherpa-onnx` at the pinned
  commit in their environment.
* The `Binaries:` / `AllowedAPKSigningKeys:` workflow for an app that is also
  signed by upstream (see the recipe comments). The field syntax is from the
  Build Metadata Reference; the values are placeholders.
