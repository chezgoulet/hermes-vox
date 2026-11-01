# #224 — the four Settings sections, on the phone (device captures)

Real device captures of the branch build, not mocks. Every PNG is the app's own
screen: the section the home row opens, with its one disclosure collapsed or
expanded, on the light page and on the dark ground.

- **Device:** the Android 15 emulator (`sdk_gphone16k_x86_64`, AVD `vox16k`),
  1080 × 2400, 420 dpi, host GPU (so the Being section's live being renders through
  the real renderer). The physical Pixel 7 (`2A091FDH200AJ1`) was the intended
  device, but it was sitting on its secure lockscreen after the host restart
  (`dumpsys user` = `RUNNING_LOCKED`, `locksettings get-disabled` = `false`), so the
  captures come from the emulator the brief also allows. Both were driven through
  the same app, layout and code.
- **Build:** `fix/224-settings-split` (rebased on `testing` after #275), debug APK
  from the tree this PR contains.
- **Package:** `com.hermesvox.shot`. On the phone the installed `com.hermesvox` is
  the release-signed nightly `0.9.0.3-nightly-c697907`, and Android refuses a debug
  build over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`). The
  shot build therefore uses a side-by-side application id, so the owner's app and its
  data are untouched and `install -r` is used for every shot-build rebuild. Only three
  local, uncommitted build tweaks differ from the tree: the application id, the
  FileProvider authority that follows it, and `SettingsActivity exported="true"` so
  the capture can open a section directly with its existing `section` extra.
- **Theme:** the screen's own theme preference set to `system`, with the phone's
  night mode toggled (`cmd uimode night no` / `yes`). The light shots are the
  authored light ground from #275, not a dark screen in daylight.
- **Seed:** `onboarding_skipped` and `coach_marks_seen`, so the shots are Settings
  itself, not onboarding. No gateway, key, mic or model is used.
- **Capture:** `scripts/capture-settings-sections.sh` — one fresh accessibility dump
  per tap, the disclosure tapped by resource id, then its first advanced row brought
  to the top so the panel is what the shot proves.

## The shots

| file | section | state on screen |
|---|---|---|
| `hearing-collapsed-light.png` | Hearing | everyday controls: barge-in, AEC, extra noise suppression, partials, continuous capture, judgement, voice-call routing, tone reading; each with its one-line hint |
| `hearing-expanded-light.png` | Hearing | `Advanced — timing tuning` open (`HIDE`): VAD threshold, pause to end a turn, early-start pause, min speech, max speech |
| `voice-light.png` | Voice | TTS engine, delivery, speak responses — no advanced rows, so no disclosure |
| `presence-collapsed-light.png` | Presence | the Presence switch (with its model note), soul narration, the one disclosure |
| `presence-expanded-light.png` | Presence | `Advanced — how Presence behaves` open: semantic barge-in, narration voice, how often it speaks, echo guard |
| `being-collapsed-light.png` | Being | live being preview (resting), visual category, cycle all, the one disclosure, sound cues |
| `being-expanded-light.png` | Being | `Advanced — fine look and shapes` open: motion energy, glow/bloom, and the rest of the fine look |
| `hearing-collapsed-dark.png` | Hearing | same surface on the dark ground |
| `hearing-expanded-dark.png` | Hearing | timing tuning open, dark |
| `voice-dark.png` | Voice | dark |
| `presence-collapsed-dark.png` | Presence | dark |
| `presence-expanded-dark.png` | Presence | how Presence behaves open, dark |
| `being-collapsed-dark.png` | Being | dark |
| `being-expanded-dark.png` | Being | fine look open, dark |

## What the shots found

The split itself is a move: the same rows, the same controls, the same prefs. The
captures are where the **hint** line was proved, and they caught one defect the
tests could not: a hinted title baseline-aligned itself against the row's `?` chip
or switch, which pushed the third line past the card's padding box where
`clipToPadding` sliced it in half ("the mic hears" cut through the middle). The fix
is `android:baselineAligned="false"` on `HvRow` in
`res/values/settings_styles.xml`, and `SettingsHintsTest.hintLinesAreNotClippedByBaselineAlignment`
now holds it there. Every shot above is from the fixed build; the hints read whole.

## Light ground

No new token or chip colour was introduced by the split, so there is nothing new to
pin: the sections use `hv_bg`, `hv_surface`, `hv_surface_high`, `hv_text`,
`hv_text_dim` and `hv_cyan`, and `LightModeContrastTest.every_text_token_clears_4_5_on_its_real_background`
already measures every one of them at ≥ 4.5:1 on both the page and the chip, in
light and dark. The light shots show that floor on the rows themselves: titles,
hints, disclosure chips, `SHOW ›`/`HIDE`, `RESET` and the being preview all sit on
the authored paper, with no black box and no washed-out grey.
