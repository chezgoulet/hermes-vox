# Hermes Vox 0.5.5.3 — the eye pivots as one object

Patch on 0.5.5.2. Christopher correctly diagnosed the remaining flaw: the iris moved over
a STATIC eyeball (it slid across a fixed sclera), which reads as mechanical/puppet-like. In
real anatomy the whole eyeball pivots in the socket — iris and sclera move as ONE object.

## The fix — A_SEEKER (the eye), object relationship
The sclera and iris now share the SAME look-target offset:
- Before: iris shifted by `pupX/pupY`, sclera frozen at `cx/cy` → iris slid over a static eye.
- After: both branches use `lookX = pupX * ehw * 0.55f` / `lookY = pupY * ehh * 0.55f` →
  **the entire particle structure pivots together, iris riding centered** within it.

So when the eye glances, the whole eye (sclera + iris) rotates in place as one unit — the
real-eyeball behavior, not a sliding pupil.

## KEEP-list — verified
Only the A_SEEKER geometry branches in AvatarView.kt touched (adding the shared lookX/lookY
offset to both the iris and sclera branches). Rest-at-center + small-saccade behavior, blink
cadence, and all other shapes unchanged. Built + gated on a fresh clone: EYEPIVOT-GATE-EXIT=0,
BUILD SUCCESSFUL.

## Notes
- Version 0.5.5.3 (versionCode 97). Patch on 0.5.5.2 (96).
- This is the final pass on the eye: range (0.5.5.1) → rest-at-center (0.5.5.2) →
  whole-object pivot (0.5.5.3). The object relationship was the real illusion-breaker.
