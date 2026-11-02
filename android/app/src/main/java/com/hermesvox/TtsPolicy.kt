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

    /** Which local voice a pref token selects, or null for the system voice. An unknown
     *  or blank token resolves to the system engine — never to a silent or ignore-the-pref
     *  engine (the pre-#31 behaviour). */
    fun localEngineOf(requested: String): String? = when (requested.trim().lowercase()) {
        "piper", "supertonic", "kokoro" -> requested.trim().lowercase()
        else -> null
    }

    /** The model-catalogue id behind a local engine token, or null when it has none. */
    fun modelIdOf(engine: String): String? = when (engine) {
        "piper" -> SherpaVoice.PIPER.modelId
        "supertonic" -> SherpaVoice.SUPERTONIC.modelId
        else -> null   // kokoro: the runtime was never shipped (see WarmTts.kokoroBackend)
    }

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
            !modelInstalled -> "$local model not installed"
            !engineReady -> "$local could not start" + if (engineIssue.isBlank()) "" else " ($engineIssue)"
            else -> ""
        }
        if (issue.isEmpty()) return TtsPlan(TtsPlan.Action.SPEAK, local, "")
        return if (systemReady) TtsPlan(TtsPlan.Action.FALL_BACK, SYSTEM, "$issue — using the system voice")
        else TtsPlan(TtsPlan.Action.REFUSE, local, "$issue, and the system voice is unavailable too")
    }

    /** The one-line status the call screen shows for a plan ("" = nothing to say). */
    fun statusLine(plan: TtsPlan): String = if (plan.action == TtsPlan.Action.SPEAK) "" else plan.reason
}
