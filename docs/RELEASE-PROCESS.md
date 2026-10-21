# Release process

Hermes Vox ships as a signed Android APK. Distribution is GitHub Releases,
Obtainium and F-Droid; Google Play is not a rail (see
[`DECISIONS.md`](DECISIONS.md)). We always publish our own signature, so a
release is not done until the APK is **reproducible** and the released artifact
is the same one a rebuild produces.

## Standard release (every cut)

1. **Bump the version** in `android/app/build.gradle` (`versionCode` + `versionName`) —
   **on `testing`, before the merge**. The merge then carries it into `main`, and the
   two branches agree afterwards.

   **The one way to get this wrong:** bumping inside the merge commit on `main` only.
   `testing` then stays a code behind, every nightly from then on carries a
   `versionCode` below the release's, and Android refuses the downgrade — so a phone
   on the release cannot take a nightly without uninstalling first. 0.8.0 did exactly
   that (`33786fa`); `testing` was brought forward to 141 to repair it.

2. **Update the in-app release note.** `ReleaseNotes.kt` carries the banner text and
   any breaking-change alert with its action. A release that changes something the
   user must act on is not finished until that entry exists — the banner is the only
   place the app can say so before the user hits it.

3. **Gate the full tree** — the authoritative build is on the Thelio (`the build host`):
   - **A fresh checkout must fetch the runtime first:** `bash scripts/fetch-runtime.sh`.
     `android/app/libs/sherpa-onnx-1.13.6.aar` is gitignored, so on a clean tree both
     `:app` and `:bench` fail to compile with `Unresolved reference 'k2fsa'` — which
     reads like a broken branch and is only a missing download. (Learned cutting 0.9.0.)
   - `bash scripts/gate.sh` (debug build, every unit test, the stream parity harness).
   - Android: `gradle assembleRelease testReleaseUnitTest`.

4. **Prove reproducibility.** Build the release twice from clean checkouts at
   different paths and compare the payloads:

   ```sh
   apksigcopier compare  <published.apk> --unsigned <rebuilt-unsigned.apk>
   diffoscope --text /tmp/diff.txt <published.apk> <rebuilt-unsigned.apk>
   ```

   Any difference is a release blocker until it is explained. The residual
   nondeterminism list lives in `docs/REPRODUCIBLE.md`; R8 determinism at our exact
   AGP/Kotlin combination is still being established, so this is a measurement, not a
   checkbox.

5. **Verify the artifact** (anchor rule — a green gate on a claim is a false witness):
   - `sha256sum` the APK and record it in the release notes.
   - `aapt2 dump badging` — confirm `versionCode`/`versionName` match the bump.
   - `apksigner verify --print-certs` — confirm `CN=Hermes Vox`, not the debug key.

6. **Release** via `gh release create` with the version + notes (referencing
   `docs/RELEASE-<version>.md`), attaching the APK and its `SHA256SUMS`.

7. **Ask the site to rebuild** — the page is generated from the tag:

   ```sh
   scripts/notify-site.sh vX.Y.Z
   ```

   It needs a `gh` token with access to `chezgoulet/hermes-vox-site`. If the
   dispatch is missed, the site's daily self-heal rebuilds from the latest release,
   so the cost is a day of staleness rather than a page that drifts.

8. **Confirm** on a phone: install, connect to your gateway, one live call.

## Version codes: nightlies and releases cannot both lead

Android installs an APK only if its `versionCode` is **equal or higher** than the installed one.
Equal is a reinstall, higher is an upgrade, lower is a downgrade and is refused.

Nightlies carry the same `versionCode` as the release they follow, which gives one rule:

- **`testing` must never be behind `main`'s latest release.** If it falls behind, every nightly
  from that branch is a downgrade and cannot install over the release without an uninstall.
  0.8.0 did exactly that (`33786fa`): the bump landed on `main` only, `testing` stayed at 140,
  and phones on the release could not take a nightly.

`scripts/check-version-code.sh` enforces it, and CI runs it before the build:

```sh
scripts/check-version-code.sh            # development: fails when this branch lags the release
scripts/check-version-code.sh --release  # cut: fails unless this code is above every shipped code
```

Two consequences worth stating plainly:

- **A hotfix bumps on `testing` first**, then merges to `main`. Bumping on `main` alone costs a
  back-merge and a window in which nightlies are downgrades.
- **Rolling back to the release a nightly forked from depends on the code, not the tag.** While
  `testing` is level with the shipped release, a nightly carries the same `versionCode`, and
  moving to that release is an equal-code reinstall — allowed, no uninstall. Once `testing` bumps
  for the next release, its nightlies are ahead of the shipped code, and rolling back to that
  release *is* a downgrade and needs an uninstall. The next release is always cut above any
  nightly, so returning to the release track works at every cut — it just cannot work for a
  release the branch has already passed. If painless side-by-side switching is ever wanted, the
  only clean answer is a separate `applicationId` for nightlies (two installable apps), not a
  cleverer version code.

  Obtainium can also simply not offer that move: with "hide downgrades" on (the default) it
  compares the version strings it extracted from the tags, and a `nightly-…` tag reads as newer
  than `0.9.0.1` — so the release is suppressed even though the codes are equal. That is a display
  decision, not an install restriction; a manual install of the release APK succeeds when the
  codes match.

Nightlies carry a **label**, not a different version code: the workflow rewrites
`versionName` to `<release>-nightly-<short sha>` before the build and leaves `versionCode`
alone, so a phone can tell you which build it is running while installability is unchanged.

**Nightlies are not releases.** Every merge into `testing` publishes a `nightly-*`
prerelease built from that commit (`.github/workflows/nightly.yml`) and tagged at it:
it is signed with the release key and carries a `versionCode` at or above the
release's, so it installs over a release; it never becomes "Latest", and it is not a
cut. Only the steps above make a release, and only from `main`.

## F-Droid

F-Droid builds the app on its own buildserver and, because we ship
`Binaries:` + `AllowedAPKSigningKeys`, publishes **our** signed APK when its rebuild
matches. That makes step 4 the gate for F-Droid as well as for us.

- The recipe lives in `docs/fdroiddata/com.hermesvox.yml` (draft until submission).
- sherpa-onnx and onnxruntime are built **from source** in the recipe; the prebuilt
  AAR path is not acceptable to F-Droid (see `DECISIONS.md` D8 and issue #165).
- After a release, check that F-Droid's build of the new tag reproduces our APK; a
  mismatch means F-Droid skips that version rather than shipping something else.

## Signing / secrets

- Release signing loads the **gitignored** `keystore/keystore.properties`
  (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`). The keystore is NOT committed.
  **`storeFile` must be relative to the repository root** (`keystore/release.keystore`).
  An absolute path is resolved against the repo root like a relative one, and the build
  fails on a doubled path (`<repo>/<absolute path>`) that looks like a missing file.
  (Learned cutting 0.9.0.)
- **Never** commit `keystore/release.keystore` or `keystore/keystore.properties` — they
  live on the Thelio and in the env store. Keep a backup off-repo.
- The C0 guard fails any release build (`assembleRelease` or `bundleRelease`) that has a
  baked-in `API_SERVER_KEY`, so users only ever enter their own.

## Known landmines

- **Gradle wrapper:** the checked-in wrapper can be stale; the Thelio uses
  `~/gradle-8.12.1/bin/gradle`.
- **Build logs are never committed** — `.gitignore` covers `*.log`/`*.out`/`artifacts/`.
