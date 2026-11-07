package com.hermesvox

/**
 * CommandCatalog — the commands the app offers, grouped by what they do (#226).
 *
 * The stock list said `/compress` and nothing else; a person had to know the command to use
 * it. Each entry here states its effect in plain language, names the group a person would
 * look under (this conversation, the agent — never the subsystem that does the work), states
 * what it loses when it loses something, and declares a [Category] — the closed
 * vocabulary the being animates a command from (#227), so a new command inherits its motion
 * by saying what kind of thing it does.
 *
 * It also says how a command can report itself. A command that runs in place reports
 * queued / running / done / failed on its own row; one that opens a card reports nothing on
 * the row — the card is its report, and the tap itself says where it went. (#226 printed
 * "opens a card" on every such row; the owner's rule is no helper line that says what the
 * control already says.) No spinner, no invented percentage — the same rule as the
 * operations line, #281.
 *
 * Pure JVM, so the catalogue's copy and its rules are proven off-device.
 */
object CommandCatalog {

    /**
     * What the command is *for*, in the words a person uses — the groups the screen shows, in
     * screen order. Two, not four: the commands under four headers left two headers holding
     * one row each, which is a heading per command, not a grouping. The label is what the
     * header prints, so a command cannot appear without one.
     */
    enum class Group(val label: String) {
        CONVERSATION("This conversation"),
        AGENT("The agent"),
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
        /** What it loses, in one line — null when nothing is lost. [Cost.risk] is true only
         *  when something is gone for good, so the row's warning colour means
         *  "irreversible", not "unpleasant". */
        val cost: Cost?,
        val category: Category,
        val kind: Kind,
        /** #284: the words a person uses for what they want, not for the command. */
        val keywords: List<String> = emptyList(),
        /** Whether the sheet lists it. `/help` answers a typed or spoken "/help", but on the
         *  sheet it would be a row that opens a copy of the sheet. */
        val listed: Boolean = true,
        /** The row's own drawing, when its category's still would say the wrong thing (#3).
         *  Null means "the category's still" ([CommandMotion.verbFor]). `/clear` and `/new`
         *  are both CLEAR to the being — one empties the screen, one starts a thread — so
         *  `/new` names [Marks.Mark.NEW_THREAD] and the two rows stop looking identical. */
        val glyph: Marks.Mark? = null,
    )

    /** Every command the catalogue has. Nothing is optional any more — the bug-report command
     *  and its switch are gone: the sheet, /help and a typed word all read this list. */
    fun commands(): List<Command> = ALL

    val ALL: List<Command> = listOf(
        Command(CompressCommand.NAME, listOf(CompressCommand.ALIAS), Group.CONVERSATION,
            effect = "Fold a long thread into a summary",
            cost = Cost("Older detail survives only in the summary", risk = false),
            category = Category.CONDENSE, kind = Kind.INLINE,
            listOf("slow", "too long", "summarize", "summarise", "shorten", "memory full", "context")),
        Command("/clear", listOf("/reset"), Group.CONVERSATION,
            effect = "Clear the screen; the agent keeps the thread",
            cost = Cost("The screen's copy cannot be brought back", risk = true),
            category = Category.CLEAR, kind = Kind.INLINE,
            listOf("wipe", "clean", "erase", "screen")),
        Command("/new", emptyList(), Group.CONVERSATION,
            effect = "Start a fresh thread; the agent forgets this one",
            cost = Cost("This thread cannot be brought back", risk = true),
            category = Category.CLEAR, kind = Kind.INLINE,
            listOf("start over", "fresh", "new topic", "forget", "begin again"),
            glyph = Marks.Mark.NEW_THREAD),
        // #437: the other half of /new — pick up a thread that already exists. It left Alpha
        // with the Sessions set, so it is in the catalogue for every install (#437, D39).
        Command(Sessions.COMMAND, emptyList(), Group.CONVERSATION,
            effect = "Pick up a thread the agent already has",
            cost = null,   // nothing is lost: the session keeps its history, and so does this app
            category = Category.CHOOSE, kind = Kind.CARD,
            listOf("session", "conversation", "thread", "history", "resume", "pick up",
                "earlier", "desktop", "before", "past"),
            // #3: a list of the threads the host already has. /models chose the PULSE nucleus
            // too, and the two rows sat one above the other in the sheet looking identical.
            glyph = Marks.Mark.SESSIONS),
        Command("/models", emptyList(), Group.AGENT,
            effect = "Choose the model the agent thinks with",
            cost = Cost("Switching starts a fresh thread", risk = false),
            category = Category.CHOOSE, kind = Kind.CARD,
            listOf("smarter", "faster", "cheaper", "switch", "brain", "llm", "model")),
        // Profiles left Alpha on 2026-11-06 (#436, D39), so the switcher is a command every
        // install has.
        Command("/profiles", emptyList(), Group.AGENT,
            effect = "Talk to another of this host's agents",
            cost = Cost("Switching starts a fresh conversation", risk = false),
            category = Category.CHOOSE, kind = Kind.CARD,
            listOf("profile", "agent", "another agent", "who am i talking to", "switch agent",
                "connection", "home", "work"),
            // #3: /models chooses and draws PULSE; this row chooses an *agent*, and the two are
            // neighbours on the sheet now that profiles is ungated.
            glyph = Marks.Mark.SWITCH),
        Command("/reconnect", emptyList(), Group.AGENT,
            effect = "Reach the gateway again; a live call re-dials",
            cost = null,   // nothing is lost: the thread and the call both survive
            category = Category.CONNECT, kind = Kind.INLINE,
            listOf("offline", "not working", "can't reach", "cannot reach", "disconnected", "retry", "stuck", "frozen")),
        Command("/health", listOf("/status"), Group.AGENT,
            effect = "Whether the agent is up, and its version",
            cost = null,   // it asks and reports; nothing changes
            category = Category.INSPECT, kind = Kind.CARD,
            listOf("is it working", "server", "gateway", "up", "down", "version", "status")),
        Command("/help", emptyList(), Group.AGENT,
            effect = "What each command does",
            cost = null,
            category = Category.INFORM, kind = Kind.CARD,
            listOf("how", "what can i", "list", "help"),
            listed = false),
    )

    /**
     * #370: the share row. It is deliberately **not** in [ALL]: "Share with agent" is an
     * artifact action, not a slash command, so the catalogue's command invariants (names,
     * groups, aliases, `/help`) stay true. It still uses the same row grammar, the same cost
     * line and one of the six motions ([Category.CHOOSE] → pulse).
     */
    val SHARE: Command = Command(
        name = "Share with agent",
        aliases = emptyList(),
        group = Group.CONVERSATION,
        effect = "The clipboard, a file, or what another app shared",
        cost = null,   // nothing is sent from this row; the share card asks before anything goes
        category = Category.CHOOSE,
        kind = Kind.CARD,
        keywords = listOf("share", "file", "clipboard", "attach", "document", "send file"),
        // #3: the platform's share mark, not the nucleus the CHOOSE motion would give it —
        // this row opens Android's share, and the owners' eyes already know that drawing.
        glyph = Marks.Mark.SHARE,
    )

    /** The command a typed or spoken name means, by its name or an alias. Null for a name no
     *  build answers to, exactly like `/nope`. */
    fun find(name: String): Command? {
        val n = name.trim().lowercase()
        return (commands() + SHARE).firstOrNull { it.name.lowercase() == n || n in it.aliases }
    }

    /** The sheet in screen order: groups in [Group] order, listed commands in catalogue order.
     *  [all] includes the unlisted ones (the /help card is written from every command). */
    fun grouped(all: Boolean = false): List<Pair<Group, List<Command>>> =
        Group.values().map { g -> g to commands().filter { it.group == g && (all || it.listed) } }
            .filter { it.second.isNotEmpty() }

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

    /**
     * The words in a row's state slot. A long-running command says "still working…" after
     * [Operations.LONG_MS] — the same move as the operations line (#281): the first change is
     * the indeterminate phrase, never a number that claims progress.
     */
    fun stateText(c: Command, s: RunState, elapsedMs: Long = 0L): String = when {
        !reportsOnRow(c) -> ""   // the card it opens is the report; the row stays quiet
        s == RunState.RUNNING && elapsedMs >= Operations.LONG_MS -> "still working…"
        else -> s.label
    }

    // ---- #284: find a command by what you want to do -------------------------------------

    /**
     * The commands that match [query], best first. Matched against what a person would say —
     * the effect line and each command's keywords — as well as its name, so "it's slow" finds
     * /compress and "can't reach it" finds /reconnect. A blank query is the whole catalogue.
     *
     * Nothing on a screen searches since #500: the sheet shows its groups. The vocabulary
     * stays because it is the catalogue's own — what a person means, not what a command is
     * called — and this is the single place that reads it.
     */
    fun search(query: String): List<Command> {
        val q = query.trim().lowercase().removePrefix("/")
        if (q.isEmpty()) return commands()
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
        return (commands() + SHARE).map { it to score(it) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
    }

    /** What the app knows about the moment, for [suggest]. */
    data class Situation(val connected: Boolean, val lastTurnFailed: Boolean, val sessionTurns: Long)

    /** A conversation this long is worth offering a compaction (a heuristic, not a measure:
     *  retire or tune it if the field says the offer comes too early or too late). */
    const val COMPRESS_AFTER_TURNS = 16L

    /** How a row tints its verb mark: the warning colour for a command that loses something
     *  for good (the same fact as its amber cost line), the accent otherwise. */
    enum class Tint { ACCENT, RISK }
    fun tint(c: Command): Tint = if (c.cost?.risk == true) Tint.RISK else Tint.ACCENT

    /** What a row says when its command fits the moment (#284/#499) — the row's own state
     *  slot, so the screen marks the row it already has rather than repeating it. */
    const val FITS_NOW = "fits now"

    /** The commands that fit now — the interface revealing what became relevant (#284).
     *  Never more than two, and nothing when nothing fits. The sheet asks [fitsNow] of each
     *  row, so this list marks rows in place: a command has one home ([Group]) and one row. */
    fun suggest(s: Situation): List<Command> = when {
        !s.connected || s.lastTurnFailed -> listOfNotNull(find("/reconnect"), find("/health"))
        s.sessionTurns >= COMPRESS_AFTER_TURNS -> listOfNotNull(find(CompressCommand.NAME))
        else -> emptyList()
    }

    /** Whether one row fits the moment. The sheet marks the row it already has — its state
     *  slot reads [FITS_NOW] — instead of printing the command a second time under a heading
     *  of its own (#499: the screen showed `/reconnect` and `/health` twice). */
    fun fitsNow(c: Command, s: Situation): Boolean =
        suggest(s).any { it.name == c.name }

    /** The /help card, written from the catalogue so the two cannot disagree. */
    fun helpText(): String =
        grouped(all = true).joinToString("\n\n") { (g, cmds) ->
            g.label + "\n" + cmds.joinToString("\n") { c ->
                (listOf(c.name) + c.aliases).joinToString(" ") + " — " + c.effect
            }
        }
}
