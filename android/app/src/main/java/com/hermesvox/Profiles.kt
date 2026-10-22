package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import com.hermesvox.core.JsonException

/**
 * Profiles — saved connections, one active at a time (#174).
 *
 * Client-side only: `GET /v1/models` returns one entity name, not a profile list, so the
 * gateway cannot be asked which profiles it serves (docs/PLAN-0.9.1.md §4). A Hermes profile
 * is addressed by its base URL (`https://host/p/<name>`) and needs its own key, so a profile
 * here is the whole connection: name, base URL, key, model, agent name, session scope and
 * provider.
 *
 * The rest of the app keeps reading the one active connection from the ordinary prefs
 * (`url`, `key`, `model`, …). Switching writes the chosen profile into those keys, and the
 * session is rebuilt from them — so **a switch starts a new conversation**:
 * `previous_response_id` resolves inside the profile's own store and must never cross over.
 *
 * Keys are stored exactly as the single connection's key is: encrypted by [SecureStore]
 * before they reach this list. Nothing here logs, shows or decrypts one.
 *
 * Pure JVM, so the registry's rules are proven off-device.
 */
object Profiles {

    const val PREF_LIST = "profiles"
    const val PREF_ACTIVE = "profile_active"

    /** The connection prefs a profile owns. Everything else (voice, models, look) is device-wide. */
    const val K_URL = "url"
    const val K_KEY = "key"
    const val K_MODEL = "model"
    const val K_AGENT = "agent_name"
    const val K_SCOPE = "session_scope"
    const val K_PROVIDER = "provider"

    const val NAME_MAX = 24

    /** One saved connection. [keyEnc] is the stored (encrypted) key, never the plain one. */
    data class Profile(
        val name: String,
        val url: String,
        val keyEnc: String,
        val model: String = "hermes-agent",
        val agentName: String = "",
        val scope: String = "",
        val provider: String = "",
    )

    /** What `GET <base>/v1/models` said about a profile, in the words the user needs. */
    enum class Check(val message: String) {
        OK("Connected"),
        NOT_SERVED("Not served — this gateway has no such profile"),
        KEY_REJECTED("Key rejected — each profile needs its own key"),
        UNREACHABLE("Can't reach the gateway"),
        OTHER("The gateway answered with an error"),
    }

    /** Map the `/v1/models` status (or -1 for no answer) to a [Check]. The gateway answers
     *  404 `Unknown or unconfigured profile` for a `/p/<name>` it does not serve, and 401
     *  when the key is missing or wrong for that profile. */
    fun check(code: Int): Check = when (code) {
        200 -> Check.OK
        401, 403 -> Check.KEY_REJECTED
        404 -> Check.NOT_SERVED
        -1 -> Check.UNREACHABLE
        else -> Check.OTHER
    }

    /** Why [name] cannot be saved into [existing] (null when it can). [editing] is the
     *  profile being renamed, which may keep its own name. */
    fun nameError(name: String, existing: List<Profile>, editing: String? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Give the profile a name"
        if (n.length > NAME_MAX) return "Keep the name under $NAME_MAX characters"
        if (existing.any { it.name.equals(n, ignoreCase = true) && !it.name.equals(editing, ignoreCase = true) })
            return "A profile called \"$n\" already exists"
        return null
    }

    /** The current single connection, as a profile named [name]. */
    fun fromPrefs(name: String, get: (String) -> String): Profile = Profile(
        name = name.trim(),
        url = get(K_URL).trim(),
        keyEnc = get(K_KEY),
        model = get(K_MODEL).ifBlank { "hermes-agent" },
        agentName = get(K_AGENT),
        scope = get(K_SCOPE),
        provider = get(K_PROVIDER),
    )

    /** The pref writes that make [p] the active connection. */
    fun toPrefs(p: Profile): Map<String, String> = linkedMapOf(
        K_URL to p.url, K_KEY to p.keyEnc, K_MODEL to p.model.ifBlank { "hermes-agent" },
        K_AGENT to p.agentName, K_SCOPE to p.scope, K_PROVIDER to p.provider,
    )

    /** Add [p], or replace the profile named [replacing] (default: the same name). */
    fun upsert(list: List<Profile>, p: Profile, replacing: String = p.name): List<Profile> {
        val i = list.indexOfFirst { it.name.equals(replacing, ignoreCase = true) }
        return if (i < 0) list + p else list.toMutableList().also { it[i] = p }
    }

    fun remove(list: List<Profile>, name: String): List<Profile> =
        list.filterNot { it.name.equals(name, ignoreCase = true) }

    fun find(list: List<Profile>, name: String): Profile? =
        list.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The header label: the agent's name, and the profile's once there is more than one. */
    fun headerLabel(agent: String, active: String, count: Int): String {
        val a = agent.uppercase()
        return if (count < 2 || active.isBlank()) a else "$a · ${active.uppercase()}"
    }

    fun encode(list: List<Profile>): String = Json.encode(list.map {
        linkedMapOf("name" to it.name, "url" to it.url, "key" to it.keyEnc, "model" to it.model,
            "agent" to it.agentName, "scope" to it.scope, "provider" to it.provider)
    })

    /** Decode the saved list; anything unreadable is dropped rather than crashing the app. */
    fun decode(json: String): List<Profile> {
        if (json.isBlank()) return emptyList()
        val arr = try { Json.parse(json) as? JArr } catch (_: JsonException) { null } ?: return emptyList()
        return arr.items.mapNotNull { n ->
            val o = n as? JObj ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JStr)?.value.orEmpty()
            val name = s("name"); val url = s("url")
            if (name.isBlank() || url.isBlank()) null
            else Profile(name, url, s("key"), s("model").ifBlank { "hermes-agent" }, s("agent"), s("scope"), s("provider"))
        }
    }
}
