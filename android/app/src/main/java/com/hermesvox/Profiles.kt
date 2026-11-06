package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import com.hermesvox.core.JsonException

/**
 * Profiles — saved connections, one active at a time (#174).
 *
 * The list comes from the host (#371/#384): [ProfilesAsk] asks the agent in conversation on
 * connect and on an explicit refresh, proves every name with `/v1/models`, and [HostProfiles]
 * folds the proven list in — so the user never types the agent's name. The answer is cached:
 * opening the switcher reads it instantly. What
 * stays the user's is the connection itself — a Hermes profile is addressed by its base URL
 * (`https://host/p/<name>`) and needs its own key, so a profile here is the whole connection:
 * name, base URL, key, model, agent name, session scope and provider.
 *
 * The rest of the app keeps reading the one active connection from the ordinary prefs
 * (`url`, `key`, `model`, …). Switching writes the chosen profile into those keys, and the
 * session is rebuilt from them — so **a switch starts a new conversation**:
 * `previous_response_id` resolves inside the profile's own store and must never cross over.
 *
 * A profile is also *how the agent presents*: its model ([model]/[provider]) and, since the
 * per-profile pass, its own voice and look — held in [Profile.own] and applied by
 * [ProfileScope]. Those live in the same prefs the rest of the app reads, so a switch is one
 * write and the being, the next utterance and the next turn all follow.
 *
 * Keys are stored exactly as the single connection's key is: encrypted by [SecureStore]
 * before they reach this list. Nothing here logs, shows or decrypts one.
 *
 * Pure JVM, so the registry's rules are proven off-device.
 */
object Profiles {

    const val PREF_LIST = "profiles"
    const val PREF_ACTIVE = "profile_active"
    /** Set once the one-time per-profile inheritance has run ([ProfileScope.migrate]). */
    const val PREF_SCOPED = "profile_scoped"

    /** The connection prefs a profile owns. Voice and look are per profile too — see
     *  [ProfileScope] and the record's [Profile.own]. The model and provider are here. */
    const val K_URL = "url"
    const val K_KEY = "key"
    const val K_MODEL = "model"
    const val K_AGENT = "agent_name"
    const val K_SCOPE = "session_scope"
    const val K_PROVIDER = "provider"

    /**
     * The longest saved name. #384: the profile list comes from the host, whose names follow
     * the spec's `[a-z0-9][a-z0-9_-]{0,31}` — 32 characters. A smaller UI cap would silently
     * skip a name the host legitimately serves, so the registry's limit is the spec's.
     */
    const val NAME_MAX = 32

    /**
     * One saved connection. [keyEnc] is the stored (encrypted) key, never the plain one.
     *
     * [own] is this profile's own voice and look ([ProfileScope.KEYS]): the settings that used
     * to be device-wide. A key that is absent here is not "unset" — it means the device-wide
     * value applies, which is what every profile written before 0.9.1 relies on. The model is
     * [model]/[provider] above, per profile since #174.
     */
    data class Profile(
        val name: String,
        val url: String,
        val keyEnc: String,
        val model: String = "hermes-agent",
        val agentName: String = "",
        val scope: String = "",
        val provider: String = "",
        val own: Map<String, String> = emptyMap(),
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

    /** The active profile's name, read from the store. Blank when nothing is active yet. */
    fun activeName(get: (String) -> String): String = get(PREF_ACTIVE).trim()

    /** The header label: the agent's name, and the profile's once there is more than one.
     *  The words live in [Header] so the title, the call screen and this one spelling cannot
     *  drift apart. */
    fun headerLabel(agent: String, active: String, count: Int): String =
        Header.title(agent, active, count)

    fun encode(list: List<Profile>): String = Json.encode(list.map {
        linkedMapOf("name" to it.name, "url" to it.url, "key" to it.keyEnc, "model" to it.model,
            "agent" to it.agentName, "scope" to it.scope, "provider" to it.provider,
            // This profile's own voice and look. Absent (or empty) on a record written before
            // 0.9.1, which is what makes the device-wide fallback work for it.
            "own" to it.own)
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
            else Profile(name, url, s("key"), s("model").ifBlank { "hermes-agent" }, s("agent"), s("scope"), s("provider"),
                own = ownOf(o))
        }
    }

    /** The profile's own scoped values; a malformed or absent `own` reads as none. */
    private fun ownOf(o: JObj): Map<String, String> {
        val obj = o["own"] as? JObj ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj.fields) (v as? JStr)?.let { out[k] = it.value }
        return out
    }
}
