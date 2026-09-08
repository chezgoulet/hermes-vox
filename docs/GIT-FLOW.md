# Hermes Vox — Git Flow (adopted 2026-09-08)

Christopher's flow, verbatim in intent:

- **`main`** — always deployable/buildable. Releases are cut from `main` ONLY.
- **`testing`** — the integration branch. Features branch OFF `testing` and
  merge INTO `testing`. Every merge into `testing` produces a **nightly**
  build (below).
- **Releases** — when `testing` is satisfied and known good, it merges into
  `main` and a versioned release is cut per `docs/RELEASE-PROCESS.md`
  (APK + AAB, `gh release create`, notes doc).

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
