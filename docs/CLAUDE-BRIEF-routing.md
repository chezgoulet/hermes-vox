# Visual Routing Fix — spec for the next 0.5 point release

**Problem (from field screenshots):** the at-rest being and the "thinking" fallback
both resolve to a narrow set of round/diffuse shapes (A_ORB cloud + the A_GYRE
spiral), so the 13 designed archetypes mostly never reach the screen. The being reads
"samey" even though Qwen designed 13 distinct body-states.

**Root cause — the routing is narrower than the design, in 4 places that disagree:**
1. `themeArch()` (AvatarView.kt:716) maps only **6** idle themes; everything else →
   `null` → `A_ORB`. So `aura` (the default), `lumen`, `listening`, `bloom` all
   collapse to the default cloud.
2. `cycleList` (AvatarView.kt:727) holds the same **6**.
3. `SHAPES` (AvatarView.kt:136) lists **9** — but 3 of those (`listening`, `lumen`,
   `bloom`) map to `null`/A_ORB, so they're dead in practice.
4. `SettingsActivity` theme picker (SettingsActivity.kt:565) offers **6** — and
   `bracket` (→A_FORGE, one of the good ones) is in `SHAPES` but NOT reachable from
   the UI, and FLAME/RIBBON/INFALL are never user-selectable at idle.
5. `mapTool` (MainActivity.kt:967) maps only 5 tool names; `thinking` with no
   matching tool → `A_GYRE` spiral (the fallback you saw).

## Design intent (confirmed with Christopher)
- Both idle controls are ALREADY user settings: hold-one (`particles_theme`) and
  auto-cycle (`particles_cycle`). **Do not invent new settings or change behavior.**
- The fix is purely to **widen the vocabulary** both dials draw from, so a chosen or
  cycled shape actually renders as the shape it names — and so the characterful
  archetypes are reachable at rest, not walled off.
- This is a **point release** on the 0.5 line (no feature expansion beyond the
  existing 13-archetype contract; the KEEP-list is sacred).

## KEEP-list (must NOT change)
- The 13 `A_*` constants, their `when(arch)` geometry, and the `P` physics body.
- The `MotionState` machine + `resolveArch` state dispatch (recoil/waiting/speaking/
  gather/listening/streaming/thinking + tool → archetype). These are correct.
- The two settings dials (`particles_theme`, `particles_cycle`) and their wiring.
- The render loop's zero-alloc guarantee (const arrays, no per-frame allocate).

## The fix (4 coordinated edits, all in the "widen the picker/dispatcher" family)

### Edit 1 — `AvatarView.kt:themeArch` (~716)
Extend so EVERY idle theme maps to a real archetype, not null. Add a mapping for the
missing themes and for the shapes that currently fall to A_ORB. The full vocab to
expose at idle (curated — see design menu; below is the base proposal):
```
"aura"        -> A_ORB        (default cloud)
"iris"        -> A_BREATH
"vortex"      -> A_GYRE
"waveform"    -> A_VOICE
"scan"        -> A_SWEEP
"constellation"-> A_NODES
"bracket"     -> A_FORGE
"flame"       -> A_FLAME       (NEW at idle)
"ribbon"      -> A_RIBBON      (NEW at idle)
"infall"      -> A_INFALL      (NEW at idle)
"bloom"       -> A_BLOOM
"hearth"/"drift" -> A_ORB      (alias)
else          -> null          (future-proof: still fall to A_ORB)
```
Result: no named idle theme silently becomes the wrong shape; the characterful ones
fire.

### Edit 2 — `AvatarView.kt:cycleList` (~727)
Grow to the same curated set so auto-cycle roams the full vocabulary:
```
arrayOf("aura","iris","vortex","waveform","scan","constellation",
        "bracket","flame","ribbon","infall","bloom")
```
Keep it a const array (zero-alloc). (Exact set finalised once the design menu is
confirmed — these must match Edit 1 and Edit 4.)

### Edit 3 — `MainActivity.kt:mapTool` (~967)
Widen so fewer "thinking" turns fall to the A_GYRE spiral:
- add `web_search`, `web_extract`, `web_fetch` → "web" (already spans "web"/"search"/"extract")
- add `delegate_task`, `run_agent`, `spawn` → a new motif "agent" (→ A_GYRE is fine,
  but at least map it explicitly so it doesn't read as "unknown")
- add `terminal` variants are already covered ("terminal"/"shell"/"exec")
- add `memory`, `recall` already covered; `ragamuffin` already covered
- add `video_analyze`, `image`, `vision` → "eye"/"vision" motif (→ A_GYRE fallback OR a
  new archetype if the design menu adds one)
The point: **map known tools explicitly; only truly unknown ones fall to spiral.

### Edit 4 — `SettingsActivity.kt` theme picker (~565)
Grow `themeVals` + `themeLabels` to the same curated set as Edit 1/2, so the user can
actually CHOOSE a flame/ribbon/infall/bracket at idle. Add human labels for each.

## Verification (per the repo's contract)
- `api`-free: run `go test ./voice/...` (no Go change here, but confirm nothing broke).
- Kotlin: `android/gradlew :app:testReleaseUnitTest` + `:app:assembleRelease`.
- Behavioral check (manual/desktop): for EACH curated theme, call `avatar.preview(<name>)`
  and confirm the rendered figure matches the intended archetype (no A_ORB fallback,
  no spiral where a flame/ribbon should be). Cross-check against the `spriteKey`/
  archetype table.
- Regression: confirm the two settings dials still read/write the same prefs, and that
  `preview()` for the 6 pre-existing themes renders identically to before.

## Open decision before build
The exact curated idle set (which of the 13 + any new ones from the design menu are
worth exposing at rest) — see Part 2 design menu. Edit 1/2/4 must use the same set.
