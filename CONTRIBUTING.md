# Contributing to Hermes Vox

Hermes Vox is the Android voice client for Hermes, the agent you run on your own
gateway (the server that holds the agent). The phone carries the conversation, the
on-device speech pipeline and the avatar; the agent on the gateway does the reasoning,
keeps the memory and runs the tools. This file is what a change has to satisfy: where
it goes, what it must pass, and what the project checks it against.

You need a GitHub account, JDK 17 and the Android SDK (compileSdk 34). A phone is
needed for anything that touches audio, the service lifecycle, the network or the UI.
A Hermes gateway is needed only for the optional live suite. The gate downloads its
own pinned speech runtime, so nothing else is required to build.

## Where a change goes

- **An issue** for anything broken, and for a feature request concrete enough to act
  on. Say what you are trying to do, not only the feature you have in mind.
- **A discussion** for questions, open-ended ideas and setups worth showing.
- **A private report** for a vulnerability, never a public issue. The address and the
  process are in [`SECURITY.md`](SECURITY.md).

The tree is small:

| Path | What lives there |
|---|---|
| `android/` | the Gradle project and the product. Kotlin sources in `android/app/src/main/java/com/hermesvox/`; the gateway connector in `.../core/`; JVM tests in `android/app/src/test/` |
| `scripts/` | the build plumbing. `scripts/gate.sh` is the gate; `scripts/fetch-runtime.sh` fetches the pinned sherpa-onnx AAR; `scripts/check-doc-truth.py` and `scripts/check-skill-claims.py` check documentation |
| `docs/` | the living documents, the release notes and the records. `docs/DOC-TRUTH.yml` is a checked ledger; `docs/INDEX.md` says which document is current and which is history |
| `testdata/stream/` | the recorded event streams and their expected output, replayed by the stream parity test |
| `skills/hermes-vox-app/` | the app skill the agent can install to answer questions about the app |

The Gradle root is `android/`; the repository root holds the scripts, the documents
and the fixtures. The wrapper (`android/gradlew`) pins Gradle 8.12.1; no system
Gradle is needed.

## The branch flow

Two long-lived branches:

- **`testing`** is where work lands. Branch from it, open a pull request into it,
  merge into it.
- **`main`** carries releases. It changes only when `testing` is known good and a
  release is cut: the version bump lands on `testing` first, `testing` merges into
  `main`, and the release is cut from `main`. `docs/RELEASE-PROCESS.md` is the
  step-by-step version of that cut.

Every push to `testing` builds a **nightly**: a prerelease tagged
`nightly-<date>-<time>-<sha>` with the signed APK attached
(`.github/workflows/nightly.yml`). A nightly never becomes the repository's latest
release, and a red gate publishes nothing.

A hotfix for a broken release branches from `main`, merges there, and is
cherry-picked back to `testing`.

Name a branch for the work: `feature/<slug>`, `fix/<slug>`, `docs/<slug>`. Commit
one coherent change at a time, with an imperative subject and a conventional prefix
(`fix(voice):`, `feat(phone):`, `docs:`). Reference the issue when one exists.

## The licence and the CLA

The project is GPL-3.0 ([`LICENSE`](LICENSE)). The GPL covers what anyone may do with the
code; it does not cover your permission to put your work into it. That is what the
contributor licence agreement in [`CLA.md`](CLA.md) is for, and every contribution needs it.

You keep your copyright. You grant Christopher Goulet a perpetual, worldwide,
non-exclusive, royalty-free and irrevocable licence to use, reproduce, modify and
distribute your contribution as part of the project under GPL-3.0. There is no copyright
assignment, and no relicensing under other terms: the project is copyleft and stays that
way.

**How to sign.** On your first pull request, comment with:

```
I have read the CLA at CLA.md and I agree to it.
Name: <your legal name>
GitHub handle: <your handle>
Email: <optional>
```

**The check is automatic.** `.github/workflows/cla.yml` runs on every pull request and on
every new comment. It looks for that sentence in a comment by the pull request's author
and turns the `CLA signature` check green when it finds it. When it does not, the workflow
posts the text to copy and the check stays red — including for a one-line typo. A comment
from anyone other than the author does not count, and a contributor with write access to
the repository does not need to sign. Signing once covers your later pull requests.

Do not send code you do not have the right to license.

## The gate

One command from a checkout, with `JAVA_HOME` and `ANDROID_HOME` set:

```bash
bash scripts/gate.sh
```

`make test` runs the same script. It prints `GATE-GREEN` only when every step below
has passed.

### What it runs

1. **The pinned speech runtime.** `scripts/fetch-runtime.sh` downloads the pinned
   sherpa-onnx AAR (an Android library archive) and verifies its SHA-256. The file
   is gitignored, so a clean checkout needs this step before Gradle can compile.
2. **The report self-tests.** Three Python suites exercise the presence report
   parser, grouping, counts and the daily-cadence rule, plus the invention
   classifier. They need no device and no network.
3. **The app skill claims.** `scripts/check-skill-claims.py` checks every claim in
   `skills/hermes-vox-app/SKILL.md` against the file and literal token it names.
4. **The document claims.** `scripts/check-doc-truth.py` reads
   `docs/DOC-TRUTH.yml` and checks each entry in both directions: the literal phrase
   is still in the document, and the named pattern is still in the code.
5. **The Android build and every JVM unit test.** From `android/`:
   `./gradlew --no-daemon -q clean assembleDebug testDebugUnitTest`.
6. **The verification harnesses.** The gate requires the result files for
   `StreamParityTest`, `MtlsConfigTest`, `MtlsKeyManagerTest`, `MtlsHandshakeTest`,
   `MtlsEntityTest` and `LiveGatewayTest`, and requires each to report a non-zero
   test count with nothing skipped, failed or errored. A harness that silently stops
   running fails the gate instead of passing it.

The gate also unpacks the built APK and fails if it contains key material: any
`.p12`, `.pfx`, `.jks`, `.bks`, `.keystore` or `.pem` file.

### What it refuses

- A `JAVA_HOME` that is missing, has no `bin/java`, or is any Java other than 17;
  an `ANDROID_HOME` that does not exist. It exits rather than substituting another
  toolchain.
- A second gate running on the same machine. The lock is
  `/tmp/hermes-vox-gate.lock`, held for the whole run; a waiter prints that it is
  waiting, blocks, and gives up after an hour, naming the holder when the platform can
  say who it is. On a host without `flock` it says it is running unlocked.
- A documentation claim the code does not support, a stale app-skill claim, or a
  failing report self-test.
- A compile or unit-test failure.
- A missing test-result file, a skipped test, or a suite with zero tests.
- Key material inside the APK.

The gate builds a debug APK and runs debug unit tests. A release additionally builds
`assembleRelease` and `testReleaseUnitTest`, as `docs/RELEASE-PROCESS.md` describes;
a contributor without the release keystore can run the debug gate but not a signed
release.

### What CI adds

`.github/workflows/ci.yml` (CI, the pull-request checks) runs on every pull request
into `testing` or `main` and on every push to either. It runs the same documentation
checks, the build and the unit tests, with no secrets, so a fork's pull request is
gated too. It also:

- runs `scripts/check-version-code.sh`, which fails a branch whose `versionCode` (the
  Android build number) is behind a shipped release;
- checks that `LiveGatewayTest` ran its six cases plus its self-check, and fails on
  a skip;
- for a change to `android/`, `libs/`, `scripts/repro-check.sh` or
  `scripts/fetch-runtime.sh`, builds an unsigned release APK twice from different
  absolute paths and requires the two to be byte-identical (`scripts/repro-check.sh`).

### Running a subset by hand

```bash
# The gateway connector, offline against the deterministic fake gateway
(cd android && ./gradlew :app:testDebugUnitTest --tests 'com.hermesvox.core.*' --no-daemon)

# The live suite against a real gateway (the key is an environment value only)
(cd android && HERMES_VOX_LIVE=1 HERMES_VOX_HERMES_API_KEY=<secret> \
  ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest' --no-daemon)

# A debug APK
(cd android && ./gradlew --no-daemon clean assembleDebug)
```

Without the two variables the live suite runs every assertion against `FakeGateway`,
a deterministic HTTP server on a loopback port. Setting `HERMES_VOX_LIVE=1` without
a key fails rather than quietly using the fake. The key is the bearer credential of
an agent with tools: keep it in the environment, never in a commit, a log or an
issue.

## The rule that surprises people: the gate reads the documentation

A sentence in this repository that says what the code does is treated as a claim,
and the gate checks it. Changing behaviour can therefore fail the gate because a
document became false.

Two ledgers enforce it:

- **`docs/DOC-TRUTH.yml`** — each entry names the document, a literal phrase in it,
  the source file that settles the claim, and a regular expression that must still
  match that file. `scripts/check-doc-truth.py` fails if either side drifts. The
  entries cover the architecture, the phone surface, the approval rules, the
  per-origin certificate rule, the cleartext rule and the claims in these three
  policy documents.
- **`skills/hermes-vox-app/SKILL.md`** — each claim names a file and a literal token,
  never a line number. `scripts/check-skill-claims.py` fails on a token that is gone
  or ambiguous.

When the check fails, fix the sentence or fix the code. Deleting an entry is also a
change to what the project promises, and it has to be justified in the pull request.
The living documents — `README.md`, `docs/ARCHITECTURE.md`, `docs/MCP-PHONE.md`,
`docs/MCP-SURFACE.md`, `PRIVACY.md`, `SECURITY.md` and the rest of the list in
`docs/INDEX.md` — are meant to describe the build in this tree, not an earlier one.

## How a change is reviewed

Three things are mechanical and must be green before a merge: CI, the CLA check and
the gate. The pull request states what changed, which behaviour contracts it
touches, and the commands and output that show the change was run rather than
guessed. A change that touches audio, the service lifecycle, networking or the UI
names the phone it was tried on; a performance claim carries the number and the
method. If something could not be tested, say so — a named gap is reviewable.

The rest is practice, and nothing in the repository enforces its shape. A maintainer
reads every pull request and looks for what the author missed. Security-sensitive
work gets a separate read before merge. Say in the pull request when a change
touches the trust model, the key, cleartext or approvals, so it is routed for that
read.

## What you are credited for

- **Your copyright.** The CLA grants a licence; it does not assign ownership.
- **Your signature on record.** The comment you post is the record — it stays in the
  pull request thread, with its date and your handle beside it.
- **Authorship of your commits**, which the history keeps.
- **The change itself**, under GPL-3.0, in the repository and in the APKs built
  from it.

## What a contribution writes and changes

- Files under `android/`, `scripts/`, `docs/`, `testdata/` or `skills/`, depending on
  the change.
- Nothing else on your first pull request: signing writes no file and changes no
  configuration. The CLA workflow reads your comment and turns a check green.
- A nightly prerelease on `testing` after the merge, signed with the release key,
  with the APK attached.
- Local build output under `android/app/build/` and the fetched runtime under
  `android/app/libs/`. Both are gitignored.

Never commit a key, a token, a gateway address you would rather not share, a
keystore, or a personal log. `keystore/` and `keystore/keystore.properties` are
gitignored and stay that way.

## What it cannot do

- CI proves the code compiles and the JVM tests pass; it does not prove the phone
  works. A device run is the only evidence for audio, echo, wake, battery and the
  system TTS engine.
- The gate cannot sign a release: the release keystore is not in the repository, and
  a release build refuses to fall back to the debug key.
- The live suite cannot reach a real gateway from a fork, because a fork has no
  secret to give it. It runs against the deterministic fake instead.
- The documentation checks are specific claims, not a proof of the prose. A sentence
  with no ledger entry can still drift.
- The CLA check sets a status; whether that status blocks a merge is a repository
  setting, not something this tree can prove.
- There is no bug bounty and no response-time guarantee.
