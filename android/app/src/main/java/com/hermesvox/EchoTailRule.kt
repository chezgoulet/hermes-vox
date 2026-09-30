package com.hermesvox

/**
 * EchoTailRule — reopening the mic after a reply without going deaf, pure JVM.
 *
 * After every turn the capture loop used to stop the recorder and sleep 450 ms before
 * listening again, so the tail of the reply's own audio (the room, the AEC's settling) could
 * not trip the VAD and come back as a phantom turn of the agent's last word. The cost was a
 * footgun: a caller who answered straight away lost their first syllables — the loop was deaf
 * exactly when a quick conversation needs it most.
 *
 * Now the recorder keeps running and the first segment after a reply is captured from its
 * first frame. Echo is judged AFTER the fact instead: a segment whose speech is short AND ended
 * inside the guard window is the reply's tail, not the caller. A person who starts talking
 * immediately speaks well past the window; an echo blip does not.
 */
object EchoTailRule {
    /** How long after the reply's audio ends its tail is plausible (the old cooldown). */
    const val GUARD_MS = 450L
    /** Speech that ends this soon after the guard still counts as inside it (VAD hangover). */
    const val MARGIN_MS = 150L
    /** Longer than this is a person, whatever its timing. */
    const val MAX_ECHO_SPEECH_MS = 700L

    /** True when a segment is the reply's echo tail and must not become a turn. */
    fun isEchoTail(speechEndAtMs: Long, speechMs: Long, guardUntilMs: Long): Boolean =
        guardUntilMs > 0L && speechMs < MAX_ECHO_SPEECH_MS && speechEndAtMs <= guardUntilMs + MARGIN_MS
}
