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
    )

    val ALL: List<Command> = listOf(
        Command("/new", emptyList(), Group.CONVERSATION,
            "Start a fresh conversation; the agent forgets this one's thread", Category.CLEAR, Kind.INLINE),
        Command("/clear", listOf("/reset"), Group.CONVERSATION,
            "Clear the screen and start over; the agent's copy is kept", Category.CLEAR, Kind.INLINE),
        Command(CompressCommand.NAME, listOf(CompressCommand.ALIAS), Group.CONVERSATION,
            "Ask the agent to compact a long conversation so it stays quick", Category.CONDENSE, Kind.INLINE),
        Command("/reconnect", emptyList(), Group.CONNECTION,
            "Reach the gateway again and re-dial a live call", Category.CONNECT, Kind.INLINE),
        Command("/health", listOf("/status"), Group.CONNECTION,
            "Show whether the agent is up, and its version", Category.INSPECT, Kind.CARD),
        Command("/models", emptyList(), Group.AGENT,
            "Choose which model the agent thinks with", Category.CHOOSE, Kind.CARD),
        Command("/help", emptyList(), Group.HELP,
            "What each command does", Category.INFORM, Kind.CARD),
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

    /** The /help card, written from the catalogue so the two cannot disagree. */
    fun helpText(): String = grouped().joinToString("\n\n") { (g, cmds) ->
        g.label + "\n" + cmds.joinToString("\n") { c ->
            (listOf(c.name) + c.aliases).joinToString(" ") + " — " + c.effect
        }
    }
}
