# #275 — the being on the light ground (device captures)

Real Pixel 7 captures of the branch build, not mocks. The being is rendered by the
phone's GPU through the real `BeingShader`/`BeingRenderer` path; the state line in
each shot is set by the debug harness, so every PNG says what it is.

- **Device:** Pixel 7 (`2A091FDH200AJ1`), 1080 × 2400, 420 dpi, Android 17
  (`google/panther/panther:17/CP3A.261005.002.A1/16269273`).
- **Build:** `feat/275-light-mode-being`, debug APK from the tree this PR contains.
- **Package:** `com.hermesvox.shot`. The phone's installed `com.hermesvox` is the
  release-signed nightly `0.9.0.3-nightly-590d591`, and Android refuses a debug build
  over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`). The shot
  build therefore uses a side-by-side application id (manifest authority follows it),
  so the owner's app and its data are untouched and `install -r` is used for every
  shot-build rebuild. It is the same source tree; only the application id differs.
- **Surface:** `@color/hv_bg` = `#F3F5F9`, the app's real light page. The shader
  writes that same paper, so the being has no box and no seam.
- **Capture:** `scripts/capture-light-being.sh` (debug harness extras `category` and
  `being`; no gateway, key, mic or model is needed).

## The shots

| file | family | state on screen |
|---|---|---|
| `lumen-listening.png` | Lumen — its own light | listening (the iris) |
| `lumen-thinking.png` | Lumen — its own light | thinking (the spiral) |
| `lumen-speaking.png` | Lumen — its own light | speaking (the soundwave) |
| `abyss-listening.png` | Abyss — deep water | listening (the iris) |
| `abyss-thinking.png` | Abyss — deep water | thinking (the spiral) |
| `abyss-speaking.png` | Abyss — deep water | speaking (the soundwave) |
| `abyss-stalled.png` | Abyss — deep water | stalled (the waiting hourglass) |
| `nebula-listening.png` | Nebula — interstellar | listening (the iris) |
| `nebula-thinking.png` | Nebula — interstellar | thinking (the spiral) |
| `nebula-speaking.png` | Nebula — interstellar | speaking (the soundwave) |
| `ink-listening.png` | Ink — 1-bit | listening (the iris) |
| `ink-thinking.png` | Ink — 1-bit | thinking (the spiral) |
| `ink-speaking.png` | Ink — 1-bit | speaking (the soundwave) |
| `settings-being-light.png` | Lumen — its own light | Settings → Being, live preview (working) |
| `before-light-release-nightly.png` | Lumen (old code) | **before**: the installed release nightly, same light page — the pale cyan atom the issue is about |

The four families are deliberately different substances: Lumen has no family tint
(the state hue itself as ink), Abyss pulls hard toward deep indigo, Nebula is the
glow-only family that becomes a wide violet wash, and Ink is the 1-bit plotter — the
family that gains most from a paper ground.

## What the ground does

- The light branch composes **ink on paper**, not light on black:
  `out = paper + (ink - paper) * cover`, where `cover` comes from the emission's
  luminance. A hot core is a **denser** mark, never a white one that vanishes.
- Each family carries an authored `VisualStyle.Light` — its own ink tint, tint
  amount, saturation, accent turn, ink value, coverage gain, bake and ambient wash.
  It is not a luminance inversion of the dark palette (see the comments in
  `VisualStyle.kt` for what each family becomes; Prism's "sums to white" becomes a
  subtractive graphite overlap, Nebula/Aura's glow becomes a wash).
- Every light family's body and accent are measured against `#F3F5F9` in
  `LightModeContrastTest` (floors: 3:1 body, 4.5:1 thin body, 4.5:1 every text
  token on both the page and the chip). The dark scheme is pinned by
  `VisualStyleTest.the_dark_palette_is_the_shipped_one`.
