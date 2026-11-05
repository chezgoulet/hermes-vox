# F-Droid, path B: the runtime built from source (#165, D8)

F-Droid rejects an app that fetches a prebuilt native library at build time — the pattern
`scripts/fetch-runtime.sh` uses for the sherpa-onnx AAR (and what they rejected for Anti-Vocale
and Ramblr). Path B builds sherpa-onnx from source and takes onnxruntime from Maven Central,
which the inclusion policy allows. **The listing is 0.9.1 work and the release waits for it
(D29)** — the bar is the app being listed: the recipe reproducible, a merge request against a
released tag, and the listing granted. As this is written the recipe is still a draft and the app
is not listed.

## What is in the repo

- `third_party/sherpa-onnx` — a git submodule pinned at sherpa-onnx **v1.13.6, commit
  `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911`**. A submodule rather than an fdroiddata
  `srclibs:` entry: the pin lives in this repo's tree, so our release, CI and F-Droid's
  rebuild (`submodules: true`) build one recorded commit, and nothing is cloned during the
  build. Fresh checkout: `git submodule update --init --depth 1 third_party/sherpa-onnx`.
- `scripts/build-sherpa-from-source.sh` — exports that submodule (`git archive`, so upstream's
  in-tree build leaves it clean) and builds it with **NDK r28c** (`28.2.13676358`; it warns on
  any other NDK, which could not reproduce our library),
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
  build is unchanged** — the gate, CI and nightlies keep the prebuilt AAR
  (`scripts/fetch-runtime.sh`, which now says on every run that it is the development runtime).
- **Releases use path B** (`.github/workflows/release.yml`, both builds): submodule checkout,
  NDK r28c, this script, `-Phermesvox.sherpaSource`. F-Droid's rebuild has to match the APK we
  publish, so the release cannot be built from the prebuilt AAR any more.

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

## What was verified (2026-11-03, the submodule build)

- From `third_party/sherpa-onnx` on sasquatch (NDK r28c, CMake 3.28), with no
  `android/app/libs/` present: arm64-v8a `3a8662e6…f634ca` — **the same bytes as the
  2026-10-22 clone build** — and armeabi-v7a `d70b6f21…cd7e81`.
- `./gradlew -Phermesvox.sherpaSource=../sherpa-src -Phermesvox.unsigned :app:assembleRelease`:
  BUILD SUCCESSFUL. `hermes-vox-0.9.1.apk` (versionCode 147) carries the source-built
  `libsherpa-onnx-jni.so` per ABI (same sha256 as staged), Maven's `libonnxruntime.so` +
  `libonnxruntime4j_jni.so`, and `liblitertlm_jni.so` (arm64, Google Maven) — and no
  `libsherpa-onnx-c-api.so`/`-cxx-api.so`, which only the prebuilt AAR carried.
- `testReleaseUnitTest` on the source path: 1150 tests, 0 failures. `scripts/gate.sh`: green.
- Not run: `release.yml`'s path-B steps on GitHub Actions, and the source-built APK on a device.

## The fdroiddata recipe (draft — not submitted)

[`docs/fdroiddata/com.hermesvox.yml`](fdroiddata/com.hermesvox.yml), in the same anatomy as
Ozymandosis's: the pinned tag, `submodules: true`, `ndk: r28c`, the library built in `build:`
(after the scanner), `Binaries:` pointing at our release asset, and `AllowedAPKSigningKeys` —
the SHA-256 of the certificate the GitHub releases are signed with, read from the 0.9.0.3
release APK with `apksigner verify --print-certs` (`8be7feed…dbbb19`; 0.9.0.1 and the
nightlies carry the same one). Its `MaintainerNotes` carry the questions a reviewer will ask.

## Store metadata, and the unsigned build path

`fastlane/metadata/android/en-US` carries the title, the short and full descriptions, and a
changelog per version code, which is what an F-Droid listing renders. The descriptions are
written to be true of the app as it ships: a client for a gateway the user already runs, no
account, no service of its own, recognition on the device. F-Droid's own screenshot slot (the
`images/phoneScreenshots` directory) is still empty; the captures under `docs/screenshots/` are
from the 0.8.0 emulator and are not store assets.

Signing is deliberately **not** made optional. A release build with no
`keystore/keystore.properties` fails loudly rather than falling back to the public debug key,
which is the right default: a maintainer build that silently produced a debug-signed APK would
be worse than one that stopped. The unsigned path already exists for reproducibility —
`scripts/repro-check.sh` builds with `-Phermesvox.unsigned` — and an F-Droid build has no
maintainer keystore, so the fdroiddata recipe must pass that same property. Anything else, such
as making the signing config conditional on the file, weakens a guard to save one line of
recipe.

## What is left, and whose it is

1. **The reproducible-build check against a released tag.** F-Droid's rebuild must match our
   signed APK minus the signature. The release now builds path B (`.github/workflows/release.yml`,
   both builds), so the two sides can match; the check itself has **not** been run against a
   released tag, because `v0.9.1` does not exist yet. Releasing from the prebuilt AAR is no longer
   an option — `docs/RELEASE-PROCESS.md` is the process that says so.
2. **Submitting the recipe** to fdroiddata (a merge request on gitlab.com): outward-facing; the
   owner's to send. `repos/f-droid/fdroiddata` has no `metadata/com.hermesvox.yml` today.
3. Cross-path reproducibility is checked for the arm64-v8a library; armeabi-v7a uses the same
   flags but was not compared, and the whole-APK comparison (`scripts/repro-check.sh` with the
   property set) has not been run on the 0.9.1 tree.
