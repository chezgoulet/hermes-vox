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
 */
internal object ApprovalVoice {

    private val DENY = listOf(
        "no", "nope", "nah", "deny", "denied", "reject", "cancel", "stop", "never",
        "block", "don t", "do not", "not now", "don't",
    )
    private val APPROVE = listOf(
        "approve", "approved", "approves", "yes", "yeah", "yep", "ok", "okay",
        "allow", "allowed", "proceed", "go ahead", "go", "sure", "confirm",
        "confirmed", "do it",
    )

    /** The gateway choices that count as a yes, least privilege first. */
    private val POSITIVE = listOf("once", "session", "always")

    /**
     * Maps a recognised utterance to an offered choice, or null when it is neither a
     * yes nor a no. A denial wins when both appear ("no, don't approve").
     */
    fun resolve(text: String, choices: List<String>): String? {
        val said = normalize(text)
        if (said.isEmpty()) return null
        if (DENY.any { hasPhrase(said, it) }) return "deny"
        if (APPROVE.any { hasPhrase(said, it) }) return POSITIVE.firstOrNull { it in choices }
        return null
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Word-boundary match, so "no" does not fire inside "notify" and "ok" not inside "token". */
    private fun hasPhrase(said: String, phrase: String): Boolean {
        val pattern = "(^| )" + Regex.escape(phrase) + "( |$)"
        return Regex(pattern).containsMatchIn(said)
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
