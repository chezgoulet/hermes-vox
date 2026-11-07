package com.hermesvox

import com.hermesvox.core.HermesSession

/**
 * M6 (0.9.1 review): ONE way to build the session for the connected profile, so every
 * caller talks to the entity as the same declared identity.
 *
 * `connectFromPrefs` set the provider and the scope (`X-Hermes-Session-Key`) after
 * construction; the VOX.md first-call ask and the Settings resync built a bare
 * `HermesSession(url, key, model)` instead. That ask then ran unscoped on the gateway's
 * default provider, so for two profiles that share one URL and differ only by scope, the
 * document stored under profile B was authored outside B's scope.
 *
 * Pure (prefs are read through [pref]), so the rule is pinned off-device.
 */
object EntitySession {

    /** The prefs keys a session is declared from (besides the URL and the key). */
    const val PREF_MODEL = "model"
    const val PREF_PROVIDER = "provider"
    const val DEFAULT_MODEL = "hermes-agent"

    /** The model route the prefs name ([DEFAULT_MODEL] when blank). */
    fun model(pref: (String) -> String): String = pref(PREF_MODEL).ifBlank { DEFAULT_MODEL }

    /** The declared scope the prefs name, normalised ("" = undeclared). */
    fun scope(pref: (String) -> String): String = SessionScope.normalize(pref(SessionScope.PREF))

    /**
     * A session from explicit values — a snapshot taken while the profile was known to be
     * the active one. Use this off the UI thread: a lazy `pref` read after a switch would
     * declare the NEW profile's scope on the OLD profile's ask.
     */
    fun of(url: String, key: String, model: String, provider: String, scope: String): HermesSession =
        HermesSession(url, key, model.ifBlank { DEFAULT_MODEL }).apply {
            setProvider(provider)
            setSessionKey(SessionScope.normalize(scope))
        }

    /**
     * A session for [url] + [key] carrying the prefs' model, provider override and scope.
     * [model] overrides the prefs' model when the caller already resolved it.
     */
    fun build(url: String, key: String, pref: (String) -> String, model: String = model(pref)): HermesSession =
        of(url, key, model, pref(PREF_PROVIDER), pref(SessionScope.PREF))
}
