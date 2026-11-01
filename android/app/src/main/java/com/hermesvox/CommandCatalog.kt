package com.hermesvox

/**
 * CommandCatalog — the commands the app offers, grouped by what they do (#226).
 *
 * The stock list said `/compress` and nothing else; a person had to know the command to use
 * it. Each entry here states its effect in plain language, names the group a person would
 * look under (conversation, connection, session, diagnostics — never the subsystem that does
 * the work), states its cost or risk when it has one, and declares a [Category] — the closed
 * vocabulary the being animates a command from (#227), so a new command inherits its motion
 * by saying what kind of thing it does.
 *
 * It also says how a command can report itself. A command that runs in place reports
 * queued / running / done / failed on its own row; one that opens a card says so, because
 * its progress is in the card and the row must not pretend (no spinner, no invented
 * percentage — the same rule as the operations line, #281).
 *
 * Pure JVM, so the catalogue's copy and its rules are proven off-device.
 */
object CommandCatalog {

    /**
     * What the command is *for*, in the words a person uses — the four groups the screen
     * shows, in screen order. The label is what the header prints, so a command cannot
     * appear without one.
     */
    enum class Group(val label: String) {
        CONVERSATION("Conversation"),
        CONNECTION("Connection"),
        SESSION("Session"),
        DIAGNOSTICS("Diagnostics"),
    }

    /** What a command does to the world, in the being's terms (#227). */
    enum class Category { CLEAR, CONDENSE, CONNECT, INSPECT, CHOOSE, INFORM }

    /** [INLINE] runs in place and reports running → done / failed on its row; [CARD] opens
     *  its own view (a chooser, a status card) and closes the sheet. */
    enum class Kind { INLINE, CARD }

    /** What a command costs — and whether it can be taken back. */
    data class Cost(val text: String, val risk: Boolean)

    data class Command(
        val name: String,
        val aliases: List<String>,
        val group: Group,
        /** What it does, in one line. */
        val effect: String,
        /** What it spends or risks, in one line — null when it only asks and changes
         *  nothing. [Cost.risk] is true only when something is lost and cannot be brought
         *  back, so the row's warning colour means "irreversible", not "unpleasant". */
        val cost: Cost?,
        val category: Category,
        val kind: Kind,
        /** #284: the words a person uses for what they want, not for the command. */
        val keywords: List<String> = emptyList(),
    )

    val ALL: List<Command> = listOf(
        Command("/new", emptyList(), Group.SESSION,
            effect = "Start fresh — the agent forgets this thread",
            cost = Cost("Cannot be undone; a live call is re-dialled", risk = true),
            category = Category.CLEAR, kind = Kind.INLINE,
            listOf("start over", "fresh", "new topic", "forget", "begin again")),
        Command("/clear", listOf("/reset"), Group.CONVERSATION,
            effect = "Clear the screen; the agent keeps its copy",
            cost = Cost("The screen's copy cannot be brought back", risk = true),
            category = Category.CLEAR, kind = Kind.INLINE,
            listOf("wipe", "clean", "erase", "screen")),
        Command(CompressCommand.NAME, listOf(CompressCommand.ALIAS), Group.CONVERSATION,
            effect = "Compact a long conversation so it stays quick",
            cost = Cost("Costs one turn; the agent keeps the summary", risk = false),
            category = Category.CONDENSE, kind = Kind.INLINE,
            listOf("slow", "too long", "summarize", "summarise", "shorten", "memory full", "context")),
        Command("/reconnect", emptyList(), Group.CONNECTION,
            effect = "Reach the gateway again and re-dial a call",
            cost = Cost("Stays down if the gateway does not answer", risk = false),
            category = Category.CONNECT, kind = Kind.INLINE,
            listOf("offline", "not working", "can't reach", "cannot reach", "disconnected", "retry", "stuck", "frozen")),
        Command("/health", listOf("/status"), Group.DIAGNOSTICS,
            effect = "Whether the agent is up, and its version",
            cost = null,   // it asks and reports; nothing changes
            category = Category.INSPECT, kind = Kind.CARD,
            listOf("is it working", "server", "gateway", "up", "down", "version", "status")),
        Command("/models", emptyList(), Group.SESSION,
            effect = "Choose which model the agent thinks with",
            cost = Cost("Switching starts a fresh thread", risk = false),
            category = Category.CHOOSE, kind = Kind.CARD,
            listOf("smarter", "faster", "cheaper", "switch", "brain", "llm", "model")),
        Command("/help", emptyList(), Group.DIAGNOSTICS,
            effect = "What each command does",
            cost = null,
            category = Category.INFORM, kind = Kind.CARD,
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

    /** Whether the row can report this command's own progress. A card command's work happens
     *  in the view it opens, so its row says where to look instead of showing a state it
     *  cannot see (#226). */
    fun reportsOnRow(c: Command): Boolean = c.kind == Kind.INLINE

    /** A command's state on its row (#226): never a toast, an endless spinner or a made-up
     *  percentage. */
    enum class RunState(val label: String) {
        IDLE(""),
        QUEUED("queued"),
        RUNNING("running…"),
        DONE("done"),
        FAILED("failed"),
    }

    /** What a row says when the command's progress is not on the row at all: it opens a
     *  card, and the card is where you see what happened. */
    const val IN_CARD = "opens a card"

    /**
     * The words in a row's state slot. A long-running command says "still working…" after
     * [Operations.LONG_MS] — the same move as the operations line (#281): the first change is
     * the indeterminate phrase, never a number that claims progress.
     */
    fun stateText(c: Command, s: RunState, elapsedMs: Long = 0L): String = when {
        !reportsOnRow(c) -> IN_CARD
        s == RunState.RUNNING && elapsedMs >= Operations.LONG_MS -> "still working…"
        else -> s.label
    }

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
