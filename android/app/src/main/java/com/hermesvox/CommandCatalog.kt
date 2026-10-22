package com.hermesvox

/**
 * CommandCatalog — the commands the app offers, grouped by what they do (#226).
 *
 * The stock list said `/compress` and nothing else; a person had to know the command to use
 * it. Each entry here states its effect in plain language, belongs to a group named for what
 * it does, and declares a [Category] — the closed vocabulary the being animates a command
 * from (#227), so a new command inherits its motion by saying what kind of thing it does.
 *
 * Pure JVM, so the catalogue's copy and its rules are proven off-device.
 */
object CommandCatalog {

    enum class Group(val label: String) {
        CONVERSATION("Conversation"),
        CONNECTION("Connection"),
        AGENT("The agent"),
        HELP("Help"),
    }

    /** What a command does to the world, in the being's terms (#227). */
    enum class Category { CLEAR, CONDENSE, CONNECT, INSPECT, CHOOSE, INFORM }

    /** [INLINE] runs in place and reports running → done / failed on its row; [CARD] opens
     *  its own view (a chooser, a status card) and closes the sheet. */
    enum class Kind { INLINE, CARD }

    data class Command(
        val name: String,
        val aliases: List<String>,
        val group: Group,
        val effect: String,
        val category: Category,
        val kind: Kind,
        /** #284: the words a person uses for what they want, not for the command. */
        val keywords: List<String> = emptyList(),
    )

    val ALL: List<Command> = listOf(
        Command("/new", emptyList(), Group.CONVERSATION,
            "Start a fresh conversation; the agent forgets this one's thread", Category.CLEAR, Kind.INLINE,
            listOf("start over", "fresh", "new topic", "forget", "begin again")),
        Command("/clear", listOf("/reset"), Group.CONVERSATION,
            "Clear the screen and start over; the agent's copy is kept", Category.CLEAR, Kind.INLINE,
            listOf("wipe", "clean", "erase", "screen")),
        Command(CompressCommand.NAME, listOf(CompressCommand.ALIAS), Group.CONVERSATION,
            "Ask the agent to compact a long conversation so it stays quick", Category.CONDENSE, Kind.INLINE,
            listOf("slow", "too long", "summarize", "summarise", "shorten", "memory full", "context")),
        Command("/reconnect", emptyList(), Group.CONNECTION,
            "Reach the gateway again and re-dial a live call", Category.CONNECT, Kind.INLINE,
            listOf("offline", "not working", "can't reach", "cannot reach", "disconnected", "retry", "stuck", "frozen")),
        Command("/health", listOf("/status"), Group.CONNECTION,
            "Show whether the agent is up, and its version", Category.INSPECT, Kind.CARD,
            listOf("is it working", "server", "gateway", "up", "down", "version", "status")),
        Command("/models", emptyList(), Group.AGENT,
            "Choose which model the agent thinks with", Category.CHOOSE, Kind.CARD,
            listOf("smarter", "faster", "cheaper", "switch", "brain", "llm", "model")),
        Command("/help", emptyList(), Group.HELP,
            "What each command does", Category.INFORM, Kind.CARD,
            listOf("how", "what can i", "list", "help")),
    )

    /** The command a typed or spoken name means, by its name or an alias. */
    fun find(name: String): Command? {
        val n = name.trim().lowercase()
        return ALL.firstOrNull { it.name == n || n in it.aliases }
    }

    /** The catalogue in screen order: groups in [Group] order, commands in catalogue order. */
    fun grouped(): List<Pair<Group, List<Command>>> =
        Group.values().map { g -> g to ALL.filter { it.group == g } }.filter { it.second.isNotEmpty() }

    /** A command's state on its row (#226): never a toast. */
    enum class RunState(val label: String) { IDLE(""), QUEUED("queued"), RUNNING("running…"), DONE("done"), FAILED("failed") }

    // ---- #284: find a command by what you want to do -------------------------------------

    /**
     * The commands that match [query], best first. Matched against what a person would say —
     * the effect line and each command's keywords — as well as its name, so "it's slow" finds
     * /compress and "can't reach it" finds /reconnect. A blank query is the whole catalogue.
     */
    fun search(query: String): List<Command> {
        val q = query.trim().lowercase().removePrefix("/")
        if (q.isEmpty()) return ALL
        val words = q.split(Regex("[^a-z0-9']+")).filter { it.length >= 2 }
        fun score(c: Command): Int {
            var s = 0
            val names = (listOf(c.name) + c.aliases).map { it.removePrefix("/") }
            if (names.any { it.startsWith(q) }) s += 10
            for (k in c.keywords) if (q.contains(k) || k.contains(q)) s += 6
            val effect = c.effect.lowercase()
            for (w in words) {
                if (names.any { it.startsWith(w) }) s += 4
                if (c.keywords.any { k -> k.split(' ').any { it.startsWith(w) } }) s += 2
                if (Regex("\\b" + Regex.escape(w)).containsMatchIn(effect)) s += 1
            }
            return s
        }
        return ALL.map { it to score(it) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
    }

    /** What the app knows about the moment, for [suggest]. */
    data class Situation(val connected: Boolean, val lastTurnFailed: Boolean, val sessionTurns: Long)

    /** A conversation this long is worth offering a compaction (a heuristic, not a measure:
     *  retire or tune it if the field says the offer comes too early or too late). */
    const val COMPRESS_AFTER_TURNS = 16L

    /** The commands that fit now, shown first on the screen — the interface revealing what
     *  became relevant (#284). Never more than two, and nothing when nothing fits. */
    fun suggest(s: Situation): List<Command> = when {
        !s.connected || s.lastTurnFailed -> listOfNotNull(find("/reconnect"), find("/health"))
        s.sessionTurns >= COMPRESS_AFTER_TURNS -> listOfNotNull(find(CompressCommand.NAME))
        else -> emptyList()
    }

    /** The /help card, written from the catalogue so the two cannot disagree. */
    fun helpText(): String = grouped().joinToString("\n\n") { (g, cmds) ->
        g.label + "\n" + cmds.joinToString("\n") { c ->
            (listOf(c.name) + c.aliases).joinToString(" ") + " — " + c.effect
        }
    }
}
