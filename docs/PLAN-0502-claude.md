# SPEC — 0.5.2-CLAUDE: fix the false-401 conn-test + add a user-facing /compress command

## Field evidence (2026-09-07 log, 0.5.1 device pass)
1. `conn-test: verdict=auth ping=401 stream=-1` fires at pipe startup (14:27 and 16:05),
   yet the VERY NEXT turns stream fine. The 0.5.1 ConnectionPhase correctly renamed the
   verdict to "auth" (better than the old `ping=false(unknown)`), but it's a FALSE 401:
   the probe hits the endpoint with a state the live voice channel doesn't. Root cause
   needed, not a reword.
2. Christopher, verbatim (end of the long 15-turn session): "We need to expose a compress
   command to the user through this app and/or have [the entity do it]." — a user-facing
   /compress (context compaction), so long conversations keep going.

## K1 — fix the false-401 conn-test (ROOT CAUSE, not reword)
Read `VoiceController.kt` probeConnection / testConnectionHuman (the conn-test path).
The probe does GET /v1/models (ping) and POST /v1/responses (stream), both with the
bearer key. A 401 while the stream channel works = the probe is NOT using the same
auth/state as the real stream. Likely causes to investigate, in order:
  (a) the probe runs BEFORE the session/token is warm (sentinel case: it's a startup
      probe; the real stream establishes auth lazily). If so, the fix is to not present
      an auth verdict until the stream channel has actually authorized (or re-probe
      once warm).
  (b) the probe uses a different endpoint path or omits a header the streaming path
      includes (e.g. the real stream uses the /v1/responses with a session; the probe
      POSTs a bare "hello" that the gateway 401s before streaming).
  (c) the probe reads a stale/blank key pref while the live path uses the session's key.
WHATEVER the cause, the fix must make `verdict=auth` only appear when the probe TRULY
fails auth AND the live channel would also fail; when the live stream works, the probe
must not scream auth. When in doubt, have the probe defer to the live stream's actual
observed state (the real truth) rather than a synthetic one-shot HTTP result.
- The verbose copy should distinguish "gateway needs auth (check key)" from
  "gateway reachable but not yet warm" from "unreachable." 0.5.1 already has the phase
  machine — wire the 401 into it correctly.

## K2 — user-facing /compress command
- `/compress` (context compaction) exposed as a command the user can send in-app,
  mirroring the existing native commands (/health, /new, /reconnect, /clear, /reset,
  /status, /help) already wired in MainActivity. Find the native command dispatcher and
  add "compress" (+ alias "/compact" if natural).
- It must send the compaction request to the HERMES GATEWAY (the agent session) so long
  conversations keep going — the gateway-side compaction, not an app-side string trim.
- Keep the same pattern as the other native commands (mini-UI / status feedback), and
  add it to the /help listing. No new permission.

## KEEP-LIST (zero-touch)
MotionState enum/signals, public AvatarView API, silenceAll, stream fence, retirement,
focus, BargeGate/EscapeRule, ReplySettleRule, crash guard, static-transcript reveal
boundary, screen-alive, the VisualStyle category system + Settings surface, CrawlView,
SherpaTts, VoiceController turn engine (conn-test surface only for K1), the 13-archetype
swarm. ONLY the conn-test probe + the /compress command dispatcher change.

## Gate (assembleRelease, must compile; testDebugUnitTest exit 0)
cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon  (exit 0) AND assembleRelease compiles.

## Deliverable — one commit
'fix(conn-test)+feat(compress): no false-401, expose /compress — 0.5.2-claude'
versionCode 91 / versionName 0.5.2 + docs/RELEASE-0.5.2-claude.md
Print: K1_CAUSE (root cause of the 401 — was it (a)/(b)/(c)? and the fix),
K2_SITE (where /compress is wired + how), KEEP_VERIFY.
