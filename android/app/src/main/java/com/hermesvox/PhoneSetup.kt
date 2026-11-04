package com.hermesvox

/**
 * PhoneSetup — #421 step 5: the phone asks its own agent to configure the MCP server, and the
 * phone itself decides when the connection is real.
 *
 * **Why the app composes a turn.** The owner should not be handed a YAML block to paste. The agent
 * can edit its own configuration with the tools it already has, so the app sends it one ordinary
 * turn: add `vox`, verify it appears, reload if needed, and say what happened. The turn is visible
 * in the conversation and in this sheet — a change to the agent's own configuration must be
 * legible, never silent.
 *
 * **Why the app does not trust the agent's reply.** An agent can say "done" without a working
 * connection. The only evidence this class accepts for **Connected** is an MCP `initialize` that
 * the phone's own server saw ([PhoneMcpServer.onInitialize]). The agent's reply is still shown
 * beneath the waiting line, because when it fails, its own words are the most useful next clue.
 *
 * **Idempotent by content.** The prompt names the existing entry and says to update it in place,
 * never to add a second one. Sending the same prompt twice therefore describes the same end state;
 * no entry is duplicated by construction.
 *
 * Pure JVM. [PhoneSetupStore] is the Android side that persists the record; this file is what
 * [PhoneSetupTest] proves without a device.
 */
object PhoneSetup {

    /** How long a setup is allowed to wait for the agent's handshake before it is called a failure. */
    const val TIMEOUT_MS = 3 * 60_000L

    /** The state a person sees. [WAITING] is the only state that means "asked, not yet proven". */
    enum class State { IDLE, WAITING, CONNECTED, UNREACHABLE, REFUSED, TIMED_OUT }

    /**
     * Everything the sheet remembers between launches. [tokenTag] is a non-reversible fingerprint
     * of the credential, so a rotation can invalidate a prior handshake without storing the token
     * twice; it is never shown and never logged.
     */
    data class Record(
        val requestedAt: Long = 0L,
        val reply: String? = null,
        val sendFailed: Boolean = false,
        val refused: Boolean = false,
        val handshakeAt: Long = 0L,
        val handshakeTag: String? = null,
        val tokenTag: String = "",
    )

    /**
     * The exact turn the app sends. It carries the real address and bearer token, the server and
     * tool names, what the phone can and cannot do, the idempotence rule, the reload note, and the
     * question the reply should answer. No second mechanism writes the gateway's config.
     */
    fun prompt(host: String, token: String, port: Int = PhoneMcpServer.PORT): String {
        val url = "http://${PhoneMcpConfig.hostForUrl(host)}:$port${PhoneMcpServer.PATH}"
        return buildString {
            appendLine("Please add this phone to your own MCP configuration. This is a configuration change you are making for the owner, so make it carefully and report exactly what you changed.")
            appendLine()
            appendLine("Server name: ${PhoneMcpConfig.SERVER_NAME}")
            appendLine("URL: $url")
            appendLine("Authorization: Bearer $token")
            appendLine("Tool: ${PhoneTool.NAME} — you will see it as ${PhoneMcpConfig.MODEL_TOOL_NAME}")
            appendLine()
            appendLine("Add it to the MCP servers in your configuration (the `mcp_servers` block in your Hermes config, or the equivalent for the profile you are running). Keep `tools.include` to `${PhoneTool.NAME}`.")
            appendLine()
            appendLine("This must be idempotent. If a server named `${PhoneMcpConfig.SERVER_NAME}` already exists, update that entry in place with the URL and bearer token above. Do not add a second `${PhoneMcpConfig.SERVER_NAME}` entry, do not duplicate the block, and do not leave two entries pointing at this phone.")
            appendLine()
            appendLine("What the phone can do: set and cancel timers and alarms; turn Do Not Disturb on and off; change media volume; save a short note; report what is running; undo a reversible action. What it cannot do: read messages, place calls, send email, share files, launch apps, delete anything, or make purchases. Those actions need the owner's approval on the phone, and this server refuses them rather than performing them.")
            appendLine()
            appendLine("The listener is only up while a voice call is live on the phone, so an early connection attempt may fail; that is expected. A connection is real only when your client has completed the MCP handshake with it — the phone verifies that itself.")
            appendLine()
            appendLine("After editing, verify that a server named `${PhoneMcpConfig.SERVER_NAME}` appears in the MCP configuration you loaded (list or show it), and reload or restart your gateway if it needs that to pick up a new server. Then reply with: what you changed, whether `${PhoneMcpConfig.SERVER_NAME}` is present, and whether you needed to restart.")
            appendLine()
            appendLine("Do not claim the phone is connected — the phone checks that when your handshake arrives. If you cannot edit your own configuration, say so plainly and name the file or permission that stopped you. Never paste the bearer token into your reply or a log.")
        }.trimEnd()
    }

    /** A setup turn has been sent: waiting, with the old reply cleared. */
    fun begin(record: Record, now: Long, tokenTag: String): Record =
        record.copy(requestedAt = now, reply = null, sendFailed = false, refused = false, tokenTag = tokenTag)

    /** The agent answered. An empty answer is an unreachable agent, not a success. */
    fun replied(record: Record, reply: String?): Record {
        val text = reply?.trim().orEmpty()
        if (text.isEmpty()) return record.copy(reply = null, sendFailed = true, refused = false)
        return record.copy(reply = text, sendFailed = false, refused = looksRefused(text))
    }

    /** The turn never reached the agent (transport failure, missing connection). */
    fun unreachable(record: Record): Record =
        record.copy(reply = null, sendFailed = true, refused = false)

    /**
     * The phone's server saw a valid `initialize`. This is the only transition to [State.CONNECTED];
     * the tag makes a handshake earned with an old token stop counting after a rotation.
     */
    fun observedHandshake(record: Record, at: Long, tokenTag: String): Record =
        record.copy(handshakeAt = at, handshakeTag = tokenTag)

    /** A new token invalidates both the old credential and the connection proven with it. */
    fun rotated(record: Record): Record =
        record.copy(
            requestedAt = 0L,
            reply = null,
            sendFailed = false,
            refused = false,
            handshakeAt = 0L,
            handshakeTag = null,
            tokenTag = "",
        )

    /**
     * The state at [now]. [currentTokenTag] is the tag of the token in force; a handshake made with
     * any other token is not evidence for this one.
     */
    fun state(record: Record, now: Long, currentTokenTag: String): State {
        val tag = currentTokenTag.ifBlank { record.tokenTag }
        if (record.handshakeAt > 0 && tag.isNotEmpty() && record.handshakeTag == tag) return State.CONNECTED
        if (record.requestedAt == 0L) return State.IDLE
        if (record.sendFailed) return State.UNREACHABLE
        if (record.refused) return State.REFUSED
        if (now - record.requestedAt >= TIMEOUT_MS) return State.TIMED_OUT
        return State.WAITING
    }

    /**
     * Does the agent's reply say it could not edit its own configuration? This is a reading of the
     * agent's words, not a verdict: the UI shows the reply too, so a wrong guess is still legible.
     */
    fun looksRefused(reply: String): Boolean {
        val t = reply.lowercase()
        return REFUSALS.any { t.contains(it) }
    }

    private val REFUSALS = listOf(
        "cannot edit", "can't edit", "can not edit", "unable to edit",
        "cannot modify", "can't modify", "unable to modify",
        "could not edit", "couldn't edit", "could not modify", "couldn't modify",
        "could not update", "couldn't update",
        "cannot write", "can't write", "unable to write",
        "read-only", "read only", "no write access",
        "not allowed to edit", "don't have permission", "do not have permission",
        "cannot change my config", "can't change my config",
    )
}
