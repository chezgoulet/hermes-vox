# Hermes Vox 0.9.1 — release notes

**Status:** prepared, not cut. This file is the record of what 0.9.1 contains and what it explicitly
does not; the version bump and this document merge last, after the remaining lanes land.

**versionCode** 147 · **versionName** `0.9.1` · built from `testing` at the merge commit, released
through `release.yml` (builds twice from `main` and compares payload hashes) after the cherry-pick.

## What is in it

### The being
- **Light mode is a ground, not a black panel.** Every visual family carries an authored light value
  and a specified subtractive blend: `out = paper + (ink − paper) × cover`, with `paper = #F3F5F9`.
  Additive glow is replaced because it vanishes on white, and the hue is normalised by its max
  channel rather than its luma — the old luma normalisation clipped a green toward white, which is
  why light mode looked washed out even after the panel was removed.
- Measured floors, enforced by `LightModeContrastTest` over all 20 families: worst solid body
  **4.13:1**, worst thin body **4.74:1**, reply text **16.36:1**, call glyphs **5.96 / 5.93**,
  verdict chips **5.06–6.12**. The dark table is pinned unchanged by its own test.
- Particle controls with a live preview in the Being section (density, speed, trail, hue, motion
  scale), judged with Presence off.

### Settings
- Split by **what you are changing**: Being, Hearing, Voice, Presence. Every row carries a one-line
  statement of its effect; advanced rows sit behind one disclosure per section.
- No pref key renamed, no default changed; a test holds the set of keys the screen reads.

### The call
- **`/new` during a call** re-opens the microphone *before* deleting the server copy, so the next
  utterance is recognised without hanging up.
- **Interrupted audio** is keyed to its reply id and ended once: nothing from a stopped reply resumes
  after the next turn begins.
- **Presence is a layer, not a mode** (#222), the transcript path is fed by it (#223), and a repaired
  presence line is logged as repaired (#257) so a shortened reply is never read as silence.

### Surfaces
- **The line** (#281): one plain-language line saying what is running and on what; long jobs show
  movement without inventing a percentage; failures name what failed.
- **Paste** (#327): read → preview (head plus real character count) → **SEND**; the measured limit is
  1,000,000 characters and truncation is always visible.
- **Assistant role** (#329): Vox can hold `android.app.role.ASSISTANT`. The role is ours; the
  side-button long-press mapping belongs to the device maker.
- **Commands screen** (#226): the app's own identity, grouped by what a command does, with real
  queued/running/done/failed state instead of a toast.
- **The app teaches your agent about itself** (#285): after the connection is verified it offers,
  once, to install `hermes-vox-app` on the gateway — installed is not enabled, and the mechanism
  (`skills.platform_disabled.api_server`) is documented.

### Fixed earlier in the 0.9.x line, still shipping here
`#328` cold-start listening retry, `#330` a failed TTS engine must not block a call, `#346` TTS
package visibility, `#240` in-flight audio, `#351` `/new` re-arm.

## What 0.9.1 does not include

- **#202** (clipping) and **#240** (interruption) are fixed in code and await a human ear on this
  build; they are not "known broken", they are "unconfirmed by the only instrument that counts".
- **#191 / #179** presence after the Gemma download and co-residency are unverified: the 2.6 GB
  presence model is not installed on the test device.
- **#208** the two-device behaviour matrix; one handset only.
- **#228 / #229** continuous capture and semantic endpointing: open, not in this release.
- **#325** silence-notifications, **#282** approvals, **#284** discovery: open, not in this release.

## Verification

`docs/` carries the live acceptance document (forty cards) used for the release run; the measured
baselines live in issue #209 (cold start median 161.5 ms over ten runs, 641 MB PSS in a live call
with all three voice models) and are re-measured on the 0.9.1 build before the run.

## Release mechanics

1. Cherry-pick the release commit to `main`.
2. Dispatch `release.yml` from `main`; it builds twice and asserts version, signature and payload
   equality.
3. Attach the APK and `SHA256SUMS` to the GitHub release; nightlies install over the release because
   both are signed with the release key.
4. `docs/RELEASE-0.9.1.md` is filled with the final hashes and the run's verdict, not before.
