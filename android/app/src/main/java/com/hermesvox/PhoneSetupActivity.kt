package com.hermesvox

/**
 * PhoneSetupActivity — the work the setup card shows, in the owner's words, and the age of the
 * last event.
 *
 * **Why the card shows work.** The first version of the one-button setup showed one line —
 * *waiting for your agent* — for as long as the run lasted, and stopped the run at the ordinary
 * two-minute turn ceiling. The agent was still working; the owner was told nothing, then told
 * nothing again when the ceiling fired. A card that shows the agent's tool calls as they land is
 * the difference between "something is happening" and a frozen screen.
 *
 * **Never the arguments.** The setup instruction carries this phone's token, and a tool call made
 * to act on that instruction can carry the same bytes in its arguments. So a line is built from
 * the tool's *name* and this file's own phrase for it, and from nothing else: there is no field
 * here for an argument, a preview or an output, so no caller can leak one into the screen. The
 * token is scrubbed again on the way into the record ([PhoneSetupSend.redact]) and never logged.
 *
 * **Never an invented action.** [line] is called only because a tool event arrived, and it takes
 * the tool name from that event. A tool this file has no phrase for is drawn as its own name —
 * the app's vocabulary is allowed to be less fluent than the agent's, but it may not guess.
 *
 * **Appended, never replaced.** [Line] is history: every event adds one, in arrival order, and the
 * card keeps the whole list after the attempt ends. Only the silence line is about "now".
 *
 * **Silence has an age, not a deadline.** [silence] states how long since the last event; it
 * never counts down to a stop, because this turn has no stop on any clock. The only reasons to
 * stop a run are the owner cancelling and this sheet going away.
 *
 * Pure JVM: [PhoneSetupActivityTest] proves the ordering, the unknown tool, the token rule and the
 * age without a device.
 */
object PhoneSetupActivity {

    /** One tool event, as the app's route-blind stream already names it. */
    data class Tool(val name: String, val callId: String)

    /**
     * One rendered line: the tool's own name, this file's phrase for it (empty when there is
     * none), and when the event arrived. There is deliberately no field for arguments, a preview
     * or a result — see the class comment.
     */
    data class Line(val tool: String, val phrase: String, val at: Long)

    /** The smallest silence that is worth a line: under a minute is just the agent thinking. */
    const val SILENCE_AFTER_MS = 60_000L

    /**
     * Our phrase for the tools whose work we can describe plainly. A tool that is not here is
     * still shown — by its own name — so a new tool on the gateway needs no release to appear.
     */
    private val PHRASES = mapOf(
        "terminal" to "ran a command",
        "read_file" to "read a file",
        "write_file" to "wrote a file",
        "patch" to "edited a file",
        "search_files" to "searched the files",
        "web_search" to "searched the web",
        "web_extract" to "read a web page",
        "web_fetch" to "fetched a web page",
        "todo" to "updated the plan",
        "memory" to "checked its memory",
    )

    /** The phrase for [tool], or "" when this file has not written one. */
    fun phraseOf(tool: String): String = PHRASES[tool.trim().lowercase()].orEmpty()

    /**
     * A line for one tool event, or null when the event named no tool. The tool name is kept
     * exactly as the gateway sent it; only the phrase lookup is case-insensitive.
     */
    fun line(tool: String, at: Long): Line? {
        val name = tool.trim()
        if (name.isEmpty()) return null
        return Line(tool = name, phrase = phraseOf(name), at = at)
    }

    /** The rendered line: `terminal · ran a command`, or just `mystery_tool` when unknown. */
    fun render(line: Line): String =
        if (line.phrase.isBlank()) line.tool else "${line.tool} · ${line.phrase}"

    /** The whole history, in arrival order. */
    fun renderAll(lines: List<Line>): List<String> = lines.map { render(it) }

    /**
     * How long since the last event, once that is worth saying: `Nothing new for 4 minutes.` or
     * empty. It is a fact about the *last* event, never a countdown and never a warning that
     * something will stop.
     */
    fun silence(lastEventAt: Long, now: Long): String {
        if (lastEventAt <= 0L) return ""
        val age = (now - lastEventAt).coerceAtLeast(0L)
        if (age < SILENCE_AFTER_MS) return ""
        val minutes = age / 60_000L
        val unit = if (minutes == 1L) "minute" else "minutes"
        return "Nothing new for $minutes $unit."
    }
}
