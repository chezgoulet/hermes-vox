package com.hermesvox

/**
 * Discovery — when the app should teach, and how little it should say (#284).
 *
 * Three mechanisms, one system: the being suggests in context (here: the state line's words
 * when a caller is stuck), the interface reveals (a failed turn's line offers the command that
 * fixes it; the commands screen leads with what fits now — [CommandCatalog.suggest]), and the
 * search teaches ([CommandCatalog.search]). The metric is help, not usage: each prompt is
 * rare, specific to a moment, and stops once the user has shown they do not need it.
 *
 * Pure JVM, so the timing rules are proven off-device.
 */
object Discovery {

    /** A call this long with nothing said reads as "I don't know what to say". */
    const val STUCK_AFTER_MS = 20_000L
    /** The first few calls only: after that the caller has learned the line is open. */
    const val STUCK_HINT_CALLS = 3
    const val PREF_CALLS = "discovery_calls_placed"

    const val STUCK_HINT = "Ask anything — or say \"what can you do?\""
    const val RECONNECT_OFFER = "tap to reconnect"

    /** Show the stuck hint now? Once per call, on the first [STUCK_HINT_CALLS] calls, after
     *  [STUCK_AFTER_MS] of a live call in which the caller has said nothing. */
    fun stuckHint(callAgeMs: Long, turnsThisCall: Long, callsPlaced: Int, shownThisCall: Boolean): Boolean =
        !shownThisCall && callsPlaced <= STUCK_HINT_CALLS && turnsThisCall == 0L && callAgeMs >= STUCK_AFTER_MS

    /** A failed turn's line, with the command that usually fixes it offered in place. An
     *  interruption is not a failure and offers nothing. */
    fun errorLine(msg: String): Pair<String, Boolean> =
        if (msg.contains("interrupt")) "You interrupted" to false
        else "$msg · $RECONNECT_OFFER" to true
}
