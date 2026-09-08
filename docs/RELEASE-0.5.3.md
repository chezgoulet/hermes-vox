# Hermes Vox 0.5.3 — the reliability batch (part 1)

Built by qwen3.8-max (agentic build on the Thelio) from the Opus review sprint map.
These are the FOUR highest-priority findings from the adversarial code review
(2026-09-07) — the ones that fix real, user-visible reliability problems.

## H1 — Long replies destroyed by a poll-iteration cap (the big one)
The streaming loop was `while (!done && tries < 600)` — but under active streaming
`WaitStream` returns immediately per buffered batch, so 600 became an *event
counter* not a *clock*. A ~600-token reply (or a reply with several tool-call
events) blew the cap and threw `hermes: timeout`, **discarding a reply the gateway
already generated**. The faster the provider streamed, the sooner the reply was
destroyed. This is very likely the cause of the short/terse replies seen in field
logs. Fixed with a wall-clock deadline (120s) instead of an iteration cap.
+ `StreamPollGate` helper + `StreamPollGateTest`.

## H3 — The teardown-crash guard was written but never wired up
The 0.5.0.1 crash fix authored `execSubmit` (re-checks stopped/shutdown + catches
RejectedExecutionException) but it had **zero call sites** — all four submits used
raw `exec.execute`, and two sites (:349 partial-STT, :890 streaming-TTS from the
main thread) had **no guard at all**. The crash class 0.5.0.1 was meant to prevent
was still open on those two sites. Now all four route through the guard, and the
manual pre-checks are redundant.
+ `ExecGuard` helper + `ExecGuardTest`.

## H2 — /compress ran on a different response chain and orphaned the conversation
`HermesSession` kept `lastID` for streaming, but `Conversation` kept a *separate*
`lastResponseID` for `turnStored`. On a multi-turn voice call, `/compress` sent into
a **fresh empty conversation** (no `previous_response_id`) and then clobbered the
streaming id — **silently orphaning the entire conversation**, the exact opposite of
its purpose. Now `TurnStored` uses the session-level id directly; no second source
of truth.
+ `session_test.go` + `conversation_test.go` regression tests.

## M1 — Every barge-in leaked a streamState in the Go map, forever
`CancelStream` cancelled the context but never deleted the `streams` map entry —
and on barge-in the Kotlin worker broke out before the drain-poll, so the entry
(holding the accumulated text, buffered events, and a chan) stayed in the map for
the life of the process. A long interrupt-heavy session accumulated unbounded
native memory. Now `CancelStream` owns the removal.
+ `stream_test.go` regression test.

## KEEP-list — verified
No motion-state/visual/barge/escape/reveal/crash-guard regressions. Each fix is a
contained change with its own regression test. The Opus review's "claims checked
and did NOT hold up" list (render loop zero-alloc, reveal-freeze boundary, barge
gate, teardown drain ordering) remains intact.

## What is NOT in this release (the rest of the review findings — still open)
The review surfaced 10 findings; this release lands the 4 highest. The remaining 6
(M2 conn-test probe generates a real turn, M3 ModelDownloader cleanup no-op,
M4 RealtimeActivity plaintext-key dead code, M5 sprite-cache full-clear, and the
L1-L3 lows) are tracked and NOT yet built. M2 was partially attempted
(`ConnProbe.kt`, incomplete) and is not included. These are queued for a follow-up
batch.

## Notes
- Version 0.5.3 (versionCode 92). qwen3.8-max built from the sprint map, improving
  where it judged a safer/better implementation — each fix includes a regression
  test (behavior-contract, per the repo's AGENTS.md).
- The 0.6 release gates are tracked as GitHub issues #91-100 (LICENSE, Play
  privacy/data-safety, repo discovery optimization, CONTRIBUTING/SECURITY, fresh
  screenshots, README rewrite, AAB build, icon wiring, GitHub Pages site, ER-alpha
  labeling).
