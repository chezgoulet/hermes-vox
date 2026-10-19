package com.hermesvox

/**
 * StreamTurnTerminal — the ONE terminal state of a streamed turn (#6).
 *
 * Before this, a stream that failed part-way (a chunk/poll error, a dropped
 * connection, a non-200 mid-stream, the poll-error cap, the wall-clock timeout)
 * discarded every assembled delta: the worker surfaced an error and the user
 * heard nothing, even though the entity had already written most of an answer.
 * The fix is a single terminal state for the turn instead of the old scatter of
 * booleans (`done`, `genCancelled`, `pollErrors`): the first transition out of
 * [StreamPhase.RUNNING] wins, and every later event — a late chunk, a second
 * failure, a completion after the fallback — is a no-op.
 *
 * [fail] is the ONLY path that may fall back. It delivers iff text was
 * assembled, which is why an empty reply stays an error. [cancel] is the
 * user-initiated barge/hush/stop path and is terminal without speaking. The
 * caller delivers a [StreamAction.Fallback] through the SAME settle path a
 * completed reply uses (TTS + transcript + turn settlement), so the text is
 * spoken exactly once.
 *
 * Pure JVM: no Android deps, unit-proven off-device.
 */
internal enum class StreamPhase { RUNNING, COMPLETED, FALLBACK, FAILED, CANCELLED }

/** What the caller should do as a result of a terminal transition. */
internal sealed class StreamAction {
    /** The turn was already terminal, or a cancel: nothing to do. */
    object None : StreamAction()

    /** The stream completed normally; [text] is the reply to settle. */
    data class Completed(val text: String) : StreamAction()

    /** The stream failed after assembling [text]: deliver it once, with [reason]. */
    data class Fallback(val text: String, val reason: String) : StreamAction() {
        val chars: Int get() = text.length
    }

    /** The stream failed with nothing assembled: a real error, never speech. */
    data class Failure(val reason: String) : StreamAction()
}

internal class StreamTurnTerminal {

    @Volatile private var phase: StreamPhase = StreamPhase.RUNNING
    private val sb = StringBuilder()

    /** The current phase — observability for tests and logs, never a delivery gate. */
    val state: StreamPhase get() = phase

    /** Chars assembled so far. */
    @Synchronized fun chars(): Int = sb.length

    /** The assembled assistant text. */
    @Synchronized fun text(): String = sb.toString()

    /**
     * Replace the assembled text with the gateway's authoritative poll snapshot
     * (the SSE stream's `text` field). Ignored once the turn is terminal, so a
     * late payload can never rewrite the reply being delivered.
     *
     * @return true when the snapshot was accepted
     */
    @Synchronized fun assembled(snapshot: String): Boolean {
        if (phase != StreamPhase.RUNNING) return false
        sb.setLength(0)
        sb.append(snapshot)
        return true
    }

    /**
     * One assistant delta off the wire. Returns false (and appends nothing) when
     * the turn has already reached a terminal state — the late-chunk guard.
     */
    @Synchronized fun delta(chunk: String): Boolean {
        if (phase != StreamPhase.RUNNING) return false
        sb.append(chunk)
        return true
    }

    /** The stream completed normally (done=true, no error). */
    @Synchronized fun complete(): StreamAction {
        if (phase != StreamPhase.RUNNING) return StreamAction.None
        phase = StreamPhase.COMPLETED
        return StreamAction.Completed(sb.toString().trim())
    }

    /**
     * The stream failed for [reason] (a poll/chunk error, a dropped connection, a
     * non-200 mid-stream, a timeout, or the gateway's own terminal error).
     *
     * With ANY assembled text this is a [StreamAction.Fallback] — the caller
     * delivers it exactly once through the normal settle path. With nothing
     * assembled it stays a [StreamAction.Failure] (an empty reply is still an
     * error). A failure after the turn is terminal is a no-op.
     */
    @Synchronized fun fail(reason: String): StreamAction {
        if (phase != StreamPhase.RUNNING) return StreamAction.None
        val reply = sb.toString().trim()
        if (reply.isEmpty()) {
            phase = StreamPhase.FAILED
            return StreamAction.Failure(reason)
        }
        phase = StreamPhase.FALLBACK
        return StreamAction.Fallback(reply, reason)
    }

    /**
     * The user cancelled the turn (barge-in / hush / hangup / stop). Terminal and
     * silent: a cancel NEVER starts speaking, and it beats any later completion
     * or failure event.
     */
    @Synchronized fun cancel(): StreamAction {
        if (phase != StreamPhase.RUNNING) return StreamAction.None
        phase = StreamPhase.CANCELLED
        return StreamAction.None
    }
}
