package com.hermesvox

/**
 * #373 / lifecycle follow-up: the voice-output leg's owner. One engine at a time, acquired
 * once, replaced when it dies, released only at hang-up — and never used again after a
 * release without a fresh acquisition.
 *
 * The field reports this fixes: "I set it to System Fallback. It said one greeting, and
 * then never spoke again." A warm failure used to be recorded and the call continued:
 * nothing re-warmed, nothing noticed that an engine which spoke once had stopped, and a
 * rebuild budget of three could silently cycle the SAME dead engine. Now the sequence is
 * bounded and stated:
 *
 *   1. a failed utterance / engine onError / silence where audio was due marks the engine
 *      unavailable with the reason;
 *   2. ONE re-warm of that engine is attempted;
 *   3. if the re-warm fails (or the re-warmed engine dies), the leg degrades ONCE to the
 *      other engine (system ⇄ the installed local voice) and says which voice is speaking;
 *   4. if the other engine also fails, the leg states a steady "spoken replies off" reason
 *      and makes no further attempts. There is no loop.
 *
 * The policy lives in [TtsPolicy] (pure, unit-tested); this class carries it out.
 */
internal class TtsEngineHost(
    /** The live `tts` preference (re-read on every acquisition: Settings writes are immediate). */
    private val requested: () -> String,
    /** Is the model behind a local engine token installed? */
    private val installed: (String) -> Boolean,
    /** Build exactly this engine token, or null when this build cannot. */
    private val factory: (String) -> VoxTts?,
    /** Called on every state transition (engine install, ready, failure, release). */
    private val onChange: (State) -> Unit = {},
    /** Warn/debug sinks. Injected so the host's log lines — the instrument this issue is
     *  missing — can be asserted in a unit test instead of only read off a device. */
    private val warn: (String) -> Unit = {},
    private val debug: (String) -> Unit = {},
) {

    /** What the call screen and the log need to know about the leg right now. */
    data class State(
        /** What the user asked for (the pref token). */
        val requested: String = "",
        /** The engine that would speak next ("system", "piper", …; "" = none). */
        val engine: String = "",
        val ready: Boolean = false,
        /** Non-empty = spoken replies are OFF, and this is why. */
        val issue: String = "",
        /** Non-empty = spoken replies work, but not in the requested voice, and this is why. */
        val note: String = "",
        /** The leg has reached a decision (ready, or genuinely refused). The call gate
         *  waits for this instead of guessing from a half-initialised engine. */
        val settled: Boolean = false,
        val released: Boolean = false,
    ) {
        val speaking: Boolean get() = ready && issue.isEmpty() && !released
        /** No engine can speak at all, and the reason is known. */
        val refused: Boolean get() = settled && !speaking && issue.isNotBlank()
    }

    companion object {
        /**
         * #549: failures of the output path rather than of the engine. A track that could not
         * be built is retried on the next utterance; it must not spend the one re-warm or the
         * one degradation, and it must never reach [steadyOff] — a transient audio failure used
         * to turn a working voice into a silent call until the next session.
         */
        val NOT_THE_ENGINE = setOf("track-unavailable")

        /** Re-warm attempts per call. A second warm failure degrades instead of trying
         *  the same engine again. */
        const val MAX_REWARMS = 1
        /** Degradations per call (system ⇄ local). After this the leg states its reason. */
        const val MAX_DEGRADATIONS = 1
    }

    /** Why an engine is being acquired. It decides what a failed init means. */
    private enum class Acquire { INITIAL, REWARM, DEGRADE }

    private val lock = Any()
    private var generation = 0
    private var initializing = false
    private var rewarmed = false
    private var degraded = false
    /** No further acquisition attempts: the leg has stated its steady state. */
    private var terminal = false
    private var acquiring = Acquire.INITIAL
    /** The missing/unstartable local voice that forced the initial system fallback, kept so
     *  a refusal can name it even when the system voice is the engine that failed. */
    private var initialIssue = ""
    private var initialLocal: String? = null

    @Volatile var engine: VoxTts? = null
        private set

    @Volatile var state: State = State()
        private set

    /** Make sure an engine is loaded. Starts the load when there is none; a no-op once
     *  ready, while an init is in flight, after a steady-state failure, or after [release]. */
    fun ensure() {
        synchronized(lock) {
            if (state.released || terminal) return
            if (engine == null) { installForPreference(); return }
            if (state.ready || initializing) return
            val gen = generation
            val e = engine ?: return
            initializing = true
            acquiring = Acquire.INITIAL
            e.init { ok -> settle(gen, ok) }
        }
    }

    /**
     * Speak one utterance. Returns false when nothing could be spoken — in that case
     * [onDone] is NOT called, and the caller must settle its own state (the reply still
     * ends; a failed output leg never blocks a turn, #330).
     */
    fun speak(text: String, onDone: (TtsOutcome) -> Unit): Boolean = speak(text, {}, onDone)

    /** [speak], plus [onStart] at the moment the line becomes audible (see [VoxTts.speakReporting]). */
    fun speak(text: String, onStart: () -> Unit, onDone: (TtsOutcome) -> Unit): Boolean {
        val e: VoxTts
        val engineToken: String
        synchronized(lock) {
            val cur = engine
            if (cur == null || !state.speaking) {
                val why = when {
                    state.released -> "released"
                    state.issue.isNotBlank() -> state.issue
                    initializing -> "warming"
                    terminal -> "stopped"
                    else -> "engine-not-ready"
                }
                // The line that was missing from every field report: nothing spoke, and why.
                warn("event=tts-speak engine=none reason=$why chars=${text.length}")
                // Repair for the NEXT utterance rather than leaving the leg dead. A speak
                // during an in-flight re-warm must not count as another failure (that is
                // how a single bad turn used to burn the whole retry budget).
                if (!state.released && !initializing && !terminal) recover("not-ready")
                return false
            }
            e = cur
            engineToken = state.engine
            debug(
                "event=tts-speak engine=$engineToken chars=${text.length} " +
                    "reason=${state.note.ifBlank { "ok" }}"
            )
        }
        // An engine that reports SPOKEN without ever becoming audible did not speak: that
        // is silence where audio was due, not success. The start callback is the only
        // honest witness (a line can be dropped after hand-over).
        var started = false
        e.speakReporting(text, {
            started = true
            try { onStart() } catch (_: Throwable) {}
        }) { raw ->
            val silent = raw.end == TtsEnd.SPOKEN && !started
            val out = if (silent) TtsOutcome(TtsEnd.FAILED, "silent") else raw
            when (out.end) {
                TtsEnd.SPOKEN -> {}
                // A deliberate cut (barge / hush / hang-up / a newer utterance) is not a
                // failure: the engine is healthy, so nothing is rebuilt.
                TtsEnd.INTERRUPTED -> debug("event=tts-cut engine=$engineToken code=${out.code}")
                TtsEnd.FAILED -> {
                    warn("event=tts-fail engine=$engineToken code=${out.code}")
                    if (out.code in NOT_THE_ENGINE) {
                        debug("event=tts-recover-skip engine=$engineToken reason=${out.code}")
                    } else {
                        recover(if (silent) "silent" else "speak-failed")
                    }
                }
            }
            onDone(out)
        }
        return true
    }

    /**
     * Hang-up: shut the engine down and drop it. A stopped controller is terminal (its
     * executor is shut down), so this is the last transition — and [speak] can never
     * reach a released engine, because the only way back is a new [ensure]/[recover]
     * that installs a fresh one.
     */
    fun release() {
        synchronized(lock) {
            if (state.released) return
            generation++
            initializing = false
            terminal = true
            val old = engine
            engine = null
            state = state.copy(engine = "", ready = false, issue = "", note = "", settled = true, released = true)
            if (old != null) try { old.shutdown() } catch (_: Throwable) {}
            publish()
        }
    }

    /** Diagnostics: one line for `warm-wait`/`event=call` logs. */
    fun diagnostics(): String =
        "ttsRequested=${state.requested.ifBlank { "-" }} ttsEngine=${state.engine.ifBlank { "-" }} " +
            "ttsReady=${state.speaking} ttsSettled=${state.settled} " +
            "ttsIssue=${state.issue.ifBlank { "-" }} ttsNote=${state.note.ifBlank { "-" }}"

    // ---- acquisition -------------------------------------------------------

    private fun installForPreference() {
        val req = requested().trim().lowercase()
        val local = TtsPolicy.localEngineOf(req)
        initialLocal = local
        when {
            local == null -> install(TtsPolicy.SYSTEM, note = "", kind = Acquire.INITIAL)
            !modelPresent(local) -> {
                // Policy: the call is the product. Speak through the system voice and keep
                // the reason — a missing model used to be a VoxLog.d nobody could see.
                val why = missingReason(local)
                initialIssue = missingIssue(local)
                warn("event=tts-fallback from=$local to=system reason=$why")
                install(TtsPolicy.SYSTEM, note = why, kind = Acquire.INITIAL)
            }
            else -> install(local, note = "", kind = Acquire.INITIAL)
        }
    }

    private fun modelPresent(local: String): Boolean {
        val id = TtsPolicy.modelIdOf(local) ?: return false   // no catalogue id -> no shipped runtime
        return try { installed(id) } catch (_: Throwable) { false }
    }

    /** The plain reason a local voice cannot be the one that speaks. */
    private fun missingIssue(local: String): String =
        if (TtsPolicy.modelIdOf(local) == null) "${TtsPolicy.displayName(local)} has no runtime in this build"
        else "${TtsPolicy.displayName(local)} model not installed"

    private fun missingReason(local: String): String =
        "${missingIssue(local)} — using the system voice"

    private fun install(token: String, note: String, kind: Acquire) {
        val gen = ++generation
        initializing = false
        acquiring = kind
        val old = engine
        engine = null
        if (old != null) try { old.shutdown() } catch (_: Throwable) {}
        val built = try { factory(token) } catch (_: Throwable) { null }
        if (built == null) {
            state = State(
                requested = requested().trim().lowercase(), engine = token, ready = false,
                issue = "", note = note, settled = false,
            )
            refuse(token, "not available in this build")
            return
        }
        engine = built
        initializing = true
        state = State(
            requested = requested().trim().lowercase(),
            engine = token, ready = false, issue = "", note = note, settled = false,
        )
        publish()
        built.init { ok -> settle(gen, ok) }
    }

    private fun settle(gen: Int, ok: Boolean) {
        synchronized(lock) {
            if (gen != generation) return   // a newer engine owns the leg
            initializing = false
            val e = engine ?: return
            if (ok) {
                state = state.copy(ready = true, issue = "", settled = true)
                debug(
                    "event=tts-ready engine=${state.engine} " +
                        "reason=${state.note.ifBlank { "ok" }}"
                )
                publish()
                return
            }
            val why = e.warmReason.ifBlank { "engine unavailable" }
            val tried = state.engine
            when (acquiring) {
                Acquire.INITIAL -> initialInitFailed(tried, why)
                Acquire.REWARM -> warmFailed(tried, why)
                Acquire.DEGRADE -> steadyOff(tried, why)
            }
        }
    }

    /** First acquisition failed. A local voice falls back to the guaranteed system voice;
     *  a failed system voice tries the other installed voice, else refuses with a reason. */
    private fun initialInitFailed(tried: String, why: String) {
        if (tried != TtsPolicy.SYSTEM) {
            initialIssue = "${TtsPolicy.displayName(tried)} could not start ($why)"
            warn("event=tts-fallback from=$tried to=system reason=$why")
            install(
                TtsPolicy.SYSTEM,
                note = "${TtsPolicy.displayName(tried)} could not start ($why) — using the system voice",
                kind = Acquire.INITIAL,
            )
            return
        }
        val other = otherEngineFor(tried)
        if (other != null && degraded.not()) {
            degraded = true
            warn("event=tts-degrade from=$tried to=$other reason=$why")
            install(
                other,
                note = "${TtsPolicy.sentenceName(tried)} could not start ($why) — using ${TtsPolicy.voiceName(other)}",
                kind = Acquire.DEGRADE,
            )
            return
        }
        refuse(tried, why)
    }

    /** A re-warm failed: degrade once to the other engine, or state the steady reason. */
    private fun warmFailed(tried: String, why: String) {
        if (!degraded) {
            degraded = true
            val other = otherEngineFor(tried)
            if (other != null) {
                warn("event=tts-degrade from=$tried to=$other reason=rewarm-failed")
                install(
                    other,
                    note = "${TtsPolicy.sentenceName(tried)} stopped responding — " +
                        "using ${TtsPolicy.voiceName(other)}",
                    kind = Acquire.DEGRADE,
                )
                return
            }
        }
        steadyOff(tried, why)
    }

    /** The leg has nowhere left to go: say why, and make no further attempts. */
    private fun steadyOff(tried: String, why: String) {
        terminal = true
        val subject = if (tried == TtsPolicy.SYSTEM) "the system voice" else TtsPolicy.displayName(tried)
        val reason = "$subject stopped responding and no other voice is available"
        state = state.copy(ready = false, issue = reason, note = "", settled = true)
        warn("event=tts-unavailable engine=$tried reason=$why -> $reason (call continues text-only)")
        publish()
    }

    /** No engine at all: name what is missing and where to get it. */
    private fun refuse(tried: String, why: String) {
        terminal = true
        val reason = TtsPolicy.refusalLine(tried, why, initialIssue, initialLocal)
        state = state.copy(ready = false, issue = reason, note = "", settled = true)
        warn("event=tts-unavailable reason=$reason (call refused)")
        publish()
    }

    /** The engine that can stand in for [current]: the other side of system ⇄ local. */
    private fun otherEngineFor(current: String): String? {
        if (current != TtsPolicy.SYSTEM) return TtsPolicy.SYSTEM
        val wanted = TtsPolicy.localEngineOf(requested().trim().lowercase())
        if (wanted != null && wanted != current && modelPresent(wanted)) return wanted
        // An explicit "system" preference still has a usable local voice installed: the
        // point of a degradation is that SOMETHING speaks, and the line names it.
        return listOf("supertonic", "piper").firstOrNull { it != current && modelPresent(it) }
    }

    /**
     * A warm failure was reported outside [speak] (e.g. a streaming synth error). Apply
     * the same bounded lifecycle: one re-warm, one degradation, then a stated reason.
     */
    fun noteFailure(reason: String) {
        synchronized(lock) {
            if (state.released) return
            warn("event=tts-fail engine=${state.engine} code=$reason")
            // #538: the streaming path's track failures are #549's, and cost the same: nothing.
            if (reason in NOT_THE_ENGINE) {
                debug("event=tts-recover-skip engine=${state.engine} reason=$reason")
                return
            }
            recover(reason)
        }
    }

    /** A dying engine is re-warmed ONCE; a second failure degrades instead of retrying. */
    private fun recover(reason: String) {
        synchronized(lock) {
            if (state.released || terminal) return
            // A speak during an in-flight acquisition is not a new failure.
            if (initializing) {
                debug("event=tts-recover-skip reason=$reason state=warming")
                return
            }
            val failed = state.engine
            if (failed.isBlank()) return
            // Mark the engine unavailable with the reason (the state line follows from it).
            state = state.copy(ready = false)
            warn("event=tts-unavailable engine=$failed reason=$reason")
            if (!rewarmed) {
                rewarmed = true
                warn("event=tts-recover engine=$failed reason=$reason attempt=$MAX_REWARMS")
                install(failed, note = state.note, kind = Acquire.REWARM)
                return
            }
            if (!degraded) {
                degraded = true
                val other = otherEngineFor(failed)
                if (other != null) {
                    warn("event=tts-degrade from=$failed to=$other reason=$reason")
                    install(
                        other,
                        note = "${TtsPolicy.sentenceName(failed)} stopped responding — " +
                            "using ${TtsPolicy.voiceName(other)}",
                        kind = Acquire.DEGRADE,
                    )
                    return
                }
            }
            steadyOff(failed, reason)
        }
    }

    private fun publish() {
        try { onChange(state) } catch (_: Throwable) {}
    }
}
