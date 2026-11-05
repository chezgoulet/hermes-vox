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

    private fun reset() {
        items.clear()
        byKey.clear()
        summarised.clear()
        nextId = 0
    }
}
