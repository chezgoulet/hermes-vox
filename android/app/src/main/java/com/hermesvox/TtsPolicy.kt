package com.hermesvox

/**
 * #373: which engine speaks this call, and when the app refuses to speak at all.
 *
 * The field report ("Piper model was not installed, and it let me place a call
 * anyway — no TTS and no signal") came from two questions the app answered
 * silently: a missing model was swapped for the system voice with only a
 * `VoxLog.d`, and the setting still said Piper. The answer is a policy, decided
 * from plain inputs so all three outcomes are testable without a device:
 *
 *  - SPEAK      the requested engine is there and loaded;
 *  - FALL_BACK  the requested voice cannot speak, so the guaranteed system voice
 *               does, and the reason travels with the plan (status line + log);
 *  - REFUSE     no engine exists at all. The call may still open (that is #330's
 *               rule — the output leg never blocks the call), but spoken replies
 *               are announced off WITH the reason, never left to silence.
 *
 * Proceeding into silence is not an outcome here: every plan either names the
 * engine that will speak or names why nothing can.
 */
internal data class TtsPlan(
    val action: Action,
    /** The engine that will speak ("system", "piper", "supertonic", "kokoro"), or the
     *  voice the user asked for when the action is REFUSE. */
    val engine: String,
    /** Why this plan — the text a status line or a log line carries. Empty for a clean SPEAK. */
    val reason: String,
) {
    enum class Action { SPEAK, FALL_BACK, REFUSE }

    val speaks: Boolean get() = action != Action.REFUSE
    /** Degraded but audible: the requested voice is not the one speaking. */
    val degraded: Boolean get() = action == Action.FALL_BACK
}

internal object TtsPolicy {

    /** The engine that is always present: Android's own TextToSpeech. */
    const val SYSTEM = "system"

    /** The engine name as the owner reads it in Settings. */
    fun displayName(engine: String): String {
        val t = engine.trim().lowercase()
        SherpaVoice.fromEngineToken(t)?.let {
            // Keep the short historical name for the en-US Piper token; a French voice
            // has no shorter name and is named exactly as Settings names it.
            return if (it == SherpaVoice.PIPER) "Piper" else it.displayName
        }
        return when (t) {
            SYSTEM -> "System"
            "kokoro" -> "Kokoro"
            else -> engine.trim().replaceFirstChar { it.uppercase() }
        }
    }

    /** Mid-sentence name: "the system voice", "the Piper voice". */
    fun voiceName(engine: String): String =
        if (engine.trim().lowercase() == SYSTEM) "the system voice"
        else "the ${displayName(engine)} voice"

    /** Sentence-opening name: "The system voice", "Piper". */
    fun sentenceName(engine: String): String =
        if (engine.trim().lowercase() == SYSTEM) "The system voice"
        else displayName(engine)

    /** What the user can do about a missing/refused voice, and where. */
    fun fixHint(local: String?): String = when {
        local == null -> "Enable a text-to-speech engine in Android Settings → Accessibility → Text-to-speech."
        else -> "Install the ${displayName(local)} model in Settings → Models, or enable a text-to-speech " +
            "engine in Android Settings → Accessibility → Text-to-speech."
    }

    /** The refusal line: no engine can speak at all, so the call does not start. It names
     *  what is missing and where to get it — never a silent call. */
    fun refusalLine(tried: String, why: String, initialIssue: String, local: String?): String {
        val missing = initialIssue.ifBlank {
            if (tried == SYSTEM) "the system text-to-speech engine is unavailable"
            else "${displayName(tried)} could not start ($why)"
        }
        return "Can't start the call — $missing, and no other voice is available. ${fixHint(local)}"
    }

    /** The call-start decision, kept pure so "does the call open?" is testable. A leg that
     *  has not settled yet does not block the call; it is still deciding. Once settled,
     *  either a voice speaks (with a note when it is not the requested one) or the call is
     *  refused with the stated reason. */
    data class CallGate(val starts: Boolean, val line: String)

    fun callGate(settled: Boolean, speaking: Boolean, issue: String, note: String): CallGate = when {
        !settled -> CallGate(starts = true, line = "")
        speaking -> CallGate(starts = true, line = note)
        issue.isNotBlank() -> CallGate(starts = false, line = issue)
        else -> CallGate(starts = false, line = "Can't start the call — no voice can speak. ${fixHint(null)}")
    }

    /** Which local voice a pref token selects, or null for the system voice. An unknown
     *  or blank token resolves to the system engine — never to a silent or ignore-the-pref
     *  engine (the pre-#31 behaviour). "piper" is normalized to the token Settings writes,
     *  so the stored model id of the en-US voice cannot become a second spelling. */
    fun localEngineOf(requested: String): String? {
        val t = requested.trim().lowercase()
        if (t == "kokoro") return "kokoro"
        return when (val voice = SherpaVoice.fromEngineToken(t)) {
            null -> null
            SherpaVoice.PIPER -> "piper"
            else -> voice.modelId
        }
    }

    /** The model-catalogue id behind a local engine token, or null when it has none. */
    fun modelIdOf(engine: String): String? =
        SherpaVoice.fromEngineToken(engine)?.modelId   // kokoro: never shipped (WarmTts.kokoroBackend)

    /**
     * The decision. [engineReady] is about the REQUESTED local engine only; it says
     * nothing about the system voice, which has its own [systemReady] input because a
     * device with no TTS service at all is a genuinely different situation.
     */
    fun plan(
        requested: String,
        modelInstalled: Boolean,
        engineReady: Boolean,
        engineIssue: String,
        systemReady: Boolean,
    ): TtsPlan {
        val local = localEngineOf(requested)
        if (local == null) {
            return if (systemReady) TtsPlan(TtsPlan.Action.SPEAK, SYSTEM, "")
            else TtsPlan(TtsPlan.Action.REFUSE, SYSTEM,
                engineIssue.ifBlank { "system text-to-speech engine unavailable" })
        }
        val issue = when {
            !modelInstalled -> "${displayName(local)} model not installed"
            !engineReady -> "${displayName(local)} could not start" + if (engineIssue.isBlank()) "" else " ($engineIssue)"
            else -> ""
        }
        if (issue.isEmpty()) return TtsPlan(TtsPlan.Action.SPEAK, local, "")
        return if (systemReady) TtsPlan(TtsPlan.Action.FALL_BACK, SYSTEM, "$issue — using the system voice")
        else TtsPlan(TtsPlan.Action.REFUSE, local, "$issue, and the system voice is unavailable too")
    }

    /** The one-line status the call screen shows for a plan ("" = nothing to say). */
    fun statusLine(plan: TtsPlan): String = if (plan.action == TtsPlan.Action.SPEAK) "" else plan.reason
}
