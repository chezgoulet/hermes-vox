package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * ProfilesAsk — the profile list asked in conversation (#384, SPEC-conversational-bridge §A).
 *
 * A Hermes gateway multiplexes profiles at `/p/<name>/…` but has no route that lists them,
 * and patching Hermes is not on the table. So the app asks the agent itself: one ordinary
 * non-streaming chat turn carrying a reserved marker, and the agent — which does have
 * `hermes profile list` — answers with one fenced `vox-profiles` block.
 *
 * The **list is a hint; a probe is the proof**. A name only becomes a usable profile when
 * `GET <base>/v1/models` (the default) or `GET <base>/p/<name>/v1/models` (a named one)
 * answers 200 with the same bearer. A hallucinated or stale name is therefore marked
 * unavailable rather than added as a broken entry; nothing is ever deleted, repointed or
 * re-keyed.
 *
 * The transport is deliberately *not* a voice turn: the ask goes to
 * `POST <base>/v1/chat/completions`, so the reply is never spoken and never enters the call
 * transcript. Only the assistant's text is read — tool calls, refusals, refusals in prose,
 * HTTP errors and timeouts all land on one stated fallback line.
 *
 * `GET /v1/profiles` (#382) is kept as an opportunistic fast path: try it first, and if it
 * answers 200 in the documented shape, use it. It costs one request and means the day Hermes
 * ships the route nothing here has to change.
 *
 * Everything in this file is pure JVM: the ask text, the request body, the parser, the
 * fallback decision, the probe plan and the cache rules are all proven off-device.
 */
object ProfilesAsk {

    /** The reserved first token, so the agent can key on it and a log line can name it. */
    const val MARKER = "[[vox:profiles]]"

    /** The one fence label the parser accepts. */
    const val LABEL = "vox-profiles"

    /**
     * The exact user turn: a question with a boundary, not a task with an implied latitude.
     *
     * Whoever reads this may be a full operator with a terminal, so the turn says what it
     * is (a question), what an answer looks like, and what must not happen on the way to
     * one. The marker and [LABEL] stay byte-identical — the parser and the skill key on
     * them — and the wording deliberately carries no verb that asks for a change.
     */
    const val ASK =
        "$MARKER This is a question, not a task. Which Hermes profiles does this gateway " +
            "serve to this key? Reply with one fenced $LABEL block and nothing else. Inside " +
            "it: one optional `default=<name>` line and one `profile=<name>` line per " +
            "profile, including the default. Names are lowercase letters, digits, `-` or " +
            "`_`, at most 64 characters. No other lines, no commentary, no table. If you " +
            "cannot answer, say plainly that the list cannot be produced. Do not modify " +
            "this host, its configuration, its profiles, its memories, its sessions or its " +
            "files. Do not run any command that changes them. If producing the list appears " +
            "to need a change to the host, do not make it: say what would be needed instead."

    /** The OpenAI-shaped chat route on the gateway the app already holds. */
    const val PATH = "/v1/chat/completions"

    /** The ask's whole network budget. */
    const val TIMEOUT_MS = 45_000

    /** One probe. A slow profile is unavailable, not a reason to hang the refresh. */
    const val PROBE_TIMEOUT_MS = 10_000

    /** At most eight probes in flight (SPEC §A: "at most 8 at a time"). */
    const val PROBE_CONCURRENCY = 8

    /** A manual refresh inside this window of the last ask is refused. */
    const val REFRESH_MIN_MS = 60_000L

    /**
     * A cached answer younger than this is rendered with its age and no turn is sent.
     * The ask is a turn the user pays for, so a connect inside the window reads the cache
     * too; only an explicit refresh (or a connect after the window) asks again.
     */
    const val CACHE_FRESH_MS = 15 * 60_000L

    /** A chat reply is a small JSON document; anything past this is not a profile list. */
    const val BODY_MAX = 256 * 1024

    const val DEFAULT_MODEL = "hermes-agent"

    // The failure lines, each naming one distinct failure (SPEC §A "Failure, stated plainly").
    const val FALLBACK_NO_BLOCK = "this host did not answer with a profile list"
    const val FALLBACK_EMPTY_BLOCK = "the host's reply had a block but no profile names"
    const val FALLBACK_TIMEOUT = "the host did not answer in time"

    /**
     * A host profile name the client will accept. Hermes enforces lowercase ids with
     * `[a-z0-9][a-z0-9_-]{0,63}` (`_PROFILE_NAME_RULE`), so 64 characters is the host's own
     * ceiling. Everything else is dropped before it reaches the registry: no slashes, no
     * dots, no whitespace, no traversal (SPEC §A "The reply", "Security").
     */
    private val NAME = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    // A fenced block whose info string is exactly the label. Other fences (an example, a
    // command) are prose and are ignored.
    private val OPEN = Regex("""^\s*```[ \t]*vox-profiles[ \t]*$""")
    private val CLOSE = Regex("""^\s*```[ \t]*$""")

    /** Where a usable list came from. Both are "this host"; the log names which one. */
    enum class Source { FAST_PATH, CONVERSATION }

    /** The four ways a conversational ask fails, each with the one line the switcher says. */
    enum class Failure {
        /** No labelled block, or prose instead of one. */
        NO_BLOCK,

        /** A block was present but contained no profile names at all. */
        EMPTY_BLOCK,

        /** Names were present but every one failed the charset. */
        UNUSABLE,

        /** The 45 s budget expired. */
        TIMEOUT,
    }

    /** The outcome of one ask: a usable list, or a stated failure with its counts. */
    sealed class Result {
        data class Named(
            val served: List<HostProfiles.Served>,
            val default: String = "",
            val source: Source = Source.CONVERSATION,
            /** Names dropped for the charset — kept for the log, never for a profile. */
            val dropped: Int = 0,
            /** Non-empty lines in the block — the diagnosis when [dropped] is not enough. */
            val seen: Int = 0,
        ) : Result()

        data class Failed(
            val failure: Failure,
            val code: Int = -1,
            /** Names the reply carried but the charset rejected. */
            val dropped: Int = 0,
            /** Non-empty lines seen in the labelled block. */
            val seen: Int = 0,
        ) : Result()
    }

    /** A parsed block: the names that survived, the default, and how many lines were seen. */
    data class Block(
        val served: List<HostProfiles.Served>,
        val default: String,
        val dropped: Int = 0,
        val seen: Int = 0,
    )

    /**
     * What a reply's text parsed as. [Echoed] is "a block was there but named nothing
     * usable"; [None] is "no block at all". The two say different things to the user, and
     * [Echoed.dropped] separates "no names offered" from "every name rejected".
     */
    sealed class Parsed {
        data class Ok(val block: Block) : Parsed()
        data class Echoed(val dropped: Int, val seen: Int) : Parsed()
        object None : Parsed()
    }

    /**
     * The exact POST body: one user turn, non-streaming, on the model route the connection
     * already uses. No tools, no temperature, no history — the ask is self-contained.
     */
    fun requestBody(model: String = DEFAULT_MODEL): String = Json.encode(
        linkedMapOf(
            "model" to model.ifBlank { DEFAULT_MODEL },
            "messages" to listOf(linkedMapOf("role" to "user", "content" to ASK)),
            "stream" to false,
        )
    )

    /**
     * The assistant's text from an OpenAI chat completion, or null when there is none.
     * Tool calls are not text; a refusal is text and is left for [parse] to reject.
     */
    fun assistantText(body: String): String? {
        val root = try { Json.parse(body) as? JObj } catch (_: Throwable) { null } ?: return null
        val first = (root["choices"] as? JArr)?.items?.firstOrNull() as? JObj ?: return null
        val message = first["message"] as? JObj ?: return null
        return when (val content = message["content"]) {
            is JStr -> content.value
            // The multimodal shape: [{type:"text", text:"…"}]. Only the text parts are read.
            is JArr -> content.items.mapNotNull { (it as? JObj)?.let { o -> (o["text"] as? JStr)?.value } }
                .joinToString("")
                .ifBlank { null }
            else -> null
        }
    }

    /**
     * Parse one assistant reply into a block, or say which kind of nothing it was.
     *
     * Rules, and the interpretations this client chose:
     *  * exactly one fenced block labelled [LABEL] — none, an unterminated one, or two of
     *    them are [Parsed.None] (two blocks are ambiguous, and guessing would be inventing);
     *  * the lines an agent plausibly writes are normalised before they are read: a leading
     *    bullet or `◆`, `=` or `:` as the separator, wrapping backticks/quotes, a trailing
     *    `,`/`;`, and a leading `◆` on the value;
     *  * names are lowercased — Hermes enforces lowercase ids, so `Geek` is `geek`, not an
     *    illegal name — then must match [NAME] (1–64 characters);
     *  * `default=<name>` alone is a one-profile host: that name is the whole served list;
     *  * when a block has no `profile=`/`default=` line at all, each surviving bare line
     *    that matches [NAME] is read as a name; when keyed lines exist, bare lines are
     *    ignored rather than guessed at. The probe still proves every name before it is saved;
     *  * duplicates are read once, first wins;
     *  * a default only means something when the same reply lists that profile — otherwise
     *    the block's names stand and no default is claimed;
     *  * prose around the block is ignored. Prose *instead of* a block reaches here as
     *    [Parsed.None], which is the fallback case.
     */
    fun parse(text: String): Parsed {
        val blocks = mutableListOf<List<String>>()
        // An agent on any platform may end its lines with CRLF; the block is the same block.
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            if (!OPEN.matches(lines[i])) { i++; continue }
            val content = mutableListOf<String>()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                if (CLOSE.matches(lines[j])) { closed = true; break }
                content += lines[j]
                j++
            }
            // An unterminated fence is not a reply we can trust; nor is a second block.
            if (!closed) return Parsed.None
            blocks += content
            i = j + 1
        }
        if (blocks.size != 1) return Parsed.None

        val content = blocks.single()
        val seen = content.count { it.trim().isNotEmpty() }
        val names = LinkedHashSet<String>()
        var dropped = 0
        var namedDefault: String? = null
        var keyed = 0

        for (raw in content) {
            val line = unbulleted(raw)
            if (line.isEmpty()) continue
            val sep = separator(line)
            if (sep < 0) continue                      // a bare line: read only by the fallback
            val key = line.substring(0, sep).trim().lowercase()
            if (key != "profile" && key != "default") continue   // no other line types honoured
            keyed++
            val value = cleanValue(line.substring(sep + 1)).lowercase()
            when (key) {
                "profile" -> if (NAME.matches(value)) names.add(value) else dropped++
                "default" -> when {
                    namedDefault != null -> Unit       // zero or one line: extras ignored
                    NAME.matches(value) -> namedDefault = value
                    else -> dropped++
                }
            }
        }

        // No keyed line at all: the agent may have written the names bare, one per line.
        // This is only a hint — every name is still probed before it is saved.
        if (keyed == 0) {
            for (raw in content) {
                val line = cleanValue(unbulleted(raw)).lowercase()
                if (line.isNotEmpty() && NAME.matches(line)) names.add(line)
            }
        }

        // A one-profile host may answer with only its default line.
        if (names.isEmpty() && namedDefault != null) names.add(namedDefault!!)
        if (names.isEmpty()) return Parsed.Echoed(dropped, seen)
        // A default names a profile in this reply, or it names nothing: never invent one.
        val default = namedDefault?.takeIf { names.contains(it) }.orEmpty()
        return Parsed.Ok(Block(names.map { HostProfiles.Served(it, it == default) }, default, dropped, seen))
    }

    private val BULLET = Regex("^[-*+]\\s+")
    private val QUOTE = mapOf(
        '`' to '`', '"' to '"', '\'' to '\'', '“' to '”', '‘' to '’',
    )

    /** Strip a leading bullet and the `◆` marker `hermes profile list` prints. */
    private fun unbulleted(raw: String): String {
        var s = BULLET.replace(raw.trim(), "").trim()
        if (s.startsWith("◆")) s = s.removePrefix("◆").trim()
        return s
    }

    /** The key/value separator an agent may use; -1 when the line is not keyed. */
    private fun separator(line: String): Int {
        val eq = line.indexOf('=')
        val colon = line.indexOf(':')
        return when {
            eq < 0 -> colon
            colon < 0 -> eq
            else -> minOf(eq, colon)
        }
    }

    /** Strip wrapping backticks/quotes and a trailing `,`/`;` from a value or bare line. */
    private fun cleanValue(raw: String): String {
        var v = raw.trim()
        if (v.startsWith("◆")) v = v.removePrefix("◆").trim()
        fun trimTail() {
            while (v.isNotEmpty() && (v.last() == ',' || v.last() == ';')) v = v.dropLast(1).trimEnd()
        }
        trimTail()
        var changed = true
        while (changed && v.length >= 2) {
            val close = QUOTE[v.first()]
            if (close != null && v.last() == close) {
                v = v.substring(1, v.length - 1).trim()
                if (v.startsWith("◆")) v = v.removePrefix("◆").trim()
                trimTail()
            } else changed = false
        }
        return v
    }

    /**
     * The decision for one chat exchange. [status] is -1 when there was no answer at all;
     * [timedOut] distinguishes the 45 s budget expiring from any other transport failure,
     * because the spec gives those two different lines.
     */
    fun fromChat(status: Int, body: String, timedOut: Boolean = false): Result {
        if (timedOut) return Result.Failed(Failure.TIMEOUT)
        if (status != 200) return Result.Failed(Failure.NO_BLOCK, status)
        val text = assistantText(body) ?: return Result.Failed(Failure.NO_BLOCK, status)
        return when (val p = parse(text)) {
            is Parsed.Ok -> Result.Named(
                p.block.served, p.block.default, Source.CONVERSATION, p.block.dropped, p.block.seen,
            )
            is Parsed.Echoed ->
                if (p.dropped > 0) Result.Failed(Failure.UNUSABLE, status, p.dropped, p.seen)
                else Result.Failed(Failure.EMPTY_BLOCK, status, 0, p.seen)
            Parsed.None -> Result.Failed(Failure.NO_BLOCK, status)
        }
    }

    /**
     * The opportunistic #382 route: a 200 in the documented shape is used as-is; anything
     * else is silence, and the caller falls through to [fromChat].
     */
    fun fastPath(code: Int, body: String): Result.Named? {
        val a = HostProfiles.answer(code, body) as? HostProfiles.Answer.Advertised ?: return null
        // The same charset rule as the conversational list: a host-supplied name never
        // carries a slash or a dot into a probe URL or the registry. If nothing survives,
        // this is not a usable fast-path answer and the conversational ask takes over.
        val served = a.served.filter { allowed(it.name) }
        if (served.isEmpty()) return null
        val default = served.firstOrNull { it.name == a.default }?.name.orEmpty()
        return Result.Named(served, default, Source.FAST_PATH)
    }

    /** Whether [name] is the spec's profile-name charset. */
    fun allowed(name: String): Boolean = NAME.matches(name)

    /**
     * What one refresh does after the host's own route was tried. [Answered] ends it: the
     * conversational turn is not sent at all. [Ask] is the only branch that may send one.
     * Kept here, beside [fastPath], so the suppression is a pure decision a test can pin.
     */
    sealed class Step {
        data class Answered(val result: Result.Named) : Step()
        object Ask : Step()
    }

    /** The route comes first, always: a fast-path answer means the ask never happens. */
    fun step(fastCode: Int, fastBody: String): Step =
        fastPath(fastCode, fastBody)?.let { Step.Answered(it) } ?: Step.Ask

    // ---- probes: the list is a hint, a probe is the proof ----

    /** One proof: the URL a served profile must answer `/v1/models` at. */
    data class Probe(val name: String, val url: String, val isDefault: Boolean)

    /**
     * The probe for each served name. The default answers at the host itself; a named
     * profile at `/p/<name>/…` — the addressing rule the registry already documents — and
     * each is asked at exactly one `/v1/models`. Built through [HostProfiles.modelsUrl], so
     * the plan is idempotent: `probes(base, served)` and `probes(base + "/v1/models",
     * served)` are the same URLs, in both directions.
     */
    fun probes(base: String, served: List<HostProfiles.Served>): List<Probe> = served.map {
        Probe(
            name = it.name,
            url = HostProfiles.modelsUrl(HostProfiles.urlFor(base, it.name, it.isDefault)),
            isDefault = it.isDefault,
        )
    }

    /** At most [PROBE_CONCURRENCY] probes per wave; the caller runs the waves in order. */
    fun batches(probes: List<Probe>, size: Int = PROBE_CONCURRENCY): List<List<Probe>> =
        probes.chunked(size.coerceAtLeast(1))

    /** A probe's verdict: a 200 is the only proof a profile is usable. */
    fun probeSucceeded(status: Int): Boolean = status == 200

    /** The registry after one proven answer, with the line the switcher should say. */
    data class Applied(
        val merge: HostProfiles.Merge,
        /** The names that answered their probe, in served order — what "Add" may offer. */
        val usable: List<HostProfiles.Served>,
        /** Served names whose probe failed — named in the line, never added. */
        val unreachable: List<String>,
        val added: Int,
        val line: String,
    )

    /**
     * Fold only the *proven* names into [local]. [reachable] is keyed by name and true only
     * for a 200. A listed name that did not answer is reported in [Applied.unreachable];
     * a saved profile it matches is kept and marked unavailable by [HostProfiles.merge],
     * exactly the #382 rule — nothing is removed, repointed or re-keyed.
     */
    fun settle(
        local: List<Profiles.Profile>,
        base: String,
        keyEnc: String,
        result: Result.Named,
        model: String = "",
        reachable: Map<String, Boolean>,
    ): Applied {
        val usable = result.served.filter { reachable[it.name] == true }
        val unreachable = result.served.filter { reachable[it.name] != true }.map { it.name }
        val default = usable.firstOrNull { it.name == result.default }?.name.orEmpty()
        val merged = HostProfiles.merge(
            local, base, keyEnc,
            HostProfiles.Answer.Advertised(usable, default), model,
        )
        return Applied(
            merged, usable, unreachable, merged.added.size,
            line(result, merged.added.size, unreachable.size),
        )
    }

    /**
     * The switcher's one line for a result. A partial refresh names both halves; a full one
     * is the familiar count. Each failure names which failure it was, never an empty list.
     */
    fun line(result: Result, added: Int = 0, unreachable: Int = 0): String = when (result) {
        is Result.Failed -> when (result.failure) {
            Failure.NO_BLOCK -> FALLBACK_NO_BLOCK
            Failure.EMPTY_BLOCK -> FALLBACK_EMPTY_BLOCK
            Failure.UNUSABLE ->
                "the host named ${count(result.dropped, "profile")}, none in a usable form"
            Failure.TIMEOUT -> FALLBACK_TIMEOUT
        }
        is Result.Named ->
            if (unreachable > 0) "${count(added, "profile")} added · $unreachable not reachable"
            else HostProfiles.line(HostProfiles.Answer.Advertised(result.served, result.default))
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    // ---- cache and refresh ----

    /** What the user did. Only two of these may ever cost a turn. */
    enum class AskKind { CONNECT, REFRESH, SWITCHER_OPEN }

    /** What the caller should do now. */
    enum class AskDecision {
        /** Run the ask. */
        NOW,

        /** Render the cache instantly — the switcher opened, or a fresh answer exists. */
        READ_CACHE,

        /** A manual refresh inside [REFRESH_MIN_MS] of the last ask: refused. */
        REFUSE_RECENT,

        /** A call is live: remember it and run when the call ends. */
        DEFER,
    }

    /**
     * The whole refresh policy in one pure function:
     *  * opening the switcher never asks — it reads the cache (the reason a cache exists);
     *  * a live call defers any ask, so a refresh can never disturb a conversation;
     *  * a connect asks only when there is no answer younger than [CACHE_FRESH_MS]: a fresh
     *    cache is rendered with its age and costs nothing;
     *  * a manual refresh is what sends the turn, and asks only when the last ask is at
     *    least [REFRESH_MIN_MS] old.
     *
     * [lastAskedAt] is the cache's clock: 0 means the host was never asked, so there is no
     * answer to render.
     */
    fun decide(kind: AskKind, lastAskedAt: Long, now: Long, callLive: Boolean): AskDecision {
        if (kind == AskKind.SWITCHER_OPEN) return AskDecision.READ_CACHE
        if (callLive) return AskDecision.DEFER
        val fresh = lastAskedAt > 0L && now - lastAskedAt < CACHE_FRESH_MS
        if (kind == AskKind.CONNECT) return if (fresh) AskDecision.READ_CACHE else AskDecision.NOW
        return if (lastAskedAt <= 0L || now - lastAskedAt >= REFRESH_MIN_MS) AskDecision.NOW
        else AskDecision.REFUSE_RECENT
    }

    /** `profiles from this host · checked 4 min ago` — freshness shown, never hidden. */
    fun freshness(askedAt: Long, now: Long): String {
        val mins = (now - askedAt).coerceAtLeast(0L) / 60_000L
        val age = when {
            mins < 1L -> "just now"
            mins < 60L -> "$mins min ago"
            else -> "${mins / 60L} h ago"
        }
        return "profiles from this host · checked $age"
    }
}
