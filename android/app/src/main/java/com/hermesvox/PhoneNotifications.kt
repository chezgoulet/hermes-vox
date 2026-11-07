package com.hermesvox

/**
 * PhoneNotifications — the **call-scoped** board behind `notifications` and `notification_read`.
 *
 * ## The lifetime rule, in one place
 *
 * Android's notification-listener grant is global and permanent for the installation. Our *use*
 * of it is not: [VoiceController] calls [attach] when a call starts and [detach] when it ends, the
 * same lifetime rule as the MCP listener. The system may keep the service bound in between, but
 * [record] ignores everything unless [attached] is true, and [detach] drops every record and every
 * id. With no live call, both actions refuse `reason:"unavailable"`.
 *
 * ## Nothing is retained
 *
 * The board lives in memory only. There is no cache file, no preference, no log line carrying a
 * title or a body, and no id written anywhere. A notification summary asks for counts; the content
 * is reachable only through [read], and only for an id a summary has already shown in this call.
 * That is the deliberate second step: a vague "what's going on?" cannot vacuum the lock screen.
 *
 * ## No title or text in a summary
 *
 * [Summary] and [AppCount] have no title or body field at all. A summary is `count` and, per app,
 * `{app, count, latest_at, ids}`. The ids are opaque handles, not content; they exist so the next
 * call can name one notification rather than ask for everything.
 *
 * Pure JVM: the Android listener ([MediaListenerService]) feeds it and the tests drive it directly.
 */
object PhoneNotifications {

    /** One notification as it was posted, held only while the call is live. */
    data class Record(
        val id: String,
        val packageName: String,
        val app: String,
        val key: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        /** Vox's own notification. It is never recorded, and never dismissed if it ever were. */
        val own: Boolean = false,
    )

    /** One app's row in a summary: counts and opaque ids, never a title or a body. */
    data class AppCount(
        val app: String,
        val count: Int,
        val latestAt: Long,
        /** Ids for [read], newest first. Opaque to everyone but this board. */
        val ids: List<String>,
    )

    /** The whole answer to `notifications`: a count and one row per app, nothing to read aloud. */
    data class Summary(val count: Int, val apps: List<AppCount>)

    private val lock = Any()
    private var live = false
    private var nextId = 0

    /** id -> record, in posting order. */
    private val items = LinkedHashMap<String, Record>()

    /** notification key -> id, so an update replaces the notification rather than counting twice. */
    private val byKey = HashMap<String, String>()

    /** Ids a summary has shown during this call. Only these may be read. */
    private val summarised = HashSet<String>()

    /**
     * The bound listener's way to hand us the notifications already on the phone. The system may
     * have bound the service before this call began (the grant is permanent), so a call start
     * needs to ask for the current set rather than waiting for the next post.
     */
    private var resync: (() -> Unit)? = null

    /**
     * The bound listener's way to dismiss one notification by its Android key. It is a callback,
     * not a stored Android object, so this board stays pure JVM and the listener owns the platform
     * call. `null` means nothing is bound.
     */
    private var dismisser: ((String) -> Boolean)? = null

    /** A call began. Idempotent, because the voice controller re-enters `start()` while warming. */
    fun attach() {
        synchronized(lock) {
            if (live) return
            live = true
            reset()
        }
        requestResync()
    }

    /** The listener service binds or unbinds; it is the only thing that can answer `activeNotifications`. */
    fun setResync(callback: (() -> Unit)?) {
        synchronized(lock) { resync = callback }
    }

    /**
     * The bound listener's dismissal door. The service registers it on connect and clears it on
     * disconnect, so a board with no listener can never pretend a notification went away. The
     * callback returns true only when Android accepted the cancellation.
     */
    fun setDismisser(callback: ((String) -> Boolean)?) {
        synchronized(lock) { dismisser = callback }
    }

    /** Ask the bound listener to hand over what is on the phone now, if anything is bound. */
    fun requestResync() {
        val callback = synchronized(lock) { resync }
        try {
            callback?.invoke()
        } catch (_: Throwable) {
            // A listener that has just gone away is not a reason to fail the call or the read.
        }
    }

    /** The call ended. Every record, id and summary handle goes with it. */
    fun detach() = synchronized(lock) {
        live = false
        reset()
    }

    /** True while this app is allowed to use the listener: a live call, and nothing else. */
    fun attached(): Boolean = synchronized(lock) { live }

    /**
     * One posted notification. Ignored when no call is live or when it is Vox's own notification
     * (an app reading itself through the listener is a loop and a confusion). [key] is Android's
     * own notification key: a repost with the same key replaces the record instead of adding a
     * second count.
     */
    fun record(
        packageName: String,
        app: String,
        key: String,
        title: String?,
        text: String?,
        postedAt: Long,
        own: Boolean = false,
    ) = synchronized(lock) {
        if (!live || own) return
        val stableKey = key.ifBlank { "$packageName@$postedAt" }
        val existing = byKey[stableKey]
        val id = existing ?: "n${++nextId}".also { byKey[stableKey] = it }
        items[id] = Record(
            id = id,
            packageName = packageName,
            app = app.trim().ifEmpty { packageName },
            key = stableKey,
            title = title.orEmpty(),
            text = text.orEmpty(),
            postedAt = postedAt,
            own = own,
        )
    }

    /** A notification left the phone. Removing it keeps the summary honest while the call lives. */
    fun remove(key: String) {
        synchronized(lock) {
            if (!live) return@synchronized
            val id = byKey.remove(key) ?: return@synchronized
            items.remove(id)
        }
    }

    /**
     * The summary now, or null when no call is live. Marks every id it shows as summarised, so a
     * later [read] can name one; an id that arrives after this summary is not readable until a
     * fresh summary shows it.
     */
    fun summary(): Summary? = synchronized(lock) {
        if (!live) return@synchronized null
        val grouped = LinkedHashMap<String, MutableList<Record>>()
        for (record in items.values) {
            grouped.getOrPut(record.app) { ArrayList() }.add(record)
        }
        val apps = grouped.entries.map { (app, records) ->
            val ordered = records.sortedByDescending { it.postedAt }
            ordered.forEach { summarised.add(it.id) }
            AppCount(
                app = app,
                count = ordered.size,
                latestAt = ordered.maxOfOrNull { it.postedAt } ?: 0L,
                ids = ordered.map { it.id },
            )
        }.sortedWith(compareByDescending<AppCount> { it.count }.thenBy { it.app.lowercase() })
        Summary(count = items.size, apps = apps)
    }

    /**
     * One notification's content, by an id a summary of **this call** has shown. Null covers no
     * live call, an id never summarised, and a notification that has since left the phone; the
     * caller distinguishes the first by [attached].
     */
    fun read(id: String): Record? = synchronized(lock) {
        if (!live || id !in summarised) return@synchronized null
        items[id]
    }

    // ---- dismissal (#apps-and-dismiss) ----

    /** The action name the tool offers; the parent wires it into [PhoneTool] and the engine. */
    const val ACTION_DISMISS = "notification_dismiss"

    /** An id from a summary is short. The parent's schema uses the same bound. */
    const val MAX_ID_CHARS = 80

    /** An app label or package is a name, not a payload. */
    const val MAX_APP_CHARS = 200

    /** No live call is the same answer `notifications` gives: the listener is only open in a call. */
    const val CLOSED_DETAIL =
        "the notification listener is only open during a call; no call is live, so nothing was " +
            "dismissed"

    /** A live call whose listener is not bound cannot dismiss anything either. */
    const val NOT_BOUND_DETAIL =
        "no notification listener is bound right now, so nothing was dismissed; the system " +
            "notification access may have been revoked"

    /**
     * Dismiss one notification by [id] from a summary of this call, or every notification from
     * [app] (its label or package, case-insensitive). Give exactly one of the two.
     *
     * This is destructive: Android cannot restore a cancelled notification, so the result carries
     * `undoable:false` and says so in words. It names the **app and the count**, never a title and
     * never a body, and [auditArguments] keeps the audit row to the same two facts.
     *
     * No call is `unavailable` with [CLOSED_DETAIL], exactly as `notifications` refuses. An id no
     * summary has shown is `unknown-id`, so a guessed handle cannot vacuum the lock screen. Vox's
     * own notifications are excluded both by [record] and by the [Record.own] guard below.
     */
    fun dismiss(id: String? = null, app: String? = null): PhoneResult {
        if (!attached()) {
            return refused(PhoneReason.UNAVAILABLE, CLOSED_DETAIL)
        }
        val callback = synchronized(lock) { dismisser }
            ?: return refused(PhoneReason.UNAVAILABLE, NOT_BOUND_DETAIL)

        val byId = id?.trim()?.takeIf { it.isNotEmpty() }
        val byApp = app?.trim()?.takeIf { it.isNotEmpty() }
        if (byId != null && byApp != null) {
            return refused(
                PhoneReason.INVALID,
                "give id or app, not both: an id dismisses one notification, an app dismisses all " +
                    "of that app's",
            )
        }
        if (byId == null && byApp == null) {
            return refused(
                PhoneReason.INVALID,
                "id or app is required: an id from a notifications summary, or an app name",
            )
        }
        if (byId != null && byId.length > MAX_ID_CHARS) {
            return refused(PhoneReason.INVALID, "id is longer than $MAX_ID_CHARS characters")
        }
        if (byApp != null && byApp.length > MAX_APP_CHARS) {
            return refused(PhoneReason.INVALID, "app is longer than $MAX_APP_CHARS characters")
        }

        val targets: List<Record>
        val label: String?
        if (byId != null) {
            val record = synchronized(lock) {
                if (byId !in summarised) null else items[byId]
            } ?: return refused(
                PhoneReason.UNKNOWN_ID,
                "no notification with that id is in a summary from this call; the id must come " +
                    "from a `notifications` summary while the call is open",
            )
            if (record.own) {
                return refused(
                    PhoneReason.UNKNOWN_ID,
                    "that notification is Vox's own; Vox never dismisses itself",
                )
            }
            targets = listOf(record)
            label = record.app
        } else {
            val wanted = byApp!!
            val found = synchronized(lock) {
                items.values.filter {
                    !it.own &&
                        (it.app.equals(wanted, ignoreCase = true) ||
                            it.packageName.equals(wanted, ignoreCase = true))
                }
            }
            if (found.isEmpty()) {
                return refused(
                    PhoneReason.UNKNOWN_ID,
                    "no notification from \"$wanted\" is on the phone in this call; a summary " +
                        "names the apps that have one",
                    app = wanted,
                )
            }
            targets = found
            label = found.first().app
        }

        var count = 0
        for (record in targets) {
            if (record.own) continue
            val gone = try {
                callback(record.key)
            } catch (_: Throwable) {
                false
            }
            if (!gone) continue
            count++
            // Android has accepted the cancellation; the board must not keep a record the phone
            // no longer has, or the next summary would count a notification that is gone.
            synchronized(lock) {
                items.remove(record.id)
                byKey.remove(record.key)
            }
        }

        if (count == 0) {
            return refused(
                PhoneReason.UNAVAILABLE,
                "Android did not dismiss the notification; nothing was dismissed",
                app = byApp,
            )
        }
        val facts = linkedMapOf<String, Any?>(
            "app" to (label ?: byApp.orEmpty()),
            "count" to count,
            // There is no undo for a dismissed notification, and the reply has to say so rather
            // than leave the mind to infer it from a missing undo handle.
            "undoable" to false,
            "note" to "not undoable: a dismissed notification cannot be restored",
        )
        val failed = targets.size - count
        if (failed > 0) facts["failed"] = failed
        return PhoneResult(
            ok = true,
            action = ACTION_DISMISS,
            tier = PhoneTiers.REPORTED,
            id = null,
            facts = facts,
        )
    }

    /**
     * The audit row's whole content for a dismissal: the app and the count, nothing else. The
     * dispatch path replaces the raw arguments (which may carry an id) with this map before
     * [PhoneAudit] sees them, so a listener id can never become an owner-visible record.
     *
     * A refusal that knows the app still carries it; one that does not carries nothing, rather
     * than an empty or invented app. The id is never a fact the audit may keep, on either path.
     */
    fun auditArguments(result: PhoneResult): Map<String, Any?> {
        if (result.action != ACTION_DISMISS) return emptyMap()
        val app = result.facts["app"] as? String ?: return emptyMap()
        val count = (result.facts["count"] as? Number)?.toLong() ?: 0L
        return linkedMapOf("app" to app, "notification_count" to count.toString())
    }

    /**
     * The app behind a summary id, for a refusal that has to name what the call was about without
     * keeping the id. Only an id a summary of this call has shown answers; a guessed or stale id
     * answers null, so this cannot be used to probe the board.
     */
    fun appOf(id: String): String? = synchronized(lock) {
        if (id !in summarised) null else items[id]?.takeIf { !it.own }?.app
    }

    /** A dismissal refusal. The app is included only when the call named one; an id never is. */
    private fun refused(reason: String, detail: String, app: String? = null): PhoneResult =
        PhoneResult(
            ok = false,
            action = ACTION_DISMISS,
            tier = PhoneTiers.REPORTED,
            reason = reason,
            detail = detail,
            facts = if (app == null) emptyMap() else linkedMapOf("app" to app),
        )

    private fun reset() {
        items.clear()
        byKey.clear()
        summarised.clear()
        nextId = 0
    }
}
