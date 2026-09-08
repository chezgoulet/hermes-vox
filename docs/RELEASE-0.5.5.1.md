# Hermes Vox 0.5.5.1 — eye-fix patch

Contained patch on 0.5.5. Fixes the one visual defect Christopher flagged: the eye's
iris was rendered off-center (rolled toward the lens rim), breaking the eye illusion.

## The fix — A_SEEKER (the eye)
Root cause: the saccade look-target used the FULL ±1 field, so the pupil drifted up to
~50% of body radius off-center — out past the almond lens's rim, reading as wall-eyed
and breaking the illusion.

Change (option B — keep the scanning character, tone it down): bounded the look-target
to ±0.72, so the pupil now stays at ~36% of body radius — comfortably INSIDE the almond
lens while still darting / scanning (the being looking around). No longer rolled to the
corner.

## KEEP-list — verified
Only the 2-line A_SEEKER look-target updater in AvatarView.kt touched. The eye's
geometry, blink cadence, and all other shapes (waveform/arc/nucleus/water/radar/octopus +
the original 13) unchanged. Built + gated on a fresh clone: EYEFIX-REL-EXIT=0,
BUILD SUCCESSFUL.

## Notes
- Version 0.5.5.1 (versionCode 95). Patch on 0.5.5 (versionCode 94).
- The other Wave 1 shapes were confirmed delightful by Christopher — no class-wide
  "never settles" fix needed.
