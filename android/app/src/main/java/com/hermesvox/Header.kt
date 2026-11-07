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
    const val AGENT = "Hermes"

    /**
     * How long a blue notice stays up, fading in the last [FADE_MS]. Five to six seconds:
     * long enough to read a model's name, short enough not to become a second status bar.
     */
    const val NOTICE_MS = 5_500L

    /** The fade at the end of a notice. The notice is gone by [NOTICE_MS]. */
    const val FADE_MS = 400L

    /** The agent's name: the host's, or the Hermes agent's own when nothing has named it. */
    fun agent(stored: String): String = stored.trim().ifBlank { AGENT }

    /** The big title: the agent, plus the profile's name once there is a choice. */
    fun title(agent: String, active: String, count: Int): String = callTitle(agent, active, count).uppercase()

    /**
     * The same name for the call screen's notification: the entity, and the profile when
     * there is more than one. One spelling for one entity — the title and the notification
     * cannot name different things.
     */
    fun callTitle(agent: String, active: String, count: Int): String {
        val a = agent(agent)
        val p = active.trim()
        return if (count < 2 || p.isEmpty()) a else "$a · $p"
    }

    /** The resting sub-pill: the model's name, plainly. No verb, no filler. */
    fun modelLine(model: String): String = model.trim()

    /** The blue notice's words: exactly the model's name, or exactly the session's title. */
    fun switchNotice(text: String): String = text.trim()
}
