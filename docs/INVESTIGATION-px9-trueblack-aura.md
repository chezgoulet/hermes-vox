# Investigation: the Pixel 9 "aura" (non-true-black region around the being)

Branch `feature/px9-trueblack`, off `testing` 8e5975e9c (0.7.1).
Evidence: `.evidence/` panel photos (camera-only captures of the physical Pixel 9
panel) vs `.evidence/` compositor screenshots (logical surface).

## What the evidence says

1. **The logical surface is clean.** Both compositor screenshots
   (`img_707d98e81d9e.jpg`, `img_b9b2c6c0c4db.jpg`) carry only the intended soft
   radial bloom: a radial luminance profile from the body centre decays smoothly
   and reaches exactly 0 by r≈225 px (measured: r=100→9, r=150→5, r=200→1,
   r≥225→0). There is no rectangle, no plateau, no lifted floor anywhere in the
   buffer the compositor captures.
2. **The panel shows a hard-edged lifted region whose boundary is the additive
   blits' coverage, not the art.** In `img_9de3ad8462fd.jpg` the lifted region is
   a rectangle (row scan: constant width ≈340–375 px and 85–91 % fill over rows
   608–728, tapering only in the first/last ~24 px = lens-rounded corners),
   plateau ≈ RGB(88,133,175)…(186,194,231), while one sprite-width outside it the
   panel reads RGB(0,0,0). A soft radial gradient cannot produce a hard edge; a
   blit-coverage rectangle can. In `img_a38211c6eb75.jpg` (radial-burst state) the
   same lifted region is the burst's coverage disc; in `img_087e73c07c51.jpg`
   (comet state) it is the comet's coverage. Hence: style-invariant, and it
   "encircles the shape being drawn".
3. **Tap → square.** The tap path is `MainActivity.kt:260`
   `avatar.setOnClickListener { hush(); feed(MotionState.Signal.BARGE) }` →
   `A_BURST` recoil. During the recoil the coverage grows: the halo dst rect
   swells `haloW = g * (1 + burstProg * 0.9)` (AvatarView.kt:1147) and the shock
   ring's bounding box grows to `bodyR * 1.95` (AvatarView.kt:1799). The lifted
   region swells to exactly that square and shrinks back with the animation —
   i.e. it tracks the *drawn coverage*, never a layout container (measured: the
   lifted rect is inset from the AvatarView bounds and from the 18 dp root
   padding, so it is not a container/background rectangle).
4. **Not a container colour.** Night `hv_bg` is `#000000`; the only near-black
   tokens (`hv_surface #0A0F16`, `hv_surface_high #101724`) are not painted
   anywhere behind the avatar (activity_main.xml root paints `hv_bg`; the avatar
   has no background/foreground/elevation/outline/clip in XML or code — a repo-wide
   grep for `setLayerType|setClipToOutline|setOutlineProvider|setElevation|
   translationZ|setRenderEffect` in `android/` returns nothing). The `#06070B`
   full-screen overlays (warming splash, models gate, coach scrim) are
   MATCH_PARENT and would lift the whole panel uniformly, not a rect around the
   being; the photos show RGB(0,0,0) outside the aura.
5. **0.7.1 could not have fixed this.** `canvas.drawColor(Color.BLACK, SRC)`
   makes each frame self-contained *in the buffer* — and the buffer was already
   clean (point 1). The artifact lives between the buffer and the panel, in how
   the window's per-frame updates are composed/emitted: `AvatarView` is the only
   view in the app that performs hundreds of partial-alpha, non-opaque-source
   blend ops (`PorterDuff.Mode.ADD`, AvatarView.kt:464/466) directly against the
   on-screen window surface every frame, so the window's per-frame update region
   and its pixel writes are keyed to the sprite coverage instead of being one
   honest opaque rectangle like every other view in the hierarchy.

## Root cause (statement)

The being is composited by blending ~300–640 additive, partial-alpha sprite blits
straight into the on-screen window surface each frame. On Pixel 9 (Tensor G4 /
Mali + LTPO partial-refresh panel) the display path emits/holds the window's
per-frame update keyed to that blit coverage, so the coverage rectangle is
emitted lifted (≈20–40/255 steady, brighter mid-animation) while the buffer
itself is clean — which is exactly why compositor screenshots are perfect, why
the region is style-invariant, why it swells to the burst's square on tap, and
why Pixel 7 (Tensor G2, 90 Hz, different HWC/panel path) never shows it. 0.7.1's
SRC clear fixed the buffer (already clean) and therefore changed nothing.

## Fix (universally correct, not a Pixel-9 hack)

Give `AvatarView` its own hardware layer (`setLayerType(LAYER_TYPE_HARDWARE,
null)` in `init`). The existing 0.7.1 `drawColor(BLACK, SRC)` then clears *the
layer* to honest opaque true black every frame, the additive art sums inside the
layer exactly as designed, and HWUI composites the view onto the window with a
single opaque, view-bounds blit — the same kind of update every ordinary view
produces. No additive/partial-alpha op ever touches the on-screen surface again,
on any device. The logical pixels are unchanged (same draws, same order, same
paints; the layer is composited at alpha 1 over the same black), so the art and
Pixel 7 are untouched.
