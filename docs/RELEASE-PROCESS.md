# Release process — standard steps (incl. Play-compliance build)

Hermes Vox ships as a signed Android build. This is the standard release checklist, kept
current. For the Play Store **compliance build**, two extra steps are gated on the release
intent (see the "Play Store builds" section) — they're part of how we build, even though
we're not publishing to Play yet.

## Standard release (every cut)

1. **Bump the version** in `android/app/build.gradle` (`versionCode` + `versionName`).
2. **Gate the full tree** — the authoritative build is on the Thelio (`c@sasquatch`):
   - Go: `go test ./voice/...` (+ `go vet ./voice/...`)
   - Bind + stage: `gomobile bind` → `mobile.aar` → copy into `android/app/libs/`
     (the AAR staging is load-bearing — Gradle consumes the copy, not the build output).
   - Android: `gradle assembleRelease testReleaseUnitTest`.
   - Confirms the **AAR exports only `HermesSession`** (no Ebitengine — 0.5.7+).
3. **Verify the artifact** (anchor rule — a green gate on a claim is a false witness):
   - `md5sum` the APK on both boxes; confirm it matches.
   - `aapt2 dump badging` — confirm `versionCode`/`versionName` match the bump.
   - `apksigner verify --print-certs` — confirm `CN=Hermes Vox` (not the debug key).
4. **Release** via `gh release create` with the version + notes (referencing
   `docs/RELEASE-<version>.md`).
5. **Confirm** on a phone: install, connect to your gateway, one live call.

## Play Store builds (the compliance half — standard going forward)

Even though publishing to Play is on hold, the **build** for it is part of our flow. When a
release is also destined for Play:

1. **Build the AAB**, not just the APK:
   ```
   cd android && ./gradlew bundleRelease --no-daemon
   # -> android/app/build/outputs/bundle/release/app-release.aab
   ```
2. **Verify the AAB is signed with the upload key** (CN=Hermes Vox). Extract the
   `META-INF/<NAME>.RSA` and confirm via `keytool -printcert` (Owner + Issuer = Hermes Vox),
   since `apksigner` doesn't read `.aab` directly.
3. **Confirm the C0 no-secret guard** passed (it runs on any `bundleRelease`/`assembleRelease`
   and fails if a key is baked in). Play users enter their **own** gateway key at onboarding.
4. **Pre-upload docs** (already in the repo): `PRIVACY.md` (link for the data-safety form),
   `LICENSE`+`NOTICE`, `CONTRIBUTING`+`SECURITY`. The AAB upload, Play App Signing
   handshake, data-safety/content-rating/listing are Christopher's Play-console steps —
   see `docs/PLAY-APP-SIGNING.md`.

## Signing / secrets
- Release signing loads the **gitignored** `keystore/keystore.properties`
  (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`). The keystore is NOT committed.
- **Never** commit `keystore/release.keystore` or `keystore/keystore.properties` — they live
  on the Thelio and in the env store. Keep a backup off-repo.
- The C0 guard fails any release build (`assembleRelease` or `bundleRelease`) that has a
  baked-in `API_SERVER_KEY`, so users only ever enter their own.

## Known landmines
- **AAR staging:** `mobile.aar` is produced by gomobile bind; Gradle links a *copy* in
  `android/app/libs/`. Never skip the copy — it silently builds against a stale bind.
- **Gradle wrapper:** the checked-in wrapper can be stale; the Thelio uses
  `/home/c/gradle-8.12.1/bin/gradle`.
- **Go toolchain:** `GOTOOLCHAIN=go1.26.4` + the cached GOMODCACHE/GOCACHE must be set (the
  auto-toolchain download can fail offline). See `scripts/gate.sh`.
- **Build logs are never committed** — `.gitignore` covers `*.log`/`*.out`/`artifacts/`.
