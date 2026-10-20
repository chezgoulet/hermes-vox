# Reproducible builds

## Status (#166, 2026-10-19)

The **unsigned release APK** is now built twice from two different absolute paths
and compared entry by entry by `scripts/repro-check.sh`, on the build host and in
a pull-request CI job. On this host the two builds are byte-identical:

```
eb602ed4f35825eb5bf5ac9794e5a9c32a960bac628ffcbc961fbf87a3062809  /tmp/opencode/vox-repro/a/.../hermes-vox-0.8.1.apk
eb602ed4f35825eb5bf5ac9794e5a9c32a960bac628ffcbc961fbf87a3062809  /tmp/opencode/vox-repro/b/.../hermes-vox-0.8.1.apk
repro: payloads identical (587 entries compared)
```

`#126` replaced the Go core with Kotlin (`docs/KOTLIN-CORE.md`), so there is no
gomobile bind, no NDK link and no `libgojni.so`. The class of nondeterminism
#138 fixed (path and random work directory baked into a native binary) cannot
recur from this repo. All native libraries are prebuilt upstream artifacts,
pinned by version or SHA-256.

## What is enforced now

`android/app/build.gradle`:

- `android.dependenciesInfo { includeInApk = false; includeInBundle = false }`
  — no dependency-info blob in the artifact.
- `release { vcsInfo { include = false } }` — no VCS metadata in the release
  APK.
- baseline/ART profile tasks are disabled (the app applies no baseline-profile
  plugin and ships no `baseline-prof.txt`; the rule also neutralises one if a
  future plugin adds it).
- the release build type is signed only for normal builds. `repro-check.sh`
  passes `-Phermesvox.unsigned`, which leaves the signing config unset and
  produces `hermes-vox-<version>.apk` unsigned. A normal release/nightly build
  that omits the property still requires the keystore and fails loudly if it is
  missing.

`android/settings.gradle` has **no** `org.gradle.toolchains.foojay-resolver-convention`:
the build uses the local JDK 17 that `scripts/gate.sh` checks. The Gradle wrapper
is pinned once, in `android/gradle/wrapper/gradle-wrapper.properties`, to
`gradle-8.12.1-bin.zip` with `distributionSha256Sum`; CI and the make/gate targets
all invoke that wrapper. (The task brief mentioned a stray `8.2-bin`; there is no
such reference in the tree — the wrapper and CI already agreed on 8.12.1, so no
version change was needed.)

`scripts/repro-check.sh`:

1. verifies the pinned sherpa AAR;
2. copies the tree to `/tmp/opencode/vox-repro/a` and `/tmp/opencode/vox-repro/b`;
3. runs a clean `assembleRelease -Phermesvox.unsigned` in each copy with the same
   Gradle user home and `--no-build-cache --no-daemon`;
4. compares ordered ZIP entries: name/order, method, flags, DOS timestamp, CRC-32,
   compressed and uncompressed sizes, attributes, extra/comment fields, and the
   **raw compressed bytes** read from each local file header. It ignores only
   `META-INF/*` and the APK signing block (both irrelevant to an unsigned build);
5. prints the first differing entry and exits non-zero;
6. runs a comparator self-test: it flips one byte in the first non-`META-INF`
   entry and requires the comparator to reject it.

`apksigcopier compare --unsigned` is used instead of the built-in comparator when
`apksigcopier` is on `PATH`.

`.github/workflows/ci.yml` has a `repro` job. On a pull request whose diff touches
`android/`, `scripts/repro-check.sh`, `scripts/fetch-runtime.sh` or `libs/`, it
runs the check and uploads `/tmp/opencode/vox-repro/hashes.txt` (the SHA-256 of both APKs)
as the `repro-hashes` artifact. Any difference fails the job. It is intentionally
skipped for docs-only pull requests because it performs two clean release builds.

## R8 probe

The release build keeps `minifyEnabled true` and
`proguard-android-optimize.txt`. At the exact versions used here — AGP 8.7.3,
Kotlin 2.2.21, JDK 17, Gradle 8.12.1 — two clean builds from different absolute
paths produced the same APK SHA-256 and identical `classes.dex` entries. No R8
nondeterminism was observed, so minification is **not** disabled and no extra R8
pin or `-dontoptimize` workaround was added.

If a future R8/AGP bump makes the two builds differ, the options (in order) are:
pin the R8 artifact explicitly; bisect the rule that exposes the unstable
ordering; only as a last, documented resort disable optimisation
(`-dontoptimize`) — never silently turn minification off.

## Exact commands

Build-host gate and reproducibility check:

```sh
export JAVA_HOME=/home/c/jdk-17
export ANDROID_HOME=/home/c/Android/Sdk
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin"

flock /home/c/build.lock -c 'bash scripts/gate.sh'
flock /home/c/build.lock -c 'bash scripts/repro-check.sh'
```

Compare two APKs you already have (used to prove the comparator rejects a
one-byte change):

```sh
cp /tmp/opencode/vox-repro/a/android/app/build/outputs/apk/release/hermes-vox-0.8.1.apk /tmp/tampered.apk
# flip one byte in the local payload, then:
bash scripts/repro-check.sh --compare \
  /tmp/opencode/vox-repro/a/android/app/build/outputs/apk/release/hermes-vox-0.8.1.apk \
  /tmp/tampered.apk   # prints "FIRST DIFFERENCE: <entry>" and exits 1
```

Useful environment variables: `REPRO_WORK_ROOT` (default `/tmp/opencode/vox-repro`) and
`REPRO_SKIP_SELFTEST=1` (skip only the one-byte self-test).

## What is still unproven

- **Signed artifacts are not compared.** Nightly/release APKs carry a signature
  block and `META-INF/*`; this check deliberately builds and compares unsigned
  APKs. Byte-for-byte reproducibility of the signed nightly is not claimed.
- **Cross-machine reproducibility is not proven.** The two checkouts ran on the
  same host, JDK, Android SDK and Gradle cache. Different JDK builds, SDK
  build-tools, OS/filesystem ordering, locale/timezone or a different R8 version
  are outside this measurement.
- **Dependency resolution is version-pinned but not locked.** All direct
  dependencies are fixed versions and the sherpa AAR is SHA-256 pinned; there is
  no Gradle dependency-lock file, so a changed repository artifact for the same
  version would change the build.
- The gate still builds the **debug** variant; reproducibility is enforced
  separately by `repro-check.sh` and the `repro` CI job.

## History

The pre-#126 version of this page documented the gomobile `libgojni.so`
investigation (`-trimpath`, canonical bind path). It was removed with the Go core
in #126; the measurements remain in this file's git history.
