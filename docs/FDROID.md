# The F-Droid listing

F-Droid is a catalogue of free-software Android apps that builds every app from its source on its
own build servers and serves the result. A **listing** is an entry in that catalogue: a recipe in
the `fdroiddata` repository that names the source, the build steps and the versions.

Hermes Vox is **not listed**. This document is the state of the work: what the recipe says, why
the speech runtime is compiled from source instead of fetched, which signature F-Droid would
serve, the anti-feature questions and their answers, and what remains before a submission.

A **recipe** here is one YAML file — [`docs/fdroiddata/com.hermesvox.yml`](fdroiddata/com.hermesvox.yml)
in this repository, `metadata/com.hermesvox.yml` in `fdroiddata`. The copy in this tree is a
draft that has not been submitted.

**Path B** is the name this repository uses for building sherpa-onnx, the on-device speech
runtime, from source inside the build. It is the only path F-Droid accepts; it is why the release
and this recipe share a build.

## What you need

To read this, nothing. To submit the recipe:

- A released tag — the recipe names `v0.9.1` and a published APK at that tag's asset URL.
- A `gitlab.com` account and a fork of `f-droid/fdroiddata`; the submission is a merge request
  there.
- The ability to check the rebuild, which means a machine that can compare the F-Droid-built APK
  with the published one (see "Where the submission stands").

## Why the runtime is built from source

F-Droid builds an app from source and rejects a build that fetches a prebuilt native library at
build time. The development path does exactly that: `scripts/fetch-runtime.sh` downloads the
prebuilt `sherpa-onnx-1.13.6.aar` and checks its SHA-256. That artifact is fine for the gate, CI
and nightlies, and F-Droid does not accept it in a recipe.

Path B replaces it with three pinned inputs:

- **The source**: `third_party/sherpa-onnx`, a git submodule at sherpa-onnx **v1.13.6, commit
  `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911`**. A submodule rather than an `srclibs:` entry, so
  the pin lives in this repository's tree and F-Droid's `submodules: true` checkout builds the
  same commit as our release, with nothing cloned during the build.
- **The compiler**: NDK r28c, `28.2.13676358`. The script warns if `ANDROID_NDK` points at any
  other NDK, because a different compiler cannot reproduce our library.
- **ONNX Runtime**: `com.microsoft.onnxruntime:onnxruntime-android:1.27.0` from Maven Central,
  SHA-256 `077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb`. This is the same
  artifact the app depends on at runtime, and the sources are compiled against its headers.

`scripts/build-sherpa-from-source.sh` does the build:

```sh
git submodule update --init --depth 1 third_party/sherpa-onnx
bash scripts/build-sherpa-from-source.sh
```

It exports the submodule with `git archive` into `android/.sherpa-build` (upstream's build runs
inside the source tree; the export keeps the submodule clean and the mapped path identical on
every host), compiles `arm64-v8a` and `armeabi-v7a`, and stages the result in
`android/sherpa-src/` — the JNI library per ABI plus upstream's Kotlin API as source, which the
prebuilt AAR carried as `classes.jar`. The output is gitignored.

Two flags make the library reproducible: `-ffile-prefix-map=$WORK=/sherpa-build` keeps the build
directory out of the binary, and `-Wl,--build-id=none` removes the build-id, which lld derives
from the unstripped output. The script then fails if the library still contains the work path.

`android/app/build.gradle` switches to that tree with `-Phermesvox.sherpaSource=<dir>`:
the prebuilt AAR is not used, the staged JNI libraries and Kotlin sources are, and onnxruntime
comes from the Gradle dependency. **Without the property nothing changes** — the gate, CI and
nightlies keep the prebuilt AAR.

The unsigned release build, which is what the recipe and the reproducibility check run, is then:

```sh
cd android
./gradlew -Phermesvox.sherpaSource=../sherpa-src -Phermesvox.unsigned assembleRelease
```

The GitHub release workflow takes the same inputs — submodules checked out, NDK r28c installed,
the script run, `-Phermesvox.sherpaSource` set — and adds the keystore, so its APK is signed.
F-Droid's rebuild has to match that APK without the keystore, which is why a release built from
the prebuilt AAR could not be reproduced by the store.

## What the recipe says

| Field | Value | Why |
|---|---|---|
| `License` | `GPL-3.0-only` | sherpa-onnx statically links espeak-ng, which is GPL-3.0, so the combined work is GPL-3.0 (`NOTICE`). |
| `Categories` | `Connectivity` | The app connects to a gateway the user runs. |
| `Repo`, `RepoType` | `git`, `https://github.com/chezgoulet/hermes-vox.git` | The source of every version. |
| `Binaries` | `…/releases/download/v%v/hermes-vox-%v.apk` | The APK we publish; the reference file F-Droid compares its rebuild against. |
| `Builds` | `0.9.1`, code `147`, commit `v0.9.1` | The cut this recipe was written for. The tag does not exist yet. |
| `subdir` | `android/app` | The application module. |
| `submodules` | `true` | Checks out `third_party/sherpa-onnx` at the pinned commit. |
| `ndk` | `r28c` | The compiler the library is pinned to. |
| `sudo` | `apt-get update`, `apt-get install -y cmake` | Upstream's build scripts need CMake. |
| `prebuild` | `rm -rf ../../libs` | Removes the encrypted prebuilt AAR, so a build that still wanted it would fail. |
| `scandelete` | `third_party/sherpa-onnx/android`, `…/java-api-examples` | Upstream demo apps and examples, each with a `gradle-wrapper.jar`; nothing in them is built. |
| `build` | `bash ../../scripts/build-sherpa-from-source.sh` | Runs **after** the scanner, so the library is compiled from source that has been scanned. |
| `gradleprops` | `hermesvox.sherpaSource=../sherpa-src`, `hermesvox.unsigned` | Uses the source-built runtime, and builds unsigned because F-Droid has no maintainer keystore. |
| `output` | `build/outputs/apk/release/hermes-vox-$$VERSION$$.apk` | The artifact name the app's Gradle config produces. |
| `AllowedAPKSigningKeys` | `8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19` | The certificate our APKs are signed with. |
| `AutoUpdateMode`, `UpdateCheckMode` | `Version`, `Tags ^v[0-9.]+$` | New versions are found by tag. |
| `CurrentVersion`, `CurrentVersionCode` | `0.9.1`, `147` | What the recipe is written against. |

`MaintainerNotes` carries the questions a reviewer asks, answered in the recipe itself: which
dependencies CMake downloads at configure time and that each is hash-pinned in upstream's
`cmake/*.cmake`; that onnxruntime comes from Maven rather than a prebuilt download; that
`litertlm-android` (Google Maven, Apache-2.0) supplies `liblitertlm_jni.so`; that no model
weights ship in the APK; that the library is compiled with the prefix map; and that
`hermesvox.unsigned` is the unsigned path the reproducibility check uses too.

## `AllowedAPKSigningKeys`, and why the published signature is the one F-Droid serves

Every APK this project publishes — releases and nightlies — is signed with the same release key,
so a phone can move between the tracks without uninstalling. The certificate's SHA-256 digest is:

```
8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19
```

That value is the recipe's `AllowedAPKSigningKeys`, and it is what `apksigner` prints for any
published APK. Confirm it from the artifact rather than from this page:

```sh
export PATH="$JAVA_HOME/bin:$PATH"   # apksigner runs java from PATH
BT="$(ls -d "$ANDROID_HOME/build-tools/"* | sort -V | tail -1)"
"$BT/apksigner" verify --print-certs hermes-vox-0.9.0.3.apk
```

With `Binaries:` naming our APK and `AllowedAPKSigningKeys` naming our certificate, F-Droid
builds the tag on its own server and compares the result with our file. When the payloads match
and the signature is ours, F-Droid serves **our signed APK** — not one it signed — so an install
from F-Droid, from Obtainium and from the release page are the same app, with the same update
path and no reinstall when the source changes. When they do not match, F-Droid skips that version
rather than ship something signed with a different key.

The digest is a property of the key, not of a build. It does not change between releases; if it
ever changes, `AllowedAPKSigningKeys` must change with it in the same submission.

## Store metadata

`fastlane/metadata/android/en-US/` holds the text a listing renders:

- `title.txt` — `Hermes Vox`.
- `short_description.txt` — one line, `Voice client for your own Hermes agent`.
- `full_description.txt` — a client for a gateway the user already runs; no account, no service
  of its own; recognition on the device; models downloaded on first use.
- `changelogs/<versionCode>.txt` — the changelog for one version code. The tree carries `142.txt`
  and `144.txt`; the current code `147` has none, so a listing of 0.9.1 would show no changelog
  until `147.txt` exists.

F-Droid's screenshot slot is `fastlane/metadata/android/en-US/images/phoneScreenshots/`. It does
not exist in this tree. The images under `docs/screenshots/` are captures of the app and are not
store assets.

## Anti-feature questions and their answers

F-Droid's **anti-features** are the labels a listing carries when the app has a property its
users should know about — ads, tracking, a dependency on a non-free network service, non-free
code or assets. The recipe sets no `AntiFeatures:` key, because none apply. The answers:

| Question | Answer |
|---|---|
| Does it show ads? | No. No advertising dependency is declared in `android/app/build.gradle`, and the app contacts no ad host. |
| Does it track the user? | No. There is no analytics, telemetry or crash-reporting SDK (`PRIVACY.md` §5). The app contacts the gateway the user configures, and the model hosts the user downloads from. |
| Does it depend on a non-free network service? | No. It has no hosted service of its own; the gateway is the user's. Model downloads come from GitHub releases and Hugging Face, which are free network services. |
| Is it a non-free dependency? | No. The shipped dependencies are Apache-2.0, MIT and GPL-3.0 (the combined app is GPL-3.0 because of espeak-ng; `NOTICE` lists every component). |
| Does it ship non-free assets? | No model weights are in the APK. They are data, downloaded on first use from their upstream hosts (`ModelCatalog.kt`), and each row states its licence. The one row whose licence is a click-through — Nemotron, under the NVIDIA Open Model License — is gated on accepting it before download. |
| Does it contain prebuilt binaries? | The speech runtime, the only native library the app's own build compiles, is built from the pinned submodule in the recipe. The rest of the native code ships in Maven artifacts: onnxruntime-android (Maven Central) and litertlm-android (Google Maven), the same artifacts the app depends on at runtime. |
| Is the signature ours? | Yes. `Binaries:` plus `AllowedAPKSigningKeys` make F-Droid verify, and then serve, the APK we signed. |

## Where the submission stands

1. **The recipe is a draft, unsubmitted.** It lives in this tree so it is reviewed with the
   code, and it has never been through `fdroidserver`. When it is submitted it becomes
   `metadata/com.hermesvox.yml` in `fdroiddata`, through a merge request on `gitlab.com`.
2. **The tag it names does not exist yet.** The recipe builds `v0.9.1` and compares against the
   APK at `releases/download/v0.9.1/…`; `git tag -l 'v*'` stops at `v0.9.0.3`. The recipe cannot
   be validated until that release is cut (`docs/RELEASE-PROCESS.md`).
3. **The release does not wait for the listing.** F-Droid reviews externally and on its own
   schedule, so 0.9.1 is published to GitHub Releases and Obtainium first, and the submission
   follows. The store rail remains the intent, and it is not a condition on the cut.
4. **What the submission still needs:** a released tag, the published APK at the `Binaries:` URL,
   a rebuild on a clean machine that matches it, and the merge request itself, which is an
   outward-facing act for the owner to send. The rebuild comparison is F-Droid's own
   reproducible-builds flow, which compares the payloads and the signature together;
   `scripts/repro-check.sh` compares unsigned pairs and is not that comparison.
5. **After listing**, each new tag needs the same check: F-Droid's rebuild of the tag must match
   the APK we published. A mismatch means F-Droid skips that version, and it falsifies the
   reproducibility claim rather than being the store's problem.

`docs/REPRODUCIBLE.md` is the measurement the listing depends on; `docs/DECISIONS.md` D8, D15 and
D40 are the decisions behind the source build, the 1.0 gate and the release order.

## What this cannot do

- It cannot be installed from F-Droid; the app is not listed.
- The recipe has never been executed by `fdroidserver`. Its `subdir` is `android/app`, while the
  Gradle wrapper is `android/gradlew`; whether the builder resolves the wrapper from that
  directory has not been tested.
- It cannot prove the rebuild yet: no released tag has been compared with an F-Droid build, and
  no tool in this repository compares a signed APK with an unsigned rebuild.
- It cannot show a full set of store metadata: no `phoneScreenshots`, and no changelog for the
  current version code.
- It cannot cover the second ABI: `armeabi-v7a` is built but has not been compared across build
  directories the way `arm64-v8a` has.
- It cannot replace the release process; a listing points at a release, it does not make one.
