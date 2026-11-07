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

    /** Asked; no handshake yet, and the agent may still be working. */
    const val WAITING_HEADING = "Asked — your agent is working"

    /** What waiting means. The run and the handshake, not a restart, are what the entry waits on. */
    const val WAITING_MEANING =
        "The setup prompt has been handed over, and no handshake has arrived yet. Your agent may " +
            "still be working; a running gateway picks the entry up by itself, so there is " +
            "nothing to restart."

    /** Connected: the phone's own server saw the handshake. The only state that may say this. */
    const val CONNECTED_HEADING = "Connected"

    /**
     * The sentence after the prompt: who writes the entry, and why nothing has to be restarted.
     * The app opens the phone's listener before sending and keeps it open for the attempt, which
     * is what lets `hermes mcp add` prove reachability; the gateway reconciles its own
     * configuration afterwards.
     */
    const val SETUP_NOTE =
        "Your agent writes the entry itself while this phone's listener is open for this attempt. " +
            "Nothing on your host restarts, and Vox writes nothing there."

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

    // ---- the Phone Tools section (#453) ----
    //
    // The section is one row on the Settings home, and its second line is the whole answer to
    // *"is it set up yet?"* It is read from [PhoneBootstrap.Step] — the value the sheet draws and
    // the button advances — and never from a second reading of the record. A parallel list is how
    // the row and the sheet come to disagree about the one thing the owner is asking.
    //
    // Only [STEP_CONNECTED] asserts a connection, and it is the sheet's own heading, so the two
    // cannot drift. [STEP_FAILED] may only deny it: the word appears in exactly two of these
    // lines, and the negative is the sheet's to explain (it names the step reached and why).

    /** Not set up: nothing has been handed to the agent in this run of the app. */
    const val STEP_IDLE = "Not set up"

    /** The instruction has gone out and the agent is being asked, right now. */
    const val STEP_ADDING = "Asking your agent"

    /** Asked; the agent may still be working and the phone's own handshake has not arrived yet. */
    const val STEP_WAITING = "Your agent is working"

    /** The one line that asserts a connection: the phone's own `initialize`. */
    const val STEP_CONNECTED = CONNECTED_HEADING

    /** The attempt stopped. Only the sheet's words may say which step it reached and why. */
    const val STEP_FAILED = "Not connected"

    /**
     * The section row's one line for [step]. [allowed] is the Alpha gate: off wins, as it does on
     * every other Phone-tools line, because a state read while the set is off is not a promise.
     */
    fun stepLine(step: PhoneBootstrap.Step, allowed: Boolean = true): String = when {
        !allowed -> ALPHA_OFF
        step == PhoneBootstrap.Step.IDLE -> STEP_IDLE
        step == PhoneBootstrap.Step.ADDING -> STEP_ADDING
        step == PhoneBootstrap.Step.WAITING -> STEP_WAITING
        step == PhoneBootstrap.Step.CONNECTED -> STEP_CONNECTED
        else -> STEP_FAILED
    }

    /**
     * Is the section's setup row drawn? It is the one action the section offers, so it is present
     * for every step that has something left to do and gone the moment the handshake lands — a
     * second press would add nothing, and the sheet says so in its own words (*Ask again*).
     */
    fun setupRowVisible(step: PhoneBootstrap.Step): Boolean = step != PhoneBootstrap.Step.CONNECTED

    /**
     * The setup row's own label. It is [PhoneBootstrap.BUTTON] in the sentence case a row's title
     * uses, not a second wording of the same action: the row surfaces the sheet's button, so the
     * two may not say different things.
     */
    fun setupRowTitle(): String =
        PhoneBootstrap.BUTTON.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * The compact value on the Settings row. Three states, three words, no overlap: *not set up*,
     * *working*, *connected*. Never says connected before the handshake.
     */
    fun rowValue(state: PhoneSetup.State, allowed: Boolean = true): String = when {
        !allowed -> "off · alpha"
        state == PhoneSetup.State.CONNECTED -> "connected"
        state == PhoneSetup.State.WAITING -> "working"
        state == PhoneSetup.State.UNREACHABLE || state == PhoneSetup.State.REFUSED -> "retry"
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
        else -> "Your agent was asked, but no handshake arrived"
    }

    /** The next step for the state, in the owner's words. Never a dead end. */
    fun setupNext(state: PhoneSetup.State, allowed: Boolean = true): String = when {
        !allowed -> "Select Phone tools in Alpha features, the block just above About in Settings, to use this."
        state == PhoneSetup.State.IDLE -> NOT_SET_UP_MEANING
        state == PhoneSetup.State.WAITING -> WAITING_MEANING
        state == PhoneSetup.State.CONNECTED ->
            "Your agent reached this phone and completed the MCP handshake. The entry is lazy: the tool is registered from its schema, so your agent can reach this phone whenever a call is live — it does not need to have reached it recently. That is the proof; its reply is below for the record."
        state == PhoneSetup.State.UNREACHABLE ->
            "Check the gateway connection in Settings, then try again."
        state == PhoneSetup.State.REFUSED ->
            "Your agent could not write its own configuration. Try again after fixing its file access, or use the manual fallback below."
        else -> "Open this sheet again and try once more."
    }

    /**
     * What the setup section draws, read once, in one place: the state's [heading], the [meaning]
     * under it, the [prompt] the owner hands to the agent with the copy action beside it, and the
     * [note] sentence that follows it.
     *
     * [prompt] and [note] are null when there is nothing to hand over — the connected state, or the
     * set being off — so the sheet cannot promise a setup for a connection that already happened.
     * The sheet renders them as heading, meaning, the state's action, the prompt, its copy action,
     * then the note: the sequence the owner was never shown.
     */
    data class SetupBlock(
        val heading: String,
        val meaning: String,
        val prompt: String?,
        val note: String?,
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
                note = null,
            )
        }
        return when (state) {
            PhoneSetup.State.CONNECTED ->
                SetupBlock(CONNECTED_HEADING, setupNext(state), null, null)
            else ->
                SetupBlock(setupHeadline(state, failure), setupNext(state), prompt, SETUP_NOTE)
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
        PhoneAudit.UNCERTAIN -> "may still have happened · outcome unknown"
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
