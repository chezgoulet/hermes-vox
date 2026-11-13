# Command icon — device captures

Real captures of the branch build, not mocks. The glyphs are drawn by `CommandGlyph`
(paths in code, no font, no bitmap) in the theme's `hv_text` ink.

- **Device:** the AVD `vox225` (1080 × 2400, 420 dpi, Android 15 / API 35), headless
  with `swiftshader_indirect`. The owner's Pixel 7 was left untouched: the shot build
  installs side by side as `com.hermesvox.iconcheck` (`-Phermesvox.benchSuffix=iconcheck`),
  so the release-signed `com.hermesvox` and its data are not touched. The emulator was
  torn down and the side-by-side package uninstalled after the captures.
- **Build:** debug APK from this tree. Grounds: `theme=light` and `theme=dark` with the
  device in the matching `cmd uimode` so the page and the ink agree.
- **The being is real**: the Appearance preview renders the same shader the main screen
  does, which is why some frames in the section are mid-motion (and why the headless
  software GPU takes ~30 s a frame — the ANR dialogs in the log are that, not the app).

| Capture | What it shows |
|---|---|
| `appearance-light-steve.png` | Settings → Appearance on the light page, the new **Command icon** row at its shipped default (Steve, ⌘) |
| `chooser-light.png` | The chooser: all five drawings live at the row's own size, label beside each, the current one checked |
| `chooser-glyphs-3x.png` | The same five glyphs at 3× for inspection (cropped, nearest-neighbour) |
| `appearance-light-clanker.png` | The row after choosing Clanker: the row's own preview and its name follow the choice |
| `main-light-clanker.png` | The main screen's commands entry drawing Clanker in ink on the light page |
| `appearance-dark-clanker.png` | The same row on the black ground, the glyph in the light ink |
| `main-dark-clanker.png` | The main screen's commands entry on the black ground |

The commands sheet header uses the same `CommandMark` (see `CommandsSheet.show()`); it
was not captured because the headless emulator could not open the sheet without an ANR
in the being renderer. It is one `CommandMark` with `icon = CommandGlyph.current(act)` —
the device pass should confirm it beside "COMMANDS".

**Note (2026-11-13).** The captures above were taken before two later changes: Steve is
the default again (the entry looked like this before the choice existed, and a setting
must not change the app until someone makes one), and Steve, Bill and Hermes were redrawn
for 24 dp — the loops now cross the square, the three letterforms share one cap height, and
each snake makes one wide turn instead of two that closed into a knot. So `chooser-glyphs-3x.png`
shows the older drawings, and the two `*-clanker.png` shots show a choice rather than the
default. Re-capture on a device when one is to hand; the numbers behind the redraw are in
`CommandGlyphTest.theDrawingsAreMadeForTheEntrysSize`.
