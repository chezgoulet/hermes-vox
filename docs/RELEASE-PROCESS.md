# Cutting a release

A **release** is a signed Android APK — the installable Android package — attached to a GitHub
release at the tag `v<versionName>`: `v0.9.1` for version name `0.9.1`. **Testing** is the rolling
pre-release channel: every push to the `testing` branch publishes one, it is always a prerelease,
and it is never "Latest". **Obtainium** is an
Android app that installs and updates APKs from a repository like this one; it follows that
release. **F-Droid** is the free-software catalogue that builds apps from source; its recipe
names this release's APK (`docs/FDROID.md`). A release is cut from `main` by tagging a commit
that was built twice in CI, verified, and published by hand.

This document is the procedure for someone who has never cut one. It assumes no history: it
names the branch, the two version fields, the workflow, the files that must change, and the
commands, in order. `docs/REPRODUCIBLE.md` explains what the two builds prove;
`docs/FDROID.md` is the store rail that follows a release.

## What you need before starting

- Push access to `chezgoulet/hermes-vox`, and `gh` (the GitHub CLI) authenticated with a token
  that can push, dispatch workflows and create releases. `gh auth status` must show it.
- JDK 17 and the Android SDK on the machine where you run the gate:

  ```sh
  export JAVA_HOME="$HOME/jdk-17"
  export ANDROID_HOME="$HOME/Android/Sdk"
  export PATH="$JAVA_HOME/bin:$PATH"
  ```

  The gate refuses to run on any other Java major version, because the release is built on 17.
  `apksigner` below runs `java` from `PATH`, so the third line is not optional.
- The release signing key. It exists in two places only: the encrypted files
  `keystore/release.keystore.age` and `keystore/keystore.properties.age` in this repository, and
  the age identity that decrypts them, held as the repository secret `KEYSTORE_AGE_KEY`. The
  workflow decrypts them into `keystore/release.keystore` and `keystore/keystore.properties`.
  There is no plaintext keystore in the tree or on the build host; that is deliberate and it is
  why the signed release APK can only be produced by CI. Keep a copy of the age identity off this
  repository — without it no future release can be signed with the key phones already trust.

## The short version

1. Bump `versionCode` and `versionName` in `android/app/build.gradle`, **on `testing`**.
2. Add the in-app release note in `ReleaseNotes.kt`, and the store changelog for the new code.
3. Run the gate — the one-command build, test and self-check at `scripts/gate.sh` — on `testing`.
4. Merge `testing` into `main`.
5. Check the version code against the shipped tags, then dispatch the release build from the
   exact commit.
6. Read the reproducibility verdict.
7. Verify the downloaded artifact: hash, badging, signer.
8. Publish the tag and the release with that APK.
9. Ask the site to rebuild, then install it on a phone and make one call.

## 1. Choose the version

Two fields in `android/app/build.gradle` name a build:

- **`versionCode`** — the integer Android compares when installing. It must rise by one for every
  build that reaches a phone; Android refuses to install a lower one over a higher one.
- **`versionName`** — the human name, and the release tag body (`v` + this string).

The convention, visible in the tag list and in `ReleaseNotes.kt`:

- **A feature release** raises the minor component: `0.9.0`, then `0.9.1`.
- **A fix to an already-published version** appends a component: `0.9.0.1`, `0.9.0.2`,
  `0.9.0.3`. Each is its own tag and its own release.
- **`versionCode` rises by exactly one per cut.** `0.9.0.3` is 146, so `0.9.1` is 147.

Read both values out of the tree rather than remembering them:

```sh
VERSION="$(grep -m1 'versionName' android/app/build.gradle | sed 's/.*"\(.*\)".*/\1/')"
CODE="$(grep -m1 'versionCode' android/app/build.gradle | awk '{print $2}')"
echo "versionName=$VERSION versionCode=$CODE tag=v$VERSION"
```

The tree carries the version of the cut being prepared. A finished, tagged cut leaves the tree
reading the version just shipped until the next bump, which is correct: a `versionCode` equal to
the shipped one is an allowed reinstall.

## 2. Bump on `testing`, before the merge

```sh
git fetch --tags --force origin
git checkout testing
git pull --ff-only
```

Edit the two lines in `android/app/build.gradle` — `versionCode` and `versionName` — and commit
them with the rest of the release.

**Why on `testing` and not on `main`:** every merge into `testing` publishes a **testing release** —
a rolling pre-release APK built from that commit, not a release — carrying the same `versionCode` as
the release it follows. A bump that lands only on `main` leaves `testing` a code behind, and every
testing release after it is a *downgrade* that a phone on the release refuses to install without an
uninstall first — 0.8.0 is the recorded case.
`scripts/check-version-code.sh` fails in that state, but the branch is the thing that has to be
right.

## 3. Add the in-app release note

`android/app/src/main/java/com/hermesvox/ReleaseNotes.kt` holds the notes that ship **inside**
the APK: a banner shown once after an upgrade, and optionally one **alert** with a single action
the app can route (open the connection settings, open the model screen, open a URL).

Add a `ReleaseNote` whose `versionCode` is the new code and whose `versionName` is the new name:

```kotlin
ReleaseNote(
    versionCode = 148,
    versionName = "0.9.2",
    title = "The <short name> release",
    bullets = listOf(
        "What changed, in the user's words.",
        "What else changed.",
    ),
),
```

Add a `ReleaseAlert` only when the user must **act** — a breaking change, a new permission, a
migration — never for marketing. An alert carries the title, the body, one `AlertAction`
(`ENTITY`, `MODELS`, `URL`, `NONE`), the button label and, for `URL`, the address.

**Why this cannot be skipped:** `ReleaseNotes.shouldShow` requires an entry for the new
`versionCode`. Without one the upgrade is silent — the app has nowhere else to tell the user
that something they rely on has changed.

## 4. Add the store changelog

F-Droid (and any other store reader) renders `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
Create the file named after the new code, in plain sentences, from the same content as the
release note. The tree currently carries `142.txt` and `144.txt`; a version without a file is
listed with no changelog.

## 5. Run the gate on `testing`

```sh
bash scripts/gate.sh
```

The gate prints `GATE-GREEN` as its last line. Anything else — a failed test, a missing suite, a
missing toolchain — is not a green gate, even if an earlier line said the build succeeded. It
fetches the pinned speech runtime itself (a clean checkout has no AAR, and `:app` will not
compile without one), builds the debug APK, runs every unit test and checks that the stream
parity, mTLS and live-gateway harnesses actually ran.

The gate takes an exclusive lock on the host (`/tmp/hermes-vox-gate.lock`) and waits for another
gate to finish. Two gates on one machine share `~/.gradle`, and the loser's test-results
directory corrupts in a way that reads as a failing test while being contention; the lock is
there so a red gate means a red tree.

## The confirmed human run — between step 5 and step 6

**Adopted 2026-11-11 (D41).** A release is not a build that passed; it is a build that was used. The
gate is a person, not a duration.

- The candidate is the **testing release** built from the commit you are about to merge — the same
  code with a different label, already signed with the release key, and it installs over the
  previous release.
- **One human installs it and uses it in ordinary course**: a real session, on a real device, with
  the twelve paths in `docs/REGRESSION.md` among what they do. Ten minutes on a phone is the device
  smoke (step 3 of the per-cut procedure), not this.
- **It defaults to the owner, and a volunteer takes it when one exists.** The release document names
  who did it and says what they did. Until a volunteer exists, the reviewer and the cutter are the
  same person and the document records that plainly: it is a weaker check than a second pair of
  eyes, and the day a volunteer runs a candidate, the author's own run stops counting.

There is no soak duration and there is no calendar. A release ships when this gate and the others
are green, and not before.

## 6. Merge `testing` into `main`

```sh
git checkout main
git pull --ff-only
git merge --no-ff testing
git push origin main
```

The bump arrives on `main` with the merge; do not edit the version here. `main` is the branch
releases are cut from, and cutting from it means the tagged commit is the one that had a testing
release and a green gate.

The push runs CI (`.github/workflows/ci.yml`): the version-code check, the presence-report and
app-skill self-tests, the debug build and every unit test. A red CI on `main` stops the cut here.

## 7. Check the code, then build the release from the commit you will tag

Run this **before** the tag exists:

```sh
git fetch --tags --force
bash scripts/check-version-code.sh --release
```

It must print `a release may be cut`. **Why the order matters:** the check reads every `v*` tag
and compares the current code with the code recorded in the newest tag's `build.gradle`. Once
`v0.9.1` exists, the newest tag *is* this commit, the two codes are equal, and `--release`
refuses an equality by design — a re-cut of a shipped code cannot install over it.

Then dispatch the release build from the exact commit, not from a branch name:

```sh
VERSION="$(grep -m1 'versionName' android/app/build.gradle | sed 's/.*"\(.*\)".*/\1/')"
SHA="$(git rev-parse HEAD)"
gh workflow run release-build --ref main -f version="$VERSION" -f ref="$SHA"
```

`--ref main` selects the workflow file; `-f ref=$SHA` is the commit that is built. **Why the
SHA and not `main`:** `main` can move while two release builds run, and the tag must name the
commit that produced the APK, or the release page and the artifact disagree about what shipped.
Keep `$SHA` and `$VERSION` for the publish step.

The workflow `.github/workflows/release.yml` runs three jobs:

- **build (A)** — decrypts the keystore, builds the sherpa-onnx runtime from the pinned
  submodule with NDK r28c, runs `assembleRelease testReleaseUnitTest` with
  `-Phermesvox.sherpaSource=../sherpa-src`, and prints the APK's badging, signer and SHA-256.
- **rebuild (B)** — the same build again from a clean checkout, with no tests, and hashes it.
- **compare** — prints both hashes and a verdict.

The version name is asserted against the `version` input, and a testing-stamped name
(`…-nightly-…`) is refused. Nothing else about the version is asserted, and the workflow does not
run the gate or the version-code check: those are steps 5 and 7.

```sh
RUN="$(gh run list --workflow release-build --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run watch "$RUN" --exit-status
gh run download "$RUN" --dir dist
```

`dist/` then holds `release-apk-a/` (`hermes-vox-<version>.apk`, `SHA256SUMS`),
`release-apk-b/` (`hermes-vox-<version>-rebuild.apk`, `hash-b.txt`) and
`reproducibility-report/compare.txt`.

## 8. Read the reproducibility verdict

The commands from here to step 12 use `$VERSION`, `$SHA` and later `$APK` from the shell in step
7; run them in that shell.

```sh
cat dist/reproducibility-report/compare.txt
```

It prints `VERDICT: reproducible — identical payloads`, or that the two builds differ. **The
compare job does not fail on a difference** — it reports — so this line, not the job's green
status, is the proof. A difference blocks publication until it is explained;
`docs/REPRODUCIBLE.md` is the list of what can differ and how each is measured.

The proof here is two signed builds of the same commit on the same CI image. A rebuild on a
different toolchain is compared by `scripts/repro-check.sh` (two unsigned builds, the
development runtime), not by this workflow.

## 9. Verify the artifact you are about to publish

```sh
APK="dist/release-apk-a/hermes-vox-$VERSION.apk"
BT="$(ls -d "$ANDROID_HOME/build-tools/"* | sort -V | tail -1)"
sha256sum "$APK"
"$BT/aapt2" dump badging "$APK" | head -1
"$BT/apksigner" verify --print-certs "$APK"
```

Three checks, three different failures:

- **Hash.** The value must equal the one in `dist/release-apk-a/SHA256SUMS` and the one printed
  in `compare.txt`. The file inside `SHA256SUMS` names the CI build path
  (`android/app/build/outputs/…`), so compare the hash value, not with `sha256sum -c`.
- **Badging.** The first line is the package line: `versionCode='<code>' versionName='<name>'`
  and `com.hermesvox`. A wrong version here means the wrong commit or an interrupted bump.
- **Signer.** `apksigner` prints `Signer #1 certificate SHA-256 digest:`. It must be
  `8be7feed8a6135d3d2df8fd51598517dabd4b14ea16a1a02b00c10ea4dcbbb19`, the value in the F-Droid
  recipe's `AllowedAPKSigningKeys`. Any other digest — in practice, a build signed with the debug
  keystore — is an APK phones on the release track cannot update over.

## 10. Publish

```sh
gh release create "v$VERSION" \
  --target "$SHA" \
  --title "Hermes Vox $VERSION" \
  --notes-file "docs/RELEASE-$VERSION.md" \
  "$APK" "dist/release-apk-a/SHA256SUMS"
```

- **`--target "$SHA"`** makes the tag point at the commit that was built. Without it `gh` tags
  the default branch's current head, which may have moved: a tag whose name says one commit and
  whose artifact came from another. The testing-release workflow guards the same failure with
  `target_commitish`.
- **`--notes-file docs/RELEASE-<version>.md`** makes the release page the record of the cut, and
  fails the command if that file is absent. `CONTRIBUTING.md` requires one per release; write it
  in the same pull request as the bump, from the release note's bullets.
- The APK asset must keep the name `hermes-vox-<versionName>.apk`: Obtainium matches APK assets,
  and F-Droid's `Binaries:` URL is built from that pattern (`docs/FDROID.md`).
- A non-prerelease release becomes the repository's **Latest**. Nightlies are published as
  prereleases with `make_latest: false`, so this is the only thing that moves the `latest` link.

## 11. Ask the site to rebuild

```sh
bash scripts/notify-site.sh "v$VERSION"
```

The public site is generated from the tag: version, date, APK size and checksum, and the
release-note bullets. Dispatch after the release exists, or the site has nothing to read. The
script sends a `hermes-vox-release` repository dispatch and needs a `gh` token with write access
to `chezgoulet/hermes-vox-site`. A missed dispatch is repaired by the site's daily rebuild; the
cost is a day of staleness, not a page that drifts.

## 12. Confirm on a phone

```sh
adb install -r "$APK"
```

Install over the previous build without uninstalling (the signing key is the same), connect to a
gateway, and make one live call. A release that installs but cannot hold a call is not done;
this is the only check that the artifact, not just the build, works.

## The testing release is not a release

Every push to `testing` publishes a **testing release** — a rolling pre-release of active
development — with the same release signature and the same `versionCode` as the release it
follows, and a `versionName` stamped with the commit. The label changes and nothing else, so a
phone can name the build it is running while installability is unchanged. A testing release never
becomes "Latest", and nothing about it is a cut. Only the steps above make a release.

**Vocabulary, and the one place the old name survives.** The channel was called a *nightly* until
2026-11-11 and is called **testing** from then on: it is the branch's rolling release, and the name
now says so. Records written before that date — baselines, budgets, release notes — still say
"nightly" and are correct to: they describe the channel under its old name. The workflow still
tags `nightly-YYYYMMDD-HHMMSS-<sha>` and stamps `…-nightly-<sha>`; renaming the tag prefix is a CI
change, it is not made here, and nothing in the process depends on the prefix.

`scripts/check-version-code.sh` encodes the one rule that keeps both tracks installable:

```sh
bash scripts/check-version-code.sh            # development: fails if testing lags a shipped release
bash scripts/check-version-code.sh --release  # a cut: fails unless the code is above every shipped one
```

CI runs the first on every push to `testing` and `main`. The second is step 7, run by hand
because it has to happen before the tag exists.

## Signing and secrets

- The signing configuration loads `keystore/keystore.properties` — `storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`. The file and the keystore it names are gitignored; only their
  age-encrypted forms are committed.
- **`storeFile` is relative to the repository root**: `keystore/release.keystore`. The build
  resolves it against the repository root explicitly, so an absolute path becomes
  `<repo>/<absolute path>` and the build reports a missing keystore that is in fact present.
- The key alias defaults to `hermes-vox` when `keyAlias` is absent.
- **Never** commit `keystore/release.keystore` or `keystore/keystore.properties`, and never
  paste either into a chat, an issue or a log.
- A release build (`assembleRelease`, `bundleRelease`) fails if `HERMES_VOX_API_KEY` is set in the
  environment or if a `buildConfigField` that mentions a key is added to the Gradle sources: the
  gateway key is entered by the user in the app, never baked into an APK.
- A release build without `keystore/keystore.properties` fails rather than falling back to the
  debug key. The unsigned path exists only for the reproducibility check
  (`-Phermesvox.unsigned`).

## What a release changes

- A git tag `v<versionName>` on the commit that was built, and a GitHub release with
  `hermes-vox-<versionName>.apk` and `SHA256SUMS` attached.
- The repository's "Latest" release, which Obtainium and the README's download link follow.
- The site's version panel, after the dispatch.
- Nothing about `testing`'s nightlies beyond the code they already carry.

## Known landmines

- **Build logs and outputs are never committed.** `.gitignore` covers `*.log`, `*.out`,
  `artifacts/` and the build directories; `dist/` from step 7 is scratch.
- **The Gradle wrapper is pinned.** `android/gradle/wrapper/gradle-wrapper.properties` names
  `gradle-8.12.1-bin.zip` with its `distributionSha256Sum`; use `./gradlew`, not a system
  `gradle`, so the release build and the gate run the same Gradle.

## What it cannot do

- It cannot be cut without the age identity for `KEYSTORE_AGE_KEY`; no signed APK can be built
  anywhere else.
- It does not run the gate or the version-code check for you: the workflow builds and tests, and
  steps 5 and 7 are the human checks.
- It cannot publish a lower `versionCode` over a phone that has a higher one — an install is
  refused, not downgraded.
- It cannot prove a third-party rebuild: the workflow compares its own two builds. The
  independent check is `scripts/repro-check.sh`, which builds unsigned.
- It cannot list the app on F-Droid; that is a separate submission that follows the release
  (`docs/FDROID.md`).
- It cannot be published to Google Play; Play is not a rail.
