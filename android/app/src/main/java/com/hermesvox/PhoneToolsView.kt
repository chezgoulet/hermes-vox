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

    /** The compact value on the Settings row. Never says connected before the handshake. */
    fun rowValue(state: PhoneSetup.State): String = when (state) {
        PhoneSetup.State.CONNECTED -> "connected"
        PhoneSetup.State.WAITING -> "waiting"
        PhoneSetup.State.UNREACHABLE, PhoneSetup.State.REFUSED, PhoneSetup.State.TIMED_OUT -> "retry"
        PhoneSetup.State.IDLE -> "set up"
    }

    /** The one line under "Set up with your agent". */
    fun setupHeadline(state: PhoneSetup.State): String = when (state) {
        PhoneSetup.State.IDLE -> "Not set up yet"
        PhoneSetup.State.WAITING -> "Waiting for your agent to connect"
        PhoneSetup.State.CONNECTED -> "Connected"
        PhoneSetup.State.UNREACHABLE -> "Could not reach your agent"
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
}
