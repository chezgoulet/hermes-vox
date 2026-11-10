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

13. **No version bump, no push, no merge.** These are the brief's constraints. I did not touch
    `skills/hermes-vox-app/SKILL.md`: it makes no claim about `stream-synth`, the settle rule,
    or `noteFailure`, and the gate's 440 skill claims stayed green.

14. **No device reproduction was attempted.** The brief establishes that a spoken turn cannot be
    produced headlessly on this host. Both links are instead proven against the real
    `TtsEngineHost` and `ReplySettleRule` in JVM tests. The test that shows the 0.9.1 cascade
    (`before_538_…`) uses the *old* reading of chunk results, so it documents the defect and
    does not test dead code.
