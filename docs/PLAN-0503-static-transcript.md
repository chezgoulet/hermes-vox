# SPEC — 0.5.0.3: kill the Star-Wars drift + screen-alive toggle (post-facelift)

## Field evidence (2026-09-07 13:00 log, 0.5.0.2)
ANIMATION IS GREAT ("holy smokes they look good", "jellyfish animation", "most
productive session"). Barge-in, transcript-freeze, no-phantom-turn all proven.
Christopher's verbatim in-log feedback (the only two changes + one flag):
1. "we should simply eliminate the Star Wars scroll and have the text render in."
2. Toggle exposed in Settings to keep the screen alive through a call.
3. Flag: "the app pushes the last message text to the screen when the upstream is
   slow" — investigate before changing.

## The Star-Wars drift (CrawlView.kt)
`drift` offset (:34) added to `baseBottom - tl.height - drift` (:73), incremented by
`drift += rate*dt` (:71) — the upward crawl. KEEP the reveal-freeze transcript logic
(:88 cut = spoken boundary) and the top-fade; REMOVE the upward motion.

### K1 — text renders IN PLACE (no drift)
- In CrawView: stop incrementing `drift` (or set rate=0) so `blockTop = baseBottom -
  tl.height` (static). The reply appears at rest, top-faded, reveal-frozen boundary
  intact. The "Star Wars intro treatment" becomes a "static top-faded transcript."
- KEEP: the spoken/unspoken split (:88), the reveal-freeze chars= of= logging, the
  hide-when-done (:101), setRole, setText signature. Only the drift motion goes.
- Cleanest: remove the `drift` field + its increment, blockTop = baseBottom - height.
  Do NOT touch the reveal logic (that's the part that works and the log proves it).

### K2 — settings screen-alive toggle
- New pref `keep_screen_on` (default false) in Settings; when ON, the foreground
  service (or the active call) holds `getWindow().addFlags(FLAG_KEEP_SCREEN_ON)` on
  the activity surface, cleared on call end / toggle off. NO new permission (it's a
  window flag, not WAKE_LOCK). Keep-lifetime: armed at call start (wake acquired),
  released at the same teardown as focus/wake — so it can't leak.
- Expose in SettingsActivity + layout, restore-defaults covers it (default false).

### K3 — investigate, don't assume
- Flag #3: "app pushes the last message text when the upstream is slow." Read where
  onReply sets the full text (the reply.setText that runs on done) and whether it
  races the reveal-loop. If it's the text-only settle showing full text during a
  slow stall, that's arguably correct (you see what it'll say) — but Christopher may
  mean the OPPOSITE (text appears that shouldn't, or the full text surfaces over the
  frozen reveal). Read it; report the mechanism; do NOT change behavior unless the
  read shows it's wrong. This is measure-before-fix — no behavior change without a
  confirmed rationale.

## KEEP-LIST (zero-touch)
The whole 0.5.0.2 AvatarView/PorterDuff/13-archetype swarm, MotionState, silenceAll,
fence, retirement, focus, BargeGate/EscapeRule, ReplySettleRule, crash guard,
transcript reveal chars logic, session_turns. CrawlView setRole/setText signature.

## Gate
cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon exit 0 (+ assembleRelease must compile).

## Deliverable — one commit
'anim/display: static in-place transcript (no Star-Wars drift) + screen-alive toggle — 0.5.0.3'
versionCode 89 / versionName 0.5.0.3 + docs/RELEASE-0.5.0.3.md
Print: K1_DIFF (drift removed, reveal intact), K2_SITE (toggle wired), K3_FINDING
(<mechanism for the slow-upstream text flag, and whether any change was needed>).
