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

    fun reachability(tailnet: String?): String =
        if (tailnet.isNullOrBlank()) "Cannot run — Tailscale is not connected"
        else "Can run on the tailnet · $tailnet"

    fun server(tailnet: String?, listening: Boolean, url: String?): String = when {
        tailnet.isNullOrBlank() -> "Stopped — no tailnet address to bind"
        listening -> "Listening · ${url ?: "on the tailnet"}"
        else -> "Stopped — the listener opens when a call starts"
    }

    fun lastRequest(request: String?, at: Long?, now: Long): String {
        if (request.isNullOrBlank() || at == null) return "None yet"
        val ago = (now - at).coerceAtLeast(0L)
        return "$request · ${ago(ago)} ago"
    }

    fun token(state: PhoneMcp.TokenState): String = when (state) {
        PhoneMcp.TokenState.NONE -> "Not created yet"
        PhoneMcp.TokenState.STORED -> "Set · stored encrypted on this phone"
        PhoneMcp.TokenState.SESSION_ONLY -> "Set for this launch only · not saved"
        PhoneMcp.TokenState.UNREADABLE -> "Set but unreadable · rotate to replace it"
    }

    fun dnd(granted: Boolean): String =
        if (granted) "Granted — the phone may change Do Not Disturb"
        else "Not granted — only you can give this in system settings"

    fun volume(granted: Boolean): String =
        if (granted) "Granted — the phone may change the media volume"
        else "Not granted — volume changes will refuse with a reason"

    /** The compact value on the Settings row. Never says connected before the handshake. */
    fun rowValue(state: PhoneSetup.State): String = when (state) {
        PhoneSetup.State.CONNECTED -> "connected"
        PhoneSetup.State.WAITING -> "waiting"
        PhoneSetup.State.UNREACHABLE, PhoneSetup.State.REFUSED, PhoneSetup.State.TIMED_OUT -> "retry"
        PhoneSetup.State.IDLE -> "set up"
    }

    /**
     * The one line under "Set up with your agent". [failure] is the record's own named cause
     * ([PhoneSetupSend]); it is what an unreachable send says, because "could not reach" for every
     * cause is the wrong-cause message #430 removed.
     */
    fun setupHeadline(state: PhoneSetup.State, failure: String? = null): String = when (state) {
        PhoneSetup.State.IDLE -> "Not set up yet"
        PhoneSetup.State.WAITING -> "Waiting for your agent to connect"
        PhoneSetup.State.CONNECTED -> "Connected"
        PhoneSetup.State.UNREACHABLE ->
            failure?.takeIf { it.isNotBlank() }
                ?: PhoneSetupSend.message(PhoneSetupSend.Failure.NO_ANSWER)
        PhoneSetup.State.REFUSED -> "Your agent could not edit its configuration"
        PhoneSetup.State.TIMED_OUT -> "Your agent was asked, but nothing connected in time"
    }

    /** The next step for the state, in the owner's words. Never a dead end. */
    fun setupNext(state: PhoneSetup.State): String = when (state) {
        PhoneSetup.State.IDLE ->
            "Set up asks your agent to add this phone to its own MCP configuration. You do not edit any files."
        PhoneSetup.State.WAITING ->
            "The listener opens when a call starts. Skim your agent's reply below — it is the best clue if this does not complete."
        PhoneSetup.State.CONNECTED ->
            "Your agent reached this phone and completed the MCP handshake. That is the proof; its reply is below for the record."
        PhoneSetup.State.UNREACHABLE ->
            "Check the gateway connection in Settings, then try again."
        PhoneSetup.State.REFUSED ->
            "Your agent could not write its own configuration. Try again after fixing its file access, or use the manual fallback below."
        PhoneSetup.State.TIMED_OUT ->
            "Nothing connected within three minutes. Try again, or rotate the token and ask once more."
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

    /** What a state means, in the row the owner is about to change. */
    fun grantMeaning(state: PhoneGrants.State): String = when (state) {
        PhoneGrants.State.OFF -> "Off hides it from your agent’s tool list."
        PhoneGrants.State.ASK -> "Ask lets your agent try; every use waits for you."
        PhoneGrants.State.ALLOW -> "Allow runs it instantly, and it is reported below."
    }

    /** Why Allow is absent for a group, or null when it is offered. */
    fun allowAbsent(tier: Int): String? = when (tier) {
        PhoneTiers.INSTANT -> null
        PhoneTiers.REPORTED -> "Allow is not offered here: you see the result, so you stay in the loop."
        else -> "Allow is not offered here: this is consequential, so every use is a card."
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
    fun outcomeLine(entry: PhoneAudit.Entry): String = when {
        entry.ok -> "done"
        entry.outcome == PhoneReason.NOT_GRANTED -> "refused · not granted"
        entry.outcome == PhoneReason.APPROVAL -> "refused · needs your approval"
        entry.outcome == PhoneReason.PERMISSION -> "refused · permission not granted"
        else -> "refused · ${entry.outcome}"
    }

    /** The audit row's first line: time and action. */
    fun auditHeadline(entry: PhoneAudit.Entry): String =
        "${clockTime(entry.atEpochMs)}  ${entry.action}"
}
