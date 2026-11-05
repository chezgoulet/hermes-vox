package com.hermesvox

import com.hermesvox.core.ApprovalVoice
import java.security.MessageDigest

/**
 * PhoneApproval — the approval card for the one phone action that cannot be handed off:
 * **`uninstall`**.
 *
 * Messages and calls are handoffs (D36): Vox composes and opens, the owner sends and presses call.
 * Contacts and notifications are reads, so a grant is enough. Removing an app is the one capability
 * left that is irreversible and has no app to hand it to — so it gets a card on the phone.
 *
 * ## Two steps, on the same tool
 *
 * An MCP call carries no run id, so it cannot pause a host run the way the gateway's
 * `approval.request` does. The protocol is therefore a handshake on the tool itself:
 *
 * 1. **Request.** `uninstall` without `approval` raises a card and returns
 *    `ok:false, reason:"approval_required", approval_id, expires_at, summary`. Nothing is removed.
 * 2. **Execute.** The agent calls the **same action with the same canonical arguments** plus
 *    `approval:"<id>"`. It runs only when the id is unexpired, unburnt, and the action and its
 *    canonical arguments hash to the value the card was raised for.
 *
 * Every refusal burns the id: `approval_denied`, `approval_expired`, `approval_mismatch`,
 * `approval_unknown` (no such id, or already used) and `approval_busy` (three cards already
 * waiting). One card is on screen at a time; a second request queues, up to [MAX_PENDING].
 *
 * ## What the owner answers
 *
 * [Card.prompt] is an [ApprovalPrompt], the same model the gateway's cards use, with `once`/`deny`
 * as its only choices. Voice goes through [ApprovalVoice] — the same allowlist, the same
 * "not an answer" rule, the same "say approve or no" nudge. There is no second matcher here.
 * Silence is a deny at [TIMEOUT_MS]; dismissing the card is a deny too.
 *
 * Pure JVM. [PhoneEngine] owns the instance; the Android side only raises and withdraws the
 * surface, so every rule above is proven in [PhoneApprovalTest] without a phone.
 */
object PhoneApproval {

    /** The one action this card exists for. */
    const val ACTION = "uninstall"

    /** Two minutes, stated on the card. Expiry burns the id. */
    const val TIMEOUT_MS = 120_000L

    /** One card on screen; two may wait behind it. The fourth request is `approval_busy`. */
    const val MAX_PENDING = 3

    /** A package name is a name, not a path or a paragraph. */
    const val MAX_PACKAGE_CHARS = 255

    /** One approval, in the owner's language. The app's label is used; the tool name never is. */
    data class Card(
        val id: String,
        val packageName: String,
        /** The app's own label, as the platform reports it. */
        val label: String,
        /** The canonical action+arguments the card was raised for. */
        val canonical: String,
        /** SHA-256 of [canonical], hex. Compared alongside it, never instead of it. */
        val digest: String,
        val raisedAtMs: Long,
        val expiresAtMs: Long,
    ) {
        /** The one-line summary the request result carries and the audit stores. */
        val summary: String get() = "$ACTION $label"

        /** The exact line on the card, in the owner's language. */
        val line: String get() = "Your agent wants to remove $label. Approve?"

        /** The card model the existing approval surface already renders and answers. */
        fun prompt(): ApprovalPrompt = ApprovalPrompt(
            runId = "",
            requestId = id,
            action = "remove $label",
            line = line,
            choices = listOf("once", "deny"),
            timeoutMs = TIMEOUT_MS,
            labels = linkedMapOf("once" to "APPROVE", "deny" to "DENY"),
        )
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
     * Raise a card for [packageName]. [canonical] is the exact action+arguments text; [digest] is
     * its hash. The label comes from the platform and is the only name the card shows.
     */
    fun request(
        packageName: String,
        label: String,
        canonical: String,
        digest: String,
        now: Long,
    ): PhoneApproval.Request = synchronized(lock) {
        expire(now)
        if (waiting.size >= capacity) return PhoneApproval.Request.Busy
        val card = PhoneApproval.Card(
            id = "ap-${++next}",
            packageName = packageName,
            label = label,
            canonical = canonical,
            digest = digest,
            raisedAtMs = now,
            expiresAtMs = now + timeoutMs,
        )
        waiting.add(card)
        audit(card, "approval-requested", "agent", now)
        // A card queued behind another is not raised yet; it is shown when the first is answered.
        if (waiting.size == 1 && !surface.raise(card)) {
            waiting.removeAll { it.id == card.id }
            return PhoneApproval.Request.Unavailable(
                "the owner could not be asked: no card and no notification could be shown, so " +
                    "nothing was removed",
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
        decide(card.id, choice, "voice", now)
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
                        "that approval was for a different app; nothing was removed",
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
                        "that approval was for a different app; nothing was removed",
                    )
                }
                return PhoneApproval.Grant.Allowed(card)
            }

            // A refusal that already happened is reported once; using the id again is unknown.
            val burnt = terminal.remove(id)
            if (burnt != null) {
                return PhoneApproval.Grant.Refused(burnt.reason, detailOf(burnt.reason))
            }
            PhoneApproval.Grant.Refused(
                PhoneApproval.Reason.UNKNOWN,
                "no approval with that id is waiting; nothing was removed",
            )
        }

    /** Expire what is due. Called by both the engine and the surface's timer. */
    fun tick(now: Long) = synchronized(lock) { expire(now) }

    private fun matches(card: PhoneApproval.Card, expected: String, digest: String): Boolean =
        card.canonical == expected && card.digest == digest

    private fun detailOf(reason: String): String = when (reason) {
        PhoneApproval.Reason.DENIED -> "the owner denied that request; nothing was removed"
        PhoneApproval.Reason.EXPIRED -> "that approval expired; nothing was removed"
        PhoneApproval.Reason.MISMATCH -> "that approval was for a different app; nothing was removed"
        else -> "that approval is no longer usable; nothing was removed"
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
