package com.hermesvox.core

/**
 * #282 — approvals, the phone half of the loop.
 *
 * Hermes stops and asks its owner to approve a consequential action. The gateway
 * advertises `approval_events` / `run_approval_response` and emits one run event on
 * the live stream:
 *
 *     approval.request { run_id, request_id?, command (already redacted),
 *                        description?, reason?, choices: [once|session|always|deny] }
 *
 * Answer with `POST /v1/runs/{run_id}/approval {choice, request_id}`. `choices` is
 * authoritative: the app renders exactly what the gateway offered, never one it did
 * not list, and `deny` is always available. The gateway can also narrow the list
 * (`smart_denied` / `allow_session=false` produce `[once, deny]`), which is why the
 * choices are data here rather than a fixed four-button menu.
 *
 * Everything in this file is pure JVM: the wire model, the choice rules, the voice
 * vocabulary, the timeout decision, and the once-only answer gate. The controller
 * owns the Android scheduling; this owns the safety properties, so they can be
 * proven off-device.
 */

/** One `approval.request` as the gateway sent it. [command] is already redacted upstream. */
internal data class ApprovalRequest(
    val runId: String,
    val requestId: String,
    val command: String,
    val description: String,
    val reason: String,
    val choices: List<String>,
    /**
     * Optional run-generation fence: gateways running under session authority include
     * it in the event and require it back verbatim. It is echoed only when the event
     * carried one, so the plain `{choice, request_id}` contract still holds elsewhere.
     */
    val executionGeneration: Int? = null,
) {
    /** The local identity of this request: the gateway's request id, or the run when
     *  an older gateway omitted it. Used for the exactly-once latch. */
    fun key(): String = if (requestId.isNotEmpty()) requestId else "run:$runId"

    /** The action in user language, never the tool's name. */
    fun action(): String = ApprovalCopy.action(this)

    /** The one plain line the call shows: what is being approved and why. */
    fun line(): String = ApprovalCopy.line(this)

    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "run_id" to runId,
        "request_id" to requestId,
        "command" to command,
        "description" to description,
        "reason" to reason,
        "choices" to choices,
    ).apply {
        executionGeneration?.let { put("execution_generation", it) }
    }
}

/**
 * The choices the app may render. The gateway's list is authoritative, so unknown
 * words are dropped rather than translated into something the gateway never offered;
 * `deny` is appended when absent because it is always available.
 */
internal object ApprovalChoices {
    val KNOWN = listOf("once", "session", "always", "deny")

    fun sanitize(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (candidate in raw) {
            val choice = candidate.trim().lowercase()
            if (choice in KNOWN) out.add(choice)
        }
        out.add("deny")   // always answerable, even when the gateway's list is malformed
        return out.toList()
    }

    /** Button text. The wire word stays the same; only the label is humanised. */
    fun label(choice: String): String = when (choice) {
        "once" -> "ONCE"
        "session" -> "THIS SESSION"
        "always" -> "ALWAYS"
        "deny" -> "NO"
        else -> choice.uppercase()
    }

    /** Spoken form for the transcript/log: the scope of a yes, or the denial. */
    fun spoken(choice: String): String = when (choice) {
        "once" -> "once"
        "session" -> "for this session"
        "always" -> "always"
        "deny" -> "denied"
        else -> choice
    }
}

/**
 * Hands-free answers while a request is up. "Approve" and "no" are the two the
 * owner asked for; a positive answer takes the LEAST privilege the gateway offered
 * (once → session → always). It never invents a choice the event did not list.
 *
 * H1 (0.9.1 review): the WHOLE normalised utterance must be one of a short list of
 * answers (at most [MAX_WORDS] words). It is never a phrase search over a sentence: a
 * substring search let "I'm not sure" approve on `sure`, and "where does that go" on
 * `go`. Anything else — a question, a negation that is not a plain no, thinking aloud —
 * is no answer at all (null), so the card stays up and the owner is asked again. That
 * is deliberately not a deny either: only a plain no denies.
 */
internal object ApprovalVoice {

    /** Plain refusals, matched as the whole utterance. */
    private val DENY = setOf(
        "no", "nope", "nah", "no thanks", "no thank you", "deny", "denied", "deny it",
        "reject", "reject it", "cancel", "cancel that", "cancel it", "stop", "stop it",
        "never", "block", "block it", "don't", "do not", "not now", "no don't",
        "don't approve", "do not approve", "no don't approve", "don't do it", "no stop",
    )

    /** Plain approvals, matched as the whole utterance. No bare soft words: `sure`, `ok`,
     *  `okay`, `go` and `allow` are said while thinking far more often than as a yes. */
    private val APPROVE = setOf(
        "approve", "approved", "approve it", "approve once", "approve that",
        "yes", "yes approve", "yes approve it", "yes please", "yes do it",
        "do it", "go ahead", "proceed", "confirm", "confirmed",
    )

    /** Any of these makes a non-deny utterance no answer (re-prompt), never a yes. */
    private val NEGATION = setOf(
        "not", "no", "don't", "dont", "never", "nope", "nah", "can't", "cannot", "won't",
        "wouldn't", "shouldn't", "isn't", "doesn't", "without",
    )

    /** An utterance that opens like a question is a question, whatever follows. */
    private val QUESTION = setOf(
        "what", "what's", "whats", "where", "why", "how", "who", "which", "when", "is",
        "are", "does", "did", "can", "could", "should", "would", "will", "wait",
    )

    private const val MAX_WORDS = 3

    /** The gateway choices that count as a yes, least privilege first. */
    private val POSITIVE = listOf("once", "session", "always")

    /**
     * Maps a recognised utterance to an offered choice, or null when it is not an
     * answer (the owner is asked again).
     */
    fun resolve(text: String, choices: List<String>): String? {
        if (text.contains('?')) return null
        val said = normalize(text)
        if (said.isEmpty()) return null
        val words = said.split(' ')
        if (words.size > MAX_WORDS) return null
        if (words.first() in QUESTION) return null
        if (said in DENY) return "deny"
        if (words.any { it in NEGATION }) return null
        if (said in APPROVE) return POSITIVE.firstOrNull { it in choices }
        return null
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace('\u2019', '\'')
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * 0.9.1 soak: the no-answer must not be a silent dead end. Since H1, [ApprovalVoice.resolve]
 * returns null for anything that is not a plain answer, which left "I'm not sure" doing
 * nothing at all. The caller is now told, ONCE per card, what the card can hear.
 *
 * This is only the latch. It never touches [ApprovalGate]: it cannot approve, deny or time
 * out a request, and the card stays up whatever it decides. A busy voice skips the prompt
 * (it is never queued behind a reply) and does not spend the latch.
 */
internal class ApprovalNudge {

    enum class Outcome { SPEAK, ALREADY, BUSY }

    private var prompted = ""

    /** A non-answer arrived while the card [key] is up. */
    @Synchronized
    fun onNoAnswer(key: String, voiceBusy: Boolean): Outcome {
        if (key.isEmpty() || key == prompted) return Outcome.ALREADY
        if (voiceBusy) return Outcome.BUSY
        prompted = key
        return Outcome.SPEAK
    }

    /** The card was answered, replaced or timed out. */
    @Synchronized
    fun reset() { prompted = "" }

    companion object {
        /** Four words on purpose: an echo of it is longer than any answer, so it can never
         *  resolve to a choice. */
        const val LINE = "say approve or no"

        /** No transcript, no command: the request id and why. */
        fun logLine(key: String): String = "event=approval-prompt request=$key reason=not-an-answer"
    }
}

/** The words the surface uses. Kept pure so the wording is pinned by tests. */
internal object ApprovalCopy {

    fun action(req: ApprovalRequest): String {
        val description = oneLine(req.description, 140)
        val command = oneLine(req.command, 160)
        if (description.isEmpty()) return command.ifEmpty { "an action" }
        if (command.isEmpty() || description.equals(command, ignoreCase = true) ||
            description.contains(command, ignoreCase = true)) return description
        return "$description — $command"
    }

    fun line(req: ApprovalRequest): String {
        val action = action(req)
        val why = oneLine(req.reason, 120)
        val duplicate = why.isEmpty() || why.equals(req.description.trim(), ignoreCase = true) ||
            action.contains(why, ignoreCase = true)
        return if (duplicate) "Approve: $action" else "Approve: $action — why: $why"
    }

    fun oneLine(text: String, max: Int): String =
        text.replace(Regex("\\s+"), " ").trim().take(max)
}

/** The answer actually sent — one per request, by construction. */
internal class ApprovalAnswer(
    val request: ApprovalRequest,
    val choice: String,
    /** "touch", "voice" or "timeout". */
    val source: String,
    val timedOut: Boolean,
) {
    /** `timeout` names the decision that produced the deny; the posted choice stays `deny`. */
    val decision: String get() = if (timedOut) "timeout" else choice

    /**
     * The one field-report line. It carries both ids so a report can be triaged without
     * a screenshot, and the decision separately from the posted choice (a timeout posts
     * `deny` but must never be read as a human no).
     */
    fun logLine(): String =
        "event=approval decision=$decision choice=$choice run=${request.runId} " +
            "request=${request.key()} cause=$source"
}

/**
 * The exactly-once answer gate.
 *
 * A request is pending until it is answered or times out; the first answer removes it
 * and records its key, so a double tap, a duplicate voice match, or a late timeout is a
 * local no-op (never a second POST). Requests are dropped when their run ends — a
 * request that arrives after the run is not surfaced at all.
 *
 * [timeout] is the ONLY automatic path, and it always chooses `deny`. There is no
 * auto-approve path in this class; a positive choice can only come from
 * [answer], which the UI and the voice matcher call.
 */
internal class ApprovalGate(val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val pending = ArrayList<ApprovalRequest>()
    private val answered = LinkedHashSet<String>()

    /** The request the surface should show now, or null when nothing is pending. */
    @Synchronized
    fun current(): ApprovalRequest? = pending.firstOrNull()

    @Synchronized
    fun pendingCount(): Int = pending.size

    /**
     * The pending requests whose keys are in [keys]. A turn uses this to ask about the
     * cards IT raised: an older held card (or the debug arm's) must not pause the new
     * turn's clock or be held on its behalf.
     */
    @Synchronized
    fun pendingFor(keys: Set<String>): List<ApprovalRequest> =
        if (keys.isEmpty()) emptyList() else pending.filter { it.key() in keys }

    /**
     * Accepts [request] only while its run is alive. Returns the request as it will be
     * rendered (choices sanitised), or null when it was ignored as duplicate/ended.
     */
    @Synchronized
    fun offer(request: ApprovalRequest, runAlive: Boolean): ApprovalRequest? {
        if (!runAlive) return null
        if (request.runId.isEmpty() && request.requestId.isEmpty()) return null
        val accepted = request.copy(choices = ApprovalChoices.sanitize(request.choices))
        val key = accepted.key()
        if (key in answered) return null
        if (pending.any { it.key() == key }) return null
        pending.add(accepted)
        return accepted
    }

    /**
     * Answers [key] with [choice] exactly once. Null means "no-op": already answered,
     * timed out, the run ended, or the choice was not one the gateway offered.
     */
    @Synchronized
    fun answer(key: String, choice: String, source: String): ApprovalAnswer? {
        val request = pending.firstOrNull { it.key() == key } ?: return null
        val chosen = choice.trim().lowercase()
        if (chosen !in request.choices) return null
        pending.remove(request)
        remember(request.key())
        return ApprovalAnswer(request, chosen, source, timedOut = false)
    }

    /**
     * The timeout decision: deny, always. Returns null when the request was already
     * answered (the race a late timeout must lose) or is no longer pending.
     */
    @Synchronized
    fun timeout(key: String): ApprovalAnswer? {
        val request = pending.firstOrNull { it.key() == key } ?: return null
        pending.remove(request)
        remember(request.key())
        return ApprovalAnswer(request, "deny", "timeout", timedOut = true)
    }

    /** The run ended: drop its requests without answering them. */
    @Synchronized
    fun closeRun(runId: String): List<ApprovalRequest> {
        val dropped = pending.filter { it.runId == runId || runId.isEmpty() }
        pending.removeAll(dropped)
        dropped.forEach { remember(it.key()) }
        return dropped
    }

    /** The turn ended (completed, failed, cancelled): nothing can be answered any more. */
    @Synchronized
    fun closeAll(): List<ApprovalRequest> {
        val dropped = pending.toList()
        pending.clear()
        dropped.forEach { remember(it.key()) }
        return dropped
    }

    /**
     * The turn ended, but the runs in [keep] outlive it: drop everything except their
     * requests. #376's arm places its run outside any turn, so a turn finishing must not
     * withdraw a card the phone can still answer.
     */
    @Synchronized
    fun closeAllExcept(keep: Set<String>): List<ApprovalRequest> {
        if (keep.isEmpty()) return closeAll()
        val dropped = pending.filter { it.runId !in keep }
        pending.removeAll(dropped)
        dropped.forEach { remember(it.key()) }
        return dropped
    }

    private fun remember(key: String) {
        answered.add(key)
        // A long call with many approvals must not grow this set without bound. The
        // oldest keys are safe to forget: their requests are long gone and a replay is
        // refused by the gateway.
        while (answered.size > ANSWERED_MEMORY) answered.remove(answered.first())
    }

    companion object {
        /** Slightly inside the gateway's own 300 s default (`approvals.timeout`), so the
         *  phone's deny lands while the request is still pending instead of racing the
         *  gateway's timeout and getting a 409. A gateway configured shorter wins the
         *  race itself; either way the answer is deny. */
        const val DEFAULT_TIMEOUT_MS = 290_000L
        private const val ANSWERED_MEMORY = 64
    }
}
