package com.hermesvox

/**
 * PhoneToolsView — the setup sheet's lines, pure so they are tested without an Activity.
 *
 * A state the owner cannot read is a state they will guess at. These functions say "stopped",
 * "waiting", "connected", "could not reach the agent", "the agent could not edit its config" in
 * words, and never render the token itself. The one place the token is shown is the manual
 * fallback's config block, which is the thing the owner has to copy.
 */
object PhoneToolsView {

    /** The one line every status row uses while Alpha features' Phone tools set is off. */
    const val ALPHA_OFF = "Off — select Phone tools in Alpha features"

    // ---- the three setup states, in their own words ----
    //
    // Enabling Phone tools starts this phone's listener and mints its credential. It does not teach
    // the agent that the phone exists: that is one entry in the agent's own configuration on the
    // host, and Hermes reads that configuration at startup. So "the set is on" and "the agent can
    // see this phone" are different facts, and a sheet that draws them the same leaves the owner at
    // a step nobody showed him. These lines are that step. They are constants because the three
    // states are a contract with the owner: they may not share wording, and only the handshake's
    // state may use the word *connected*.

    /** Not set up: the set is on, and no entry has been handed to the agent yet. */
    const val NOT_SET_UP_HEADING = "Your agent does not know about this phone yet"

    /** What not being set up actually means for the answers the owner gets. */
    const val NOT_SET_UP_MEANING =
        "Until it is set up, your agent cannot see this phone at all. Any answer it gives about " +
            "the phone is honest but unhelpful."

    /** Asked, waiting for the agent: the prompt was handed over and no handshake has arrived. */
    const val WAITING_HEADING = "Asked — waiting for your agent"

    /** What waiting means, including the restart Hermes needs to read a new server entry. */
    const val WAITING_MEANING =
        "The setup prompt has been handed over, and no handshake has arrived yet. If your agent " +
            "has written the entry, your gateway may need restarting before the tool appears."

    /** Connected: the phone's own server saw the handshake. The only state that may say this. */
    const val CONNECTED_HEADING = "Connected"

    /**
     * The sentence after the prompt: who writes the entry, and when it starts working. The restart
     * is part of the sequence, not a footnote — Hermes reads MCP configuration at startup, so the
     * entry exists for the agent only after the gateway comes back up.
     */
    const val SETUP_RESTART =
        "Your agent writes the entry itself; it takes effect when your gateway restarts."

    fun reachability(tailnet: String?, allowed: Boolean = true): String = when {
        !allowed -> ALPHA_OFF
        tailnet.isNullOrBlank() -> "Cannot run — Tailscale is not connected"
        else -> "Can run on the tailnet · $tailnet"
    }

    fun server(tailnet: String?, listening: Boolean, url: String?, allowed: Boolean = true): String = when {
        !allowed -> ALPHA_OFF
        tailnet.isNullOrBlank() -> "Stopped — no tailnet address to bind"
        listening -> "Listening · ${url ?: "on the tailnet"}"
        else -> "Stopped — the listener opens when a call starts"
    }

    fun lastRequest(request: String?, at: Long?, now: Long): String {
        if (request.isNullOrBlank() || at == null) return "None yet"
        val ago = (now - at).coerceAtLeast(0L)
        return "$request · ${ago(ago)} ago"
    }

    fun token(state: PhoneMcp.TokenState, allowed: Boolean = true): String = when {
        !allowed -> "Off — no token is created while Phone tools is off"
        state == PhoneMcp.TokenState.NONE -> "Not created yet"
        state == PhoneMcp.TokenState.STORED -> "Set · stored encrypted on this phone"
        state == PhoneMcp.TokenState.SESSION_ONLY -> "Set for this launch only · not saved"
        else -> "Set but unreadable · rotate to replace it"
    }

    fun dnd(granted: Boolean): String =
        if (granted) "Granted — the phone may change Do Not Disturb"
        else "Not granted — only you can give this in system settings"

    /** The notification-listener grant `now_playing` uses to name the app and the track. */
    fun nowPlaying(granted: Boolean): String =
        if (granted) "Granted — now_playing names the app and the track"
        else "Not granted — now_playing reports only whether audio is playing"

    /**
     * The same listener, seen from the notifications group: the grant is global, but the app only
     * listens while a call is live, so the line says both facts rather than only "granted".
     */
    fun notifications(granted: Boolean): String =
        if (granted) "Granted — the listener reads only while a call is live"
        else "Not granted — only you can give notification-listener access in system settings"

    /** The contacts runtime permission, naming the string Android reports when it is missing. */
    fun contacts(granted: Boolean, missing: String?): String =
        if (granted) "Granted — your agent may look up a contact's number by name"
        else "Not granted — ${missing ?: "contact access"} must be allowed before a lookup can run"

    /** The folder the system picker gave, or the plain fact that none has been given. */
    fun files(folders: List<String>): String = when {
        folders.isEmpty() ->
            "No folder granted — your agent cannot read or offer a file until you pick one"
        folders.size == 1 -> "Granted · ${folders.single()}"
        else -> "Granted · ${folders.joinToString(", ")}"
    }

    /** The calendar's runtime permission, in the owner's words, naming Android's own string. */
    fun calendar(granted: Boolean, missing: String?): String =
        if (granted) "Granted — your agent may read today's events and create one"
        else "Not granted — ${missing ?: "calendar access"} must be allowed before an action can run"

    fun volume(granted: Boolean): String =
        if (granted) "Granted — the phone may change the media volume"
        else "Not granted — volume changes will refuse with a reason"

    /**
     * The compact value on the Settings row. Three states, three words, no overlap: *not set up*,
     * *waiting for your agent*, *connected*. Never says connected before the handshake.
     */
    fun rowValue(state: PhoneSetup.State, allowed: Boolean = true): String = when {
        !allowed -> "off · alpha"
        state == PhoneSetup.State.CONNECTED -> "connected"
        state == PhoneSetup.State.WAITING -> "waiting for your agent"
        state == PhoneSetup.State.UNREACHABLE || state == PhoneSetup.State.REFUSED ||
            state == PhoneSetup.State.TIMED_OUT -> "retry"
        else -> "not set up"
    }

    /**
     * The one line under "Set up with your agent". [failure] is the record's own named cause
     * ([PhoneSetupSend]); it is what an unreachable send says, because "could not reach" for every
     * cause is the wrong-cause message #430 removed. [allowed] is the Alpha gate: off wins.
     *
     * The three states this lane is about each have their own heading: [NOT_SET_UP_HEADING] while
     * nothing has been handed over, [WAITING_HEADING] once the prompt is with the agent and no
     * handshake has been seen, and [CONNECTED_HEADING] only when this phone saw an `initialize`.
     */
    fun setupHeadline(
        state: PhoneSetup.State,
        failure: String? = null,
        allowed: Boolean = true,
    ): String = when {
        !allowed -> ALPHA_OFF
        state == PhoneSetup.State.IDLE -> NOT_SET_UP_HEADING
        state == PhoneSetup.State.WAITING -> WAITING_HEADING
        state == PhoneSetup.State.CONNECTED -> CONNECTED_HEADING
        state == PhoneSetup.State.UNREACHABLE ->
            failure?.takeIf { it.isNotBlank() }
                ?: PhoneSetupSend.message(PhoneSetupSend.Failure.NO_ANSWER)
        state == PhoneSetup.State.REFUSED -> "Your agent could not edit its configuration"
        else -> "Your agent was asked, but no handshake arrived in time"
    }

    /** The next step for the state, in the owner's words. Never a dead end. */
    fun setupNext(state: PhoneSetup.State, allowed: Boolean = true): String = when {
        !allowed -> "Select Phone tools in Alpha features at the top of Settings to use this."
        state == PhoneSetup.State.IDLE -> NOT_SET_UP_MEANING
        state == PhoneSetup.State.WAITING -> WAITING_MEANING
        state == PhoneSetup.State.CONNECTED ->
            "Your agent reached this phone and completed the MCP handshake. That is the proof; its reply is below for the record."
        state == PhoneSetup.State.UNREACHABLE ->
            "Check the gateway connection in Settings, then try again."
        state == PhoneSetup.State.REFUSED ->
            "Your agent could not write its own configuration. Try again after fixing its file access, or use the manual fallback below."
        else ->
            "No handshake arrived within three minutes. Try again, or rotate the token and ask once more."
    }

    /**
     * What the setup section draws, read once, in one place: the state's [heading], the [meaning]
     * under it, the [prompt] the owner hands to the agent with the copy action beside it, and the
     * [restart] sentence that follows it.
     *
     * [prompt] and [restart] are null when there is nothing to hand over — the connected state, or
     * the set being off — so the sheet cannot draw a restart for a connection that already
     * happened. The sheet renders them as heading, meaning, the state's action, the prompt, its
     * copy action, then the restart: the sequence the owner was never shown.
     */
    data class SetupBlock(
        val heading: String,
        val meaning: String,
        val prompt: String?,
        val restart: String?,
    )

    /**
     * The setup section for [state]. [prompt] is the turn this app produced ([PhoneSetup.prompt]);
     * it is passed through unchanged — it is a contract with the agent, not copy to improve.
     */
    fun setupBlock(
        state: PhoneSetup.State,
        prompt: String,
        failure: String? = null,
        allowed: Boolean = true,
    ): SetupBlock {
        if (!allowed) {
            return SetupBlock(
                heading = ALPHA_OFF,
                meaning = setupNext(state, allowed = false),
                prompt = null,
                restart = null,
            )
        }
        return when (state) {
            PhoneSetup.State.CONNECTED ->
                SetupBlock(CONNECTED_HEADING, setupNext(state), null, null)
            else ->
                SetupBlock(setupHeadline(state, failure), setupNext(state), prompt, SETUP_RESTART)
        }
    }

    /** "2m ago", "just now" — the connection proof's age, not a promise about right now. */
    fun handshakeLine(at: Long, now: Long): String {
        val ago = (now - at).coerceAtLeast(0L)
        return "Verified by an MCP initialize ${ago(ago)} ago"
    }

    /** "just now", "42s ago", "3m ago", "2h ago" — enough for a last-request line. */
    fun ago(ms: Long): String = when {
        ms < 5_000L -> "just now"
        ms < 60_000L -> "${ms / 1_000L}s"
        ms < 3_600_000L -> "${ms / 60_000L}m"
        else -> "${ms / 3_600_000L}h"
    }

    // ---- capabilities and the audit (#429 step 1) ----

    /** "4 of 12 groups on" — the one number that says how much has been granted. */
    fun grantSummary(states: Map<String, PhoneGrants.State>): String =
        "${states.values.count { it != PhoneGrants.State.OFF }} of ${states.size} groups on"

    /**
     * What a state means, in the row the owner is about to change. The same three sentences for
     * every group, every tier: the words are the policy, so they may not mean different things in
     * different rows.
     */
    fun grantMeaning(state: PhoneGrants.State): String = when (state) {
        PhoneGrants.State.OFF -> "Off — the agent cannot use this."
        PhoneGrants.State.ASK -> "Ask — the agent asks you first, by tap or by voice."
        PhoneGrants.State.ALLOW -> "Allow — the agent acts without asking."
    }

    /** The HH:mm an action happened, in the phone's own time zone. */
    fun clockTime(atEpochMs: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = atEpochMs }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    /** One line of arguments as the audit stored them: never a body, never a secret. */
    fun argumentsLine(arguments: Map<String, String>): String =
        if (arguments.isEmpty()) "no arguments"
        else arguments.entries.joinToString(" · ") { "${it.key}=${it.value}" }

    /** The outcome as the owner reads it; the reason stays visible because it is the claim's check. */
    fun outcomeLine(entry: PhoneAudit.Entry): String = when (entry.outcome) {
        "approval-requested" -> "asked you · approval required"
        "approved" -> "approved by ${entry.arguments["via"] ?: "tap"} · not performed yet"
        "denied" -> "denied by ${entry.arguments["via"] ?: "tap"}"
        "expired" -> "expired · no answer, so not performed"
        else -> when {
            entry.ok -> "done"
            entry.outcome == PhoneReason.NOT_GRANTED -> "refused · not granted"
            entry.outcome == PhoneReason.APPROVAL -> "refused · needs your approval"
            entry.outcome == PhoneReason.PERMISSION -> "refused · permission not granted"
            else -> "refused · ${entry.outcome}"
        }
    }

    /** The audit row's first line: time and action. */
    fun auditHeadline(entry: PhoneAudit.Entry): String =
        "${clockTime(entry.atEpochMs)}  ${entry.action}"
}
