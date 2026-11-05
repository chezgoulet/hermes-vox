package com.hermesvox

import java.io.IOException

/**
 * PhoneSetupSend — what the setup turn rides, and what the sheet says when it cannot go out (#430).
 *
 * **The connection is the active profile's.** The setup turn is an ordinary turn, so it must leave
 * on the same client every other turn uses: the live prefs' `url`, `key`, `model`, `provider` and
 * declared `session_scope` — read from the same live prefs the call path ([EntitySession]) and
 * Settings' connection test build their session from. It is *not* whatever session object happens
 * to be held in memory: a
 * `HermesSession` is built from prefs once and keeps that url and key for life, so an owner who
 * re-points the connection (Settings → Entity & Connection → Save) has a live object that still
 * names the OLD address while the connection test — which builds its own from the prefs — says
 * **Connection OK**. That was #430: the turn went to the previous connection, so the gateway they
 * were watching logged no inbound request, and "could not reach your agent" named a cause that was
 * not the one.
 *
 * [plan] is the rule that ends that class of failure: the live session may be reused **only** when
 * it was built for exactly the connection in force; otherwise one is built there, from the active
 * connection, rather than failing. Only a genuinely unconfigured connection is a refusal, and it
 * has its own words ([Failure.NO_CONNECTION]).
 *
 * **Each failure says what happened.** [classify] turns the turn's outcome into one of four named
 * failures and [message] gives each its own line — "no connection configured", "key rejected",
 * "your agent did not answer", "turn failed: <reason>". One sentence for every case is how a local
 * misconfiguration reads as a network outage, which is what this release exists to remove.
 *
 * Pure JVM: the connection rule and the wording are proven off-device ([PhoneSetupSendTest]).
 */
object PhoneSetupSend {

    /**
     * The connection a turn rides. [key] is the decrypted credential and is never logged, shown or
     * persisted by this file; [scope] is the normalised `X-Hermes-Session-Key` identity.
     */
    data class Connection(
        val url: String,
        val key: String,
        val model: String = EntitySession.DEFAULT_MODEL,
        val provider: String = "",
        val scope: String = "",
    ) {
        /** A request needs both halves: an address with a scheme, and a credential. */
        val configured: Boolean get() = isEndpoint(url) && key.isNotBlank()

        private fun isEndpoint(u: String): Boolean {
            val s = u.trim().lowercase()
            return s.startsWith("http://") || s.startsWith("https://")
        }
    }

    /** What to do with the session the app is holding. */
    enum class Plan {
        /** The live session was built for this very connection: ride it, so the turn is in its chain. */
        RIDE_LIVE,

        /** No session, or one for a different connection: build it here from the active one. */
        BUILD,

        /** Nothing configured to send to. */
        NO_CONNECTION,
    }

    /**
     * The reuse rule. [live] is the connection the held session was built for (null when there is
     * no session), [hasSession] whether one is held, [active] the connection in force right now.
     */
    fun plan(live: Connection?, hasSession: Boolean, active: Connection?): Plan = when {
        active == null || !active.configured -> Plan.NO_CONNECTION
        hasSession && live == active -> Plan.RIDE_LIVE
        else -> Plan.BUILD
    }

    /**
     * The active connection, read exactly as the call path reads it. [get] is the prefs reader,
     * [decrypt] the keystore. A blank url, a missing key or an address without a scheme is simply
     * not configured — nothing is invented to make the send look possible.
     */
    fun connection(get: (String) -> String, decrypt: (String) -> String?): Connection = Connection(
        url = get(Profiles.K_URL).trim(),
        key = GatewayKey.resolve(get(Profiles.K_KEY), decrypt),
        model = EntitySession.model(get),
        provider = get(EntitySession.PREF_PROVIDER),
        scope = EntitySession.scope(get),
    )

    /** The four ways a setup turn can fail to reach its agent. Each is named, none is generic. */
    enum class Failure { NO_CONNECTION, KEY_REJECTED, NO_ANSWER, TURN_FAILED }

    /**
     * The failure an outcome represents, or null when the agent answered. [reply] is what the turn
     * returned; [error] what it threw. An empty answer is [Failure.NO_ANSWER], never a success.
     */
    fun classify(error: Throwable?, reply: String?): Failure? {
        if (error == null) return if (reply.isNullOrBlank()) Failure.NO_ANSWER else null
        if (keyRejected(error)) return Failure.KEY_REJECTED
        if (error is IOException || neverAnswered(error)) return Failure.NO_ANSWER
        return Failure.TURN_FAILED
    }

    /**
     * The one line for [f]. [detail] is the gateway's or the transport's own words, when it had
     * any; it is scrubbed before it gets here (see [reason]).
     */
    fun message(f: Failure, detail: String = ""): String {
        val why = detail.trim().trimEnd('.')
        return when (f) {
            Failure.NO_CONNECTION ->
                "No connection configured: set the gateway under Entity & Connection, then try again."
            Failure.KEY_REJECTED ->
                "Key rejected: the gateway refused this profile's key. Fix it under Profiles, then try again."
            Failure.NO_ANSWER ->
                "Your agent did not answer: " +
                    (why.ifBlank { "the gateway was not reachable" }) +
                    ". Check the connection, then try again."
            Failure.TURN_FAILED ->
                "Turn failed: " + (why.ifBlank { "the agent's answer could not be read" }) + "."
        }
    }

    /**
     * The failure line for an outcome (null when the agent answered). [token] is scrubbed out of
     * the reason: the transport's words are the gateway's, but they travel to a persisted record
     * and a screen, and the one secret on this path is the phone's own bearer token.
     */
    fun reason(error: Throwable?, reply: String?, token: String = "", max: Int = 160): String? {
        val f = classify(error, reply) ?: return null
        val detail = when (f) {
            Failure.NO_CONNECTION, Failure.KEY_REJECTED -> ""
            else -> scrub(error?.message.orEmpty(), token, max)
        }
        return message(f, detail)
    }

    /** The transport's message, without the token, on one line, bounded. */
    fun scrub(text: String, token: String, max: Int = 160): String {
        var s = text
        if (token.isNotBlank()) s = s.replace(token, "[token]")
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.length <= max) s else s.take(max).trimEnd() + "…"
    }

    /** 401/403 and the words gateways use for them. The status line is `"… 401 Unauthorized: …"`. */
    private fun keyRejected(e: Throwable): Boolean {
        val m = e.message.orEmpty().lowercase()
        return m.contains(" 401") || m.contains(" 403") || m.contains("unauthorized") ||
            m.contains("invalid api key") || m.contains("key rejected")
    }

    /**
     * The turn never got an answer: the client's own timeout, the idle watchdog, or a socket that
     * died with the run unread. These are "did not answer", not a malformed exchange.
     */
    private fun neverAnswered(e: Throwable): Boolean {
        val m = e.message.orEmpty().lowercase()
        return m.contains("timed out") || m.contains("no answer within") ||
            m.contains("no data from the gateway") || m.contains("connection lost") ||
            m.contains("did not answer")
    }
}
