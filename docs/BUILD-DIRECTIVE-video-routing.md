# BUILD-DIRECTIVE: video-routing (Part 1) — widen the shape vocabulary

REPO (sandbox): `/home/c/vox-video`  — branch `video-routing-spec`, HEAD `fc23d7f`.
Read the design docs FIRST: `docs/CLAUDE-BRIEF-routing.md` (the 4 edits) + cross-check
`docs/design-menu-shapes.md` (the vocab + locked decisions).

## The single goal
The being's at-rest scene and its "thinking" fallback both collapse to a narrow set
(ORB cloud + gyre spiral) even though 13 archetypes exist. Widen the 4 routing surfaces
so the characterful shapes actually render, matching exactly the design docs. The idle
controls (hold-one `particles_theme`, auto-cycle `particles_cycle`) are ALREADY user
settings — this only widens WHAT they draw from. **No new settings, no behavior change.**

## KEEP-list (sacred — do NOT touch)
- The 13 `A_*` constants and their `when(arch)` geometry (AvatarView.kt:176-...).
- The `P` physics body + the `MotionState` machine + `resolveArch` dispatch logic.
- The two settings dials (`particles_theme`, `particles_cycle`) and their wiring.
- The zero-alloc render loop (const arrays, no per-frame allocate).
- The reference `A_GYRE`/`A_ORB` geometry EXACTLY as-is.

## The 4 edits (file:line anchors from docs/CLAUDE-BRIEF-routing.md)

### Edit 1 — AvatarView.kt `themeArch` (~716)
Extend the `when` so every named idle theme maps to a real archetype, not `null`. Follow
the route spec's proposed mapping (aura→A_ORB, iris→A_BREATH, vortex→A_GYRE,
waveform→A_VOICE, scan→A_SWEEP, constellation→A_NODES, bracket→A_FORGE, flame→A_FLAME,
ribbon→A_RIBBON, infall→A_INFALL, bloom→A_BLOOM, else→null). Any theme that has no
archetype yet stays `null` (falls to A_ORB).

### Edit 2 — AvatarView.kt `cycleList` (~727)
Grow the const array to the same curated set from Edit 1 (do NOT add a new archetype
here; only list themes that Edit 1 maps — see the route spec). Keep it `const`/zero-alloc.

### Edit 3 — MainActivity.kt `mapTool` (~967)
Add explicit tool-name→motif mappings so fewer "thinking" turns fall to the A_GYRE spiral.
Add: `web_search/web_extract/web_fetch` (already "web" spans search/extract — extend),
`delegate_task/spawn/run_agent` → "agent", `terminal` variants already covered,
`video_analyze/image/vision` → "vision". Keep the `else -> null` fallback (→ A_GYRE).

### Edit 4 — SettingsActivity.kt theme picker (~565)
Grow `themeVals` + `themeLabels` to the same curated set (so the user can choose a
flame/ribbon/infall/bracket at idle). Human labels for each.

## Verify after EACH edit (anchor rule — no green gate without it)
- Edit 1+2: `cd /home/c/vox-video/android && /home/c/gradle-8.12.1/bin/gradle
  compileReleaseKotlin --no-daemon` (quick) — must be BUILD SUCCESSFUL before proceeding.
- Edit 3: same, `compileReleaseKotlin`.
- Edit 4: `testReleaseUnitTest` (the picker is UI; the Kotlin compile is the gate).
- FINAL: `assembleRelease testReleaseUnitTest --no-daemon` → BUILD SUCCESSFUL + the APK
  badging shows the unchanged versionName (do NOT bump the version — this is part of the
  in-flight video-point batch, version bump happens at release time).

## IMPROVEMENTS section (required at the end)
List every file:line you changed, and anything you did BETTER than the spec (e.g. a theme
I mapped that was wrong, a cleaner mapTool key). If you judged a mapping suboptimal,
say so and why. Do NOT touch the geometry of the existing 13.

## Do NOT
- Do NOT run `go build ./...` (GLFW/X11 env gap, not your concern — only ./voice/...).
- Do NOT bump versionName/versionCode.
- Do NOT push. The branch is already pushed; you work locally, I gate + push.
