package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * Sessions — the conversations the host already has, and how one is picked up (#437).
 *
 * The host has had this all along: `GET /api/sessions` lists what it holds for this key —
 * telegram sessions, `api_server` sessions, and the `vox_…` ones this app wrote — and
 * `GET /api/sessions/{id}/messages` is their transcript. Vox used to have no reference to
 * either, so a conversation started at a desk was invisible from the phone.
 *
 * Three facts decide everything in this file:
 *
 *  * **The list is a property of the gateway, not of a profile.** A Hermes profile is
 *    addressed at `https://host/p/<name>`; the session list answers at the host. [rootBase]
 *    is that rule, and it is [HostProfiles.rootBase] — the same one the profile listing uses,
 *    because it is the same fact. The key is the one the app already holds for the profile.
 *  * **Adopting a session is setting a field.** `POST /v1/runs` ranks an explicit body
 *    `session_id` above the response chain and above the declared key, so pointing Vox at an
 *    existing session is [com.hermesvox.core.HermesSession.adoptConversation] and nothing else.
 *    Nothing is copied and nothing is forked.
 *  * **The transcript belongs to the host.** Another surface may be writing it while the
 *    phone reads it. That is the feature, and it is the trap: [merge] is the only way a
 *    transcript reaches the screen, so what the app already drew can never be appended twice.
 *
 * What the card must say when it has nothing to show is part of the contract, not a detail:
 * a key the host refused, a host that did not answer and a host with no sessions are three
 * different facts and get three different lines ([KEY_REJECTED], [NO_ANSWER], [EMPTY]).
 * A fourth covers the transcript itself ([TRANSCRIPT_FAILED]) — the list may answer while one
 * session's messages do not.
 *
 * Pure JVM: the parsing, the ordering, the merge, the lines and the age are proven off-device
 * ([SessionsTest]); the Android edge is one GET ([SessionsClient]) and one sheet.
 */
object Sessions {

    /** The host's list route. */
    const val PATH = "/api/sessions"

    /** The command, as the sheet lists it and as a person types it. */
    const val COMMAND = "/sessions"

    /** One page of the list. Long lists page with `has_more`/`offset`; they are never cut short. */
    const val LIMIT = 20

    /** The list's whole network budget: a card must settle, not spin. */
    const val TIMEOUT_MS = 8_000

    /**
     * How stale a resumed transcript may be before returning to the app re-fetches it. The
     * other surface is writing to the same session while the phone is away; a gap long enough
     * for a desk turn is the moment the screen is most likely to be behind.
     */
    const val GAP_MS = 30_000L

    // ---- the four lines a card may show instead of rows --------------------------------
    //
    // The release rule: a state names its cause. These are the only failure lines, so the
    // card, the status pill and the field log cannot drift into four spellings of one fact.

    /** 401/403 on the list: the key the profile uses was not accepted at the host. */
    const val KEY_REJECTED = "the key was not accepted for the host's session list"

    /** No answer at all — a transport failure, a timeout, or a status we cannot read. */
    const val NO_ANSWER = "the host did not answer"

    /** The host answered, and it has nothing for this key. */
    const val EMPTY = "no sessions on this host"

    /** The list answered but one session's transcript did not. */
    const val TRANSCRIPT_FAILED = "the host did not answer with this session's transcript"

    /** A session the host did not title. Said, not left blank. */
    const val UNTITLED = "an untitled session"

    /** One session as the host listed it. */
    data class Row(
        val id: String,
        val title: String = "",
        val source: String = "",
        val startedAt: String = "",
        val endedAt: String = "",
        /**
         * The host's own message count, when it states one, else null. Null is *not* zero: the
         * list route documents `id/source/user_id/model/title/started_at/ended_at` and no count,
         * so a build that printed "0 messages" for every row would be inventing a number (and
         * fetching twenty transcripts to count them would be inventing a cost). A count the
         * host does state — under any of the names [count] accepts — is shown.
         */
        val messages: Int? = null,
    ) {
        /** The row's title, or the plain statement that there isn't one. */
        val label: String get() = title.trim().ifBlank { UNTITLED }

        /** When this session was last active: its end, else its start. 0 when neither parsed. */
        val activityAt: Long
            get() = parseTime(endedAt).takeIf { it > 0 } ?: parseTime(startedAt)
    }

    /** One page of the list, in the host's own paging terms. */
    data class Page(
        val rows: List<Row>,
        val hasMore: Boolean = false,
        val offset: Int = 0,
        val limit: Int = rows.size,
    )

    /** What one session's transcript said. */
    data class Message(
        val role: String,
        val content: String,
        /** The host's timestamp, as it wrote it; "" when it wrote none. */
        val createdAt: String = "",
        /** The host's own message id, when the shape has one. */
        val id: String = "",
    )

    /** The list, or the reason there is none. */
    sealed class Listing {
        data class Ok(val page: Page) : Listing()
        object Empty : Listing()
        object KeyRejected : Listing()
        object NoAnswer : Listing()
    }

    /** One session's transcript, or the reason there is none. */
    sealed class Transcript {
        data class Ok(val messages: List<Message>) : Transcript()
        object KeyRejected : Transcript()
        object NoAnswer : Transcript()
    }

    /** One line of the crawl, built from a host message. */
    data class Line(val text: String, val kind: CrawlHistory.Kind)

    /**
     * The client side of an adopted session: which session this phone is talking in, the
     * transcript the host last served for it, and when that was.
     *
     * It is an object rather than three fields on the activity for one reason: the rules
     * around it are things to prove. Adopting starts with nothing from the session being left;
     * a switch, a new conversation and a saved connection clear **both** the id and the
     * listing, because sessions belong to a profile and another host's answer must never be
     * shown as this one's; and [due] is the gap rule that makes coming back to the app read
     * the transcript again. [merge] is the only way messages reach [resumed], so nothing can
     * be appended to what the app believes it already has.
     */
    class Adoption {

        /** The session in use, or null when this app is writing its own conversation. */
        var row: Row? = null
            private set

        /** The transcript as last merged — never appended to directly. */
        var resumed: List<Message> = emptyList()
            private set

        /** When the transcript was last asked for (0 = never, which is always due). */
        var fetchedAt: Long = 0L
            private set

        /** The id in force ("" when none) — what the next turn would be filed under. */
        val id: String get() = row?.id.orEmpty()

        val active: Boolean get() = row != null

        /** Pick up [r]. Whatever the previous session's transcript was, it is not this one's. */
        fun adopt(r: Row) {
            row = r
            resumed = emptyList()
            fetchedAt = 0L
        }

        /** Forget the session and everything read from it. */
        fun clear() {
            row = null
            resumed = emptyList()
            fetchedAt = 0L
        }

        /** Record when the transcript was asked for — at the start, so a slow answer still
         *  throttles the next foreground return. */
        fun markFetched(now: Long) {
            fetchedAt = now
        }

        /** Fold one fetched transcript in, and return what to draw. */
        fun settle(messages: List<Message>): List<Message> {
            resumed = merge(resumed, messages)
            return resumed
        }

        /**
         * Record this app's own reply while a session is adopted. It carries no id and no
         * timestamp — the host has not written it yet — so [merge] drops it once the host
         * serves the same words, and the screen shows one copy either way.
         */
        fun echo(text: String) {
            val said = text.trim()
            if (said.isEmpty()) return
            resumed = merge(resumed + Message(role = "assistant", content = said), resumed)
        }

        /** Whether a foreground return should read the transcript again (the gap rule). */
        fun due(now: Long): Boolean = active && now - fetchedAt >= GAP_MS
    }

    // ---- where the list is asked ------------------------------------------------------

    /**
     * The gateway behind [url]: the connection with any `/p/<profile>` suffix removed.
     *
     * A session list is a property of the host — the same host that multiplexes profiles — so
     * a profile-scoped base (`https://host/p/home`) must ask `https://host/api/sessions`. The
     * rule itself is [HostProfiles.rootBase]: it is one fact, and a second copy of it would be
     * a second answer the day Hermes changes its profile addressing.
     */
    fun rootBase(url: String): String = HostProfiles.rootBase(url)

    /** `GET /api/sessions?limit=…&offset=…` on the host. */
    fun listPath(limit: Int = LIMIT, offset: Int = 0): String =
        "$PATH?limit=${limit.coerceAtLeast(1)}&offset=${offset.coerceAtLeast(0)}"

    /**
     * `GET /api/sessions/{id}/messages`. The id is the host's, so it travels as one path
     * segment and never raw: an id is ASCII today, but concatenating untrusted text into a
     * URL is how a query or a traversal gets in.
     */
    fun messagesPath(id: String): String =
        "$PATH/" + java.net.URLEncoder.encode(id.trim(), "UTF-8").replace("+", "%20") + "/messages"

    // ---- what the host said -----------------------------------------------------------

    /**
     * The list's verdict. 401/403 is the key being refused — a fact about the credential, not
     * about the host being down — and it is never rendered as an empty list. Anything else
     * that is not a readable 200 is [Listing.NoAnswer]: the host answered, but not with a list,
     * and the four lines above are the whole vocabulary.
     */
    fun listing(f: SessionsClient.Fetched): Listing = when {
        f.code == 401 || f.code == 403 -> Listing.KeyRejected
        f.code != 200 -> Listing.NoAnswer
        else -> {
            val page = parsePage(f.body) ?: return Listing.NoAnswer
            if (page.rows.isEmpty()) Listing.Empty else Listing.Ok(page)
        }
    }

    /** One session's transcript, with the same three outcomes as the list. */
    fun transcript(f: SessionsClient.Fetched): Transcript = when {
        f.code == 401 || f.code == 403 -> Transcript.KeyRejected
        f.code != 200 -> Transcript.NoAnswer
        else -> parseMessages(f.body)?.let { Transcript.Ok(it) } ?: Transcript.NoAnswer
    }

    /**
     * `{object, data[], limit, offset, has_more}`. A body that is not that shape is null —
     * a proxy's HTML page and a future shape are both "not a list", and the card says so
     * rather than showing a blank card that looks like an empty host.
     */
    fun parsePage(text: String): Page? {
        val root = obj(text) ?: return null
        val items = (root["data"] as? JArr)?.items ?: return null
        val rows = items.mapNotNull { node ->
            val o = node as? JObj ?: return@mapNotNull null
            val id = str(o, "id").trim()
            if (id.isEmpty()) return@mapNotNull null
            Row(
                id = id,
                title = str(o, "title"),
                source = str(o, "source"),
                startedAt = str(o, "started_at"),
                endedAt = str(o, "ended_at"),
                messages = count(o),
            )
        }
        val limit = num(root, "limit") ?: rows.size
        val offset = num(root, "offset") ?: 0
        // The host states has_more; when it does not, a full page is the honest inference
        // (there may be more), and a short page is the end.
        val hasMore = bool(root, "has_more") ?: (limit in 1..rows.size)
        return Page(rows, hasMore, offset, limit)
    }

    /**
     * `{object, session_id, data[], pagination}`. [parseMessages] reads `data` — the same
     * OpenAI-list container `/v1/models` and the session list use — and nothing else: a body
     * with no `data` array is not a transcript.
     */
    fun parseMessages(text: String): List<Message>? {
        val root = obj(text) ?: return null
        val items = (root["data"] as? JArr)?.items ?: return null
        return items.mapNotNull { node ->
            val o = node as? JObj ?: return@mapNotNull null
            val role = str(o, "role").trim().lowercase()
            if (role.isEmpty()) return@mapNotNull null
            Message(
                role = role,
                content = contentOf(o["content"]),
                createdAt = str(o, "created_at"),
                id = str(o, "id"),
            )
        }
    }

    /** A message's text: a plain string, or the block shape `[{type:"text", text:"…"}]`. */
    private fun contentOf(node: com.hermesvox.core.JNode?): String = when (node) {
        is JStr -> node.value
        is JArr -> node.items.mapNotNull { (it as? JObj)?.let { o -> (o["text"] as? JStr)?.value } }
            .joinToString("")
        else -> ""
    }

    /** The message count, under any of the names a SessionDB row has used for it. */
    private fun count(o: JObj): Int? {
        for (key in listOf("message_count", "messages", "count", "total_messages")) {
            val n = (o[key] as? JNum)?.raw?.trim()?.toIntOrNull()
            if (n != null && n >= 0) return n
        }
        return null
    }

    // ---- the card's lines -------------------------------------------------------------

    /**
     * Newest activity first. The host lists in its own order; the card's promise is "what
     * changed most recently is at the top", which is the order a person picks from. A row with
     * no parseable timestamp sorts last rather than first: unknown is not recent.
     */
    fun order(rows: List<Row>): List<Row> = rows.sortedByDescending { it.activityAt }

    /**
     * `telegram · 2 h ago · 12 messages` — the one line under a session's title. A part the
     * host did not give is left out; a part it gave is never rounded into a claim.
     */
    fun line(row: Row, now: Long): String = listOf(
        row.source.trim().replace('_', ' '),
        age(row.activityAt, now),
        row.messages?.let { if (it == 1) "1 message" else "$it messages" }.orEmpty(),
    ).filter { it.isNotBlank() }.joinToString(" · ")

    /** How long ago, in the app's words. "" when the host gave no usable timestamp. */
    fun age(at: Long, now: Long): String {
        if (at <= 0L) return ""
        val mins = (now - at).coerceAtLeast(0L) / 60_000L
        return when {
            mins < 1L -> "just now"
            mins < 60L -> "$mins min ago"
            mins < 24L * 60L -> "${mins / 60L} h ago"
            else -> "${mins / (24L * 60L)} d ago"
        }
    }

    /** The one line under the card's title: what the host served, or why there is nothing. */
    fun summary(listing: Listing): String = when (listing) {
        is Listing.Ok -> {
            val n = listing.page.rows.size
            if (n == 1) "1 session from this host" else "$n sessions from this host"
        }
        Listing.Empty -> EMPTY
        Listing.KeyRejected -> KEY_REJECTED
        Listing.NoAnswer -> NO_ANSWER
    }

    // ---- the transcript: the host's, merged, never appended twice ---------------------

    /**
     * A message's identity. The host's `id` when the shape has one; otherwise
     * `created_at|role`.
     *
     * Why that pair is stable enough to key on: the gateway writes one row per turn with a
     * second-resolution timestamp, and one turn at a time is written per session — two rows
     * with the same role in the same second would have to be two simultaneous turns in one
     * conversation. A `user` row and an `assistant` row in the same second are distinct,
     * which is the case that actually happens on a fast exchange. Where a row has neither an
     * id nor a timestamp (a local echo the host has not written yet), the identity is ""
     * and [merge] reconciles it by role and text instead.
     */
    fun identity(m: Message): String = when {
        m.id.isNotBlank() -> "id:" + m.id
        m.createdAt.isNotBlank() -> "at:" + m.createdAt + "|" + m.role
        else -> ""
    }

    /**
     * The transcript to show, given what is on screen and what the host just served.
     *
     * The host owns the transcript, so its rows and its order come first and whole. What was
     * shown survives only where the host has not accounted for it:
     *
     *  * a shown row whose identity the host served is **dropped** — this is the rule that
     *    makes a re-fetch on reconnect unable to duplicate a conversation;
     *  * a shown row with no identity (a local echo — this app's own reply, recorded before
     *    the host had written it) is dropped once the host serves the same role and text, so
     *    the echo does not become a second copy of itself;
     *  * anything else the host has not served yet is kept, after the host's rows, so the
     *    screen does not blink a turn away in the second before the host records it.
     *
     * Idempotent: merging the same answer twice changes nothing.
     */
    fun merge(shown: List<Message>, fetched: List<Message>): List<Message> {
        val served = fetched.map { identity(it) }.filter { it.isNotEmpty() }.toHashSet()
        val out = ArrayList<Message>(fetched.size + shown.size)
        out += fetched
        for (m in shown) {
            val id = identity(m)
            if (id.isNotEmpty()) {
                if (id in served) continue
            } else {
                val said = m.content.trim()
                val echo = fetched.any { it.role == m.role && it.content.trim() == said }
                if (echo) continue
            }
            // A row kept from the screen must not be kept twice either.
            if (out.any { identity(it) == id && id.isNotEmpty() }) continue
            if (out.any { identity(it).isEmpty() && it.role == m.role && it.content.trim() == m.content.trim() }) continue
            out += m
        }
        return out
    }

    /**
     * The crawl's history for [messages], in order: what the caller said and what the agent
     * answered, labelled with the transcript's own words. System and tool rows are not "what
     * was said" and are left out.
     */
    fun render(messages: List<Message>): List<Line> = messages.mapNotNull { m ->
        val said = m.content.trim()
        if (said.isEmpty()) null
        else when (m.role) {
            "user" -> Line(com.hermesvox.Transcript.YOU + said, CrawlHistory.Kind.SAID)
            "assistant" -> Line(com.hermesvox.Transcript.AGENT + said, CrawlHistory.Kind.REPLY)
            else -> null
        }
    }

    /**
     * A resumed transcript as one block of trail text, bounded at [maxChars] **from the
     * tail**: on a scrollback the newest lines are the ones worth keeping, and the crawl
     * already shows the end. Whole lines are dropped, never halves, so a line the screen shows
     * is a line the host wrote; only a single line longer than the whole budget keeps its tail.
     */
    fun trail(messages: List<Message>, maxChars: Int = TRAIL_MAX): String {
        val lines = render(messages).map { it.text }.toMutableList()
        fun len(): Int = if (lines.isEmpty()) 0 else
            lines.sumOf { it.length } + CrawlHistory.GAP.length * (lines.size - 1)
        while (lines.size > 1 && len() > maxChars) lines.removeAt(0)
        if (lines.isEmpty()) return ""
        val only = lines.joinToString(CrawlHistory.GAP)
        return if (only.length <= maxChars) only else only.takeLast(maxChars.coerceAtLeast(0))
    }

    /** The trail block's budget — the same one [CrawlHistory] keeps for its own ring. */
    const val TRAIL_MAX = CrawlHistory.MAX_CHARS

    // ---- the word that reaches the card -----------------------------------------------

    /**
     * The command a typed (or dictated) word means, or null when this is not that word.
     * Sessions left Alpha (#437, D39), so the word is the catalogue's for every install.
     */
    fun commandFor(input: String): CommandCatalog.Command? {
        val word = input.trim().lowercase().removePrefix("/")
        if (word != COMMAND.removePrefix("/")) return null
        return CommandCatalog.find(COMMAND)
    }

    // ---- timestamps -------------------------------------------------------------------

    /**
     * A host timestamp in epoch milliseconds, or 0 when it cannot be read. Accepts the ISO
     * form the API serves (`2026-10-30T23:54:03`, optional fraction, optional `Z`/`±hh:mm`),
     * the same with a space separator, and bare epoch seconds or milliseconds.
     *
     * A timestamp with no offset is read as UTC: the host's own session ids are UTC
     * (`20261030_235403_…`), so its clock is UTC and guessing local time would move every
     * age by the phone's offset. 0 is "no age to state", which the card leaves out — never
     * "just now", which would be a claim.
     */
    fun parseTime(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        if (s.all { it.isDigit() }) {
            return when (s.length) {
                10 -> s.toLong() * 1000L
                13 -> s.toLong()
                else -> 0
            }
        }
        val m = ISO.find(s) ?: return 0
        val g = m.groupValues
        val zone = g[8]
        val tz = when {
            zone.isEmpty() || zone.equals("Z", ignoreCase = true) -> "UTC"
            else -> "GMT" + if (zone.contains(':')) zone else zone.substring(0, 3) + ":" + zone.substring(3)
        }
        return try {
            val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone(tz))
            cal.clear()
            cal.isLenient = false
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            val ms = cal.timeInMillis
            val frac = g[7]
            ms + if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
        } catch (_: Throwable) { 0 }
    }

    private val ISO = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})?$""",
        RegexOption.IGNORE_CASE,
    )

    // ---- reading the wire shapes defensively ------------------------------------------

    private fun obj(text: String): JObj? = try {
        Json.parse(text, allowTrailing = true) as? JObj
    } catch (_: Throwable) { null }

    private fun str(o: JObj, key: String): String = (o[key] as? JStr)?.value.orEmpty()

    private fun num(o: JObj, key: String): Int? = (o[key] as? JNum)?.raw?.trim()?.toIntOrNull()

    private fun bool(o: JObj, key: String): Boolean? = (o[key] as? com.hermesvox.core.JBool)?.value
}
