# #225 — the being's look controls, and the preview that shows them

## Status

32 captures and a lifetime measurement from the branch build, **on the Android 15
emulator**. The Pixel 7 pass is still pending: the phone is on its secure lock screen and
needs its owner's PIN (`dumpsys user` = `RUNNING_LOCKED`, `locksettings get-disabled` =
`false`, `dumpsys trust` = `strongAuthRequired=0x1`), so after the reboot Android will not
start the credential-encrypted app — `am start` answers `Activity class … does not exist`.
This is the blocker #327 recorded; one unlock and the two commands at the bottom replace
every number and image here with the phone's own.

- **Device:** emulator `sdk_gphone16k_x86_64` (AVD `vox225`, 1080 × 2400, 420 dpi, software
  GL), Android 15, `google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925`.
- **Build:** this branch, debug APK, package `com.hermesvox.shot` (a side-by-side id: the
  phone's release-signed `com.hermesvox` cannot be replaced by a debug build, and its data
  stays untouched).
- **Theme:** the app's own `theme` pref (`light` / `dark`) with the emulator's night mode
  set to match. The light shots are the authored #275 page (`#F3F5F9`); there is no dark
  panel in light mode.
- **Presence is off** in every shot (`presence_enabled=false`), so the being is the pure
  visual layer; no gateway, key, mic or model is used.
- **Capture:** `scripts/capture-225-particle-controls.sh` (below). Manifest authority
  follows the application id; nothing else differs from the tree.

## The shots (32)

Each `<ground>-<control>-<level>` is the Being section scrolled so that control's row sits
at the bottom of the screen — its reading, its range, its slider — with the rows above it
(and, for the upper controls, the live being) in the same frame.

| files | level shown |
|---|---|
| `<ground>-top` | the shipped look: the live being, the category rows, the panel's first controls |
| `<ground>-density-default` / `-min` / `-max` | 100 % / 30 % / 100 % |
| `<ground>-speed-…` | 1.0× / 0.5× / 1.8× |
| `<ground>-trail-…` | the category's / 0 % / 90 % |
| `<ground>-hue-…` | 0° / −180° / 180° |
| `<ground>-energy-…` | 1.0× / 0.5× / 1.6× |

for `ground` = `light` and `dark`.

What they show:

- every row reads `current · range` ("100 % · 30–100 %", "1.0× · 0.5–1.6 ×", "the
  category's · 0–90 %", "0° · −180° … 180°") — the value on screen is the value stored;
- **RESET** is bold once a control leaves its default and dimmed at it (compare
  `-default` with `-min`/`-max` in any row);
- the two grounds: the light shots are ink on the app's own paper, the dark ones are the
  existing night ground — no new colour token, no panel behind the being;
- `light-top` and `dark-top` show the live preview drawing the being ("working"), because
  the capture script refuses to keep a top shot without the being's ink in the preview
  band. Software GL still drops the being from some of the scrolled frames — an emulator
  artifact, noted in #327 too — but the control under test is always the subject of its own
  shot.

## The measurement

`scripts/measure-225-preview-pss.sh` walks Settings as a user walks it — Settings home,
tap the Being row, Back, Back — in **one process**, so every row describes the same process
rather than a cold start.

Light ground:

| state | TOTAL PSS (kB) | frames rendered (cumulative) | wake locks held |
|---|---|---|---|
| settings-home | 46496 | 28 | 0 |
| preview-running | 67423 | ? | 0 |
| after-back | 54664 | 71 | 0 |
| after-onpause | 53479 | 71 | 0 |

Dark ground:

| state | TOTAL PSS (kB) | frames rendered (cumulative) | wake locks held |
|---|---|---|---|
| settings-home | 61268 | 39 | 0 |
| preview-running | 62665 | 93 | 0 |
| after-back | 63001 | 95 | 0 |
| after-onpause | 54298 | 95 | 0 |

- **The renderer stops when it leaves the screen.** Light: 28 → 71 frames while Being was
  open, then 71 → 71 across Back and Back, and **0 frames in the five seconds after
  `onPause`**. Dark: same shape (39 → 93 → 95, then 0). The frame counter is the sharp
  witness; a stopped renderer draws nothing.
- **No wake lock**, in any state, in the power service's own list (`uid=10209`).
- **PSS.** The preview peak is 14–21 MB above the preview's own released state on this
  emulator; after leaving, the process sits back in the band it started in (light 53.5 MB
  against a 46.5 MB home band, dark 54.3 MB against 61.3 MB). PSS on a software-GL
  emulator with a freshly warmed process is noisy — the light preview peak carries its
  first-frame GL allocations — which is why the frame counter and the wake-lock list are
  reported beside it. **These are emulator numbers, not the phone's**; the Pixel pass is
  the one the issue asks for and is still owed.

## Reproduce

```sh
# 1. an unlocked device, the shot build installed and debuggable
adb install -g hermes-vox-0.9.0.3.apk   # built with applicationId com.hermesvox.shot

# 2. the lifetime measurement (one process; paste both tables above)
scripts/measure-225-preview-pss.sh com.hermesvox.shot light
scripts/measure-225-preview-pss.sh com.hermesvox.shot dark

# 3. the captures (32 PNGs; resumable — real frames already on disk are kept)
scripts/capture-225-particle-controls.sh artifacts/225-particle-controls com.hermesvox.shot
```

The capture script checks every frame before keeping it: the app's page colour must be in
the margin, the PNG must not be mostly empty, and a top shot must contain the being's ink in
the preview band. On the phone those checks pass on the first frame; on a software-GL
emulator they are what stops a launcher or a stale buffer from becoming "evidence".
