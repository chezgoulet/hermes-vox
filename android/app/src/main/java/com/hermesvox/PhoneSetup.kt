package com.hermesvox

/**
 * PhoneSetup — #421 step 5: the phone asks its own agent to configure the MCP server, and the
 * phone itself decides when the connection is real.
 *
 * **Why the app composes a turn.** The owner should not be handed a YAML block to paste. The agent
 * can edit its own configuration with the tools it already has, so the app sends it one ordinary
 * turn: add `vox` with `hermes mcp add`, create the one-shot restart job, verify the entry, and say
 * what happened. The turn is visible in the conversation and in this sheet — a change to the agent's
 * own configuration must be legible, never silent.
 *
 * **The token goes with the instruction.** `hermes mcp add` writes the phone's bearer into the
 * agent's own configuration; there is no pairing step that avoids it. The token therefore rides
 * this one turn, over the same authenticated connection as every other turn, and the app keeps the
 * text in memory only. It is never logged, never audited and never written to a preference; the
 * agent's reply is scrubbed before it is stored.
 *
 * **The restart is arranged, never issued by the app.** The API surface has no restart route. The
 * turn asks the agent to create a one-shot, never-firing job whose script is
 * `systemctl --user restart hermes-gateway`; [PhoneBootstrapStore] then triggers that job through
 * `POST /api/jobs/{id}/run`. The agent's act is the configuration; the app's act is the owner's
 * press.
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
 * **A failure names itself.** [Record.failure] carries the line [PhoneSetupSend] chose — no
 * connection configured, key rejected, the agent did not answer, or the turn failed with the
 * gateway's own reason. One "could not reach your agent" for every cause was #430: it reported a
 * local misconfiguration as a network outage, so the owner checked a network that was fine.
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
     *
     * [failure] is the line the sheet shows when the turn did not reach an agent — one of
     * [PhoneSetupSend]'s named failures, never a single "could not reach" for every cause.
     */
    data class Record(
        val requestedAt: Long = 0L,
        val reply: String? = null,
        val failure: String? = null,
        val refused: Boolean = false,
        val handshakeAt: Long = 0L,
        val handshakeTag: String? = null,
        val tokenTag: String = "",
    )

    /**
     * The exact turn the app sends. It carries the address, the server and tool names, the token,
     * the documented `hermes mcp add` command, what the phone can and cannot do, the idempotence
     * rule, and the exact restart job the agent must arrange.
     *
     * **It carries this phone's token** because `hermes mcp add` writes it into the agent's own
     * configuration as the server's bearer. The token leaves the phone only on this turn, over the
     * same authenticated connection every other turn uses; [PhoneSetupStore] keeps the text in
     * memory for this process only, and [PhoneBootstrapStore] never logs, audits or persists it.
     * The agent is told to keep it out of its reply.
     *
     * **It asks for the job id in a parseable form.** The app cannot discover the id itself, so it
     * asks for one marked line and [PhoneBootstrap.jobId] refuses anything that is not exactly the
     * gateway's `[a-f0-9]{12}` form. A reply without it does not advance the flow.
     */
    fun prompt(
        host: String,
        token: String = PhoneMcpConfig.DOC_TOKEN,
        port: Int = PhoneMcpServer.PORT,
    ): String {
        val url = "http://${PhoneMcpConfig.hostForUrl(host)}:$port${PhoneMcpServer.PATH}"
        return buildString {
            appendLine("Please add this phone to your own MCP configuration and arrange one restart of your gateway. This is a configuration change you are making for the owner, so make it carefully and report exactly what you changed.")
            appendLine()
            appendLine("Server name: ${PhoneMcpConfig.SERVER_NAME}")
            appendLine("URL: $url")
            appendLine("Tool: ${PhoneTool.NAME} — you will see it as ${PhoneMcpConfig.MODEL_TOOL_NAME}")
            appendLine("Token: $token")
            appendLine()
            appendLine("Add it with the documented CLI:")
            appendLine()
            appendLine("hermes mcp add ${PhoneMcpConfig.SERVER_NAME} --url \"$url\" --auth header --connect-timeout 5")
            appendLine()
            appendLine("When it asks for the API key / bearer token, paste exactly the token above. If the connection probe fails because this phone's listener is only up while a voice call is live, save the entry anyway, keep it enabled, and continue; an early failed probe is expected.")
            appendLine()
            appendLine("This also works with the equivalent `mcp_servers` entry, in the profile's `~/.hermes/config.yaml`: the same `$url` and an `Authorization: \"Bearer $token\"` header. Whichever route you use, the server name stays `${PhoneMcpConfig.SERVER_NAME}`.")
            appendLine()
            appendLine("This phone exposes exactly one tool, `${PhoneTool.NAME}`, so the entry needs no tool filter; the phone itself offers only what the owner has granted and refuses the rest with a reason.")
            appendLine()
            appendLine("This must be idempotent. If a server named `${PhoneMcpConfig.SERVER_NAME}` already exists, update that entry in place with the URL and the token above. Do not add a second `${PhoneMcpConfig.SERVER_NAME}` entry, do not duplicate the block, and do not leave two entries pointing at this phone.")
            appendLine()
            appendLine("What the phone can do: set and cancel timers and alarms; turn Do Not Disturb on and off; change media volume; save a short note; report what is running; undo a reversible action. Only the capability groups the owner has granted are offered — a group that is Off is absent from the tool's action list, and a call that still names one is refused with reason=not_granted rather than performed. What it cannot do: read messages, place calls, send email, share files, launch apps, delete anything, or make purchases. Those actions need the owner's approval on the phone, and this server refuses them rather than performing them.")
            appendLine()
            appendLine("The listener is only up while a voice call is live on the phone, so an early connection attempt may fail; that is expected. A connection is real only when your client has completed the MCP handshake with it — the phone verifies that itself.")
            appendLine()
            appendLine("Now arrange the one restart. Write a script at `~/.hermes/scripts/vox-gateway-restart.sh` whose body is exactly:")
            appendLine()
            appendLine("systemctl --user restart hermes-gateway")
            appendLine()
            appendLine("Then create a one-shot, on-demand job with a schedule that never fires, and leave it disabled from firing but runnable on demand:")
            appendLine()
            appendLine("hermes cron create \"2099-01-01T00:00:00\" --name vox-gateway-restart --script vox-gateway-restart.sh --no-agent --paused --repeat 1")
            appendLine()
            appendLine("Do not run it now. Do not restart the gateway yourself, and do not call `hermes gateway restart`: that is refused on this surface. The app will trigger the job through `POST /api/jobs/{job_id}/run`. If a job named `vox-gateway-restart` already exists, reuse it instead of creating a second one.")
            appendLine()
            appendLine("After editing, verify that a server named `${PhoneMcpConfig.SERVER_NAME}` appears in the MCP configuration you loaded. Then reply with what you changed, and put the job id on its own line in exactly this form (12 lowercase hex characters):")
            appendLine()
            appendLine("${PhoneBootstrap.JOB_ID_PREFIX} <job id>")
            appendLine()
            appendLine("Do not claim the phone is connected — the phone checks that when your handshake arrives. If you cannot edit your own configuration, say so plainly and name the file or permission that stopped you. Never paste the token into your reply, a message or a log: it stays in your configuration and in the job, and this conversation needs no credential beyond this instruction.")
        }.trimEnd()
    }

    /** A setup turn has been sent: waiting, with the old answer and the old failure cleared. */
    fun begin(record: Record, now: Long, tokenTag: String): Record =
        record.copy(requestedAt = now, reply = null, failure = null, refused = false, tokenTag = tokenTag)

    /** The agent answered. An empty answer is an unreachable agent, not a success. */
    fun replied(record: Record, reply: String?): Record {
        val text = reply?.trim().orEmpty()
        if (text.isEmpty()) {
            return record.copy(
                reply = null,
                failure = PhoneSetupSend.message(PhoneSetupSend.Failure.NO_ANSWER),
                refused = false,
            )
        }
        return record.copy(reply = text, failure = null, refused = looksRefused(text))
    }

    /** The turn could not be made, or it failed. [reason] is the named cause ([PhoneSetupSend]). */
    fun failed(record: Record, reason: String): Record =
        record.copy(reply = null, failure = reason, refused = false)

    /**
     * The turn never reached the agent and the cause is unknown here — kept for callers that have
     * only a null reply. It still names the failure instead of inventing a verdict.
     */
    fun unreachable(record: Record): Record =
        failed(record, PhoneSetupSend.message(PhoneSetupSend.Failure.NO_ANSWER))

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
            failure = null,
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
        if (record.failure != null) return State.UNREACHABLE
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
