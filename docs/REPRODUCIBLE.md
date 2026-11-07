# Reproducible builds

A **reproducible build** is one where the same source, at the same tag, produces the same APK
bytes on any machine. That is what lets a person who does not trust the release page rebuild the
APK and compare it with the file they downloaded, and it is what F-Droid requires before it will
serve this project's own signed APK instead of one signed with its key.

The build is arranged for this: almost every input is pinned — the JDK major version, the Gradle
distribution and its checksum, the Android Gradle Plugin, Kotlin, the speech runtime's source
commit, the NDK that compiles it, and every native artifact. This document lists those pins, the
two checks that measure the build, the differences that can still appear, and the commands that
produce the evidence.

The terms it leans on: **sherpa-onnx** is the speech runtime — the native library that recognises
and synthesises speech, and the only native code this project compiles from source; the rest
ships prebuilt in Maven artifacts. An **ABI** is a CPU architecture native code is built for; the
app ships `arm64-v8a` and `armeabi-v7a`. **R8** is Android's optimiser and shrinker, which the
release build runs. A **submodule** is a git checkout pinned inside this repository. **The gate**
is `scripts/gate.sh`: one command that fetches the runtime, builds the app and runs every test.

`docs/RELEASE-PROCESS.md` uses the result; `docs/FDROID.md` needs it to be true against a
released tag.

## What you need

- JDK 17 and the Android SDK. Both scripts default to `$HOME/jdk-17` and `$HOME/Android/Sdk`;
  `scripts/gate.sh` stops unless the Java major is 17, and `scripts/repro-check.sh` stops if either
  path is missing.
- **3 GB free** on the checkout's filesystem — the check refuses to start below that — plus room
  under `/tmp` for the two release builds it makes in `/tmp/opencode/vox-repro`.
- For the release runtime only (not for this check): the `third_party/sherpa-onnx` submodule and
  NDK r28c. `scripts/build-sherpa-from-source.sh` refuses a submodule at any other commit and
  warns on any other NDK.

## Two builds, two different claims

There are two comparisons, and they answer different questions.

**The unsigned check — `scripts/repro-check.sh`.** Copies the tree to two absolute paths, builds
`assembleRelease -Phermesvox.unsigned` in each with the same Gradle user home and
`--no-build-cache --no-daemon`, and compares the payloads entry by entry. It uses the prebuilt
sherpa-onnx AAR that `scripts/fetch-runtime.sh` verifies — the development runtime, *not* the
release runtime. This is the check a contributor or a reviewer can run anywhere; it proves the
build inputs are path-independent on that host.

**The signed pair — `.github/workflows/release.yml`.** Builds the release twice, in two jobs
(`build` and `rebuild`), from the same commit, each in a clean checkout, both with the sherpa
runtime built from source and both signed with the release key. It then prints both SHA-256
values and a verdict. This is the only comparison of the artifact that actually ships, and it is
the one that stands behind the published hash. Its verdict is printed, not enforced: the
`compare` job succeeds either way, so a reader must read `compare.txt`.

Neither check compares a published APK with an independent third-party rebuild of the released
tag. That comparison is the F-Droid submission step (`docs/FDROID.md`).

## What is pinned

| Input | Pin | Where |
|---|---|---|
| Java | major 17 | `scripts/gate.sh` (refuses any other major), `actions/setup-java` 17 |
| Gradle | `gradle-8.12.1-bin.zip`, `distributionSha256Sum=8d97a97984f6cbd2b85fe4c60a743440a347544bf18818048e611f5288d46c94` | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.3 | `android/build.gradle` |
| Kotlin | 2.2.21 | `android/build.gradle`, `kotlin-stdlib` in `android/app/build.gradle` |
| SDK | compileSdk 34, minSdk 24, targetSdk 34 | `android/app/build.gradle` |
| ABIs | `arm64-v8a`, `armeabi-v7a` only | `android/app/build.gradle` (`abiFilters`) |
| Speech runtime source | sherpa-onnx `v1.13.6`, commit `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911` | `third_party/sherpa-onnx` (submodule), checked by `scripts/build-sherpa-from-source.sh` |
| NDK | r28c, `28.2.13676358` | `scripts/build-sherpa-from-source.sh` |
| onnxruntime | `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`, SHA-256 `077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb` | `android/app/build.gradle`, `scripts/build-sherpa-from-source.sh` |
| Development sherpa AAR | `sherpa-onnx-1.13.6.aar`, SHA-256 `0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698` | `scripts/fetch-runtime.sh` |
| On-device runtime | `com.google.ai.edge.litertlm:litertlm-android:0.16.1` | `android/app/build.gradle` |
| Archives | `org.apache.commons:commons-compress:1.26.2` | `android/app/build.gradle` |

The pinned AAR is gitignored (`android/app/libs/sherpa-onnx-1.13.6.aar`); a clean checkout has
none, and both `:app` and `:bench` fail to compile with `Unresolved reference 'k2fsa'` until
`scripts/fetch-runtime.sh` has fetched and verified it. That error reads like a broken branch and
is only a missing download.

### What the build removes on purpose

`android/app/build.gradle`:

- `dependenciesInfo { includeInApk = false; includeInBundle = false }` — no dependency-info blob
  in the artifact.
- `release { vcsInfo { include = false } }` — no VCS metadata in the release APK.
- Baseline and ART profile tasks are disabled. The app applies no baseline-profile plugin and
  ships no `baseline-prof.txt`; the rule also neutralises one if a future plugin adds it, because
  profiles carry timestamps and tool versions that are not source.
- The release build type is signed only when `-Phermesvox.unsigned` is absent. The check passes
  it, which leaves the signing config unset and produces an unsigned
  `hermes-vox-<version>.apk`; a normal release or nightly build that omits the property still
  requires the keystore and fails loudly without one.

`android/settings.gradle` has no toolchain resolver (`org.gradle.toolchains.foojay-resolver-convention`),
so the build cannot silently download a different JDK than the one the gate checked.

### What the source build pins

`scripts/build-sherpa-from-source.sh` is the release and F-Droid path:

- exports the pinned submodule with `git archive` into `android/.sherpa-build`, so upstream's
  in-tree build never dirties the submodule and every host compiles the same prefix;
- refuses a submodule whose `HEAD` is not the pinned commit, and warns if `ANDROID_NDK` is not
  r28c;
- downloads onnxruntime-android 1.27.0 from Maven Central and checks its SHA-256 before use;
- compiles per ABI with `-ffile-prefix-map=$WORK=/sherpa-build`, so no build-directory path is
  embedded in the library (without it, every `__FILE__` in a log line carries the builder's
  path), and links with `-Wl,--build-id=none`, because lld hashes the unstripped output and the
  build-id was the last difference between two builds;
- fails if the finished library still contains the work path (`strings … | grep -F "$WORK"`);
- stages `android/sherpa-src/jniLibs/<abi>/` and `android/sherpa-src/kotlin/` — the JNI library
  per ABI plus upstream's Kotlin API as source, which the prebuilt AAR carried as `classes.jar`.

`-Phermesvox.sherpaSource=<dir>` switches `android/app/build.gradle` from the AAR to that staged
tree and takes onnxruntime from the Gradle dependency instead. Without the property nothing
changes.

## Exact commands

The gate and the two-path check on the machine that cuts a release:

```sh
export JAVA_HOME="$HOME/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"

bash scripts/gate.sh
bash scripts/repro-check.sh
```

A passing check prints the two hashes, `repro: payloads identical (<n> entries compared)` and
`REPRO-OK`. It then tampers with one byte of one entry and requires the comparator to reject the
copy, so a comparator that compares nothing cannot pass. It writes the two checkouts and their
build logs under `/tmp/opencode/vox-repro`, plus `hashes.txt` with both SHA-256 values; it writes
nothing in the checkout.

Compare two APKs you already have (for example a previously built pair, or a rebuilt unsigned
APK against another):

```sh
bash scripts/repro-check.sh --compare \
  /tmp/opencode/vox-repro/a/android/app/build/outputs/apk/release/hermes-vox-<version>.apk \
  /tmp/opencode/vox-repro/b/android/app/build/outputs/apk/release/hermes-vox-<version>.apk
```

It prints `repro: payloads identical (…)` and exits 0, or `repro: FIRST DIFFERENCE: <entry>` and
exits 1.

Environment variables the check reads:

- `REPRO_WORK_ROOT` — where the two checkouts are made. Default `/tmp/opencode/vox-repro`; the
  script deletes and recreates it.
- `REPRO_SKIP_SELFTEST=1` — skip only the one-byte self-test.
- `JAVA_HOME`, `ANDROID_HOME` — as in the gate.

Two clean release builds, as CI runs them (`release.yml`, jobs `build` and `rebuild`):

```sh
gh workflow run release-build --ref main -f version="<versionName>" -f ref="<commit>"
gh run watch "$(gh run list --workflow release-build --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
gh run download "$(gh run list --workflow release-build --limit 1 --json databaseId --jq '.[0].databaseId')" --dir dist
cat dist/reproducibility-report/compare.txt
```

## What the check compares

`scripts/repro-check.sh` compares ordered ZIP entries — name and order, compression method, flags,
DOS timestamp, CRC-32, compressed and uncompressed sizes, attributes, extra and comment fields —
and the **raw compressed bytes** read from each local file header. It ignores only `META-INF/*`
and, implicitly, the APK signing block (Python's `zipfile` reads the central directory, and the
signing block sits outside it). It prints the first differing entry and exits non-zero.

When `apksigcopier` is on `PATH` the script uses
`apksigcopier compare --unsigned <a> <b>` instead of its built-in comparator. It is not required.

In CI, `.github/workflows/ci.yml` has a `repro` job. On a pull request whose diff touches
`android/`, `scripts/repro-check.sh`, `scripts/fetch-runtime.sh` or `libs/`, it runs the check and
uploads `/tmp/opencode/vox-repro/hashes.txt` as the `repro-hashes` artifact. It is skipped for
docs-only pull requests because it performs two clean release builds inside a 60-minute timeout.

## What is proven, and what is not

The evidence on record:

| Claim | Subject | Result |
|---|---|---|
| Two paths on one host produce the same unsigned APK | 0.8.1, 2026-10-19, 587 entries | identical; `eb602ed4…062809` for both |
| The release build's code output is stable at these versions | same run, AGP 8.7.3 / Kotlin 2.2.21 / JDK 17 / Gradle 8.12.1 | identical APK SHA-256 and identical `classes.dex` entries |
| The source-built arm64-v8a library is path-independent | `libsherpa-onnx-jni.so`, two build directories and two fresh clones, 2026-10-22 and 2026-11-03 | byte-identical, `3a8662e6…f634ca` |
| The signed pair | `release.yml`, a released tag | no verdict is recorded for a released tag; the workflow prints both hashes and a verdict |

The differences that remain, and how each is measured today:

- **Signing.** `scripts/repro-check.sh` builds and compares unsigned APKs. The signature block and
  `META-INF/*` are excluded by design, and whether the signing step itself is deterministic is
  measured only by `release.yml` hashing two signed APKs. Byte-for-byte reproducibility of a
  signed APK is claimed only for those two CI jobs.
- **Host and toolchain.** The two checkouts in the unsigned check run on one host, one JDK, one
  Android SDK and one Gradle cache. Different JDK builds, SDK build-tools, filesystem ordering,
  locale or timezone are outside that measurement. CI's two jobs run on the same
  `ubuntu-latest` image, not on a pinned image digest and not on two different operating systems.
- **Dependency resolution.** Direct dependencies are fixed versions and two artifacts carry
  SHA-256 pins (the sherpa AAR and onnxruntime), but there is no Gradle dependency-lock file: a
  repository serving different bytes under the same version would change the build. Build-tools
  are chosen by the Android Gradle Plugin, not pinned in Gradle; `release.yml`'s
  `build-tools/* | sort -V | tail -1` selects the *verifier* only.
- **The two runtimes are different builds.** `scripts/repro-check.sh` compiles against the
  prebuilt sherpa AAR; the release compiles the same sherpa version from source. The whole-APK
  comparison of the release path has not been run locally, and the unsigned check says nothing
  about it.
- **The second ABI.** `armeabi-v7a` is built with the same flags as `arm64-v8a` but has not been
  compared across build directories; only `arm64-v8a` has.
- **R8 and optimisation.** Minification stays on (`proguard-android-optimize.txt`). Two clean
  builds at the versions above produced the same output, so no R8 pin or `-dontoptimize`
  workaround was added. If a future AGP or R8 bump makes them differ, the options in order are:
  pin the R8 artifact, bisect the rule that exposes the unstable ordering, and only as a last
  documented resort disable optimisation — never silently turn minification off.
- **Debug builds.** `scripts/gate.sh` builds and tests the **debug** variant. Reproducibility is
  enforced separately, on release builds.

## What this cannot do

- It cannot prove a rebuild on a different machine or toolchain; the strongest recorded claim is
  two paths on one host, plus two CI jobs on one image.
- It cannot compare a published signed APK with an unsigned rebuild; the unsigned check compares
  unsigned pairs, and the signed pair is only compared inside `release.yml`.
- It cannot cover the F-Droid build, which must match the release APK without sharing its
  keystore; that comparison is the submission step.
- It cannot make a mismatch publishable: a difference is a blocker, and `docs/RELEASE-PROCESS.md`
  says where it is read.
