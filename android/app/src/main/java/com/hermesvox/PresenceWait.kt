package com.hermesvox

/**
 * PresenceWait — what a call says while it waits for Presence's model (#191).
 *
 * The field report: "hangs on initializing, then the process dies." On a Pixel 7 (8 GB) with
 * another app keeping a 2.5 GB model resident, the Presence warm-up sat on one word —
 * "Initializing…" — for ~50 s while Gemma loaded, MemAvailable fell to 0 and the system's
 * low-memory killer cleared ~30 other apps to make room (2026-10-22). It was a slow load
 * under memory starvation, not a deadlock — but a caller cannot tell those apart from a word.
 *
 * The gate still waits for Presence before opening the line (0.8/M3: Gemma's warm-up renders
 * must not run over the first turn). What changes is what the wait says, and that it has a
 * way out: the line names Presence and counts, and after [OFFER_SKIP_MS] it offers to start
 * the call without Presence. Skipping is for this call only; Presence joins when it loads.
 *
 * Pure JVM, so the wording and timing are proven off-device.
 */
object PresenceWait {

    /** After this long waiting on Presence alone, the caller is offered a way out. */
    const val OFFER_SKIP_MS = 20_000L

    const val SKIP_HINT = "tap to start without it"

    /** The status line while warming. [voiceReady]: STT, TTS and VAD are loaded, so only
     *  Presence is pending. Null = the ordinary warming pill (the voice itself is loading). */
    fun line(voiceReady: Boolean, waitingOnPresence: Boolean, waitedMs: Long): String? {
        if (!voiceReady || !waitingOnPresence) return null
        val s = waitedMs / 1000
        val base = if (s < 3) "Loading Presence…" else "Loading Presence · $s s"
        return if (waitedMs >= OFFER_SKIP_MS) "$base · $SKIP_HINT" else base
    }

    /** May the caller skip Presence now? Only once the offer is on the line. */
    fun canSkip(voiceReady: Boolean, waitingOnPresence: Boolean, waitedMs: Long): Boolean =
        voiceReady && waitingOnPresence && waitedMs >= OFFER_SKIP_MS
}
