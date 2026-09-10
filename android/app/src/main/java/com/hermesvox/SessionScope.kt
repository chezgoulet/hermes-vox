package com.hermesvox

/**
 * Entity scope — `X-Hermes-Session-Key`, the answer to "WHO is talking?".
 *
 * One Hermes gateway can serve several people (and several devices), and the
 * gateway's bearer key is a SINGLE shared credential: it names the deployment,
 * not the caller. Vox sent only that bearer, so every install pointed at the
 * same gateway wrote into the same long-term-memory scope and the entity had no
 * way to tell one person from another.
 *
 * The API server's own hook for this is `X-Hermes-Session-Key` (advertised in
 * `GET /v1/capabilities` as `features.session_key_header`): a stable per-channel
 * identifier from which it derives the long-term-memory scope. The gateway's
 * documented example shape is `agent:main:webui:dm:user-42`; anything stable and
 * unique per person works (`agent:vox:tablet:cody`).
 *
 * The rules here are a SUPERSET of the gateway's own validation (max 256 chars;
 * CR, LF and NUL rejected): they also refuse the remaining ISO control
 * characters (TAB, DEL), so a value the gateway would have accepted but that
 * cannot travel safely through a header is caught in the field instead of
 * failing a turn mid-call. Over-strict is the safe direction — the app never
 * sends what the gateway rejects. Blank means NOT DECLARED — the gateway then
 * scopes memory per
 * transcript, which is exactly what every install did before this setting
 * existed. It names a channel rather than carrying a credential, so it is stored
 * in plain prefs (the API key stays behind SecureStore).
 *
 * Pure JVM, like GatewayKey / EndpointRule, so the rule is testable off-device.
 */
object SessionScope {
    /** SharedPreferences key for the declared scope. */
    const val PREF = "session_scope"

    /** The API server's header name (its advertised `session_key_header`). */
    const val HEADER = "X-Hermes-Session-Key"

    /** The API server's cap; longer values are rejected outright. */
    const val MAX_LEN = 256

    /**
     * The value to store/send: trimmed, or "" when it cannot be declared. An
     * unusable value resolves to "" (not a truncated or stripped variant) —
     * silently rewriting somebody's identity is worse than not declaring one,
     * and [validationError] is what tells the user why.
     */
    fun normalize(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        if (v.length > MAX_LEN) return ""
        if (v.any { it.isISOControl() }) return ""
        return v
    }

    /** True when this value declares a scope (i.e. the header will be sent). */
    fun isDeclared(raw: String): Boolean = normalize(raw).isNotEmpty()

    /**
     * Why this value cannot be used, or null when it is fine. Blank is always
     * fine — it means "leave the gateway's per-transcript default alone".
     */
    fun validationError(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.length > MAX_LEN) return "Too long — the gateway accepts up to $MAX_LEN characters"
        if (v.any { it.isISOControl() }) return "Control characters are not allowed in the entity scope"
        return null
    }
}
