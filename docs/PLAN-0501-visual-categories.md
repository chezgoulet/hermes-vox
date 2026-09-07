# SPEC — 0.5.1: exposable visual categories + connection-test/dialing pill fix

## Field evidence (2026-09-07 13:43–13:55 log, 0.5.0.3 device pass — the "most
## productive session" + follow-up)
Animation praised ("great app", "really, really good", "so many more possibilities",
"make it really, really interactive"). Two concrete asks, verbatim:
1. "I would like the visuals to be very diverse and expose categories of visuals in
   the user settings." (Christopher, 13:54:41)
2. "The warming up pill should say 'Dialing' separately. The connection test does
   not [report correctly]." — field shows `conn-test: ping=false(unknown)
   stream=false(unknown)` firing at pipe startup (13:49:21) BEFORE the gateway is
   warm, so the pill sits on "Warming up" and never transitions.

## Part A — expose visual categories in Settings

### A1 ground truth (what exists now)
- `SettingsActivity.kt:504-507` — a 6-value "Presence shape / theme" picker:
  `themeLabels = Aura/Iris/Vortex/Waveform/Scan/Constellation`,
  `themeVals = aura/iris/vortex/waveform/scan/constellation`, pref `particles_theme`
  (default "aura"), plus `particles_cycle` (bool, default true).
- `MainActivity.kt:1101-1103` — reads `particles_theme` + `particles_cycle` and calls
  `avatar.setIdleTheme(theme)` / `avatar.setCycleThemes(...)`.
- `AvatarView.kt` (0.5.0.2 swarm) — 13 archetypes (A_ORB..A_BLOOM), `setIdleTheme`,
  `setCycleThemes`, `preview`. The swarm is the body; the theme is the *idle shape*.

### A2 the requested change
Expose CATEGORIES of visual style as user-selectable settings — not just the idle
shape. "Visually diverse." Design decision to make:
- A **Visual category** selector (e.g. Aura / Ember / Ripple / Nebula / Fields / 1-bit
  — painterly families) OR a **style-diversity** control (per-archetype richness,
  speed/bright sweep exposed as user sliders) — the SPEC leaves the palette to the
  designer but MUST land *something* the user can choose that meaningfully diversifies
  what the being looks like moment to moment, not just its idle blob.
- Keep it a Settings surface (a "Visuals" group): pickers + the existing
  `particles_theme`/`particles_cycle` preserved into it.
- Persist like the existing prefs (prefs.getString/getBoolean). Wire any new selectors
  through `avatar.setX(...)` calls so MainActivity's feed stays the controller.

### A3 constraints
- KEEP-list (zero-touch): MotionState.kt enum/signals, the public AvatarView API
  MainActivity calls (applyMotion/driveMotion/setStall/onTool/pulseTool/setStateLevel/
  preview/setIdleTheme/setCycleThemes), silenceAll, fence, retirement, focus,
  BargeGate/EscapeRule, ReplySettleRule, crash guard, 0.5.0.3 static-transcript +
  reveal boundary + screen-alive. ONLY AvatarView.kt rendering internals + the Settings
  surface + MainActivity wiring may change.
- Any new visual feature must not regress the proven frame budget (0-alloc hot loop,
  ~0.03-0.2% of 33ms). New categories/richness must be re-measured; if a category is
  heavier, gate it behind a setting the user can turn off (default = light/cheap).

## Part B — connection-test / "Dialing" pill fix

### B1 ground truth
- `VoiceController.kt:1326-1354` `testConnectionHuman()` — GET /v1/models (ping) + POST
  /v1/responses (stream), 8s timeouts, returns success/failure copy. The `conn-test:
  ping=.. stream=..` VoxLog line is at :1349 (and the raw `testConnection()` at :1320).
- `MainActivity.kt:344-353` `openLine` — if `!c.isWarm()`, shows the `warming`
  TextView + `setStatus("Warming up…", true)`; on warm it hides it. There is a
  `warming` TextView (:264-276) and a status pill (`setStatus`).
- The field bug: conn-test runs (or is read) at pipe startup while the gateway is
  still cold → `ping=false stream=false` → the pill says "Warming up" perpetually and
  the user can't tell warm-up from "Dialing"/connected.

### B2 the requested change
- **"Dialing" vs "Warming up" as DISTINCT pill states.** Introduce the semantic:
  "Dialing" = the app is reaching out to the configured gateway (endpoint set, network
  attempt in flight); "Warming up" = models/TTS/STT still loading. These are different
  phases and must not be conflated.
- **Connection test must report honestly and at the right time.** The conn-test (ping
  + stream) should NOT be presented as a verdict while the gateway is still warming.
  Either (a) run it only when warm, (b) label a pre-warm result "not tested yet," or
  (c) re-run it at the moment warmth completes and transition the pill
  "Warming up → Dialing → Connected" accordingly. Pick (c) as the corrected UX: the
  pill reflects the real phase, not a single sticky "Warming up."
- When the connection test DOES run, the verbose copy stays, but the failure must not
  be a blanket "Couldn't reach the gateway. Check your network" when the real cause is
  a cold gateway (it did reach it; it just wasn't ready). Distinguish "reachable but
  warming" from "unreachable."

### B3 constraints
- No new permission. No change to the auth/key flow (C0). Keep the SECRETS rule — no
  key material in logs.
- The status pill + warming TextView are the surface; only these and the conn-test
  failure copy change. Do not touch the stream/turn engine.

## KEEP-LIST (zero-touch) — repeat for the worker
MotionState.kt enum/signals, public AvatarView API, silenceAll, stream fence,
retirement, focus, BargeGate/EscapeRule, ReplySettleRule, crash guard, static
transcript reveal boundary, screen-alive toggle, the whole voice/turn pipeline.

## Gate
cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon (exit 0) AND assembleRelease compiles.

## Deliverable — one commit
'anim+ux: exposable visual categories + Dialing/warming pill phases — 0.5.1'
versionCode 90 / versionName 0.5.1 + docs/RELEASE-0.5.1.md
Print: A_PALETTE (what category selector/diversity was added, how it diversifies),
A_COST (frame budget re-measured, any heavier category gated), B_PHASES (the
Dialing/Warming/Connected transition), B_CONNTEST (whether+why conn-test was
re-timed/reworded). Note: this is a DESIGN-OWNER spec — the palette is deliberately
left to the implementer's taste, but it must be genuinely `very diverse` and
`user-selectable`, not a 7th theme label in the same family.
