package com.hermesvox

import com.hermesvox.core.ApprovalVoice
import java.security.MessageDigest

/**
 * PhoneApproval — the approval card that answers **every action at Ask**, whichever group it is in.
 *
 * The card is not a Tier 2 special case any more. `Messages` set to Ask, a note at Ask, `uninstall`
 * at Ask, `file_offer` at Ask: they all take the same two steps, on the same tool, and the owner
 * answers the same card. The tier decides what the card *says* and whether an undo exists, not
 * whether a card can be raised.
 *
 * ## Two steps, on the same tool
 *
 * An MCP call carries no run id, so it cannot pause a host run the way the gateway's
 * `approval.request` does. The protocol is therefore a handshake on the tool itself:
 *
 * 1. **Request.** Any action at Ask without `approval` raises a card naming the action and its
 *    arguments, and returns `ok:false, reason:"approval-required", approval_id, expires_at,
 *    summary`. Nothing is performed.
 * 2. **Execute.** The agent calls the **same action with the same canonical arguments** plus
 *    `approval:"<id>"`. It runs only when the id is unexpired, unburnt, and the action and its
 *    canonical arguments hash to the value the card was raised for.
 *
 * Every refusal burns the id: `approval_denied`, `approval_expired`, `approval_mismatch`,
 * `approval_unknown` (no such id, or already used), `approval_locked` (answered from a locked
 * phone) and `approval_busy` (three cards already waiting). One card is on screen at a time; a
 * second request queues, up to [MAX_PENDING].
 *
 * ## What the owner answers
 *
 * [Card.prompt] is an [ApprovalPrompt], the same model the gateway's cards use, with `once`/`deny`
 * as its only choices. Voice goes through [ApprovalVoice] — the same allowlist, the same
 * "not an answer" rule, the same "say approve or no" nudge. There is no second matcher here.
 * Silence is a deny at [TIMEOUT_MS]; dismissing the card is a deny too. An approval answered while
 * the phone is locked is refused: it must be the owner's decision, not whoever is holding the
 * locked phone.
 *
 * Pure JVM. [PhoneEngine] owns the instance; the Android side only raises and withdraws the
 * surface, so every rule above is proven in [PhoneApprovalTest] without a phone.
 */
object PhoneApproval {

    /** The app-removal card's action name. */
    const val ACTION = "uninstall"

    /** The file-export card's action name. The owner's content leaves the phone over the tailnet. */
    const val FILE_ACTION = "file_offer"

    /** Two minutes, stated on the card. Expiry burns the id. */
    const val TIMEOUT_MS = 120_000L

    /** One card on screen; two may wait behind it. The fourth request is `approval_busy`. */
    const val MAX_PENDING = 3

    /** A package name is a name, not a path or a paragraph. */
    const val MAX_PACKAGE_CHARS = 255

    /**
     * One approval, in the owner's language. [label] is the app's label, the file's name, or the
     * subject the action works on; the tool's name never appears. [detail] carries a file's type
     * and size, or another short fact, because handing over a 40 MB file is a different act from
     * handing over a note.
     */
    data class Card(
        val id: String,
        val action: String,
        /** The canonical subject: the package for `uninstall`, the file name for `file_offer`. */
        val subject: String,
        /** The app's own label, as the platform reports it, or the file's name. */
        val label: String,
        /** `PDF, 240 KB` for a file; empty for an app. */
        val detail: String = "",
        /** The canonical action+arguments the card was raised for. */
        val canonical: String,
        /** SHA-256 of [canonical], hex. Compared alongside it, never instead of it. */
        val digest: String,
        val raisedAtMs: Long,
        val expiresAtMs: Long,
        /**
         * What the action does, without the leading "Your agent wants to": `remove Firefox`,
         * `set a timer for 5 minutes`, `write a text to Sam`. The card line is built from it, so
         * every action says exactly what it is about to do.
         */
        val phrase: String = "",
        /** The one-line summary the result carries and the audit stores; [phrase] by default. */
        val summaryText: String = "",
    ) {
        /** The one-line summary the request result carries and the audit stores. */
        val summary: String get() = when {
            summaryText.isNotEmpty() -> summaryText
            action == FILE_ACTION -> "offer $label" + if (detail.isEmpty()) "" else " ($detail)"
            action == ACTION -> "uninstall $label"
            phrase.isNotEmpty() -> phrase
            else -> "$action $label"
        }

        /** The exact line on the card, in the owner's language. */
        fun line(lang: String = "en"): String = when {
            lang == "fr" -> when {
                phrase.isNotEmpty() -> "Votre agent souhaite $phrase. Approuver ?"
                action == FILE_ACTION ->
                    "Votre agent souhaite lire $label" +
                        (if (detail.isEmpty()) "" else " ($detail)") + ". Approuver ?"
                action == ACTION -> "Votre agent souhaite supprimer $label. Approuver ?"
                else -> "Votre agent souhaite $action $label. Approuver ?"
            }
            else -> when {
                phrase.isNotEmpty() -> "Your agent wants to $phrase. Approve?"
                action == FILE_ACTION ->
                    "Your agent wants to read $label" +
                        (if (detail.isEmpty()) "" else " ($detail)") + ". Approve?"
                action == ACTION -> "Your agent wants to remove $label. Approve?"
                else -> "Your agent wants to $action $label. Approve?"
            }
        }

        val line: String get() = line("en")

        /** The card model the existing approval surface already renders and answers. */
        fun prompt(): ApprovalPrompt = ApprovalPrompt(
            runId = "",
            requestId = id,
            action = when {
                phrase.isNotEmpty() -> phrase
                action == FILE_ACTION -> "read $label"
                action == ACTION -> "remove $label"
                else -> "$action $label"
            },
            line = line,
            choices = listOf("once", "deny"),
            timeoutMs = TIMEOUT_MS,
            labels = linkedMapOf("once" to "APPROVE", "deny" to "DENY"),
        )
    }

    /**
     * What a card says for one validated call: [label] is the safe short subject the audit keeps,
     * [detail] a safe extra, and [phrase] the owner-language sentence after "Your agent wants to".
     *
     * **Private payloads never appear.** A note's text, a clip's text and a message's body are
     * shown only as a length; an event's title is shown because the owner is approving that event.
     */
    data class Description(
        val label: String,
        val detail: String,
        val phrase: String,
        val summaryText: String = "",
    )

    /** The card's words for every implemented action. */
    fun describe(call: PhoneTool.Call): Description = when (call) {
        is PhoneTool.Call.Timer -> Description(
            "timer",
            LocalActionSpeech.spokenDuration(call.durationMs),
            "set a timer for ${LocalActionSpeech.spokenDuration(call.durationMs)}",
        )
        is PhoneTool.Call.Alarm -> Description(
            "alarm",
            LocalActionSpeech.spokenClock(call.hour, call.minute),
            "set an alarm for ${LocalActionSpeech.spokenClock(call.hour, call.minute)}",
        )
        is PhoneTool.Call.Dnd -> Description(
            "do not disturb",
            if (call.on) "on" else "off",
            if (call.on) "turn Do Not Disturb on" else "turn Do Not Disturb off",
        )
        is PhoneTool.Call.Volume -> if (call.level != null) {
            Description("volume", "${call.level}%", "set the media volume to ${call.level}%")
        } else {
            val way = if (call.direction > 0) "up" else "down"
            Description("volume", "one step $way", "turn the media volume $way")
        }
        is PhoneTool.Call.Note -> Description(
            "note",
            "${call.text.length} characters",
            "keep a note (${call.text.length} characters)",
        )
        is PhoneTool.Call.PlayPause -> Description(
            "media",
            if (call.pause) "pause" else "play",
            if (call.pause) "pause what is playing" else "play",
        )
        is PhoneTool.Call.Skip -> Description(
            "media",
            if (call.previous) "previous" else "next",
            if (call.previous) "skip back a track" else "skip to the next track",
        )
        PhoneTool.Call.NowPlaying ->
            Description("media", "now playing", "see what is playing")
        PhoneTool.Call.State ->
            Description("device state", "", "read this phone's state")
        is PhoneTool.Call.Clip -> Description(
            "clipboard text",
            "${call.text.length} characters",
            "put ${call.text.length} characters on the clipboard",
        )
        PhoneTool.Call.ClipRead ->
            Description("clipboard", "", "read your clipboard")
        is PhoneTool.Call.FileList -> {
            val folder = call.folder ?: "the folder you granted"
            val at = if (call.path.isEmpty()) folder else "$folder/${call.path}"
            Description(folder, call.path, "list the files in $at")
        }
        is PhoneTool.Call.FileRead -> {
            val folder = call.folder ?: "the folder you granted"
            Description(call.name, folder, "read ${call.name} in $folder")
        }
        is PhoneTool.Call.FileOffer -> Description(
            call.name,
            call.folder ?: "the folder you granted",
            "read ${call.name} in ${call.folder ?: "the folder you granted"}",
        )
        PhoneTool.Call.Agenda ->
            Description("calendar", "today", "read today's events")
        is PhoneTool.Call.EventCreate -> Description(
            "event",
            "${call.durationMin} minutes at ${call.start}",
            "create \"${call.title}\" at ${call.start} for ${call.durationMin} minutes",
        )
        PhoneTool.Call.Notifications ->
            Description("notifications", "", "read the notifications on this phone")
        is PhoneTool.Call.NotificationRead ->
            Description("notifications", "one", "read one notification on this phone")
        is PhoneTool.Call.NotificationDismiss -> Description(
            "notifications",
            call.app ?: "one",
            if (call.app != null) {
                "dismiss all notifications from ${call.app} on this phone"
            } else {
                "dismiss one notification on this phone"
            },
        )
        is PhoneTool.Call.Contact ->
            Description("contact lookup", "", "look up \"${call.name}\" in your contacts")
        is PhoneTool.Call.Message -> Description(
            "text message to ${call.to}",
            "${call.text.length} characters",
            "write a text to ${call.to} (${call.text.length} characters)",
        )
        is PhoneTool.Call.Dial ->
            Description("dialer", call.to, "open the dialer for ${call.to}")
        is PhoneTool.Call.Launch ->
            Description("app", call.app, "open ${call.app}")
        PhoneTool.Call.Apps ->
            Description("apps", "", "list the apps on your phone")
        is PhoneTool.Call.Open ->
            Description("link", call.uri, "open ${call.uri}")
        is PhoneTool.Call.Uninstall ->
            Description("app", call.packageName, "remove ${call.packageName}")
        is PhoneTool.Call.Undo -> Description("undo", call.id, "undo ${call.id}")
        PhoneTool.Call.Status -> Description("status", "", "report what this phone is doing")
    }

    /**
     * Whether the phone is unlocked enough to give a yes. An approval answered on the lock screen
     * is not the owner's decision — whoever is holding the phone can tap it — so a locked device
     * refuses. [ALWAYS] is the pure-JVM default; the Android surface supplies the keyguard read.
     */
    fun interface Unlocked {
        fun isUnlocked(): Boolean

        companion object {
            val ALWAYS: Unlocked = Unlocked { true }
        }
    }

    /** The surface the card is raised on. Android: the in-app card, else a heads-up notification. */
    interface Surface {
        /** Raise [card]. False means neither surface could be shown, so the owner cannot be asked. */
        fun raise(card: Card): Boolean

        /** Remove [card] from the surface; it has been answered, expired, or replaced. */
        fun withdraw(card: Card) {}

        companion object {
            /** A surface that can never show anything. The request then refuses `unavailable`. */
            val NONE: Surface = object : Surface {
                override fun raise(card: Card): Boolean = false
            }
        }
    }

    /** What a request produced. */
    sealed class Request {
        data class Raised(val card: Card) : Request()
        data object Busy : Request()
        data class Unavailable(val detail: String) : Request()
    }

    /** What a second call with an `approval` id produced. */
    sealed class Grant {
        /** The owner approved and the id is unexpired and the arguments match: execute it now. */
        data class Allowed(val card: Card) : Grant()

        /** The card is up and unanswered; nothing may run yet. */
        data class Pending(val card: Card) : Grant()

        /** Nothing may run. [reason] is one of the `PhoneReason` approval strings. */
        data class Refused(val reason: String, val detail: String) : Grant()
    }

    /** What one spoken utterance did to the current card. */
    sealed class Voice {
        /** A plain yes or no. [choice] is `once` or `deny`. */
        data class Answered(val choice: String, val via: String = "voice") : Voice()

        /** Not an answer. The card must be re-asked once, then left alone. */
        data object Reask : Voice()

        /** Not an answer, and the card has already been re-asked once. */
        data object Unheard : Voice()
    }

    /**
     * The canonical form of one action and its arguments: the action, then every argument except
     * `approval`, sorted by key, with string values trimmed and their internal whitespace collapsed.
     *
     * Two maps that differ only in key order or whitespace therefore produce the same text; two that
     * name different packages cannot, because a package name is one of the values. Numbers are
     * written without a redundant `.0`, so a JSON reader's Long/Double choice does not change the
     * hash.
     */
    fun canonical(action: String, args: Map<String, Any?>): String {
        val head = action.trim().lowercase()
        val body = args.entries
            .filter { it.key != "approval" }
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key=${canonicalValue(value)}" }
        return "$head\n$body"
    }

    /** SHA-256 of [canonical], lowercase hex. */
    fun digest(canonical: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim().replace(Regex("\\s+"), " ")
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Float -> canonicalValue(value.toDouble())
        else -> value.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** `PDF, 240 KB` — the type and size the file card shows, never hidden behind a generic "file". */
    fun fileDetail(name: String, mime: String?, bytes: Long?): String =
        typeLabel(name, mime) + ", " + if (bytes == null) "size unknown" else FileOffer.size(bytes)

    /**
     * The short type for the card: the provider's MIME type when it says something useful, else the
     * extension. It is the owner's word ("PDF"), not a MIME string read aloud.
     */
    fun typeLabel(name: String, mime: String?): String {
        val m = mime?.trim()?.lowercase().orEmpty()
        val fromMime = when (m) {
            "application/pdf" -> "PDF"
            "image/png" -> "PNG"
            "image/jpeg", "image/jpg" -> "JPEG"
            "image/gif" -> "GIF"
            "image/webp" -> "WEBP"
            "text/plain" -> "text"
            "text/csv" -> "CSV"
            "text/html" -> "HTML"
            "application/json" -> "JSON"
            "application/zip" -> "ZIP"
            "", "application/octet-stream" -> ""
            else -> when {
                m.startsWith("image/") -> m.removePrefix("image/").uppercase()
                m.startsWith("text/") -> m.removePrefix("text/").uppercase()
                m.startsWith("audio/") -> m.removePrefix("audio/").uppercase()
                m.startsWith("video/") -> m.removePrefix("video/").uppercase()
                else -> ""
            }
        }
        if (fromMime.isNotEmpty()) return fromMime
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "PDF"
            "png" -> "PNG"
            "jpg", "jpeg" -> "JPEG"
            "gif" -> "GIF"
            "webp" -> "WEBP"
            "txt", "md", "log" -> "text"
            "csv" -> "CSV"
            "html", "htm" -> "HTML"
            "json" -> "JSON"
            "zip" -> "ZIP"
            "mp3", "m4a", "wav", "ogg" -> "audio"
            "mp4", "mov", "mkv", "webm" -> "video"
            else -> "file"
        }
    }

    /** A package name is dotted and has no separators; null when it is not one. */
    fun packageName(raw: String?): String? {
        val p = raw?.trim().orEmpty()
        if (p.isEmpty() || p.length > MAX_PACKAGE_CHARS) return null
        if (p.contains('/') || p.contains('\\') || p.contains("..")) return null
        return p.takeIf { Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(it) }
    }

    /** The refusal vocabulary this card adds to [PhoneReason]. */
    object Reason {
        const val DENIED = "approval_denied"
        const val EXPIRED = "approval_expired"
        const val MISMATCH = "approval_mismatch"
        const val UNKNOWN = "approval_unknown"
        const val BUSY = "approval_busy"

        /** The card was answered while the device was locked. Nothing was approved. */
        const val LOCKED = "approval_locked"
    }
}

/**
 * The state behind [PhoneApproval]: the queue, the ids, the decisions and the burn list.
 *
 * Every method is synchronous and takes the clock, so expiry is proven without waiting. The desk
 * never performs the action; [PhoneEngine] does that only after [authorize] returns
 * [PhoneApproval.Grant.Allowed].
 */
class PhoneApprovalDesk(
    private val surface: PhoneApproval.Surface = PhoneApproval.Surface.NONE,
    /** (card, outcome, via, at) — the request and decision audit rows. */
    private val audit: (PhoneApproval.Card, String, String, Long) -> Unit = { _, _, _, _ -> },
    private val timeoutMs: Long = PhoneApproval.TIMEOUT_MS,
    private val capacity: Int = PhoneApproval.MAX_PENDING,
    /** The keyguard read. A yes answered while locked is refused, never recorded. */
    private val unlocked: PhoneApproval.Unlocked = PhoneApproval.Unlocked.ALWAYS,
) {

    private data class Approved(val card: PhoneApproval.Card, val via: String)

    private data class Terminal(val reason: String, val card: PhoneApproval.Card)

    private val lock = Any()
    private val waiting = ArrayList<PhoneApproval.Card>()
    private val approved = LinkedHashMap<String, Approved>()
    private val terminal = LinkedHashMap<String, Terminal>()
    private val nudged = HashSet<String>()
    private var next = 0

    /** The card on the surface now, or null. */
    fun current(): PhoneApproval.Card? = synchronized(lock) { waiting.firstOrNull() }

    fun pendingCount(): Int = synchronized(lock) { waiting.size }

    /**
     * Raise a card for [action] and its [subject]. [canonical] is the exact action+arguments text;
     * [digest] is its hash. The label comes from the platform and is the only name the card shows;
     * [detail] is the file's type and size, empty for an app.
     */
    fun request(
        action: String,
        subject: String,
        label: String,
        detail: String,
        canonical: String,
        digest: String,
        now: Long,
        phrase: String = "",
        summaryText: String = "",
    ): PhoneApproval.Request = synchronized(lock) {
        expire(now)
        if (waiting.size >= capacity) return PhoneApproval.Request.Busy
        val card = PhoneApproval.Card(
            id = "ap-${++next}",
            action = action,
            subject = subject,
            label = label,
            detail = detail,
            canonical = canonical,
            digest = digest,
            raisedAtMs = now,
            expiresAtMs = now + timeoutMs,
            phrase = phrase,
            summaryText = summaryText,
        )
        waiting.add(card)
        audit(card, "approval-requested", "agent", now)
        // A card queued behind another is not raised yet; it is shown when the first is answered.
        if (waiting.size == 1 && !surface.raise(card)) {
            waiting.removeAll { it.id == card.id }
            return PhoneApproval.Request.Unavailable(
                "the owner could not be asked: no card and no notification could be shown, so " +
                    "nothing was performed",
            )
        }
        PhoneApproval.Request.Raised(card)
    }

    /**
     * Answer the card [id]. [choice] is `once`/`approve` or `deny`; [via] is `tap` or `voice`.
     * Returns the card when it was waiting, or null when it was not.
     */
    fun decide(id: String, choice: String, via: String, now: Long): PhoneApproval.Card? =
        synchronized(lock) {
            expire(now)
            val index = waiting.indexOfFirst { it.id == id }
            if (index < 0) return null
            val card = waiting.removeAt(index)
            val decided = if (choice.equals("deny", true)) "deny" else "once"
            if (decided == "once" && !unlocked.isUnlocked()) {
                // A lock-screen tap must not approve anything. The id is burnt with a reason the
                // agent can report; unlocking and asking again is the path, not a stored yes.
                terminal[id] = Terminal(PhoneApproval.Reason.LOCKED, card)
                audit(card, "locked", via, now)
                surface.withdraw(card)
                raiseNext(now)
                return null
            }
            if (decided == "once") {
                approved[id] = Approved(card, via)
            } else {
                terminal[id] = Terminal(PhoneApproval.Reason.DENIED, card)
            }
            audit(card, if (decided == "once") "approved" else "denied", via, now)
            surface.withdraw(card)
            raiseNext(now)
            card
        }

    /** A spoken answer, through the one allowlist [ApprovalVoice] owns. */
    fun voice(text: String, now: Long): PhoneApproval.Voice = synchronized(lock) {
        expire(now)
        val card = waiting.firstOrNull() ?: return PhoneApproval.Voice.Unheard
        val choice = ApprovalVoice.resolve(text, listOf("once", "deny"))
            ?: run {
                if (nudged.add(card.id)) return PhoneApproval.Voice.Reask
                return PhoneApproval.Voice.Unheard
            }
        if (decide(card.id, choice, "voice", now) == null) return PhoneApproval.Voice.Unheard
        PhoneApproval.Voice.Answered(choice, "voice")
    }

    /**
     * The execution check. Only an approved, unexpired id whose canonical action+arguments still
     * match may run. Every other outcome burns the id: a second use is `approval_unknown`.
     */
    fun authorize(id: String, action: String, args: Map<String, Any?>, now: Long): PhoneApproval.Grant =
        synchronized(lock) {
            expire(now)
            val expected = PhoneApproval.canonical(action, args)
            val expectedDigest = PhoneApproval.digest(expected)

            val index = waiting.indexOfFirst { it.id == id }
            if (index >= 0) {
                val card = waiting[index]
                if (!matches(card, expected, expectedDigest)) {
                    waiting.removeAt(index)
                    surface.withdraw(card)
                    raiseNext(now)
                    return PhoneApproval.Grant.Refused(
                        PhoneApproval.Reason.MISMATCH,
                        detailOf(PhoneApproval.Reason.MISMATCH, card),
                    )
                }
                // Still waiting: the owner has not answered, so nothing may run.
                return PhoneApproval.Grant.Pending(card)
            }

            val decision = approved.remove(id)
            if (decision != null) {
                val card = decision.card
                if (!matches(card, expected, expectedDigest)) {
                    return PhoneApproval.Grant.Refused(
                        PhoneApproval.Reason.MISMATCH,
                        detailOf(PhoneApproval.Reason.MISMATCH, card),
                    )
                }
                return PhoneApproval.Grant.Allowed(card)
            }

            // A refusal that already happened is reported once; using the id again is unknown.
            val burnt = terminal.remove(id)
            if (burnt != null) {
                return PhoneApproval.Grant.Refused(burnt.reason, detailOf(burnt.reason, burnt.card))
            }
            PhoneApproval.Grant.Refused(
                PhoneApproval.Reason.UNKNOWN,
                detailOf(PhoneApproval.Reason.UNKNOWN, null),
            )
        }

    /** Expire what is due. Called by both the engine and the surface's timer. */
    fun tick(now: Long) = synchronized(lock) { expire(now) }

    private fun matches(card: PhoneApproval.Card, expected: String, digest: String): Boolean =
        card.canonical == expected && card.digest == digest

    private fun detailOf(reason: String, card: PhoneApproval.Card?): String {
        val what = when (card?.action) {
            PhoneApproval.FILE_ACTION -> "file"
            PhoneApproval.ACTION -> "app"
            else -> card?.action?.let { "different $it request" } ?: "action"
        }
        val done = when (card?.action) {
            PhoneApproval.FILE_ACTION -> "offered"
            PhoneApproval.ACTION -> "removed"
            else -> "performed"
        }
        return when (reason) {
            PhoneApproval.Reason.DENIED -> "the owner denied that request; nothing was $done"
            PhoneApproval.Reason.EXPIRED -> "that approval expired; nothing was $done"
            PhoneApproval.Reason.MISMATCH ->
                "that approval was for a different request ($what); nothing was $done"
            PhoneApproval.Reason.LOCKED ->
                "the phone was locked when that approval was answered; nothing was $done. " +
                    "Unlock it and ask again"
            else -> "that approval is no longer usable; nothing was $done"
        }
    }

    /** Raise the head of the queue. A card that cannot be shown burns as expired, never silently. */
    private fun raiseNext(now: Long) {
        while (waiting.isNotEmpty()) {
            val card = waiting.first()
            if (surface.raise(card)) return
            waiting.removeAt(0)
            terminal[card.id] = Terminal(PhoneApproval.Reason.EXPIRED, card)
            audit(card, "expired", "unavailable", now)
        }
    }

    private fun expire(now: Long) {
        val due = waiting.filter { it.expiresAtMs <= now }
        for (card in due) {
            waiting.remove(card)
            terminal[card.id] = Terminal(PhoneApproval.Reason.EXPIRED, card)
            audit(card, "expired", "silence", now)
            surface.withdraw(card)
        }
        val approvedDue = approved.values.filter { it.card.expiresAtMs <= now }
        for (entry in approvedDue) {
            approved.remove(entry.card.id)
            terminal[entry.card.id] = Terminal(PhoneApproval.Reason.EXPIRED, entry.card)
            audit(entry.card, "expired", "silence", now)
        }
        if (due.isNotEmpty()) raiseNext(now)
    }
}
