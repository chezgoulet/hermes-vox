# Hermes Vox — Git Flow (adopted 2026-09-08)

the maintainer's flow, verbatim in intent:

- **`main`** — always deployable/buildable. Releases are cut from `main` ONLY.
- **`testing`** — the integration branch. Features branch OFF `testing` and
  merge INTO `testing`. Every merge into `testing` produces a **nightly**
  build (below).
- **Releases** — when `testing` is satisfied and known good, it merges into
  `main` and a versioned release is cut per `docs/RELEASE-PROCESS.md`
  (signed APK + `SHA256SUMS`, `gh release create`, notes doc).

## Nightlies (the new piece)

A GitHub Action (`.github/workflows/nightly.yml`) fires on every push to
`testing` and publishes a **prerelease** tagged `nightly-YYYYMMDD-HHMMSS-<sha>`
with the signed APK attached. Rules:

- Nightlies are `prerelease: true` and `make_latest: false` — they NEVER
  become the repo's "Latest" release; the release track from `main` stays
  authoritative.
- Obtainium picks them up via the repo's existing "include prereleases"
  pattern — so the nightly track and the release track are independently
  installable from the same repo.
- Nightlies are signed with the SAME release key as point releases
  (keystore restored in CI from an age-encrypted blob + repo secret), so a
  nightly installs over a release without uninstalling.
- If the gate is red, the nightly build FAILS — nothing is published. A red
  testing branch is loud, not silent.

## The gate serialises per host, not per worktree

`scripts/gate.sh` takes an exclusive lock on `/tmp/hermes-vox-gate.lock` after the
toolchain checks and before step 0, and holds it for the whole run — so two gates
on one machine run one after the other even when they belong to different
worktrees, and even when only one of them is inside Gradle. The worktrees are
separate; `~/.gradle` is not. Two gate runs sharing that cache interleave writes
into the same test-results directory, and the loser reports
`java.io.FileNotFoundException: .../testDebugUnitTest/binary/output.bin.idx` or
"Could not write XML test results" — which reads exactly like a failing test and
is not one. (It cost three gates in a day and sent a reader hunting a bug that
was contention.) A gate that finds the lock held prints `gate: another gate is
running on this host — waiting`, blocks, and gives up after an hour with the lock
path and, where the platform can say, the holder's PID. On a host without
`flock` it says it is running unlocked instead of behaving differently in
silence.

## Rules of engagement

1. Branch from `testing`: `git checkout testing && git pull && git checkout -b feature/<name>`.
2. PRs / merges target `testing`, never `main` directly.
3. Version bumps happen on the feature branch BEFORE merge (the mislabeled-
   release trap — see the build-harness reference).
4. Merge to `testing` → the nightly Action builds + publishes (or fails loudly).
5. `testing` → `main` merge is a deliberate, human-timed act (release time):
   bump versionCode/versionName first, gate, merge, cut the release, tag.
6. Hotfixes: branch from `main` (if a release is broken in the field), fix,
   PR to `main` AND cherry-pick to `testing` — the flow's hotfix lane.
