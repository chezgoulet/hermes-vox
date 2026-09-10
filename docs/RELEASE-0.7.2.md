# Hermes Vox 0.7.2 — true black

The aura fix. A faint rectangular "canvas" had been visible around the being on
accurate displays since long before 0.7.0 — it took a camera photo of the
physical panel, a colour-profile A/B, and a glow-slider test to corner it.

## The bug

On accurate displays (Pixel 9 on GrapheneOS and Pixel 7 on stock, both set to
the **Natural** colour profile), a faint lifted rectangle was visible around the
being. It was invisible on **Adaptive** (whose tone curve crushes near-blacks),
invisible in compositor screenshots (JPG compression erases the same values),
and present in every version back to 0.6.x — a long-standing rendering property
that simply hadn't been *seen* before.

## The root cause

The halo sprite's radial gradient ran its low tail all the way to
`TRANSPARENT` at the sprite rim, so the halo blit's **rectangular outer edge
carried faint-but-nonzero near-black values** — content in the frame, drawn
fresh every frame. Evidence chain: style-invariant (so not the particles);
swelled square on tap (the recoil scales the halo rect); scaled with the
**glow slider** (`visGlow` multiplies exactly one draw call — the halo blit);
and the two renderer-side experiments (the 0.7.1 per-frame clear and a
hardware layer) changed nothing, because the floor was never an accumulation
problem.

## The fix

The halo bake now tapers through a low alpha stop and reaches **true
`TRANSPARENT` at ~0.66 of the radius**, with a **guaranteed-zero skirt** from
0.66 to the rim. The rectangular boundary no longer exists on any display. The
visible bloom's character is unchanged inside the body — same colours, same
per-category halo sizing; only the invisible tail died.

## Cleanup

- The **0.7.1 per-frame `SRC` clear stays** — every frame starts from honest
  opaque black, so no cross-frame accumulation is possible on any device,
  regardless of dirty-region behaviour. A good invariant, kept.
- The **px9-trueblack hardware layer is removed** — it was the second wrong
  theory's fix, field-proven inert (the aura was identical with and without
  it), and it cost an extra offscreen composite per frame. The view draws
  directly to the window surface again, as every pre-0.7.2 version did.

## Verification

Field-confirmed by Christopher on both phones under **both** Natural and
Adaptive colour profiles, on the hardware-layer-free nightly
(`nightly-20260910-000519-70bdeac`). Promotion gate on a clean Thelio clone of
`testing`: go vet/test → gomobile bind → `assembleRelease` +
`testReleaseUnitTest` + `bundleRelease` — 34 suites / 258 tests / 0 failures.
Version 0.7.2, versionCode 114, signer CN=Hermes Vox.
