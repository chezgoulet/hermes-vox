# Command icon — captures and renders

The entry's glyph is drawn by `CommandGlyph` (paths in code, no font, no bitmap) in the
theme's `hv_text` ink. These pictures document the setting and the five drawings.

## Device captures

Real captures of the branch build, not mocks.

- **Device:** the AVD `vox225` (1080 × 2400, 420 dpi, Android 15 / API 35), headless
  with `swiftshader_indirect`. The owner's Pixel 7 was left untouched: the shot build
  installs side by side as `com.hermesvox.iconcheck`
  (`-Phermesvox.benchSuffix=iconcheck`), so the release-signed `com.hermesvox` and its
  data are not touched. The side-by-side package was uninstalled after the captures.
- **Build:** debug APK from the tree that merged the setting (PR #441). Grounds:
  `theme=light` and `theme=dark`, with the device in the matching `cmd uimode` so the
  page and the ink agree.
- **The being is real**: the Appearance preview renders the same shader the main screen
  does, which is why some frames are mid-motion (and why the software GPU's frames are
  so slow — the ANR dialogs in the log are that, not the app).

| Capture | What it shows |
|---|---|
| `appearance-light-steve.png` | Settings → Appearance on the light page: the **Command icon** row at its shipped default (Steve, ⌘). Taken before the 24 dp redraw below, so the row's ⌘ is the earlier, tangent-loop drawing. |
| `chooser-light.png` | The chooser: five options drawn live at the row's own size, label beside each, the current one checked. Also pre-redraw for Steve/Bill/Hermes. |
| `appearance-light-clanker.png` | The row after choosing Clanker: the row's own preview and its name follow the choice. |
| `main-light-clanker.png` | The main screen's commands entry drawing Clanker in ink on the light page. |
| `appearance-dark-clanker.png` | The same row on the black ground, the glyph in the light ink. |
| `main-dark-clanker.png` | The main screen's commands entry on the black ground. |

The robot is unchanged by the redraw, so every Clanker capture is current. Steve, Bill
and Hermes were redrawn at the entry's size afterwards
(`4f1aad86 fix(commands): redraw the three glyphs that did not survive 24 dp`); the
renders below show that final geometry.

## Renders (not device captures)

`glyphs-final-10x.png` and `glyphs-24px.png` are drawn from the same
`CommandGlyph.shapes()` primitives with Java2D, at 10× for inspection and at exactly
24 px (then nearest-upscaled 8×) for the size the entry actually uses. They exist so the
five pictures in this directory match the geometry in the tree; they are not screenshots
of a device.

The commands sheet header uses the same `CommandMark` (see `CommandsSheet.show()`). It
was not captured: the headless emulator could not open the sheet without an ANR in the
being renderer. It is one `CommandMark` with `icon = CommandGlyph.current(act)` — the
device pass should confirm it beside "COMMANDS".
