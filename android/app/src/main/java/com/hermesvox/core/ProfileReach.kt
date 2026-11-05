package com.hermesvox.core

import java.net.URLDecoder

/**
 * ProfileReach — the one fact a 404 with `Unknown or unconfigured profile` carries:
 * **this profile does not exist on the host**.
 *
 * THE FIELD BUG THIS EXISTS TO KILL
 * ---------------------------------
 * A profile disappeared on the test host overnight. The phone kept saying *Connected*,
 * named the profile in the header, and every turn failed against:
 *
 * ```
 * 404 {"error": "Unknown or unconfigured profile"}
 * ```
 *
 * That answer is not a rejected key (401/403), not a network failure (no response), and
 * not transient (a retry cannot make the profile exist). It is a fact about the address:
 * the host has no profile called `<name>`. It therefore becomes its own state, and that
 * state is *not connected*.
 *
 * The state is deliberately not sticky in the request path: it is recorded from an answer
 * and cleared by a successful `GET /v1/models` for the same profile. The next attempt is
 * always allowed, and always tells the truth again.
 *
 * The app still has no call that creates, renames or deletes a profile. This file only
 * reads an answer — see [NoDestructiveRequestsTest] for the wire-level proof.
 *
 * Pure JVM: recognition, the profile name in the address, the wording and the clear rule
 * are all proven off-device.
 */
object ProfileReach {

    /** The host's exact phrase for a `/p/<name>` it does not serve. */
    const val MISSING_MARKER = "Unknown or unconfigured profile"

    /** A recorded state: the profile named by the host and the base address that was asked. */
    data class Missing(val name: String, val base: String)

    @Volatile
    private var current: Missing? = null

    /** True only for a 404 that carries the host's missing-profile phrase. */
    fun isMissingAnswer(code: Int, body: String): Boolean =
        code == 404 && body.contains(MISSING_MARKER, ignoreCase = true)

    /**
     * Record one answer. Returns true when it proves [baseURL]'s profile does not exist.
     *
     * [profileHint] names the profile when the address is the bare host (the default
     * profile), because the name is not in the URL there. A non-matching answer leaves
     * the state exactly as it was: only the host's own missing-profile 404 sets it.
     */
    fun observe(code: Int, body: String, baseURL: String, profileHint: String = ""): Boolean {
        if (!isMissingAnswer(code, body)) return false
        val base = base(baseURL)
        val name = profileIn(baseURL).ifBlank { profileHint.trim() }
        current = Missing(name, base)
        return true
    }

    /**
     * Clear the state on the proof that matters: `GET /v1/models` answered 200 for the
     * profile that was missing. Any other success is not proof that this profile exists,
     * so it does not clear it.
     */
    fun clearedByModels(modelsURL: String): Boolean {
        val state = current ?: return false
        val base = base(modelsURL).removeSuffix("/v1/models").trimEnd('/')
        if (base != state.base) return false
        current = null
        return true
    }

    /** Forget the state. Used when the connection itself changes, and by tests. */
    fun clear() { current = null }

    /** The recorded state, or null when the host has not said a profile is missing. */
    fun missing(): Missing? = current

    fun isMissing(): Boolean = current != null

    /** The one line the Settings row and the status pill say. */
    fun line(name: String = current?.name.orEmpty()): String =
        if (name.isBlank()) "the host has no such profile"
        else "the host has no profile called $name"

    /** The profile named in a `/p/<name>` address, decoded; "" for the bare host. */
    fun profileIn(url: String): String {
        val m = Regex("""/p/([^/?#]+)""").find(url) ?: return ""
        return try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Throwable) { m.groupValues[1] }
    }

    private fun base(url: String): String =
        url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
}
