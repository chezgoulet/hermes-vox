package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * HostProfiles — the profiles a Hermes gateway says it serves (#371).
 *
 * This file is now the **fast path** of two (#384, SPEC-conversational-bridge §A). The
 * gateway multiplexes profiles at `/p/<name>/…` and no Hermes route lists them, so the list
 * is normally asked in conversation ([ProfilesAsk]); a `GET <base>/v1/profiles` that answers
 * 200 in the shape below is still used when it is there, so the day Hermes ships the route
 * nothing here changes.
 *
 * The owner's verdict on #174 was that the switcher must populate itself: *"The user should
 * not ever have to put in the name of their Hermes agent… It should always pull from the
 * Hermes host."* So the app asks with the bearer key it already holds, caches the answer,
 * and turns it into registry entries.
 *
 * This half is written to work the day the route lands and to degrade honestly until then:
 *
 *  * **200 with a usable list** → [Answer.Advertised]: new profiles are added without the
 *    user typing anything, an existing profile keeps its address, key, scope and provider,
 *    and a profile the host no longer serves is marked [unavailable] rather than deleted —
 *    a server-side change must not throw the user's key away.
 *  * **404 / 501 / any other status / no answer / a shape we do not know** → [Answer.Silent]:
 *    for this file, silence. The conversational ask ([ProfilesAsk]) then runs, and *its*
 *    failure lines are the ones the user sees; [FALLBACK_LINE] remains this half's own
 *    description of silence and is kept for the #382 tests.
 *
 * The assumed response is deliberately narrow and documented in the PR body so the gateway
 * lane can match it:
 *
 * ```json
 * { "object": "list",
 *   "default": "home",
 *   "profiles": [ { "name": "home", "default": true }, { "name": "work" } ] }
 * ```
 *
 * Every field is optional and read defensively: an entry may carry `id` instead of `name`, the
 * list may sit under `data` (the OpenAI-list shape `/v1/models` uses), the default may be a
 * top-level string or a per-entry boolean, and unknown fields are ignored. A missing name, a
 * non-object entry or an unknown container is *not* salvaged — the whole listing becomes the
 * fallback, because a half-read profile list is worse than the manual path.
 *
 * What this file never does:
 *  * invent a scope (`X-Hermes-Session-Key`) or a provider for a profile the host did not
 *    describe — a new entry is saved with both blank, so nothing is declared on the user's
 *    behalf;
 *  * rewrite an existing profile's endpoint or key — auto-population adds, it never repoints
 *    a saved credential at another host;
 *  * switch profiles. Adding is not switching: a switch ends any live call, and a background
 *    list refresh must never hang up on the user.
 *
 * Pure JVM: the parsing, the merge and the fallback decision are all proven off-device.
 */
object HostProfiles {

    /** The gateway route this half is written against. */
    const val PATH = "/v1/profiles"

    /** The one line the switcher shows when the host did not advertise its profiles. */
    const val FALLBACK_LINE = "this host does not advertise its profiles"

    /** Network budget for the listing: the switcher must settle, not spin. */
    const val TIMEOUT_MS = 8000

    /** A served profile refuses to be bigger than this; anything past it is unreadable. */
    const val BODY_MAX = 64 * 1024

    /** One profile as the host described it. [name] is display only. */
    data class Served(val name: String, val isDefault: Boolean = false)

    /** What the host told us: a usable list, or silence with the status that produced it. */
    sealed class Answer {
        data class Advertised(val served: List<Served>, val default: String) : Answer()
        data class Silent(val code: Int, val why: String) : Answer()
    }

    /** The outcome of folding one [Answer] into the local registry. */
    data class Merge(
        /** The registry after the merge. Nothing is ever removed. */
        val list: List<Profiles.Profile>,
        /** Names added by the host (in served order). */
        val added: List<String> = emptyList(),
        /** Local profiles on this host that the host did not list — kept, marked. */
        val unavailable: List<String> = emptyList(),
        /** Whether [list] differs from the registry that was passed in. */
        val changed: Boolean = false,
    )

    /**
     * The decision for one response: 200 *and* a usable list is [Answer.Advertised];
     * everything else — including a 200 we cannot read — is [Answer.Silent]. Never throws:
     * a proxy's HTML error page, a truncated body or a future shape are all just silence.
     */
    fun answer(code: Int, body: String): Answer {
        if (code != 200) return Answer.Silent(code, "status $code")
        return parse(body) ?: Answer.Silent(200, "unreadable body")
    }

    /** Parse the served list, or null when this is not a list of named profiles. */
    fun parse(text: String): Answer.Advertised? {
        val root = try { Json.parse(text) as? JObj } catch (_: Throwable) { null } ?: return null
        // `profiles` is the assumed name; `data` is the OpenAI-list shape /v1/models already
        // uses, accepted so the gateway lane has room without a client change.
        val items = (root["profiles"] as? JArr)?.items ?: (root["data"] as? JArr)?.items ?: return null
        val seen = mutableSetOf<String>()
        val served = mutableListOf<Served>()
        for (node in items) {
            val o = node as? JObj ?: return null
            val name = (str(o, "name") ?: str(o, "id"))?.trim().orEmpty()
            if (name.isEmpty()) return null
            if (!seen.add(name.lowercase())) continue   // one entry per name, first wins
            served += Served(name, bool(o, "default") == true)
        }
        if (served.isEmpty()) return null
        // The default is whichever the host named: a per-entry flag first, then the top-level
        // string. A name the list does not contain names nothing — do not invent one.
        val flagged = served.firstOrNull { it.isDefault }?.name.orEmpty()
        val named = flagged.ifBlank { str(root, "default")?.trim().orEmpty() }
        val default = served.firstOrNull { it.name.equals(named, ignoreCase = true) }?.name.orEmpty()
        return Answer.Advertised(served.map { it.copy(isDefault = it.name == default) }, default)
    }

    /** The switcher's one line: what the host served, or the honest fallback. */
    fun line(a: Answer): String = when (a) {
        is Answer.Advertised ->
            if (a.served.size == 1) "1 profile from this host" else "${a.served.size} profiles from this host"
        is Answer.Silent -> FALLBACK_LINE
    }

    /** The route a profile proves itself at — the one path every probe appends. */
    const val MODELS_PATH = "/v1/models"

    /**
     * [url] reduced to the address a profile answers at: the host itself for the default
     * profile, `https://host/p/<name>` for a named one.
     *
     * **The address is what remains when the addressing suffix is removed.** A connection is
     * a host, optionally with exactly one `/p/<name>`; everything after that is a route the
     * app appended — `/v1/models`, `/v1/chat/completions`, `/v1/runs`, `/v1/responses`,
     * anything else under `/v1` — and a query or fragment addresses a request, not a
     * connection. So one rule covers every suffix, and it is idempotent:
     * `address(address(url)) == address(url)`. Applying it twice must never eat a profile
     * (`https://host/p/work` keeps its `/p/work`), and a profile *named* `v1` is a name, not
     * a route: only a `/v1` that follows the host or a `/p/<name>` is stripped.
     */
    fun address(url: String): String {
        var u = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        while (true) {
            val cut = routeStart(u)
            if (cut <= 0) return u
            val next = u.substring(0, cut).trimEnd('/')
            if (next == u) return u
            u = next
        }
    }

    /** The address plus the route every probe asks: idempotent, so `…/v1/models` in never
     *  becomes `…/v1/models/v1/models` out. */
    fun modelsUrl(url: String): String {
        val a = address(url)
        return if (a.isBlank()) "" else "$a$MODELS_PATH"
    }

    /**
     * The host base of [url]: the connection with any `/p/<profile>` suffix removed. A Hermes
     * profile is addressed at `https://host/p/<name>`; the default one at the host itself.
     * **The base is what remains when the addressing suffix is removed** ([address] strips
     * the route first, so a URL that already carries an endpoint still yields the host).
     */
    fun rootBase(url: String): String {
        val a = address(url)
        val m = PROFILE_TAIL.find(a) ?: return a
        return a.substring(0, m.range.first).trimEnd('/')
    }

    /**
     * Where [url]'s route begins, or -1 when it carries none. The path must read as a
     * connection — empty or one `/p/<name>` — followed by a `/v1…` tail; anything else (a
     * gateway mounted under its own path, a profile named `v1`) is left alone rather than
     * guessed at.
     */
    private fun routeStart(url: String): Int {
        val scheme = url.indexOf("://")
        if (scheme < 0) return -1
        val pathStart = url.indexOf('/', scheme + 3)
        if (pathStart < 0) return -1
        val m = CONNECTION_PATH.matchEntire(url.substring(pathStart)) ?: return -1
        if (m.groupValues[2].isEmpty()) return -1
        return pathStart + m.groupValues[1].length
    }

    /** The address a served profile answers at: the host itself for the default, else
     *  `https://host/p/<name>` — the addressing rule the registry already documents. */
    fun urlFor(base: String, name: String, isDefault: Boolean): String {
        val b = rootBase(base)
        return if (b.isBlank()) "" else if (isDefault) b else "$b/p/$name"
    }

    /** One registry entry for a served profile. The name is display only; scope and provider
     *  are left undeclared because the host did not report them. */
    fun entry(base: String, keyEnc: String, served: Served, model: String = ""): Profiles.Profile =
        Profiles.Profile(
            name = served.name.trim(),
            url = urlFor(base, served.name.trim(), served.isDefault),
            keyEnc = keyEnc,
            model = model.ifBlank { "hermes-agent" },
            agentName = served.name.trim(),
            scope = "",
            provider = "",
        )

    /**
     * Fold a served list into [local]. Existing entries are never repointed or re-keyed; only
     * a blank display name is filled. Nothing is ever removed — a local profile this host no
     * longer serves stays, and [Merge.unavailable] names it for the switcher.
     *
     * [base] is the connection that was asked. Only profiles on the same host are judged: a
     * second gateway's profiles are none of this answer's business. [keyEnc] is the stored
     * (encrypted) key of that connection — the same credential the host just authenticated,
     * so no key travels to a host it does not belong to.
     */
    fun merge(
        local: List<Profiles.Profile>,
        base: String,
        keyEnc: String,
        a: Answer,
        model: String = "",
    ): Merge {
        val listed = a as? Answer.Advertised ?: return Merge(local)
        if (base.isBlank() || keyEnc.isBlank()) return Merge(local)
        val out = local.toMutableList()
        val added = mutableListOf<String>()
        for (s in listed.served) {
            val name = s.name.trim()
            if (name.isEmpty() || name.length > Profiles.NAME_MAX) continue   // cannot be saved
            val i = out.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (i >= 0) {
                val e = out[i]
                if (e.agentName.isBlank()) out[i] = e.copy(agentName = name)
                continue
            }
            val url = urlFor(base, name, s.isDefault)
            // The same connection may already be saved under a name the user chose. Adding a
            // second entry would be two names for one key, and one of them would be wrong.
            if (out.any { normUrl(it.url) == normUrl(url) }) continue
            out += entry(base, keyEnc, s.copy(name = name), model)
            added += name
        }
        return Merge(out, added, unavailable(out, base, a), changed = out != local)
    }

    /**
     * The local profiles this host did *not* list: same origin, neither their name nor their
     * address matched. They are kept (the user's key is not the host's to throw away) and the
     * switcher marks them unavailable. Nothing is returned when there is no usable answer —
     * silence is not evidence that a profile is gone.
     */
    fun unavailable(local: List<Profiles.Profile>, base: String, a: Answer?): List<String> {
        val listed = a as? Answer.Advertised ?: return emptyList()
        if (base.isBlank()) return emptyList()
        val names = listed.served.map { it.name.trim().lowercase() }.toSet()
        val urls = listed.served.map { normUrl(urlFor(base, it.name.trim(), it.isDefault)) }.toSet()
        return local.filter { p ->
            p.url.isNotBlank() && sameOrigin(p.url, base) &&
                p.name.lowercase() !in names && normUrl(p.url) !in urls
        }.map { it.name }
    }

    /** The same scheme, host and port — the path does not matter. */
    fun sameOrigin(a: String, b: String): Boolean {
        val x = origin(a) ?: return false
        return x == origin(b)
    }

    private fun origin(url: String): String? {
        val s = url.trim()
        val sep = s.indexOf("://")
        if (sep <= 0) return null
        val scheme = s.substring(0, sep).lowercase()
        val authority = s.substring(sep + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank()) return null
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        if (host.isBlank()) return null
        val portText = if (authority.contains(':')) authority.substringAfterLast(':') else ""
        val port = portText.toIntOrNull() ?: when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> return null
        }
        return "$scheme://$host:$port"
    }

    private fun normUrl(url: String): String = address(url)

    /** A connection's own path: the host, or exactly one named profile beneath it. */
    private val CONNECTION_PATH = Regex("""^((?:/p/[^/]+)?)(/v1(?:/.*)?)?$""")

    /** The profile a connection names, when it names one. */
    private val PROFILE_TAIL = Regex("""/p/[^/]+$""")

    private fun str(o: JObj, key: String): String? = (o[key] as? JStr)?.value

    private fun bool(o: JObj, key: String): Boolean? = (o[key] as? JBool)?.value
}
