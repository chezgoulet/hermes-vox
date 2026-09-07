# Hermes Vox 0.5.0.1 — crash guard

Field log 2026-09-07 (0.5.0 multi-turn test) surfaced a real app crash, separate from the
upstream agentic-turn issue. `java.util.concurrent.RejectedExecutionException` at
`VoiceController.runStreamedTurn` — a turn-start submitted to the executor *after* the
controller's terminal `stop()` had already called `exec.shutdown()`. The controller is
documented terminal-after-stop ("reuse-after-stop is forbidden"), but a turn-start racing
the teardown still threw instead of bailing.

## Fix
- A `@Volatile stopped` flag, set in `stop()` **before** `exec.shutdown()`.
- `if (stopped || exec.isShutdown) { … }` bail-before-submit at the turn-start site AND at
  the `listenOffline` executor submit — the whole bug class, not just the one site that
  crashed.
- On the drop path, `turnInFlight` is reset and the gate released so the loop doesn't wedge
  (an un-released gate would hang the next utterance). Verified by re-running the field
  sequence: a turn-start after `call-end` now logs `event=turn-dropped reason=stopped`
  instead of crashing.
- Belt-and-suspenders: an `execSubmit` helper catches `RejectedExecutionException` for the
  narrow race where shutdown lands *between* the `isShutdown()` check and `execute()`.

## Verified
- Independent gate on the merged tip (fresh clone, real SDK, purge + `--rerun-tasks`):
  exit 0, 17 test classes.
- KEEP-list zero-touch (only executor submits + the one `stopped`/bail block added).

## Not in this release (tracked separately)
The silent-turn fail-soft cue ("the entity is working…" when the model emits no token for
N seconds) was scoped in the hardening plan but is a behavioral addition, not a crash fix —
it's deferred to the next item so it gets its own careful review rather than riding on a
crash fix. The upstream agentic-turn issue is a gateway-side voice-turn-discipline change
(the `voice: true` request signal + voice prefix), designed and pending.

## Device test
Have a call, hang up (tap ✆), then immediately start another — no crash, no wedged gate,
the next utterance is accepted (log shows `event=turn-dropped reason=stopped` on the
teardown racing turn, then a clean new turn).
