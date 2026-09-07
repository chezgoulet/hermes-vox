# Hermes Vox 0.5.1 — exposable visual categories + Dialing/Warming pill phases

Two field asks from the 2026-09-07 device pass, both from the same session that called
the animation "great app / really, really good / so many more possibilities":

> "I would like the visuals to be very diverse and expose categories of visuals in the
> user settings." — Christopher, 13:54:41

> "The warming up pill should say 'Dialing' separately. The connection test does not
> [report correctly]."

---

## Part A — the visual CATEGORY (`VisualStyle.kt`, Settings › Visuals)

### A_PALETTE — what was added, and why it is not a seventh theme label
`particles_theme` (Aura/Iris/Vortex/Waveform/Scan/Constellation) picks the being's
**idle shape** — which archetype the swarm forms while nothing is happening. It changes
the blob and nothing else, and the moment a turn starts the shape belongs to the state
anyway, so the pick stops mattering.

A **visual category** is orthogonal: a transform applied to *every* state — listening,
thinking, speaking, stalled, recoiling — across four independent axes.

| axis | what the category owns |
|---|---|
| **palette** | a family tint + saturation pulled *through* the authored per-state hues, plus a hue **turn** applied only to the ~30% accent particles |
| **light** | the sprite bake itself: `coreHeat` (how white-hot the specular centre burns) and `edge` (how tight the falloff is) |
| **mass** | `halo` (ambient bloom) and `size` (sprite scale) |
| **motion** | `energy` multiplies the flow field + tremor, so a family is calmer or wilder in every state, including the still ones |

Seven families ship:

- **Lumen — its own light** *(default)* — the 0.5.0.2/0.5.0.3 renderer, an exact identity.
- **Ember — forge-warm** — amber/red through every state, hot specular points, hard
  flicker (1.55×), energy 1.18. Volatile.
- **Abyss — deep water** — indigo, widest bloom (1.35×), big dim slow particles,
  energy 0.72. Its listening is nearly motionless.
- **Verdant — bioluminescent** — green/gold, crisp, accent turned 24° so a second
  organism reads inside the first.
- **Prism — split spectrum** — accent thrown 150° across the wheel at 1.25 saturation:
  two opposed colours that sum to white where the swarm is dense.
- **Ink — 1-bit** — no hue at all (sat 0.06), hard little points (`edge` 0.55), bloom
  almost off. The being as a plotter drawing, and the cheapest thing here.
- **Comet — trails (heavier)** — each particle drags its own recent past.

Two sliders ride on top so a chosen family can be pushed: **Motion energy** (0.5–1.6×)
and **Glow / bloom** (0.4–1.6×). Both are pure multipliers on the category's own values.

The existing **Presence shape / theme** and **Cycle themes while idle** controls are
preserved verbatim (same prefs, same wording) into the new **Visuals** group, which also
carries its own Restore-defaults row. `particles_theme`/`particles_cycle` moved from the
Appearance restore group to the Visuals one; Appearance keeps theme/layout/keep-screen-on.

Wiring is unchanged in shape: Settings only writes prefs; `MainActivity.applyParticlePrefs`
(the existing feed, called on create and on every resume) applies them through
`avatar.setVisualCategory/setVisualEnergy/setVisualGlow`.

### A_COST — re-measured, and the one heavy family is gated
- **The hot loop gained exactly two multiplies**, both against values it already
  computed (`flick`, `p.size`), plus two frame-scalar multiplies in `tunePhysics`.
  No branch, no allocation — the 0-alloc hot loop is intact.
- **Palette + light cost nothing per frame.** The category's colour work is two
  `VisualStyle.shade` calls per frame, measured in the gate at **0.086 µs/frame**
  (`VisualStyleTest.per_frame_style_work_is_negligible` prints it) against the 33,333 µs
  budget — 0.0003%. The light model is baked into the *cached* sprite, so a hard 1-bit
  point and a wide atmospheric bloom both cost one blit, same as the default; the bake
  happens on a category or colour change, never in the loop.
- **Comet is the one heavy family**: a trail sprite per particle **doubles the blit
  count** (320 → 640). So it is opt-in, it is never the default, and its Settings label
  says "heavier". Every other category draws exactly what the default draws.
- The default category is an **exact identity** on all four axes — an untouched install
  renders bit-for-bit what 0.5.0.3 rendered (asserted in `VisualStyleTest`).

---

## Part B — the pill reports the real phase (`ConnectionPhase.kt`)

### B_PHASES — Warming up → Dialing → Connected
The pill had exactly one pre-connected word, and it was sticky. "Warming up" covered the
local pipeline load *and* every network wait, so a cold gateway and a loading STT model
looked identical and neither ever resolved.

Three phases now, because they are three different things:

- **Warming up…** — the LOCAL pipeline (STT/TTS/VAD/models) is still loading. Nothing
  has been asked of the network, so **no gateway verdict is possible** — and
  `ConnectionPhase.resolve` refuses to give one: while warmth is false the probe result
  is ignored entirely, whatever it says. The field bug is closed structurally rather than
  by remembering not to test too early.
- **Dialing…** — the app is reaching out to the configured gateway: endpoint set, key
  present, a request actually in flight.
- **Connected** — the gateway answered (and stays quiet/hidden, as it always has).

Plus honest failure phases: *Gateway is warming up* (reached, not ready), *Gateway
rejected the key*, *Gateway answered with an error*, *Can't reach the gateway*.

Where it moves:
- `connectFromPrefs` used to assert "Connected" the instant a session *object* existed —
  before a byte had been sent. It now says **Dialing** and waits for the gateway to
  answer (a ping-only probe; opening the app does not fire a real model turn to colour
  a pill).
- `openVoiceLine` shows **Warming up** while it waits on the pipeline and **re-dials at
  the moment warmth completes** (spec B2c), so the pill transitions instead of sticking.
  The dial reports; it does not gate — the line opens underneath it.
- The controller's own `"warming"` state now says so on the pill instead of falling
  through to a silent "Connected" it had not earned.
- A live call still owns the pill ("On call"); only a genuine problem interrupts it.

### B_CONNTEST — why it was re-timed AND rewritten
**Root cause of `conn-test: ping=false(unknown) stream=false(unknown)`:** the Settings
"Test connection" row called the blocking HTTP work **straight from the click handler**,
on the UI thread. Android threw `NetworkOnMainThreadException` before a single byte
moved — and that exception carries **no message**, so the old `e.message ?: "unknown"`
erased the one fact that mattered and reported a failure verdict for a test that never
reached the network.

Fixed on three fronts:
1. **Re-timed.** `testConnectionAsync` runs the probe off the UI thread and delivers the
   result back on it; Settings shows "testing…" meanwhile. The main screen dials only
   when it has something honest to dial about (session built, or warmth just completed).
2. **Reworded — reachable ≠ ready.** `ConnectionPhase.classify` separates an *exception*
   on the ping (never reached it → **unreachable**) from a *status code* (reached it →
   everything after is what it said): 408/425/429/502/503/504 or a stream timeout after
   a good ping = **cold gateway**, 401/403 = **rejected key**, other non-2xx = **gateway
   error**. A gateway that answered is never told the user to check their network again.
3. **Diagnosable.** Failure reasons now carry the exception **class name** when there is
   no message, so `unknown` cannot recur. The log line is
   `conn-test: verdict=<probe> ping=<code|class> stream=<code|class>` — codes and class
   names only; the SECRETS rule holds, no key material is logged.

The verbose copy (WS3) is kept, the C0 missing-key prompt is kept, no new permission, no
change to the auth/key flow, and the stream/turn engine is untouched.

---

## Verified
- **KEEP-list zero-touch**: `MotionState.kt`, the public `AvatarView` API
  (applyMotion/driveMotion/setStall/onTool/pulseTool/setStateLevel/preview/setIdleTheme/
  setCycleThemes), silenceAll, the stream fence, retirement, focus, BargeGate/EscapeRule,
  ReplySettleRule, the crash guard, the 0.5.0.3 static transcript + reveal boundary +
  screen-alive toggle, and the whole voice/turn pipeline are unchanged. Only AvatarView's
  rendering internals, the Settings surface, MainActivity's wiring, and the connection
  test changed.
- **Gate**: `:app:testDebugUnitTest` exit 0 — 162 tests, including 10 new
  `VisualStyleTest` and 15 new `ConnectionPhaseTest` cases (both pure JVM, no emulator).
- Release sources + resources compile (`:app:compileReleaseSources`,
  `:app:processReleaseResources` BUILD SUCCESSFUL); a full APK packages on
  `:app:assembleDebug`. `:app:assembleRelease` cannot finish **signing** in this
  environment — `../keystore/release.keystore` is not present on this machine. That is
  environmental and pre-existing, not a code failure.

## The gate not yet proven
Whether Ember/Abyss/Ink/Prism/Comet *read* as distinct substances on the device, and
whether Comet's doubled blit count holds frame rate on the phone. Both are one device
pass away; Comet is the only thing that could cost frames, and it is off by default.
