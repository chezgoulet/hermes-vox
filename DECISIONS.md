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
