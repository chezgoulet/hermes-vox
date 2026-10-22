# F-Droid, path B: the runtime built from source (#165, D8)

F-Droid rejects an app that fetches a prebuilt native library at build time — the pattern
`scripts/fetch-runtime.sh` uses for the sherpa-onnx AAR (and what they rejected for Anti-Vocale
and Ramblr). Path B builds sherpa-onnx from source and takes onnxruntime from Maven Central,
which the inclusion policy allows. **This rail never holds a release** (docs/PLAN-0.9.1.md §9).

## What is in the repo

- `scripts/build-sherpa-from-source.sh` — sherpa-onnx **v1.13.6 at commit
  `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911`** (a tag can move; the commit is checked),
  linked against **onnxruntime-android 1.27.0** from Maven Central (sha256-pinned), for
  `arm64-v8a` and `armeabi-v7a`. It stages `android/sherpa-src/{jniLibs,kotlin}` (gitignored):
  the JNI library per ABI and upstream's Kotlin API as source — what the prebuilt AAR carried
  as `classes.jar`.
- Source paths are mapped to a fixed prefix (`-ffile-prefix-map`), and the script fails if the
  build directory still appears in a library: without it every `__FILE__` in a log line embeds
  the build host's path, and no rebuild elsewhere could match our APK byte for byte (#166, D15).
- `android/app/build.gradle`: with `-Phermesvox.sherpaSource=<dir>` the prebuilt AAR is not
  used — the staged JNI libraries and Kotlin sources are, and onnxruntime comes from the Gradle
  dependency `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`. **Without the property the
  build is unchanged** — the gate, CI, nightly and releases keep the prebuilt AAR.

```
bash scripts/build-sherpa-from-source.sh
cd android && ./gradlew -Phermesvox.sherpaSource=../sherpa-src assembleRelease
```

## What was verified (2026-10-22)

- Both ABIs build from source on the maintainer host (NDK 28.2, CMake 3.22): ~5 min for both.
- `assembleDebug testDebugUnitTest` against the source runtime: the APK carries the
  source-built `libsherpa-onnx-jni.so` (same sha256 as the staged file) and Maven's
  `libonnxruntime.so`; 771 unit tests, 0 failures.
- **On a Pixel 7 (Android 16)**: the source-built library loaded (`nativeloader … ok`),
  Supertonic loaded on the Maven onnxruntime (`SherpaTts loaded: supertonic … warmMs=254`) and
  spoke the Settings preview (`event=voice-preview engine=Supertonic ms=3433`).
  (That device run used the build before the path map and `--build-id=none` below; those
  change no code, but the final libraries have not themselves been on a device.)
- No build-host path remains in either library, and **arm64-v8a is byte-identical across two
  build directories and two fresh clones** (sha256 `3a8662e6…f634ca`): the source paths are
  mapped, and the library is linked with `--build-id=none` (lld hashes the unstripped output,
  so the build-id was the last 20 bytes that differed).

## The fdroiddata recipe (draft — not submitted)

```yaml
Categories:
  - Connectivity
License: GPL-3.0-only
SourceCode: https://github.com/chezgoulet/hermes-vox
IssueTracker: https://github.com/chezgoulet/hermes-vox/issues

RepoType: git
Repo: https://github.com/chezgoulet/hermes-vox

Builds:
  - versionName: <released versionName>
    versionCode: <released versionCode>
    commit: v<released tag>
    subdir: android/app
    sudo:
      - apt-get update
      - apt-get install -y cmake
    gradle:
      - yes
    ndk: r28c
    build:
      - bash ../../scripts/build-sherpa-from-source.sh
    gradleprops:
      - hermesvox.sherpaSource=../sherpa-src
      - hermesvox.unsigned

AllowedAPKSigningKeys: 8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19
Binaries: https://github.com/chezgoulet/hermes-vox/releases/download/v%v/hermes-vox-%v.apk

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
```

`AllowedAPKSigningKeys` is the SHA-256 of the certificate the GitHub releases are signed with
(read from the 0.9.0.1 release APK with `apksigner verify --print-certs`; the nightlies carry
the same one).

## What is left, and whose it is

1. **The reproducible-build check against a released tag.** F-Droid's rebuild must match our
   signed APK minus the signature. Our release APK is built with the prebuilt AAR, so the
   release itself must switch to path B for the two to match — **a release-process decision**
   (docs/RELEASE-PROCESS.md), not taken here.
2. **Submitting the recipe** to fdroiddata (a merge request on gitlab.com): outward-facing; the
   owner's to send.
3. Cross-path reproducibility is checked for the arm64-v8a library; armeabi-v7a uses the same
   flags but was not compared, and the whole-APK comparison (`scripts/repro-check.sh` with the
   property set) has not been run.
