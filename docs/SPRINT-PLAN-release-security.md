# Hermes Vox — Sprint 3 Plan Brief: Release Signing + Security Cluster + Streaming Tidy-Up

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/release-security
**Plan + build model:** `deepseek/deepseek-v4-flash-vision-exp` (DeepSeek API) · **Owner/approver:** Torc
**Review gate:** Torc adversarial read + compile + emulator smoke + one on-device run.

## Sprint theme
**"Ship it right."** Sprint 2 (0.3.23–0.3.23.5) closed the phone-call flow — realtime multi-turn, barge-in, session isolation all work on-device (verified: 9-turn conversation, incl. a 974-char reply). This sprint makes it **safe + correctly releasable**: real release signing (it is currently debug-signed), the security cluster the opus-5/DeepSeek reviews flagged (intent-injection, plaintext key, blanket cleartext), and the small streaming turn-gate tidy-up (#60). No new voice features.

## Context / anchor points (verified, not assumed)
- **Build host:** Thelio (`c@sasquatch`). Env: `JAVA_HOME=/home/c/jdk-17.0.12+7`, `ANDROID_HOME=/home/c/Android/Sdk`, `ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.2.9519653`, Gradle 8.12.1.
- **Release keystore already generated** (2026-08-27) at `/home/c/hermes-vox/keystore/release.keystore` (PKCS12, alias `hermes-vox`, RSA 4096, 10000-day validity) + `keystore/keystore.properties` (gitignored). Password in House env store `HERMES_VOX_KEYSTORE_PASSWORD`; `keystore/` is gitignored (never committed). This is a **must-not-lose** key — same key for ALL future builds.
- Current `android/app/build.gradle`: `signingConfigs.release` hardcodes `~/.android/debug.keystore` + `storePassword "android"` + `minifyEnabled false`. This is the defect to fix.

## In-scope workstreams

### WORKSTREAM A — Release signing (the keystore + shrink)  ⭐ core
**Why:** the shipped APK is signed with the public Android **debug** keystore (password `"android"`). Anyone can spoof a same-signature update; it is not a real release. Also `minifyEnabled false` → 108 MB unsized APK.
- **A1 — Wire the release signingConfig to the real key.** Replace the hardcoded `~/.android/debug.keystore`/`android`/`androiddebugkey` with a read of `keystore/keystore.properties` (`storeFile`, `keyAlias=hermes-vox`, `storePassword`, `keyPassword`) — standard Android `Properties` load in `build.gradle`. Keep the store password in the gitignored file / env, never in the repo.
- **A2 — Enable shrink.** `minifyEnabled true` + `shrinkResources true` (+ `proguard-android-optimize.txt` defaults) to cut the 108 MB APK. **PITFALL (must handle):** R8 will strip reflectively-invoked code. Keep rules are required for: the gomobile `mobile.aar` bind class (`com.hermesvox.mobile.Mobile` — JNI/gomobile generated, reflectively invoked), the `mobile.aar` JNI entry points, sherpa-onnx JNI (`com.k2fsa.sherpa.onnx.*`), `<org.apache.commons.compress.*>` (uses service-loaders/reflection), and the AI Edge litertlm native bridge. Add `proguard-rules.pro` entries; verify the bind + STT/TTS still function after shrink (a shrunk build that strips JNI crashes at runtime → gate on an emulator + on-device call).
- **A3 — Verify the signature.** After `assembleRelease`, run `apksigner verify --print-certs` (or `keytool -printcert -jarfile`) and confirm the cert is `hermes-vox` (NOT `androiddebugkey`). If `apksigner` is on PATH mismatch, use `$ANDROID_HOME/build-tools/*/apksigner`.
- **A4 — Version + release.** Bump **0.3.24.0** (a clean feature boundary — signature changes). Cut the release (tag === versionName === APK, no leading `v`), `apksigner`-verified, uploaded.
- **A5 — Fresh-install note (REQUIRED in release notes).** The current on-device install is **debug-signed**. A release-signed APK will NOT install over it (Android signature-mismatch → "app not installed" without full-reinstall). The first release-signed build is a **fresh install**: uninstall the debug one first (loses in-app settings), then install. Document this prominently.

### WORKSTREAM B — Security cluster (#1 / #14 / #50)
- **#1 (critical) — Exported MainActivity intent injection.** `AndroidManifest.xml:22` `android:exported="true"`; `MainActivity.kt ~112-120` reads `url`/`key`/`model`/`say` off the launch intent, persists url+key, and **auto-sends `say`** on create. Any installed app can repoint the client + inject a command into an agent with shell/file tools. **Fix direction:** the launcher activity needs the MAIN/LAUNCHER filter to be launchable, so keep it usable but **stop honoring arbitrary intent extras** — remove the auto-send of `say` and the load-and-persist of url/key from the intent (drop `intentExtras()` behavior). The entity address/key are **user-entered in Settings** (+ SecureStore), never taken from an external intent. If a deep-link is genuinely wanted, gate it behind a **debug flag** and use a non-exported helper activity — do NOT auto-run the agent on a bare intent.
- **#14 (high) — Settings key defeats SecureStore (ciphertext-as-token).** `SettingsActivity.kt:53-55,60-62,41-44,158-160` populates the field with the raw `"IV:CT"` blob and writes it back plaintext; `row_reset`/`row_test_conn` pass the raw stored value as the API key → **test-conn always 401** + "New conversation" broken. **Fix direction:** mirror `OnboardingActivity` (decrypt on read, encrypt on write via SecureStore) in SettingsActivity; `row_reset` + `row_test_conn` must decrypt before handing the key to `HermesSession`.
- **#50 (medium) — Blanket cleartext.** `network_security_config.xml` base-config `cleartextTrafficPermitted="true"` for ALL hosts. It's local-first (LAN/tailnet), but a blanket exception contradicts the "no cloud / secret-safe" posture. **Fix direction:** scope cleartext to the specific local hosts — a `<domain-config cleartextTrafficPermitted="true">` for the known LAN/tailnet entity + model-store hosts (e.g. `100.84.47.125`, the LAN gateway), and set the **base-config cleartext = false** for everything else. Keep the comment accurate (don't overclaim scoping). Note: the app allows an arbitrary user-entered entity host, so either document the local-host requirement or add the IP/subnet to the domain-config.

### WORKSTREAM C — #60 turn-gate double-release tidy-up (low risk)
**Finding:** on the longest streaming replies (473/974 chars), `releaseTurnGate` fires **twice** (an early fire + the settle's worker-completion fire). The epoch guard makes the 2nd a no-op → harmless (loop re-arms, reply plays fully, confirmed on-device). **Fix:** consolidate the turn-gate release to **one canonical place** — the streaming-worker completion (`sDone`) — and ensure a streamed reply never routes into the other release paths (`settleReply`'s `speak()`/empty branches, the dispatch error/empty/timeout paths). ~4-6 lines in `settleReply`/`streamFinish`/the release sites.
- **Verify (this file is where regressions live):** compile + emulator smoke + one on-device long-reply run; add a regression assertion that the gate is released exactly once.

## Deferred (do not expand scope)
- **Google Play publish** — separate; blocked on the Play identity/passport verification. The keystore + sideload (Obtainium/GitHub) are ready regardless; Play upload reuses the SAME `hermes-vox` key (no second key needed).
- **Voice-quality roadmap** — streaming/partial STT (#38), latency instrumentation (#40), speech enhancement (later sprints).
- **0.4.0 gates** — Enhanced Realtime (Gemma/E2B), particles+settings, mic settings (separate map, after release hardening).
- #7/#8 crash-hardening (AudioTrack/native use-after-free) — separate sub-track.

## Definition of done
- `assembleRelease` yields a **`minifyEnabled`/shrink** APK signed with the **`hermes-vox`** release key (cert verified, NOT `androiddebugkey`); release **0.3.24.0** cut.
- Security closed: no exported-intent auto-send/injection (#1); Settings API key correctly decrypt/encrypt + **test-conn succeeds** (#14); cleartext scoped to local hosts, base-config cleartext=false (#50).
- #60: gate released exactly once (regression assertion + on-device long reply).
- **No regression:** the realtime loop (0.3.23.5 behavior) still does multi-turn + barge-in; STT/TTS/native bind still work AFTER shrink (emulator + on-device call).
- Fresh-install path documented for the signature change.

## Iron rules (build agent)
- **Never commit secrets.** Keystore gitignored; password from env store / gitignored `keystore.properties` only. Never echo it.
- **Release convention:** tag === versionName === APK, no leading `v`; clean semver (Obtainium). First release-signed = 0.3.24.0.
- **Do not regress** the realtime loop, session isolation, barge-in, model downloader, or offline STT/TTS.
- **Verify the security criticals against real code** before endorsing (#1/#14) — same standard as the opus-5 criticals.
- One coherent commit per fix; each compiles + its tests pass. Byte-precise edits in `VoiceController.kt` (Workstream C).
