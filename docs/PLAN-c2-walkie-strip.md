# SPEC — C2: strip walkie-talkie (PTT) mode (0.4.0 polish, batch 3)

## Directive
Christopher 2026-09-06: "This is also where we will strip out the walkie-talkie
mode." Hands-free realtime is the ONLY loop shape. Delete every PTT branch so the
controller stops carrying two-mode state (most historical half-duplex bugs lived
at that fork).

## Scope — grep shows PTT/walkie surfaces in 5 files (37 hits)
MainActivity.kt, VoiceController.kt, SettingsActivity.kt, activity_main.xml,
ModelCatalog.kt (verify — likely an incidental substring like 'pTt' inside a word;
if incidental, leave it and note so).

Rules:
1. UI: remove walkie/PTT toggle button(s) + any hold-to-talk wiring from
   activity_main.xml and MainActivity listeners. KEEP the call/hang-up button,
   hush (tap-to-stop during speech), mic mute if present, settings, log export.
2. VoiceController: remove the continuous=false branch entirely — PTT
   commitRequested paths, post-turn stop-listen-for-PTT, any walkie-only state
   fields. The realtime loop (VAD endpointing, gate, barge, retirement) is now
   the single code path. Do NOT touch: silenceAll, StreamRetirementState, fence,
   barge-watch, EndpointRule behavior.
3. Settings: remove any walkie mode pref/toggle from SettingsActivity + layout if
   present; restore-defaults updated; orphaned prefs keys left in place silently
   (removing prefs code paths is enough; never crash on stale SharedPreferences).
4. Any comments/docs inside touched files that describe the two-mode design get
   trimmed to describe the single mode (no new docs churn beyond touched regions).
5. grep -i walkie + grep -i 'ptt' over android/app/src/main must return ZERO after
   (ModelCatalog incidental noted with file:line if it survives).

## Version
NO bump (C1 already set 0.4.0-beta1; this ships inside the same beta batch).
Add '## Walkie-talkie mode removed' section to docs/RELEASE-0.4.0.md (create from
the C1-era stub if absent, listing C0 + C1 + C2 bullets).

## Gate
cd android && /home/c/gradle-8.12.1/bin/gradle :app:testDebugUnitTest -q → exit 0.
Existing tests must stay green unmodified EXCEPT any that assert PTT behavior —
those tests get DELETED with the feature (note each one removed + why).
Commit: 'polish: strip walkie-talkie (PTT) mode — hands-free realtime is the only loop (C2)'.
Print C2_DONE <hash> + PTT_REMOVAL_LIST (file:line groups removed) +
INCIDENTAL_NOTES (e.g. ModelCatalog).
