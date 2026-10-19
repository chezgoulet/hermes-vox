# F-Droid readiness

State of this repository against F-Droid main-repo inclusion. Written after
preparing the Phonon submission, which is already through `fdroid lint`.

## Done

- **Fastlane metadata** in `fastlane/metadata/android/en-US`: title, short and
  full description, and the 0.8.1 changelog. Screenshots still to be added.
- **Signing is conditional.** The release build used to require
  `keystore/keystore.properties`; an F-Droid build has no such file and must
  produce an unsigned APK for the buildserver to sign. It now signs when the
  keystore is present and builds unsigned when it is not. A maintainer build is
  unchanged.

## Blocking

1. **The speech runtime is a prebuilt AAR.** `scripts/fetch-runtime.sh`
   downloads `sherpa-onnx-1.13.6.aar` (49 MB) from a GitHub release and checks
   its SHA-256. F-Droid does not accept prebuilt binaries it cannot rebuild:
   the source is Apache-2.0 (k2-fsa/sherpa-onnx) but building it means CMake
   plus onnxruntime, and there is no Maven Central artifact to depend on
   instead. The options are a `srclibs` entry that builds sherpa-onnx from
   source inside the F-Droid build — large, slow, and unproven against their
   build timeout — or shipping without on-device speech, which changes the app.
2. **The app is not a Gradle-only build.** `scripts/gate.sh` runs Go vet and
   tests, installs a pinned gomobile fork, binds `mobile.aar` with the NDK
   (25.2.9519653), and only then calls Gradle. That is buildable in principle —
   F-Droid's buildserver has Go and can install the NDK — but the recipe is
   unlike anything already in fdroiddata and needs a real test on their
   infrastructure.
3. **No reproducible build has been measured.** Phonon needed five fixes before
   two machines agreed byte for byte. Vox has a Go/native step that is likely to
   embed paths and a build ID in the same way; nothing has been checked.

## Recommendation

Submit Phonon and Ozymandosis first. For Vox, the decision to make before
writing the recipe is whether on-device speech can be built from source within
F-Droid's limits. If it cannot, the honest outcome is that Vox stays on
Obtainium and GitHub releases, and the F-Droid listing waits until the runtime
is either built from source or published to Maven.
