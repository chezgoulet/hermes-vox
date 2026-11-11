package com.hermesvox

/**
 * Header — who you are talking to, and what answers (#second phone pass).
 *
 * The two lines under the being say two different facts, and they used to be swapped:
 *
 *  * **the title is the agent** — the entity itself. The host's name for it wins
 *    ([HostEntity], `agent_name`); the Hermes agent's own name is the floor, so a fresh
 *    install never shows a model where a name belongs. The live call's notification uses
 *    the same words ([callTitle]), because it is the same entity.
 *  * **the sub-pill is the model** — the catalogue's own name for it, with no verb and no
 *    "fresh thread" filler ([modelLine], [ModelChoices.switchedLine]).
 *
 * A change the person caused is announced on the same pill in blue for a few seconds
 * ([HeaderNotice]); the green action line is never displaced by it.
 *
 * Pure JVM: the words and the notice's clock are proven off-device ([HeaderTest]).
 */
object Header {

    /** The entity's name when neither the host nor a saved profile has given one. */
    const val AGENT = "Hermes-Agent"

    /** The host's name for the built-in connection. A profile with this name is not a choice. */
    const val BUILTIN = "default"

    /**
     * How long a blue notice stays up, fading in the last [FADE_MS]. Five to six seconds:
     * long enough to read a model's name, short enough not to become a second status bar.
     */
    const val NOTICE_MS = 5_500L

    /** The fade at the end of a notice. The notice is gone by [NOTICE_MS]. */
    const val FADE_MS = 400L

    /** The agent's name: the host's, or the Hermes agent's own when nothing has named it. */
    fun agent(stored: String): String = stored.trim().ifBlank { AGENT }

    /**
     * The connection's own name, once. A named profile is called by its label; the built-in
     * `default` is called by the agent's name, because showing the word "default" would name
     * the mechanism instead of the entity. Nothing is appended to either.
     */
    fun connection(agent: String, profile: String): String {
        val p = profile.trim()
        return if (p.isEmpty() || p.equals(BUILTIN, ignoreCase = true)) agent(agent) else p
    }

    /** The big title: exactly the connection's name. */
    fun title(name: String): String = name.trim().uppercase()

    /**
     * The same name for the call screen's notification: one spelling for one entity, so the
     * title and the notification cannot name different things.
     */
    fun callTitle(name: String): String = name.trim()

    /**
     * Whether two names are the same name to a person. Case, spaces and punctuation do not
     * count: "Hermes-Agent", "hermes agent" and "HERMESAGENT" are one name, which is what
     * lets the sub-pill stay silent instead of repeating the title.
     */
    fun sameName(a: String, b: String): Boolean {
        val x = a.lowercase().filter { it.isLetterOrDigit() }
        return x.isNotEmpty() && x == b.lowercase().filter { it.isLetterOrDigit() }
    }

    /** The resting sub-pill: the model's name, plainly. No verb, no filler. */
    fun modelLine(model: String): String = model.trim()

    /** The blue notice's words: exactly the model's name, or exactly the session's title. */
    fun switchNotice(text: String): String = text.trim()
}
