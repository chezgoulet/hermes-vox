package com.hermesvox

import com.hermesvox.core.ApprovalVoice

/**
 * WaitPrompt — what the being says at [TurnWait.SILENT_STREAM_MS] instead of quietly
 * abandoning the caller's turn.
 *
 * The field finding (2026-11-06): identical trivial requests against the same gateway
 * came back in 1.9 s, 92.1 s and 237.1 s. The app's old 60 s silent ceiling therefore
 * fired while a run could still be on its way to succeeding: "I stopped waiting" was
 * true, "there is no answer" was not. The owner asked for the difference to be put to
 * the caller instead of guessed:
 *
 *   "Maybe after 60 seconds instead of killing a call automatically we should ask the
 *    user if they want to keep waiting and give them the option of waiting or killing
 *    the call in instances where it's truly an upstream inference provider problem."
 *
 * The question rides the approval CARD SURFACE (same screen component, same tap-or-voice
 * interaction) but is emphatically not an approval: [WaitPromptCard] carries no run id,
 * no request id, no canonical arguments and no action. Nothing here can authorise
 * anything, so there is nothing to register, audit or replay. Approvals keep their own
 * handshake in [com.hermesvox.core.ApprovalGate].
 *
 * Pure JVM: the controller owns the clock, the speech and the screen.
 */
object WaitPrompt {

    /** The card heading. Deliberately not "approval", "consent" or "confirm". */
    const val TITLE = "STILL WORKING"

    /** One short line, spoken in the being's own voice and shown as the card body. */
    const val QUESTION = "This is taking a while — keep waiting, or stop?"

    /** The card's answer window. Silence is never a yes; when it closes, the turn stops. */
    const val WINDOW_MS = 10_000L

    /** The extension when the host states no remaining allowance for the run. */
    const val DEFAULT_EXTENSION_MS = 120_000L

    const val LABEL_KEEP = "KEEP WAITING"
    const val LABEL_STOP = "STOP"

    /** Spoken after a "keep waiting" answer. */
    const val LINE_KEPT = "Okay — I'll keep waiting."

    /** The caller chose to stop. */
    const val LINE_STOPPED = "Stopped waiting."

    /** The card's window closed without an answer. */
    const val LINE_NO_ANSWER = "I didn't hear an answer, so I stopped waiting."

    /** The granted extension ended with the stream still silent. */
    const val LINE_EXTENDED = "The extra time ran out with no answer, so I stopped waiting."

    enum class Answer { KEEP, STOP }

    /** What the poll loop should do about the wait prompt on this tick. */
    enum class Step {
        /** No prompt yet; keep polling normally. */
        NOT_YET,
        /** The silent ceiling is reached: show and speak the question. */
        ASK,
        /** The card is up and its window has not closed. */
        CARD_UP,
        /** "Keep waiting" was answered; the extension is still running. */
        KEEP_WAITING,
        /** The caller answered "stop". */
        STOP_ANSWERED,
        /** The card's window closed with no answer. */
        STOP_NO_ANSWER,
        /** The granted extension ended with no answer. */
        STOP_AFTER_EXTENSION,
    }

    /** What happened to the card, for the screen and the one log line. */
    enum class Outcome { KEEP, STOP, NO_ANSWER, EXTENDED, DISMISSED }

    /**
     * The words that answer the wait card. This is the SAME allowlist shape as
     * [ApprovalVoice]: the whole utterance must be one of a short list (at most
     * [ApprovalVoice.MAX_WORDS] words), a question is never an answer, and any of the
     * approval surface's negations makes a non-deny utterance no answer at all. "Keep
     * waiting" is not consent to an action, so it has its own positive set; "stop" is the
     * approval surface's own refusal vocabulary.
     */
    private val KEEP = setOf(
        "keep waiting", "keep waiting please", "keep going", "keep on waiting", "keep",
        "continue", "carry on", "still waiting", "i'll wait", "ill wait",
        "let's wait", "lets wait", "wait for it", "go on",
        "yes", "yes please", "yes keep waiting", "yes keep going", "yes wait",
        "i'll keep waiting", "ill keep waiting",
    )

    /**
     * Maps a recognised utterance to an answer, or null when it is not one. Null is the
     * caller's NEW TURN (the controller dismisses the card and sends the words to the
     * mind) — a hedge is not a yes and not a no.
     */
    fun resolve(text: String): Answer? {
        if (text.contains('?')) return null
        val said = ApprovalVoice.normalize(text)
        if (said.isEmpty()) return null
        val words = said.split(' ')
        if (words.size > ApprovalVoice.MAX_WORDS) return null
        if (words.first() in ApprovalVoice.QUESTION) return null
        if (said in ApprovalVoice.DENY) return Answer.STOP
        if (words.any { it in ApprovalVoice.NEGATION }) return null
        if (said in KEEP) return Answer.KEEP
        return null
    }

    /**
     * How much longer the caller's "keep waiting" buys. A host that states how much of the
     * run's allowance remains gets exactly that (a host-stated zero buys nothing); with no
     * host statement the app's own 120 s window applies.
     */
    fun extensionMs(hostRunRemainingMs: Long?): Long =
        if (hostRunRemainingMs == null) DEFAULT_EXTENSION_MS else hostRunRemainingMs.coerceAtLeast(0L)

    /**
     * The poll loop's decision. [askedAtMs] is -1 before the question; [answer] is null
     * until the caller answers. An answered STOP always wins, an answered KEEP runs its
     * extension even if a tool or an approval later appears (the caller already chose),
     * and neither a running tool nor an approval card ever raises the question.
     */
    fun step(
        silentForMs: Long,
        nowMs: Long,
        askedAtMs: Long,
        answer: Answer?,
        extensionEndsAtMs: Long,
        toolRunning: Boolean,
        approvalWaiting: Boolean,
    ): Step {
        if (answer == Answer.STOP) return Step.STOP_ANSWERED
        if (answer == Answer.KEEP) {
            // The extension is a wall-clock window in which the question is not asked
            // again. It only ends the turn if the stream is ALSO still silent at that
            // point: a run that came back and kept streaming is not "silent to the end".
            return if (extensionEndsAtMs >= 0L && nowMs >= extensionEndsAtMs &&
                silentForMs >= TurnWait.SILENT_STREAM_MS) Step.STOP_AFTER_EXTENSION
            else Step.KEEP_WAITING
        }
        if (toolRunning || approvalWaiting) return Step.NOT_YET
        if (askedAtMs < 0L) {
            return if (silentForMs >= TurnWait.SILENT_STREAM_MS) Step.ASK else Step.NOT_YET
        }
        return if (nowMs - askedAtMs >= WINDOW_MS) Step.STOP_NO_ANSWER else Step.CARD_UP
    }

    /** The turning-point step's abandonment reason; null when nothing is being abandoned. */
    fun reason(step: Step): String? = when (step) {
        Step.STOP_ANSWERED -> TurnRepair.REASON_WAIT_STOP
        Step.STOP_NO_ANSWER -> TurnRepair.REASON_WAIT_NO_ANSWER
        Step.STOP_AFTER_EXTENSION -> TurnRepair.REASON_WAIT_EXTENDED
        else -> null
    }

    /** The screen outcome for a stopping step, or null when the card stays/keeps. */
    fun stopOutcome(step: Step): Outcome? = when (step) {
        Step.STOP_ANSWERED -> Outcome.STOP
        Step.STOP_NO_ANSWER -> Outcome.NO_ANSWER
        Step.STOP_AFTER_EXTENSION -> Outcome.EXTENDED
        else -> null
    }
}

/**
 * The one wait prompt in a turn. It is the exactly-once latch for the two answers and the
 * one extension — and deliberately nothing else. It holds no run/request id and never
 * touches [com.hermesvox.core.ApprovalGate].
 *
 * Synchronised because the poll loop reads it on the worker while the card is answered on
 * main (touch), or on the transcriber (voice).
 */
internal class WaitPromptRun {

    private var askedAtMs = -1L
    private var answered: WaitPrompt.Answer? = null
    private var extensionEndsAtMs = -1L
    private var extensionGiven = false
    private var grantedExtensionMs = 0L

    @Synchronized fun askedAtMs(): Long = askedAtMs
    @Synchronized fun answer(): WaitPrompt.Answer? = answered
    @Synchronized fun extensionEndsAtMs(): Long = extensionEndsAtMs
    @Synchronized fun extensionGiven(): Boolean = extensionGiven
    @Synchronized fun grantedExtensionMs(): Long = grantedExtensionMs

    /** Marks the question shown. False when it was already asked this turn. */
    @Synchronized
    fun markAsked(nowMs: Long): Boolean {
        if (askedAtMs >= 0L) return false
        askedAtMs = nowMs
        return true
    }

    /**
     * Records the first answer. False for a duplicate (a second tap, a late voice match, a
     * replay), so the caller can never answer the same prompt twice. A KEEP answer grants
     * the extension exactly once.
     */
    @Synchronized
    fun answer(answer: WaitPrompt.Answer, nowMs: Long, extensionMs: Long): Boolean {
        if (askedAtMs < 0L || answered != null) return false
        answered = answer
        if (answer == WaitPrompt.Answer.KEEP && !extensionGiven) {
            extensionGiven = true
            grantedExtensionMs = extensionMs.coerceAtLeast(0L)
            extensionEndsAtMs = nowMs + grantedExtensionMs
        }
        return true
    }

    @Synchronized
    fun step(silentForMs: Long, nowMs: Long, toolRunning: Boolean, approvalWaiting: Boolean): WaitPrompt.Step =
        WaitPrompt.step(
            silentForMs = silentForMs,
            nowMs = nowMs,
            askedAtMs = askedAtMs,
            answer = answered,
            extensionEndsAtMs = extensionEndsAtMs,
            toolRunning = toolRunning,
            approvalWaiting = approvalWaiting,
        )

    /** The turn ended, or a resumed stream made the prompt irrelevant. */
    @Synchronized
    fun reset() {
        askedAtMs = -1L
        answered = null
        extensionEndsAtMs = -1L
        extensionGiven = false
        grantedExtensionMs = 0L
    }
}

/**
 * The screen snapshot for a wait prompt. It is a separate type from
 * [ApprovalPrompt] on purpose: no run id, no request id, no canonical arguments and no
 * gateway choices. A later call cannot use anything here as an approval.
 */
class WaitPromptCard(
    val title: String = WaitPrompt.TITLE,
    val question: String = WaitPrompt.QUESTION,
    val timeoutMs: Long = WaitPrompt.WINDOW_MS,
) {
    /** Exactly the two answers this card can hear, in reading order. */
    val answers: List<WaitPrompt.Answer> get() = listOf(WaitPrompt.Answer.KEEP, WaitPrompt.Answer.STOP)

    fun label(answer: WaitPrompt.Answer): String = when (answer) {
        WaitPrompt.Answer.KEEP -> WaitPrompt.LABEL_KEEP
        WaitPrompt.Answer.STOP -> WaitPrompt.LABEL_STOP
    }
}
