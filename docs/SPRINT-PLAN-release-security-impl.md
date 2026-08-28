# Sprint 3 Implementation Plan — Release Signing + Security Cluster + #60 (prepared from `docs/SPRINT-PLAN-release-security.md`)

Target artifact: `docs/SPRINT-PLAN-release-security-impl.md`

## WORKSTREAM A — Release signing (keystore + shrink)

### A0. Verified grounding facts
- `signingConfigs.release` hardcodes `~/.android/debug.keystore` / `"android"` / `"androiddebugkey"` (`android/app/build.gradle:18-25`).
- `minifyEnabled false` + **no `shrinkResources`** (`build.gradle:28`); **no `proguardFiles` line exists**, and `proguard-rules.pro` does **not exist** (must be created).
- Release key already generated: `keystore/release.keystore` (PKCS12, `/home/c/hermes-vox/keystore/`, gitignored) + `keystore/keystore.properties` (gitignored — confirmed via `git check-ignore`). Keys: `storeFile`, `keyAlias`, `storePassword`, `keyPassword`, alias `hermes-vox`. Keystore dir is in `.gitignore`. **Never echo the password (kept in env store `HERMES_VOX_KEYSTORE_PASSWORD`).**
- `mobile.aar` contains `com/hermesvox/mobile/Mobile.class` + `com/hermesvox/mobile/HermesSession.class` and `jni/*/libgojni.so`; JNI exports `Java_com_hermesvox_mobile_Mobile*` and `Java_com_hermesvox_mobile_HermesSession*`.
- `sherpa-onnx-1.13.6.aar` → `com.k2fsa.sherpa.onnx.*`, native `jni/*/libsherpa-onnx-jni.so`.
- `litertlm-android:0.16.1` → `com.google.ai.edge.litertlm.**` incl. `NativeLibraryLoader`, `LiteRtLmJni` (JNI), `ReflectionTool` (reflection).
- `commons-compress:1.26.2` used reflectively/service-loaded in `ModelDownloader.kt` (`TarArchiveInputStream`, `BZip2CompressorInputStream`).

### A1. Load the real keystore in `build.gradle`

**File:** `android/app/build.gradle`

**Current (lines 1-2):**
```gradle
apply plugin: 'com.android.application'
apply plugin: 'org.jetbrains.kotlin.android'
```
**Replacement** (append after line 2):
```gradle
apply plugin: 'com.android.application'
apply plugin: 'org.jetbrains.kotlin.android'

// Release signing: load the gitignored keystore/keystore.properties (storeFile /
// storePassword / keyAlias / keyPassword). Secrets live in that gitignored file or
// the env store — NEVER in this repo. A missing file fails loudly (with a clear
// message) so we never silently fall back to the public debug key.
// Gradle root is android/; the keystore dir sits one level up at the REPO root, so
// resolve explicitly against repoRoot = rootProject.projectDir.parentFile (hermes-vox/).
def repoRoot = rootProject.projectDir.parentFile
def keystoreProps = new Properties()
def keystorePropFile = new File(repoRoot, "keystore/keystore.properties")
if (keystorePropFile.exists()) {
    keystoreProps.load(new FileInputStream(keystorePropFile))
}
```

**Current (lines 18-25):**
```gradle
    signingConfigs {
        release {
            storeFile file("${System.getProperty('user.home')}/.android/debug.keystore")
            storePassword "android"
            keyAlias "androiddebugkey"
            keyPassword "android"
        }
    }
```
**Replacement:**
```gradle
    signingConfigs {
        release {
            def kp = keystoreProps
            storeFile new File(repoRoot, kp.getProperty("storeFile", "keystore/release.keystore"))
            storePassword kp.getProperty("storePassword")
            keyAlias kp.getProperty("keyAlias", "hermes-vox")
            keyPassword kp.getProperty("keyPassword")
        }
    }
```

### A2. Enable shrink + resource shrink, and wire `proguard-rules.pro`

**Current (lines 26-32):**
```gradle
    buildTypes {
        release {
            minifyEnabled false
            signingConfig signingConfigs.release
            ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }
        }
    }
```
**Replacement:**
```gradle
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            signingConfig signingConfigs.release
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }
        }
    }
```

### A2a. Version bump (A4 seed) — `build.gradle:11-12`

**Current:**
```gradle
        versionCode 58
        versionName "0.3.23.5"
```
**Replacement:**
```gradle
        versionCode 59
        versionName "0.3.24.0"
```
(APK filename will resolve to `hermes-vox-0.3.24.0.apk` via the existing `outputFileName` block.)

### A2b. Create `android/app/proguard-rules.pro`

**REQUIRED keep entries** (this file does not exist yet — create it; the JNI/native + reflection consumers must be kept or R8 strips them → runtime `UnsatisfiedLinkError`/NoSuchMethod crash):

```
# ---- gomobile bind (mobile.aar): JNI + reflectively-invoked bind classes ----
# Mobile is the Go bind entry (Seq.setContext resolves it by name); HermesSession is
# used directly. Both back native JNI in libgojni.so and must keep exact member names.
-keep class com.hermesvox.mobile.Mobile { *; }
-keep class com.hermesvox.mobile.HermesSession { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.hermesvox.mobile.** { *; }
-keepclassmembers class com.hermesvox.mobile.** { native <methods>; }

# gomobile/Seq runtime (go.Seq / go.Universe are reflectively wired into the bind)
-keep class go.** { *; }

# ---- sherpa-onnx JNI (com.k2fsa.sherpa.onnx.*) ----
# Native libsherpa-onnx-jni.so registers methods by name; kill off any unused-
# class stripping so STT (Vad/OfflineRecognizer) + TTS (OfflineTts) survive shrink.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** { native <methods>; }

# ---- org.apache.commons.compress: service-loaders + reflection (tar/bzip2) ----
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ---- AI Edge litertlm native bridge (JNI + reflective tool dispatch) ----
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.litertlm.** { native <methods>; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniInferenceCallback { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniMessageCallback { *; }
```

> Note: keep rules are also covered by wildcards, but the explicit `native <methods>` + `includedescriptorclasses` are the real guard against the R8-strips-JNI crash.

### A3. Verify the signature

After `./gradlew assembleRelease`:
```bash
# If apksigner is not on PATH, resolve it explicitly:
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs \
  android/app/build/outputs/apk/release/hermes-vox-0.3.24.0.apk
# or (equivalent, standalone): show the cert-subject alias must be hermes-vox, NOT androiddebugkey
# Note: on JDK17 keytool is available via JAVA_HOME:
"$JAVA_HOME/bin/keytool" -printcert -jarfile \
  android/app/build/outputs/apk/release/hermes-vox-0.3.24.0.apk
```
**Pass condition:** the cert subject CN/alias is `hermes-vox` (RSA 4096, PKCS12), **not** `androiddebugkey`. (If a `debug` build or `apksigner` reports `androiddebugkey`, the signingConfig change did not take and the gate fails.)

---

## WORKSTREAM B — Security cluster (#1 / #14 / #50)

### B1. #1 — MainActivity intent injection (remove auto-send / external url+key load)

**File:** `android/app/src/main/java/com/hermesvox/MainActivity.kt`

**Edit 1 — remove the `intentExtras()` call.**
**Current (line 98):**
```kotlin
        intentExtras()
        connectFromPrefs()
```
**Replacement:**
```kotlin
        connectFromPrefs()
```

**Edit 2 — remove the vulnerable function + its field.**
**Current (lines 112-121):**
```kotlin
    private fun intentExtras() {
        // adb/E2E deep-link: connect + send without touching the UI by hand.
        val u = intent.getStringExtra("url"); val k = intent.getStringExtra("key")
        val m = intent.getStringExtra("model"); val say = intent.getStringExtra("say")
        if (!u.isNullOrBlank() && !k.isNullOrBlank()) {
            prefs.edit().putString("url", u).putString("model", m ?: "hermes-agent").putString("key", (SecureStore.encrypt(k) ?: k)).apply()
        }
        if (!say.isNullOrBlank()) { intent.removeExtra("say"); autoSend = say }
    }
    private var autoSend: String? = null
```
**Replacement:** (delete both — no external extras are honored; the launcher stays usable because the MAIN/LAUNCHER filter is unchanged and `exported="true"` is required to be launchable. Entity url/model/key are now user-entered only, via Settings/Onboarding → SecureStore.)

**Edit 3 — remove the auto-send routing.**
**Current (line 307):**
```kotlin
        // Auto-send a routed turn (E2E proof path).
        autoSend?.let { send(it) }
```
**Replacement:** (delete the block.)

> Residual notes: `intent` import and other `Intent(...)` callers stay. `SecureStore` import stays (still used by `storedKey()`). If a genuine deep-link is wanted later, gate it behind a debug flag + a **non-exported** helper activity — do **not** re-enable auto-run on a bare intent.

### B2. #14 — SettingsActivity key defeats SecureStore (decrypt-on-read / encrypt-on-write)

**File:** `android/app/src/main/java/com/hermesvox/SettingsActivity.kt`

**Edit 1 — decrypt the key on read into the dialog.**
**Current (lines 49-51):**
```kotlin
            eurl.setText(prefs.getString("url", ""))
            emodel.setText(prefs.getString("model", "hermes-agent"))
            ekey.setText(prefs.getString("key", ""))
```
**Replacement:**
```kotlin
            eurl.setText(prefs.getString("url", ""))
            emodel.setText(prefs.getString("model", "hermes-agent"))
            ekey.setText(SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty())
```
(Now the field shows the **plaintext** key — mirroring `OnboardingActivity.kt:37`, not the `"IV:CT"` blob. A legacy/plaintext stored value decrypts as-is via `SecureStore.decrypt`.)

**Edit 2 — encrypt the key on write (Save).**
**Current (lines 55-58):**
```kotlin
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString("url", eurl.text.toString().trim())
                        .putString("model", emodel.text.toString().trim().ifEmpty { "hermes-agent" })
                        .putString("key", ekey.text.toString().trim()).apply()
                    refreshEntityVal()
                }
```
**Replacement:**
```kotlin
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString("url", eurl.text.toString().trim())
                        .putString("model", emodel.text.toString().trim().ifEmpty { "hermes-agent" })
                        .putString("key", (SecureStore.encrypt(ekey.text.toString().trim()) ?: ekey.text.toString().trim())).apply()
                    refreshEntityVal()
                }
```
(Mirrors `OnboardingActivity.kt:57`. Because the field was populated from `decrypt`, this encrypts plaintext → single correct `IV:CT`, never double-encrypted.)

**Edit 3 — row_test_conn: decrypt before handing the key to HermesSession.**
**Current (lines 155-156):**
```kotlin
            val u = prefs.getString("url", ""); val k = prefs.getString("key", "")
            val c = com.hermesvox.VoiceController(this, com.hermesvox.mobile.HermesSession(u ?: "", k ?: "", ""))
```
**Replacement:**
```kotlin
            val u = prefs.getString("url", ""); val k = SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty()
            val c = com.hermesvox.VoiceController(this, com.hermesvox.mobile.HermesSession(u ?: "", k ?: "", ""))
```

**row_reset (lines 38-43)** needs **no code change** — it delegates to `MainActivity.resetActiveConversation()`, which reuses the existing session built with the **decrypted** key in `connectFromPrefs()` (via `storedKey()`). It was "broken" only because the key stored was the raw ciphertext token; fixing save/read above fixes reset + test-conn (which was always 401 because the raw `"IV:CT"` was sent as the Bearer token).

### B3. #50 — Scope cleartext to local hosts, base-config = false

**File:** `android/app/src/main/res/xml/network_security_config.xml`

**Current (lines 1-12):**
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Hermes Vox is local-first: the entity endpoint and the model store are
     LAN/tailnet hosts over plain http by design (no cloud, house network). So
     cleartext HTTP is permitted. If a host is ever publicly reachable, serve it
     over TLS. -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system"/>
        </trust-anchors>
    </base-config>
</network-security-config>
```
**Replacement:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Hermes Vox is local-first: the entity endpoint is a LAN/tailnet host over
     plain http (no cloud, house network). Cleartext is permitted ONLY for the
     local hosts listed below; every other host (public/cloud) requires TLS.
     Note: model downloads already go over HTTPS (huggingface.co) and are covered
     by the base-config. If a host is ever publicly reachable, serve it over TLS. -->
<network-security-config>
    <!-- Default: everything not explicitly listed must be HTTPS. -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system"/>
        </trust-anchors>
    </base-config>

    <!-- Local entity endpoint + any LAN gateway (plain http allowed). List the
         actual entity host(s)/subnet(s) the user connects to (IP range of the
         Hermes relay/gateway you run). -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">100.84.47.125</domain>
        <domain includeSubdomains="false">192.168.1.1</domain>
        <!-- add your LAN gateway / tailnet host(s) here -->
    </domain-config>
</network-security-config>
```

> **Trade-off to document/verify:** the app permits an arbitrary user-entered entity host; any host **not** in this `domain-config` will be **blocked** as cleartext (connection fails unless it speaks TLS or the host is added). Either add each host/IP-subnet to the config or document the "entity must be HTTPS or listed here" requirement in the Settings copy.

---

## WORKSTREAM C — #60 turn-gate released exactly once via `sDone`

**File:** `android/app/src/main/java/com/hermesvox/VoiceController.kt`

**Design:** make the release **exactly-once per generation** with a `turnReleased` flag (belt-and-suspenders on the existing epoch guard), and make `settleReply`'s streamed branch the **single canonical** release point for a streamed reply (gated on the streaming worker's completion `sDone`). The extra `releaseTurnGate` calls in `runStreamedTurn`'s error/empty/timeout dispatch and the non-streamed `speak()`/no-speech branches remain (each must release once), but the once-flag guarantees a streamed reply can never be double-released.

**Edit 1 — add the once-flag field.**
**Current (line 51-52):**
```kotlin
    @Volatile private var turnDone = java.util.concurrent.CountDownLatch(1)
    @Volatile private var turnGen = 0L
```
**Replacement:**
```kotlin
    @Volatile private var turnDone = java.util.concurrent.CountDownLatch(1)
    @Volatile private var turnGen = 0L
    @Volatile private var turnReleased = false   // #60: exactly-once gate release per turn
```

**Edit 2 — re-arm the flag at the start of each turn.**
**Current (lines 329-331):**
```kotlin
    private fun runStreamedTurn(text: String, gen: Long) {
        if (turnInFlight) { VoxLog.d("turn suppressed (in flight)"); releaseTurnGate(turnGen); return }
        turnInFlight = true
```
**Replacement:**
```kotlin
    private fun runStreamedTurn(text: String, gen: Long) {
        if (turnInFlight) { VoxLog.d("turn suppressed (in flight)"); releaseTurnGate(turnGen); return }
        turnInFlight = true
        turnReleased = false   // #60: re-arm exactly-once for this turn
```

**Edit 3 — make `releaseTurnGate` exactly-once.**
**Current (lines 611-613):**
```kotlin
    private fun releaseTurnGate(gen: Long) {
        if (gen == turnGen) { try { turnDone.countDown() } catch (_: Throwable) {} }
    }
```
**Replacement:**
```kotlin
    private fun releaseTurnGate(gen: Long) {
        if (gen != turnGen) return
        if (turnReleased) return   // #60: a turn's gate releases exactly once
        turnReleased = true
        try { turnDone.countDown() } catch (_: Throwable) {}
    }
```

**Edit 4 — make the streamed reply take only the `sDone` path.**
**Current (lines 419-429):**
```kotlin
    private fun settleReply(finalText: String, gen: Long) {
        listener?.onLog("// agent → ${finalText.take(120)}")
        listener?.onReply(finalText)
        if (speakEnabled()) {
            if (streamed && (tts?.supportsStreaming == true)) {
                streamFinish()
                exec.execute { try { sDone.await(120, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}; releaseTurnGate(gen) }
            } else speak(finalText, gen)
        }
        else { stopStreaming(); listener?.onState("idle"); releaseTurnGate(gen) }   // no speech -> terminate streaming + release the loop's speak-gate
    }
```
**Replacement:**
```kotlin
    private fun settleReply(finalText: String, gen: Long) {
        listener?.onLog("// agent → ${finalText.take(120)}")
        listener?.onReply(finalText)
        if (streamed && (tts?.supportsStreaming == true)) {
            // STREAMED reply: the single canonical gate release. streamFinish() closes
            // the queue so the streaming worker drains + finishes; the gate releases
            // exactly once here, gated on the worker's completion (sDone). No other
            // release site runs for this reply (#60).
            streamFinish()
            exec.execute { try { sDone.await(120, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}; releaseTurnGate(gen) }
        } else if (speakEnabled()) {
            speak(finalText, gen)
        } else {
            stopStreaming(); listener?.onState("idle"); releaseTurnGate(gen)
        }
    }
```

Notes (intentional, no further edit):
- The `runStreamedTurn` dispatch error/empty/timeout branches still call `releaseTurnGate` — those are **non-reply** ends (no valid text), so each runs once and the flag prevents any overlap with a leftover streamed completion.
- `bargeIn()` (line 608) and `hush()` (line 640) also release — these are interrupt/stop paths; the once-flag makes them a single release (correct: a barge-in aborts the reply and the later `settleReply`, if it ever runs, is a no-op).

**Regression assertion (recommended, verify step):** add a lightweight count + a JVM-friendly unit test. Recommend `@Volatile var turnReleaseCount = 0` incremented inside `releaseTurnGate` plus a log line, then a new pure-JVM test `TurnGateReleaseTest` that asserts `<controller>.turnReleaseCount` is 1 for a completed streamed reply. (Optional, minimal-scope: extracting the gate into a small `TurnGate` object would make it directly unit-testable without an Android `context`; flag for a follow-up if the on-device long-reply run is the preferred gate.)

---

## Risks (must validate at the review gate)
1. **R8 strips native bind → runtime crash.** If any keep rule is wrong/missing, the gomobile bind (`Mobile`/`HermesSession` JNI), sherpa STT/TTS, or litertlm native bridge throws `UnsatisfiedLinkError`/`NoSuchMethodError` at runtime. **Gate:** emulator smoke + a real on-device call AFTER shrink; confirm STT/TTS + the bind still function.
2. **Fresh install on signature change (A5, REQUIRED in release notes).** The on-device install is debug-signed; a release-signed build will NOT install over it (Android signature-mismatch → "app not installed"). First release-signed build = **uninstall the debug one first** (loses in-app settings), then install. Document prominently.
3. **Settings test-conn (B2).** Fixing decrypt-on-read/encrypt-on-write + test-conn decrypt fixes the always-401. Verify `row_test_conn` returns a successful `stream -> 200` against the entity.
4. **#50 scoping (B3).** Any user-entered host not in the `domain-config` gets blocked as cleartext → connection failure. Either enumerate hosts/subnet or document the HTTPS/listed requirement in Settings.
5. **#60.** Low risk (already harmless) — but verify the long-reply (473/974 char) still plays fully and the loop re-arms; confirm the gate releases exactly once.

## Definition of done
- `assembleRelease` → signed with `hermes-vox` (cert verified, NOT `androiddebugkey`), `minifyEnabled`/`shrinkResources` on → release 0.3.24.0, APK `hermes-vox-0.3.24.0.apk`.
- Security: no exported-intent auto-send/injection (#1); Settings key correctly decrypt/encrypt + test-conn succeeds (#14); cleartext scoped to local hosts, base-config cleartext=false (#50).
- #60: gate released exactly once (regression assertion + on-device long reply).
- No regression of the realtime loop, session isolation, barge-in, model downloader, or offline STT/TTS after shrink.
