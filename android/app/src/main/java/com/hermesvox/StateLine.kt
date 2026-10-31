package com.hermesvox

/**
 * StateLine — the one line's rules, kept off Android (#281, STATE-AUDIT F4/F8).
 *
 * The line is a property of the **state**, not of the call. A step that is running, or a
 * failure held until the next turn, is shown wherever it happens — typed use, after the
 * turn, a pocketed call. The ambient words (`Listening…`, `The entity is working…`) keep
 * their older rule: warming/thinking, or any live call.
 *
 * The being and the cues are deliberately not inputs. The being cannot carry *what*
 * (STATE-AUDIT F4), so the line is the only carrier of which tool and what it touches;
 * turning the being or the sound off must not change a word. Light mode changes the
 * line's colour, never its text — the colours themselves are checked against the
 * shipped resources by `StateLineTest`.
 *
 * Pure JVM, so the rules are proven off-device.
 */
object StateLine {

    /** What the line says: the running step, else a held failure, else the turn's own words. */
    fun words(running: String?, heldFailure: String?, ambient: String): String =
        running ?: heldFailure ?: ambient

    /** Whether the line is on screen. A running step or a held failure is a state of its
     *  own; the ambient words need warming/thinking or a live call, as before. */
    fun visible(running: Boolean, heldFailure: Boolean, state: String, callLive: Boolean): Boolean =
        running || heldFailure || state == "warming" || state == "thinking" || callLive
}
