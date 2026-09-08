# Hermes Vox 0.5.5.4 — the eye is a rotating sphere

Patch on 0.5.5.3. Christopher correctly diagnosed that the eye still slid over a flat
plane — "an egg cracked on a table." But an eyeball is a SPHERE: when it looks, the whole
ball rotates in 3D so the iris swings to face the target, and the sclera foreshortens
around it while the silhouette stays a ball.

## The fix — A_SEEKER (the eye), spherical model
The eye is now modeled as a ball, not a sticker:
- Every particle sits on the **surface of a unit sphere** (iris = a small cap at the front
  pole facing the viewer; sclera = the rest of the visible ball).
- The **whole sphere rotates** about the horizontal + vertical look axes toward the target
  (`pupX/pupY`), so the iris rides the surface and the sclera foreshortens around it.
- **Orthographic projection** keeps the silhouette a ball while the texture turns INSIDE it.
- Blink still squashes vertically (eyelid close).
- `maxAng = 0.55` keeps the look subtle (a few degrees), matching real iris movement; the
  iris still rests ~72% of the time and only makes small, brief saccades.

## KEEP-list — verified
Only the A_SEEKER geometry branch in AvatarView.kt touched (replaced the flat-lens math with
the sphere-rotation projection). State (rest-at-center + saccade), blink, and all other
shapes unchanged. Built + gated on a fresh clone: EYESPI2-GATE-EXIT=0, BUILD SUCCESSFUL.

## Notes
- Version 0.5.5.4 (versionCode 98). Patch on 0.5.5.3 (97).
- A Kotlin type bug (bare `PI` is `Double`, `fsin`/`fcos` want `Float`) was caught by the
  independent gate and fixed with `PI.toFloat()` — the anchor rule catching it before release.
- Arc: range (0.5.5.1) → rest-at-center (0.5.5.2) → whole-object (0.5.5.3) → sphere (0.5.5.4).
  Christopher's anatomical diagnosis — "the ball moves in 3D so the iris points at the thing"
  — is the model that finally matches the object.
