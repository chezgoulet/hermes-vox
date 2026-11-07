package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * HostEntity — the entity's name comes from the host, never from the user (#T7).
 *
 * The owner's complaint: *"The user should not ever have to put in the name of their Hermes
 * agent… It should always pull from the Hermes host."* Onboarding's "Connect & verify" already
 * makes the real probe (`GET <base>/v1/models` with the bearer), so the answer is right there:
 * the gateway is an OpenAI model list whose first entry names the entity.
 *
 * ```json
 * { "object": "list", "data": [ { "id": "hermes-agent", "object": "model", "owned_by": "hermes" } ] }
 * ```
 *
 * [answer] turns that into one decision:
 *
 *  * **200 with `data[0].id`** → [Answer.Named]: the host named itself, and that name wins over
 *    anything that was typed before. It is stored as the entity's display name (`agent_name`),
 *    not as the request route (`model`).
 *  * **200 with no usable id** → [Answer.Unnamed]: the connection is real, but the body named
 *    nothing. The caller says so in one line ([FALLBACK_LINE]) and keeps the typed value as a
 *    fallback — no name is invented.
 *  * **anything else** → [Answer.Refused]: the probe failed, so nothing is saved.
 *
 * The parse is deliberately narrow: `data[0].id` in the OpenAI list shape, and nothing else.
 * A top-level `id`, a `name`, an empty list or a non-string id is not salvaged, because a
 * guessed entity name is worse than the user's own fallback. Pure JVM.
 */
object HostEntity {

    /** The one line for a connected host that did not name itself. */
    const val FALLBACK_LINE = "connected, but the host did not name itself"

    /** The longest id treated as a usable display name. */
    const val NAME_MAX = 64

    /** What one `GET /v1/models` answer decided. */
    sealed class Answer {
        /** 200 and the host named itself. [name] is the host's, never the typed value. */
        data class Named(val name: String) : Answer()

        /** 200, but the body had no usable id: connected, and the fallback stands. */
        data class Unnamed(val code: Int, val why: String) : Answer()

        /** Not a usable answer: the probe failed, so nothing may be saved. */
        data class Refused(val code: Int, val why: String) : Answer()
    }

    /** The decision for one response. Never throws: a proxy's HTML, a truncated body or a
     *  future shape all degrade to [Answer.Unnamed] (or [Answer.Refused] for a non-200). */
    fun answer(code: Int, body: String): Answer {
        if (code != 200) return Answer.Refused(code, "status $code")
        return parse(body)?.let { Answer.Named(it) } ?: Answer.Unnamed(200, "no usable id")
    }

    /** `data[0].id`, or null when this is not the list shape the gateway returns. */
    fun parse(text: String): String? {
        val root = try { Json.parse(text) as? JObj } catch (_: Throwable) { null } ?: return null
        val first = (root["data"] as? JArr)?.items?.firstOrNull() as? JObj ?: return null
        val id = (first["id"] as? JStr)?.value?.trim().orEmpty()
        if (id.isEmpty() || id.length > NAME_MAX) return null
        return id
    }

    /**
     * The name to show after [a]. The host's name when it gave one; the [fallback] only when
     * the host connected but stayed silent; null when the probe failed (nothing is saved).
     */
    fun name(a: Answer, fallback: String): String? = when (a) {
        is Answer.Named -> a.name
        is Answer.Unnamed -> fallback.trim().ifEmpty { null }
        is Answer.Refused -> null
    }

    /** The line the UI says, or null when the caller's ordinary "Connected" line is enough. */
    fun line(a: Answer): String? = when (a) {
        is Answer.Named -> null
        is Answer.Unnamed -> FALLBACK_LINE
        is Answer.Refused -> null
    }
}
