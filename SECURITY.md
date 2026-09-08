# Security Policy — Hermes Vox

Hermes Vox is **the voice of Hermes**: a thin, local-first Android voice client
that fronts the Hermes agent — the same identity, memory, tools, and context you
use everywhere else. This file describes what Hermes Vox protects, how it is
protected, what is deliberately out of scope, and how to report a vulnerability.
Every control named here exists in the code — read the cited anchors rather than
trusting the summary.

**Current supported release line: 0.5.x** (HEAD 0.5.7, versionCode 103).

---

## Reporting a vulnerability

**Do not open a public issue or PR for a vulnerability.** This repository is
private, but every collaborator can read the issue tracker — and the details of
a live exploit (especially one involving the gateway credential or the agent's
tool access) must not sit in a shared tracker before a fix ships.

Report privately to the maintainer at the address used on the repository's
release commits:

> **chris@coveredbridgecookies.com**

or via any private House channel you already share with the maintainer. Include:

- the affected version (versionName/versionCode in
  `android/app/build.gradle:23-24`) and device/Android level,
- a repro (steps, or a minimal PoC — do not include live keys),
- what you observed vs. what you expected, and
- your assessment of impact (see the threat model below).

**What happens next:** the maintainer triages privately, a fix lands on a
private branch, and a point/fix release ships on the normal release path (see
"Supported versions"). Credit is given in the release notes if you want it.
There is no bug-bounty program — this is a small House project — but serious
reports are taken seriously and answered.

---

## Scope — what this project is

Hermes Vox is a **connect-only client**. It does not host the agent, does not
ship a gateway, has no accounts, and makes no cloud calls of its own. The user
brings their own Hermes gateway and enters its address **and** its API key in
the app (onboarding + Settings → Entity). Speech processing is on-device by
default (Whisper STT, Silero VAD, Piper TTS); the only network destinations are
the endpoint the user configures and the model hosts the user's downloads come
from (`README.md:134-157`).

The security boundary therefore has three sides:

1. **The device** (the app, its storage, its logs, its intents).
2. **The wire** (the user's network path to their gateway).
3. **The gateway** (user-managed — the client's trust in it is total by design:
   the app sends the user's turns and the gateway's key over that connection).

---

## What is protected (assets)

| Asset | Why it matters | Where it lives |
|---|---|---|
| **The gateway API key** | A bearer credential to a Hermes agent **with tools** — file, shell, web, memory. A leaked key is full agent access for anyone who can reach the gateway. | User-entered; never compiled in; encrypted at rest on-device. |
| **Conversation audio + text** | The content of the user's calls with the entity. | On-device during the turn; to the user's gateway only. |
| **Device-local state** | Endpoint, model choice, preferences, logs, downloaded model files. | App-private storage + SharedPreferences. |
| **Release integrity** | A signed APK the user can trust is the real build. | House release keystore (never in the repo). |

### Threat model

- **Static analysis of the APK** — an attacker reads the binary. There is
  nothing secret in it to find (no keys, no endpoints with baked credentials).
- **Other apps on the device** — read SharedPreferences/logcat, fire intents,
  race the clipboard. Addressed by Keystore encryption, scoped cleartext,
  non-exported activities, and no intent-extras trust (see below).
- **Network on-path attackers** — sniff plain HTTP between the phone and the
  gateway. Addressed by the private-network assumption (below) and the cleartext
  scope.
- **Device compromise (root/backup)** — outside the model, but the at-rest key
  is still Keystore-wrapped to raise the bar.
- **Model supply chain** — model archives the user downloads are external
  binaries loaded into the app. Mitigated by sha256 verification and guarded
  unpacking (below).

---

## How it is secured

### The gateway key — user-entered, encrypted at rest, never shipped

- **No compiled-in key, no default, no `BuildConfig` field.** `GatewayKey.resolve`
  deliberately resolves blank or undecryptable storage to `""` — never to some
  baked fallback — and the UI prompts the user to enter their key
  (`GatewayKey.kt:14-27`). This is the C0 invariant.
- **`SecureStore`** stores the key encrypted at rest: an AES/GCM key in the
  **Android Keystore** (`alias hv_api_key`), values stored as a base64
  `IV:CT` envelope in SharedPreferences; legacy plaintext values decrypt as-is
  so old installs upgrade cleanly (`SecureStore.kt:17-56`).
- **The C0 release guard** fails the *release* build if either regression
  appears: the legacy `HERMES_VOX_API_KEY` env source is set in the build shell,
  or a `buildConfigField` key injection is re-added to the Gradle sources
  (`android/app/build.gradle:75-115`). A baked key can never ship again. The
  guard is release-only, so debug builds and unit tests are unaffected.
- **Settings never round-trip the ciphertext as the key.** The key field
  decrypts on read and encrypts on write, and the test-connection path decrypts
  before handing the key to the connector — fixing the ciphertext-as-token bug
  (#14; `docs/SPRINT-PLAN-release-security-impl.md`, B2).

### The app surface

- **No intent-injection vector.** The launcher activity is exported (it must
  be, to be launchable) but honors **no** intent extras: the old behavior that
  loaded `url`/`key`/`model` off an external intent and auto-sent a `say`
  command is gone (#1). Any installed app that fired such an intent could
  previously repoint the client and inject a command into an agent with
  shell/file tools — that path is removed, and the entity address + key are
  user-entered only (`docs/SPRINT-PLAN-release-security-impl.md`, B1).
- **Cleartext is scoped, not blanket.** The network security config defaults to
  **no** cleartext and permits plain HTTP only for the explicitly listed local
  hosts (`android/app/src/main/res/xml/network_security_config.xml`); every
  other host must speak TLS. All other activities are `exported="false"`
  (`AndroidManifest.xml`).
- **Permissions are minimal**: INTERNET, RECORD_AUDIO, audio/notification
  foreground-service permissions — nothing more (`AndroidManifest.xml:4-11`).

### Data on the wire

- The app speaks HTTP + Bearer auth to the gateway the user configured. It is
  safe **only over a network the user controls** — a private LAN or a VPN/tunnel
  (Tailscale/Nebula — Vox suggests, never requires, a product). Without one, the
  key and audio cross the path in plaintext on listed cleartext hosts. This is a
  documented trade, not an accident (`README.md:148-157`).
- On-device speech means audio need not leave the device except as turn text to
  the user's gateway.

### Model downloads

- Downloads stream → **sha256-verify** → unpack with a zip-slip guard into
  app-private storage (`README.md:142-143`). A mismatched hash aborts the
  install.

### Release integrity

- Release builds are signed with the House release keystore (alias
  `hermes-vox`, RSA 4096). The keystore and `keystore.properties` are
  gitignored; the password lives in the House env store. A missing properties
  file fails the build loudly — there is no silent fallback to the public
  Android debug key (`android/app/build.gradle:4-15,30-38`). Verify with
  `apksigner verify --print-certs` before shipping.
- The first release-signed build after a debug-signed install is a **fresh
  install** (Android signature mismatch otherwise) — documented in release
  notes (`docs/SPRINT-PLAN-release-security.md`).

### Development-process controls

- **The C0/security posture is a standing invariant**, protected by the same
  KEEP-list discipline as the voice pipeline: security clusters are planned
  with exact file:line targets and a KEEP-list
  (`docs/SPRINT-PLAN-release-security.md`), then implemented one coherent
  commit per fix.
- **Adversarial review.** Security work is never self-merged. Every security
  cluster is reviewed by an adversarial reader in a separate read-only pass
  (the `docs/REVIEW-DIRECTIVE-*.md` format), and the adversarial **co-steward
  pair** gives security changes a second, independent read before merge.
- **Self-review heartbeat.** The House's recurring self-review sweeps the
  security posture and logging blind spots on a cadence (see the logging
  audit `docs/LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md`), so degraded or
  silent-failure paths surface instead of persisting.

---

## Known scope and accepted trade-offs

- **The gateway is user-managed, and the client trusts it completely.** Hermes
  Vox does not authenticate the gateway beyond the bearer key the user entered.
  If the gateway is malicious, it can do anything the agent can. That is the
  architecture: *the entity IS Hermes*, so the client deliberately does not
  interpose on the agent.
- **Plain HTTP is a supported local-first mode** for the listed LAN/tailnet
  hosts. A user who points the app at a public or untrusted network without TLS
  exposes their key and audio to the path. The app's cleartext scope and the
  README privacy section say this; the app cannot enforce the user's network.
- **Any host not listed in the network security config must speak TLS.** A
  user-entered cleartext host outside the domain list will simply fail to
  connect — by design (#50). Users with their own local hosts add them to the
  domain-config (the file documents this).
- **Model downloads come from external hosts** (huggingface.co over HTTPS) and
  are verified by sha256 before unpack; the host itself is not pinned.
- **Rooted/compromised devices are out of scope.** The Android Keystore raises
  the cost of extracting the at-rest key but does not protect against an
  attacker who owns the OS.

---

## Supported versions and how fixes are released

- The **current 0.5.x line** is supported. This is a single-maintainer House
  project with no LTS/backport promises — fixes ship on the current line, as
  fast point releases when the issue warrants.
- Release mechanics: version bump in `android/app/build.gradle:23-24`, release
  notes in `docs/RELEASE-<version>.md`, commit
  `chore(release): <version> (<code>) — ...`, tag === versionName === APK (no
  leading `v`), release-signed APK named `hermes-vox-<versionName>.apk`.
- Security fixes are documented honestly in the release notes (including the
  C0/security KEEP-list re-verification). Update by sideload or Obtainium;
  remember the signature-change fresh-install rule when moving between
  debug-signed and release-signed installs.
