# Play app signing + AAB upload — getting Hermes Vox onto Google Play

This is the last 0.6 distribution item. The *build* half is done/verified (the Gradle
config signs the release and `bundleRelease` produces a signed `.aab`). This doc covers
the **Play App Signing** flow and the actual upload. Split honestly: **what the repo/build
provides** vs **what needs Christopher's Play account**.

---

## What is already in place (the build side — verified)

- **Release signing** is configured in `android/app/build.gradle` — it loads a **gitignored**
  `keystore/keystore.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`),
  resolves the keystore against the repo root, and **fails loudly** if the keystore is
  missing (never silently falls back to the debug key — the C0 rule).
- **Signing identity:** `CN=Hermes Vox, OU=ChezGoulet, O=ChezGoulet, L=Windsor, ST=VT, C=US`.
- **`bundleRelease` is a real, respected task** — the C0 release-guard recognizes it, so a
  release build (APK or AAB) is checked for baked-in keys and fails the build if one is
  found.
- **Proguard/R8 + ABI filters** are set for release (`minifyEnabled`, `abiFilters
  arm64-v8a + armeabi-v7a`). Android Play distributes the AAB and lets Google split the
  native libs per-device, so the AAB doesn't need the ABI filtering the APK does — but it
  doesn't hurt to keep the release config consistent.

## The one command that builds the AAB

From the repo (with the keystore present via `keystore/`):

```
cd android
./gradlew bundleRelease --no-daemon
# -> android/app/build/outputs/bundle/release/hermes-vox-<version>.aab
```

(On the Thelio: `/home/c/gradle-8.12.1/bin/gradle bundleRelease --no-daemon`.)

The AAB is **signed with the release key** (not the debug key). Verify before upload:

```
apksigner verify --print-certs app/build/outputs/bundle/release/*.aab
# cert DN should be CN=Hermes Vox, OU=ChezGoulet, ...
```

---

## Play App Signing (the Google-side handshake — Christopher's step)

Google Play **requires** Play App Signing for new apps. This is a one-time setup that
hands the signing responsibility to Google (you keep an **upload key**; Google signs the
final artifact with the *app signing key*).

### Why you want it (and why it's not a downside)
Play App Signing means Google manages the app-signing key and can **auto-apply security
updates + key rotation** without you re-uploading. It also keeps your app-signing key safe
on Google's side. The APK you upload is signed with a separate **upload key** that you
control, and Google re-signs for distribution.

### Setup (Christopher, in the Play Console):
1. Create the **Google Play developer account** (one-time $25).
2. Register the app / create the listing (package = `com.hermesvox`).
3. Create the **app-signing key** (Google generates it) AND register an **upload key**.
   - The **upload key** is the existing `keystore/release.keystore` + its alias (`hermes-vox`)
     + the keystore password/key-password from `keystore/keystore.properties` — i.e. the key
     we already sign release builds with. Register that as the upload key.
   - Google's app-signing key is separate; you only ever upload with the upload key.
4. Enable **Play App Signing** (accept the terms; you control the upload key).

### The upload key rule (critical — don't lose it)
Google Play App Signing means: **the upload key (the repo keystore) signs what YOU upload;
Google's app-signing key signs what ships.** If you lose the upload key you can recover via
Play App Signing key reset *if* you have the app-signing key — but keep both the
`release.keystore` and the `keystore.properties` (passwords) **backed up off-repo**. Never
commit them.

---

## What still needs to happen (the honest split)

**Repo/build (Torc — done or doable):**
- `bundleRelease` produces a signed AAB — **verified**. The two thing that might need a tweak
  are (a) the release-guard prints `hermes-vox-${versionName}.apk` as the output file name —
  for a `.aab` the name is `hermes-vox-<version>.aab` (minor; the AAB already gets the right
  base name from Gradle), and (b) whether the AAB needs the ABI filters (it doesn't — Play
  handles native splitting, but it's harmless to leave).
- No **keystore/password secrets** are ever committed — this stays true for Play.

**Christopher (Play account + first upload):**
- Create the Play developer account + listing.
- Register the existing `release.keystore` as the upload key.
- Enable Play App Signing.
- Upload the AAB, fill the **data-safety / privacy** form (the repo has `PRIVACY.md` — link it),
  the **content rating** questionnaire, **app category**, and the store listing (the repo README
  + GitHub Pages site are the copy source).
- The first upload/production review.

## Security note
- **Never** commit `keystore/release.keystore` or `keystore/keystore.properties`. The Gradle
  config loads them from the gitignored `keystore/` dir. If they're ever compromised, rotate
  — with Play App Signing the app-signing key is Google's (safe), and you'd re-register the
  upload key.
- The C0 build-guard stays: a release build with a baked-in `API_SERVER_KEY` fails. Play
  users **enter their own gateway key** at onboarding.

---

## Status
- Build half: **ready** (AAB signed, verified). 
- Play handshake: **Christopher's** (account + upload-key registration + first listing).
- After both: the "install from Play" story replaces the Obtainium-only path — but Obtainium
  stays first-class for side-loaders (and our data-safety frame is clean regardless).
