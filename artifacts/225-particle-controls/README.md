# #225 — the being's look controls, and the preview that shows them

## Status: code and scripts landed; the device pass is blocked

The controls, the in-Settings preview and its lifetime are implemented and covered by the
unit tests in this branch (see the PR). The screenshot pass and the PSS measurement in
this folder are **not yet taken**, because both devices are unavailable to this session:

- **Pixel 7 (`2A091FDH200AJ1`)** — on its secure lock screen, needing the owner's PIN
  (`dumpsys user` = `RUNNING_LOCKED`, `locksettings get-disabled` = `false`,
  `dumpsys trust` = `strongAuthRequired=0x1`). After a reboot Android will not start an app
  whose data is credential-encrypted, so even the side-by-side debug build cannot be
  launched: `am start` returns `Activity class … does not exist`. This is the same blocker
  #327 recorded, and it is one unlock away.
- **Emulator passes** — the shared Android 15 emulators were being restored to snapshots by
  another session while these runs were in progress: the shot package disappeared between
  shots, `screencap` handed back launcher or stale frames, and one emulator crashed
  (`ptrace: No such process`). A private AVD (`vox225`) was created for this pass and its
  emulator died on the first capture. Nothing from those runs is committed: a screenshot
  that might be the launcher is worse evidence than none.

So this directory carries no PNGs yet. The two scripts below produce them, and the
measurement, in one pass each; the moment the phone is unlocked they are the whole job.

## What the branch changed (what the shots will be of)

Settings → Being, fine-look panel. Five new sliders plus the pre-existing glow, all bound
from one table (`android/app/src/main/java/com/hermesvox/LookControls.kt`), so a slider can
only write the pref it names:

| control | pref | range | default |
|---|---|---|---|
| Density | `visual_density` | 30–100 % | 100 % |
| Speed | `visual_speed` | 0.5–1.8 × | 1.0 × |
| Trail | `visual_trail` | 0–90 % (0 = the category's own) | the category's |
| Hue | `visual_hue` | −180° … 180° | 0° |
| Motion energy | `visual_energy` | 0.5–1.6 × | 1.0 × |
| Glow / bloom (older than #225) | `visual_glow` | 0.4–1.6 × | 1.0 × |

Each row reads `current · range`, has a one-tap **RESET** (dimmed at the default), and the
section keeps its own reset (`RESET` at the bottom of Being) that restores every look pref
and re-reads the sliders. Above the rows is the **live preview**: the call screen's own
`AvatarView`, fed by the same `applyLookPrefs()`, on the authored light ground from #275
(no dark panel in light mode), walking resting → listening → working → speaking.

The preview renders only while Being is the section on screen *and* Settings is resumed.
`onPause` (and leaving the section) calls `AvatarView.setPaused(true)`, which joins the
render thread and destroys its EGL context and render targets — no ticker, no GL, no
wake lock behind another screen.

## The shot list the script produces

`scripts/capture-225-particle-controls.sh [outdir] [package]` — 32 PNGs, seed → launch →
wait for the screen → screenshot, each frame checked (page colour in the margin, non-empty
frame, and the being's ink in the preview band for the section shot):

| files | what is on screen |
|---|---|
| `<ground>-top` | Being at the shipped look: the live being, the category rows, the panel's first controls |
| `<ground>-density-default`, `-min`, `-max` | the Density row (value line, range, slider) at 100 %, 30 %, 100 % |
| `<ground>-speed-…` | Speed at 1.0×, 0.5×, 1.8× |
| `<ground>-trail-…` | Trail at the category's, 0 %, 90 % |
| `<ground>-hue-…` | Hue at 0°, −180°, 180° |
| `<ground>-energy-…` | Motion energy at 1.0×, 0.5×, 1.6× |

for `ground` = `light` and `dark` (16 + 16). Presence is off in every shot, and no gateway,
key, mic or model is used. The debug harness opens the section and the panel, holds the
preview on `thinking` (the sparsest, most legible state) and scrolls the control's row to
the bottom of the screen; both harnesses are inert in a release build.

## The measurement the script takes

`scripts/measure-225-preview-pss.sh [package] [ground]` walks Settings the way a user does,
in **one process**, and records for each state: TOTAL PSS (median of three `dumpsys meminfo`
reads), the cumulative frame counter from `dumpsys gfxinfo`, and whether the power service's
wake-lock list names the app or its uid.

| state | what it is |
|---|---|
| `settings-home` | Settings, home list — no being, no render thread (the prior band) |
| `preview-running` | Settings → Being, the preview drawing |
| `after-back` | back on the Settings home list — the preview released |
| `after-onpause` | Settings finished — `onPause` ran, the process in the background |

The pass is:

1. unlock the phone and leave it on the home screen;
2. install the shot build (`com.hermesvox.shot`, see the header of `scripts/capture-light-being.sh`
   for why it has its own application id) and, on the phone, `SETTLE=6` is enough;
3. `scripts/measure-225-preview-pss.sh com.hermesvox.shot light` and again with `dark`;
4. `scripts/capture-225-particle-controls.sh artifacts/225-particle-controls com.hermesvox.shot`;
5. paste the two measurement tables, and the "frames drawn in the 5 s after onPause" line,
   into this file — that number must be 0.
