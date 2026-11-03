package com.hermesvox.core

import com.hermesvox.StreamPollGate

/**
 * The clock for ONE streamed turn.
 *
 * The 120 s ceiling in [StreamPollGate] is right: a genuinely wedged turn must not
 * hold the phone open. The bug it had was counting the wrong thing — it started at
 * turn submit and never stopped, so a human reading an approval card for two minutes
 * spent the agent's budget. The card then vanished underneath them while the run was
 * still parked in `waiting_for_approval`.
 *
 * This budget measures AGENT time, not wall time:
 *
 *   elapsed = wall clock since submit - time an approval was pending
 *
 * Pausing is idempotent: the poll loop may call [approvalPending] every tick, and
 * only the first `true` starts the pause, only the first `false` ends it. The paused
 * time ACCUMULATES across several approvals; it never restarts the clock, because a
 * long turn with two questions is still the same turn.
 *
 * Pure JVM: the controller passes `SystemClock.uptimeMillis`; tests pass a fake clock.
 */
internal class TurnBudget(
    private val limitMs: Long = StreamPollGate.STREAM_TURN_TIMEOUT_MS,
    private val now: () -> Long,
) {
    private val startedAt = now()

    /** Wall time at which the current pause began, or [NOT_PAUSED]. */
    private var pausedAt = NOT_PAUSED

    /** Every completed pause in this turn. */
    private var pausedTotal = 0L

    val isPaused: Boolean get() = pausedAt != NOT_PAUSED

    /**
     * Tell the budget whether an approval is waiting on the human. Called on every
     * poll: `true` while the card is up, `false` once it is answered/timed out.
     * Calling it repeatedly with the same value never moves the clock.
     */
    fun approvalPending(pending: Boolean, atMs: Long = now()) {
        if (pending) pause(atMs) else resume(atMs)
    }

    fun pause(atMs: Long = now()) {
        if (pausedAt == NOT_PAUSED) pausedAt = atMs
    }

    fun resume(atMs: Long = now()) {
        if (pausedAt == NOT_PAUSED) return
        pausedTotal += (atMs - pausedAt).coerceAtLeast(0L)
        pausedAt = NOT_PAUSED
    }

    /** Agent time used: wall clock minus every approval pause, including the current one. */
    fun elapsedMs(atMs: Long = now()): Long {
        val wall = (atMs - startedAt).coerceAtLeast(0L)
        return (wall - pausedTotal - currentPauseMs(atMs)).coerceAtLeast(0L)
    }

    /** Approval time accumulated so far (completed pauses + the current one). */
    fun pausedMs(atMs: Long = now()): Long = pausedTotal + currentPauseMs(atMs)

    /**
     * The wall-clock instant this turn's agent time would reach [limitMs]. While an
     * approval is pending the instant keeps moving with the human, rather than being
     * fixed when the turn started.
     */
    fun deadlineMs(atMs: Long = now()): Long = startedAt + limitMs + pausedTotal + currentPauseMs(atMs)

    /** True once the agent's budget is spent. A pending approval never expires. */
    fun expired(atMs: Long = now()): Boolean =
        !isPaused && !StreamPollGate.keepPolling(done = false, nowMs = atMs, deadlineMs = deadlineMs(atMs))

    /** The poll-loop exit: completed, or the agent's budget is spent. */
    fun keepPolling(done: Boolean, atMs: Long = now()): Boolean = !done && !expired(atMs)

    private fun currentPauseMs(atMs: Long): Long =
        if (pausedAt == NOT_PAUSED) 0L else (atMs - pausedAt).coerceAtLeast(0L)

    private companion object {
        const val NOT_PAUSED = -1L
    }
}

/**
 * Whether a turn's ending must WITHDRAW its approval card.
 *
 * A request is answerable for as long as its run is alive. A turn that completes,
 * times out (and therefore abandons/stops the run), or is cancelled owns a dead run;
 * a turn whose stream was merely lost may not. In that last case the request is held
 * past the loop so the card survives a reattach, and the controller re-reads the run
 * when it can. Pure so the rule is pinned off-device.
 */
internal object TurnApprovalHold {

    /** The run ids whose cards must survive this loop exit (empty = withdraw all). */
    fun keep(
        done: Boolean,
        abandoned: Boolean,
        cancelled: Boolean,
        pending: List<ApprovalRequest>,
    ): Set<String> {
        if (done || abandoned || cancelled || pending.isEmpty()) return emptySet()
        return pending.mapNotNull { it.runId.takeIf { runId -> runId.isNotEmpty() } }.toSet()
    }
}
