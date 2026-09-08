# Hermes Vox 0.5.5.5 — sphere fork + eye aperture

Christopher's fork: the full sphere was genuinely interesting and deserved its own
direction; the eye should only render the eyelid-exposed slice, not the whole globe.

## A_SPHERE (new archetype 20) — the full ball, given a life of its own
- Every particle on the surface of a unit sphere.
- **Bounces** on a lazy orbit (sphOx/sphOy — a slow ellipse drift, not anchored).
- **Spins** about its own axis (sphCS/sphSN), so no two frames hold the same surface —
  reads as a rolling, tumbling ball.
- Wired into themeArch ("sphere"), cycleList, SHAPES, SHAPE_LABELS/TOKENS, halo + springK
  (calm orb-like spring). User-choosable + auto-cyclable.

## A_SEEKER (rewritten as the eye aperture) — the beach-ball stripe
- Only the **exposed almond slice** of the sphere (the eyelid opening) is rendered.
- **Iris = a centered cluster** of accent particles dead-center of that stripe, riding a
  small glance (±30% of the lens, iris-commensurate).
- **Sclera fills the almond** around it, with a slight sphere-bend so the stripe curves
  like it's wrapped on a ball, not a flat band.
- Keeps the glancing + blinking behavior. No spin, no bounce.

## KEEP-list — verified
Existing 13 + other Wave 1 shapes untouched. New A_SPHERE constant (20) appended after
A_TAKU (19); the A_SEEKER geometry branch rewritten in place. Gated on a fresh clone:
SPHEREFORK-GATE-EXIT=0, BUILD SUCCESSFUL.

## Notes
- Version 0.5.5.5 (versionCode 99). Patch on 0.5.5.4 (98).
- This is the semantic split: the SPHERE is its own growing thing (bounces + spins);
  the EYE is only the sliver the eyelid reveals (centered iris, glancing, blinking).
