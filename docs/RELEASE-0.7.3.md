# Hermes Vox 0.7.3 — the first-run fix

Version code **115** · `versionName` 0.7.3 · built from `main` (0.7.2 + this fix).

## What this is

A one-bug release. It fixes a **hard block on first run**: on a phone whose display
size or font size is turned up, the onboarding explainer could be taller than the
window, and because that screen had no scroll container, everything past the bottom
edge was never drawn. The only control that moves a new user forward — **"Got it —
connect"** — was unreachable, and there was no scroll to reach it with. Back or Home
was the only exit.

Reported from a Pixel 8 on Android 17 QPR1 beta with Font size ~150% and Display size
one step up (chezgoulet/hermes-vox#130). The framing in the report — "splash screen too
large", blamed on the beta — was not what was happening.

## The measurements, because the report's framing was wrong

From the screenshot's pixels rather than its caption: the screen was rendering at
density ~2.875 × font scale ~1.5, with Android's **non-linear font scaling** visible in
it — a 30sp heading does not scale at all below ~1.8, so the 12sp caption caught up to
it and the screen read as one uniform wall of text. That is ~1080dp of column inside a
~760dp window; the model predicts the clip edge within two pixels of where the
screenshot cuts a line mid-glyph.

At *default* settings the same column is ~790dp in ~840dp of window — about **6% of
slack**. `Font size: Large` (1.15) consumes it; `Largest` (1.3), or `Display size` one
step up, puts the button off-window. The screen that "worked" was never tested; it was
lucky.

## The fix

- **`activity_onboarding.xml` is a scroll container.** The column is wrapped in a
  `fillViewport` ScrollView and becomes `wrap_content`. When the content fits,
  `fillViewport` stretches the column to the window and `gravity="center"` centres it
  exactly as before — the default screen does not move, and the code does not even
  rewrite the declared presence size on a default device. When it does not fit, it
  scrolls instead of clipping.
- **The presence yields space as the text grows** (`OnboardingLayout.avatarSizeDp`):
  250dp at defaults, 205/170/140dp as the font scale or a short window demands. The copy
  and the controls never shrink. This puts the button ~1/8 of a screen below the fold at
  the reported settings, and ~1/5 at the accessibility maximum, instead of out of reach.
- **`OnboardingActivity` gets `windowSoftInputMode="adjustResize"`**, so the keyboard
  cannot bury Connect on the connection form (those fields are in the same container).
- **Each step opens at its top**, so a user who scrolled the explainer to reach its
  button does not arrive at the form mid-scrolled with the first field above the fold.

## Gated, not decorated

`OnboardingLayoutTest` and `OnboardingAvatarSizeTest` (device-free JVM, in the shape of
the existing suite) hold the structure: the root is a vertical scroll container with
`fillViewport`, its single child is `wrap_content`, every id in the file lives inside it,
the column still centres and pads as the old root did, and the size rule is pinned down
to its literals and boundaries. Run against the pre-fix layout, the same suite fails
three assertions — the negative control — so the gate bites.

## What is NOT in this release

The **accelerator series** (Gemma on the GPU, speech-model threading, model-size
corrections) stays on `testing`. Its plan status is **UNPROVEN** — `docs/PLAN-0.8-accelerator.md`
— and it ships as part of **0.8.0** when the series completes, per `ROADMAP.md`. Its
draft notes, which had been filed under this version number, are re-labelled
`docs/RELEASE-0.8.0.md` on `testing`. The entity-scope field (#129) and the
Enhanced-Realtime soul-lane work are likewise `testing`-only and not in 0.7.3.

## Verify on a device

Install over 0.7.2 (same release key, no uninstall), then:

1. **Defaults first:** first run looks exactly as before — presence centred, everything
   one screen.
2. **Font size → Largest:** first run scrolls; **"Got it — connect"** is reachable by
   scrolling; tapping it opens the form at its top.
3. **Form:** with the keyboard up, the fields and Connect stay reachable.
