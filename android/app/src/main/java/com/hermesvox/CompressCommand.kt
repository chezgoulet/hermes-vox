package com.hermesvox

/**
 * CompressCommand — the user-facing /compress (context compaction), 0.5.2 K2.
 *
 * WHY IT EXISTS (Christopher, 2026-09-07, at the end of a 15-turn session):
 * "We need to expose a compress command to the user through this app and/or have
 * [the entity do it]." A long conversation eventually fills the agent's context;
 * the gateway agent can compact its own thread, but nothing in the app could ask
 * it to. This is that ask, wired as a native command.
 *
 * WHAT IT IS NOT: an app-side string trim. Clearing the local transcript is what
 * /clear already does and it changes nothing about the agent's context. [DIRECTIVE]
 * is sent to the HERMES GATEWAY on the SAME server-side response chain the voice
 * turns use (HermesSession.turnStored -> /v1/responses with previous_response_id),
 * so the entity compacts the real session and the conversation keeps going.
 *
 * Pure JVM — the command's names and every line of user copy are proven off-device,
 * like ConnectionPhase / EndpointRule / BargeGate.
 */
object CompressCommand {

    /** The command as it appears in the commands sheet. */
    const val NAME = "/compress"
    /** The natural alias (same command, the other common spelling). */
    const val ALIAS = "/compact"

    /** What is sent to the gateway agent. The gateway's own command — the
     *  compaction happens THERE, on the live agent session. */
    const val DIRECTIVE = "/compress"

    /** Mini-UI feedback, same shape as the other native commands. */
    const val RUNNING = "Compacting the conversation…"
    const val DONE = "Conversation compacted"
    const val FAILED = "Couldn't compact — check the connection"
    const val TITLE = "Compress context"

    /** The /help listing line. */
    const val HELP_LINE = "/compress /compact - ask the gateway to compact the conversation context"

    /** Does this typed input mean "compress"? Tolerant of case, surrounding
     *  whitespace and the missing leading slash; nothing else matches. */
    fun matches(input: String): Boolean {
        val t = input.trim().lowercase()
        if (t.isEmpty()) return false
        val bare = if (t.startsWith("/")) t.substring(1) else t
        return bare == "compress" || bare == "compact"
    }

    /** The native card body. A blank/failed reply is reported as a failure that
     *  changed nothing — never as a silent success. */
    fun card(reply: String?): String {
        val r = reply?.trim().orEmpty()
        if (r.isEmpty()) return "The gateway didn't compact the conversation.\n\n" +
            "Nothing was changed here — check the connection (/health), then try again."
        return "The gateway compacted this conversation — the thread is kept, the bulk is " +
            "dropped, and the session keeps going.\n\n" + r.take(600)
    }
}
