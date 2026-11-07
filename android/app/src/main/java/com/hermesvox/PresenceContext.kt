package com.hermesvox

/**
 * PresenceContext — B1 (#421 step 1): a few lines in the voice turn's `instructions` so the mind
 * knows what the phone is doing.
 *
 * The mind is remote. Without this it answers "how long is left on that timer?" by asking, and
 * "what did you just say?" from nothing: the beat, the phone's own actions and the device state are
 * all real to the caller and invisible to the gateway. The block is deliberately tiny — it rides
 * every voice turn's instructions, so it is paid for on every turn — and it never carries
 * transcript text: the caller's words reach the mind as `input`, once, and are not duplicated here.
 *
 * The shape (one example):
 *
 * ```
 * presence: soul=beat | speaking=false | last_line="On it."
 * local_actions: timer(remaining=3m12s)
 * device: 18:42, battery 62%, online, dnd=off
 * ```
 *
 * [MAX_CHARS] is a hard budget, not a wish: the block is trimmed deterministically (the last line
 * first, then the oldest action) so it can never crowd the register or the soul-sync out of a small
 * model's context.
 *
 * Pure JVM, unit-tested in PresenceContextTest.
 */
object PresenceContext {

    /** The whole block must fit in about this many characters. */
    const val MAX_CHARS = 200

    /** How much of the phone's last spoken line the mind is told about. */
    const val MAX_LAST_LINE = 60

    /** At most this many local actions ride the `local_actions:` line. */
    const val MAX_ACTIONS = 3

    /**
     * @param soul what the presence layer is doing: [soulToken] of the last line it voiced.
     * @param speaking whether audio is coming out of the phone right now.
     * @param lastLine the last line the phone voiced (the soul's, not the mind's), or "".
     * @param actions the local-action registry ([LocalActions.registry]), newest last.
     * @param clock the local time as "HH:mm".
     * @param battery percent 0..100, or null when the platform would not say.
     * @param online whether the phone currently has a validated network.
     * @param dnd whether Do Not Disturb is on.
     */
    fun block(
        soul: String,
        speaking: Boolean,
        lastLine: String,
        actions: List<String>,
        clock: String,
        battery: Int?,
        online: Boolean,
        dnd: Boolean,
    ): String {
        var line = clean(lastLine)
        var acts = actions.map { clean(it) }.filter { it.isNotEmpty() }.takeLast(MAX_ACTIONS)
        var out = render(soul, speaking, line, acts, clock, battery, online, dnd)
        // Deterministic trim: the quote shrinks first (it is the least load-bearing detail), then
        // the actions oldest-first, and the soul/device lines are never dropped.
        while (out.length > MAX_CHARS && line.isNotEmpty()) {
            line = line.dropLast(1)
            out = render(soul, speaking, line, acts, clock, battery, online, dnd)
        }
        while (out.length > MAX_CHARS && acts.isNotEmpty()) {
            acts = acts.dropLast(1)
            out = render(soul, speaking, line, acts, clock, battery, online, dnd)
        }
        return out
    }

    private fun render(
        soul: String,
        speaking: Boolean,
        lastLine: String,
        actions: List<String>,
        clock: String,
        battery: Int?,
        online: Boolean,
        dnd: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("presence: soul=").append(clean(soul).ifEmpty { "quiet" })
        sb.append(" | speaking=").append(speaking)
        sb.append(" | last_line=\"").append(lastLine).append("\"")
        sb.append("\nlocal_actions: ").append(if (actions.isEmpty()) "none" else actions.joinToString(", "))
        sb.append("\ndevice: ").append(clock)
        if (battery != null) sb.append(", battery ").append(battery.coerceIn(0, 100)).append("%")
        sb.append(", ").append(if (online) "online" else "offline")
        sb.append(", dnd=").append(if (dnd) "on" else "off")
        return sb.toString()
    }

    /**
     * The one token that says what the phone's voice is doing, from the glue source that voiced the
     * last line. Never free text: the mind reads it, and a stray quote would break the block.
     */
    fun soulToken(source: String, narrationOn: Boolean): String = when {
        !narrationOn -> "off"
        source.isEmpty() -> "quiet"
        source.contains("beat") -> "beat"
        source.contains("working") -> "working"
        source.contains("greeting") -> "greeting"
        source.startsWith("presence") -> "filler"
        else -> "speaking"
    }

    /** One-line, quote-free, and bounded: the block is instructions, not a log entry. */
    fun clean(text: String): String =
        text.replace(Regex("\\s+"), " ").replace("\"", "'").trim().take(MAX_LAST_LINE)
}
