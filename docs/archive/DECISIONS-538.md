# #538 — decisions

Each entry gives the call I made, the alternative I rejected, and why.

1. **Treat A, B and C as one mechanism with two links, not three bugs.**
   *Rejected:* three separate investigations. The code joins them. An engine swap mid-reply is
   the shared precondition for the cascade (A) and for the DROP wedge (B and C), and both links
   shipped in 0.9.1 (#499 and #517). This matches "not present in previous versions" and
   "`/new` resets it". I still give each family its own confidence in `FINDINGS.md`, because
   the join is inferred and not observed.

2. **Adopt the replacement engine in the streaming worker** (open its fence, retry the chunk
   once).
   *Rejected (a):* have `TtsEngineHost` re-open the stream on install. The host knows nothing
   about streams, and teaching it would couple the lifecycle to the controller's turn state.
   *Rejected (b):* re-open from `onTtsState` when the new engine becomes ready. That races the
   worker, and it duplicates the after-glue re-open, which already has its own ownership rules
   (`replyOwnsTrack`). The worker is the stream's single owner (R1), so the fix belongs there.
   *Rejected (c):* simply not reporting any `false`. That would hide genuine silent or throwing
   synths, which #499 deliberately made visible.

3. **Retry the adopted chunk once, not in a loop.** If the second try also fails, it is read
   for what it is (a `SILENT`/`ERROR` result spends the budget as before). A loop could spin on
   a genuinely broken engine.

4. **Tell "never opened" from "stopped" with `StreamFence.opened`** rather than tracking engine
   identity in the controller. The fence already holds the state; one getter is the smallest
   true witness, and it is unit-testable.

5. **Do not adopt over a `CUT`.** A closed fence that *was* opened means a stop. Re-opening it
   would undo a barge, a hush or a hang-up, which is the resurrection bug #D1 fixed.

6. **`NO_ENGINE` blames nobody.** It only happens when the host has already shut the engine
   down to replace it, so the failure is already being handled.

7. **Route the streaming path's track failure through the existing `NOT_THE_ENGINE` set**, by
   filtering in `noteFailure`. *Rejected:* a second, stream-only exemption list. #549 already
   defined the policy, and the defect was that only one of the two entry points applied it.

8. **Settle: an open, already-heard stream retires through its worker whatever the engine.**
   *Rejected (a):* `SPEAK` the full reply on the new engine. That re-speaks audio the caller
   already heard, which is the 0.4.0.4 ghost re-speak that `genSpokeAudio` exists to prevent.
   *Rejected (b):* keep `DROP` and release the gate from the settle. That gives the stream two
   owners of "done" (#60/#71), and the worker would still be waiting.
   *Rejected (c):* shorten `TurnGateCeiling.HARD_MS`. That treats the symptom; the wedge would
   still last minutes. The reply's unspoken tail after a degradation is left as text only.
   That is the honest cost, and better than speaking it twice.

9. **No change to the re-warm/degradation budget, the system voice's watchdog, or `CallQuiet`.**
   Each one is a candidate for the *remaining* unknowns (see `OPEN.md`), but changing it
   without the reporter's log would be guessing. The brief asks for minimal changes.

10. **Do not release the old `OfflineTts` native memory in `SherpaTts.shutdown()`.** This is a
    real leak-shaped detail: `shutdown()` only nulls the reference. But a `release()` there
    could race an in-flight synth on the worker thread (a native use-after-free). It needs its
    own design and its own issue, not a line slipped into this fix.

11. **Witness lines at `W` level** (`tts-stream-adopt`, `settle-retire`) and the
    non-blaming drop at `D` level. The two `W` lines are rare and diagnostic, and they must
    survive the reporter's log level. The drop line can fire on every chunk of a cut reply, so
    it stays at debug.

12. **Leave `StreamSynthResult.failed` in place**, documented as the pre-#538 reading.
    *Rejected:* deleting it. `StreamStallTest` pins its table, and those tests still describe
    true behaviour for the cases they cover. The production path no longer calls it. Removing
    it is a clean-up for another commit.

13. **No version bump, no push, no merge.** These are the brief's constraints.
    **The skill was updated:** its log table now names `tts-recover-skip`,
    `tts-stream-adopt` and `settle-retire`, the `track-unavailable` exemption on `tts-fail`,
    and the streamed `tts-drop` reasons. The checker now verifies 443 claims (up from 440,
    0 stale). *Rejected:* leaving the skill alone. It already omitted #549's exemption, and the
    owner will look up the witness lines there.

14. **No device reproduction was attempted.** The brief establishes that a spoken turn cannot be
    produced headlessly on this host. Both links are instead proven against the real
    `TtsEngineHost` and `ReplySettleRule` in JVM tests. The test that shows the 0.9.1 cascade
    (`before_538_…`) uses the *old* reading of chunk results, so it documents the defect and
    does not test dead code.

---

## After the 2026-11-10 report — what I changed my mind about

15. **Link 2 (the DROP wedge) is not this reporter's "working" screen.** It had 0.55 for C; it
    now has 0.1. The 600 s ceiling it predicts doesn't match a 60–120 s recovery, and it needs a
    reply that was already heard, which the new episodes don't have. I keep the fix: it is a
    real defect pinned by a test. I no longer present it as the explanation for #538.

16. **I retract my use of the btop observation.** I treated "no activity on the gateway" as
    support for the wedge. With `mind: remote` and a possible provider wait, btop can't tell an
    unanswered request from a silent run. The log's `run-admit` / `turn-abandon run=` field
    does that job instead.

17. **"Spoken replies off" and "working with no reply" are now separate problems**, rather than
    one mechanism. The first report's join was inferred; the second report shows the "working"
    episodes happening without any voice-leg event.

18. **Code change: a witness, not a behaviour fix** (commit `bc4b4b33`, `event=run-admit`).
    *Rejected (a):* disabling HTTP keep-alive for admission, or shortening the admission
    timeout. H1 is 0.35, not proven, and either change would cost every healthy turn a TLS
    handshake or risk failing slow-but-alive gateways. *Rejected (b):* no code at all. The brief
    allows code only for a provable defect, and the provable defect here is diagnostic: the
    log the reporter *can* send could not say whether a turn was ever admitted (admission
    logged nothing; `response.created` is `dd`). That is checked by grep and pinned by
    `RunAdmissionWitnessTest`. I judged that a missing witness on the exact question the owner
    must answer qualifies, and I record that as a judgement call, not a cure.

19. **Log the admission in `core` through `StreamRoute.log`**, not in the controller. Only the
    reader knows about the attempt, the retry and the run id. `StreamRoute.log` is already bound
    to `VoxLog.d` (`RouteChoice.kt:44`), which writes to the file. The line carries metadata
    only: attempt, result, ms, HTTP code, opaque run id, exception class. It carries no text.

20. **Corrected the skill's `event=stream-stall` row** ("no SSE bytes, keepalives included").
    The stall is measured on events, and keepalives are not events
    (`VoiceController.kt:2683-2707`, `core/Stream.kt:1169`). Read literally, the old wording
    would have made a stall look like a dead connection, which is exactly the H1/H2 confusion
    this report needs to avoid.

21. **Killed the `newSession` teardown-ordering suspect without changing it.** The trailing
    `releaseVoiceFocus`/`unregisterRouteCallback` run after `REARM_CALL`, but the re-armed call
    acquires 500 ms later through the warm-wait. Reordering it would be a tidy-up with no proven
    effect, so it is out of scope for this branch.

22. **The original FINDINGS sections are left as written,** with a pointer at the top to the
    revision. *Rejected:* rewriting them in place. The owner is checking how the conclusion
    moved, and an edited history would hide that.

---

## After the reporter's log (2026-11-10) — the calls on `fix/stream-liveness`

23. **Followed the log, not the brief's defect list.** The brief named three defects and said
    1 and 2 explain the hang. Counting the log by build (the `startStream ->` format changes at
    line ~48,900) showed that every count behind defects 1 and 2 comes from an older build. The
    0.9.1 block has a different, deterministic wedge (D). I fixed D and said so plainly.
    *Rejected:* shipping fixes for 1 and 2 as asked. A patch to a path the current build
    doesn't misuse would read as the cure and leave the real hang in place.

24. **Defect 1: no code change.** `error` is set only when `done` (`core/Stream.kt:338`), so
    `pollErr.isNotBlank()` at `VoiceController.kt:2685` cannot refresh liveness on its own. The
    `no such stream` errors are exceptions, and that path never touches `lastEventAt`.
    *Rejected (a):* deleting the clause. It is redundant, and removing it changes nothing a test
    could show. *Rejected (b):* feeding payload errors into the poll-error budget. They are
    terminal (`done`), so there is nothing to budget.

25. **Defect 2: no code change.** Since #240, `silenceAll` → `endReply(CANCELLED)` →
    `replies.isDead(gen)` stops the loop at its next tick (`VoiceController.kt:2627`, `:3912`).
    The new block has 0 poll errors after 4 `focus-loss` releases. *Rejected:* making `no such
    stream` terminal on the first error. It would be correct, but the cap already bounds it
    (≤ 120 ms in the field), the log shows no remaining instance, and it is a separate concern.

26. **Fix D twice: at the cause and with a backstop.**
    - *Cause:* `setStartThresholdInFrames(sr/4)` on API 31+. The track starts after a quarter
      second, as the pre-#517 0.25 s track did, and keeps its 4 s capacity.
    - *Backstop (all APIs; minSdk is 24):* `StreamDrain` bounds the drain by the audio owed + 3 s,
      not a flat 120 s. A head stuck for 400 ms is played out with `stop()`.

    *Rejected (a):* reverting to the 0.25 s buffer. That re-introduces #517's boundary silence
    (the worker blocks on every chunk write). *Rejected (b):* padding short replies with silence
    to fill 4 s. That adds up to 4 s of dead air before the mic reopens. *Rejected (c):* only
    lowering the 120 s ceiling. The wedge would shrink, but the reply would still never play.
    *Rejected (d):* the threshold alone, which leaves API 24–30 wedged.

27. **The poll-abort fallthrough is recorded, not fixed.** After `stream-poll-abort` the loop
    reaches `abandonTurn(…"budget")` and throws `"timeout"` (`VoiceController.kt:2663` →
    `:2833`). That mislabels 8 field ends as `turn-timeout` and stops an already-dead run. It
    is cosmetic, it is a separate concern, and 0.9.1 never reached it in this log.

28. **Force with `stop()`, not `pause()`/`flush()`.** In `MODE_STREAM`, `stop()` plays out what
    was written and then stops, so a track still filling is made to render. It is safe against
    #7 (never stop/release under a writer) because `finishStreaming` runs after every write of
    the reply has returned. Its caller is the writer.

29. **The constants.** `STUCK_MS` = 400 ms: above a Bluetooth HAL's head period (a test pins
    200 ms steps as not stuck). A false force is benign, because `stop()` still plays the tail.
    `SLACK_MS` = 3 s for a moving head and `FORCED_SLACK_MS` = 750 ms after a force (route
    latency). The 120 s argument stays only as the outer cap.

30. **The date: `MM-dd HH:mm:ss.SSS`.** It costs 6 bytes a line, ~5% of the 5 MB window at the
    field's ~112 bytes/line. *Rejected (a):* `yyyy-MM-dd`, at 11 bytes; one export (≤ 10 MB) never
    needs the year. *Rejected (b):* a date-marker line on day change. That is cheaper, but a
    grep for one event loses it, and rotation can split the marker from its lines.

31. **The witness levels.** `tts-drain result=played|not-playing` at `D` (one per reply, like
    `tts-retire`). `forced|timeout` at `W`, because those are the lines an owner greps for.
    `tts-track` at `D`, one per track.

32. **I report the brief's three witnesses as the log reads them**, not as new lines. 0.9.1
    already emits (b) `wait-silent` and (c) `reply-end … cancelled` with no poll errors after
    it, and (a) needs no line because the defect does not exist. *Rejected:* inventing lines
    for them. That would be a witness to nothing.

33. **The skill was updated.** §8.13 said "if only `reveal-hold` appears, nothing is wrong".
    That is false for a short reply on 0.9.1: it is the wedge's signature. I added an
    exception, plus `tts-drain` and `tts-track` rows. The checker verifies 446 claims (from
    443), 0 stale.

34. **Link 1's code is untouched.** The log confirms the cascade once, completely
    (10:19:54–10:22:25, with a second `stream-synth` 15 ms after the re-warm). This branch
    already carries the earlier adopt/CUT patch, so nothing new is needed. The first trigger is
    narrowed to 0.9.1's unlogged `false` returns but not identified (`OPEN.md`).

35. **The earlier sections are left as written** and the top banner is updated, for the same
    reason as 22. The evidence (outside the repo, `../evidence/`) is not copied in, because it
    holds the reporter's activity. Quotes in `FINDINGS.md` are line shapes, with transcript and
    response ids omitted. The brief pointed to `evidence/README.md`; the file is
    `../evidence/evidence-README.md`.

36. **No device reproduction.** D rests on the field log (28 of 29 short replies) and on the
    documented AudioTrack start threshold. `StreamDrainTest` pins the decision against the
    reporter's own turn (91,665 samples, head stuck at 0) and the 0.9.1 loop's 120 s wait. That
    `setStartThresholdInFrames` takes on their device is checked by `OPEN.md` ask 1, not here.
