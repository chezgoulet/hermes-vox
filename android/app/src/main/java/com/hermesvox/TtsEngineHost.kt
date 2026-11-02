package com.hermesvox

/**
 * #373: the voice-output leg's owner. One engine at a time, acquired once, replaced when
 * it dies, released only at hang-up — and never used again after a release without a
 * fresh acquisition.
 *
 * Why this class exists: `VoiceController` used to build the engine once in
 * `initPipeline()` and never look at it again. Every failure after that point was
 * invisible (the system engine's `speak()` return code was ignored, and its completion
 * was a single delayed poll that could never fire), so a fallback that died left
 * `speaking`/`glueSpeaking` latched and every later utterance refused by the controller's
 * own gates. The field report — "one greeting, then never spoke again" — is that shape,
 * but the code cannot prove it without this instrument.
 *
 * The policy lives in [TtsPolicy] (pure, unit-tested); this class only carries it out:
 *   - acquire: the requested engine if its model is installed, else the system voice,
 *     always with the reason kept for the log and the status line;
 *   - on an init failure: a local voice falls back to the system voice, and only a
 *     failed SYSTEM engine leaves the call text-only (with its reason);
 *   - on an utterance failure: the engine is retired and re-acquired so the NEXT
 *     utterance has a live engine (bounded, so a wedged device cannot spin);
 *   - on [release]: the engine is shut down and dropped, and [speak] refuses to touch
 *     anything until a new acquisition happens.
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
        val released: Boolean = false,
    ) {
        val speaking: Boolean get() = ready && issue.isEmpty() && !released
    }

    companion object {
        /** Rebuilds allowed per call. A device whose engine dies on every utterance must
         *  not loop; after this the leg reports why it stopped trying. */
        const val MAX_REBUILDS = 3
    }

    private val lock = Any()
    private var generation = 0
    private var initializing = false
    private var rebuilds = 0

    @Volatile var engine: VoxTts? = null
        private set

    @Volatile var state: State = State()
        private set

    /** Make sure an engine is loaded. Starts the load when there is none; a no-op once
     *  ready, while an init is in flight, or after [release]. */
    fun ensure() {
        synchronized(lock) {
            if (state.released) return
            if (engine == null) { installForPreference(); return }
            if (state.ready || initializing) return
            val gen = generation
            val e = engine ?: return
            initializing = true
            e.init { ok -> settle(gen, ok) }
        }
    }

    /**
     * Speak one utterance. Returns false when nothing could be spoken — in that case
     * [onDone] is NOT called, and the caller must settle its own state (the reply still
     * ends; a failed output leg never blocks a turn, #330).
     */
    fun speak(text: String, onDone: (TtsOutcome) -> Unit): Boolean {
        val e: VoxTts
        val engineToken: String
        synchronized(lock) {
            val cur = engine
            if (cur == null || !state.speaking) {
                val why = when {
                    state.released -> "released"
                    state.issue.isNotBlank() -> state.issue
                    else -> "engine-not-ready"
                }
                // The line that was missing from every field report: nothing spoke, and why.
                warn("event=tts-speak engine=none reason=$why chars=${text.length}")
                // Repair for the NEXT utterance rather than leaving the leg dead.
                if (!state.released) recover("not-ready")
                return false
            }
            e = cur
            engineToken = state.engine
            debug(
                "event=tts-speak engine=$engineToken chars=${text.length} " +
                    "reason=${state.note.ifBlank { "ok" }}"
            )
        }
        e.speakReporting(text) { out ->
            when (out.end) {
                TtsEnd.SPOKEN -> {}
                // A deliberate cut (barge / hush / hang-up / a newer utterance) is not a
                // failure: the engine is healthy, so nothing is rebuilt.
                TtsEnd.INTERRUPTED -> debug("event=tts-cut engine=$engineToken code=${out.code}")
                TtsEnd.FAILED -> {
                    warn("event=tts-fail engine=$engineToken code=${out.code}")
                    recover("speak-failed")
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
            val old = engine
            engine = null
            state = state.copy(engine = "", ready = false, issue = "", note = "", released = true)
            if (old != null) try { old.shutdown() } catch (_: Throwable) {}
            publish()
        }
    }

    /** Diagnostics: one line for `warm-wait`/`event=call` logs. */
    fun diagnostics(): String =
        "ttsRequested=${state.requested.ifBlank { "-" }} ttsEngine=${state.engine.ifBlank { "-" }} " +
            "ttsReady=${state.speaking} ttsIssue=${state.issue.ifBlank { "-" }} ttsNote=${state.note.ifBlank { "-" }}"

    // ---- acquisition -------------------------------------------------------

    private fun installForPreference() {
        val req = requested().trim().lowercase()
        val local = TtsPolicy.localEngineOf(req)
        when {
            local == null -> install(TtsPolicy.SYSTEM, note = "")
            !modelPresent(local) -> {
                // Policy: the call is the product. Speak through the system voice and keep
                // the reason — a missing model used to be a VoxLog.d nobody could see.
                val why = missingReason(local)
                warn("event=tts-fallback from=$local to=system reason=$why")
                install(TtsPolicy.SYSTEM, note = why)
            }
            else -> install(local, note = "")
        }
    }

    private fun modelPresent(local: String): Boolean {
        val id = TtsPolicy.modelIdOf(local) ?: return false   // no catalogue id -> no shipped runtime
        return try { installed(id) } catch (_: Throwable) { false }
    }

    private fun missingReason(local: String): String =
        if (TtsPolicy.modelIdOf(local) == null) "$local has no runtime in this build — using the system voice"
        else "$local model not installed — using the system voice"

    private fun install(token: String, note: String) {
        val gen = ++generation
        initializing = false
        val old = engine
        engine = null
        if (old != null) try { old.shutdown() } catch (_: Throwable) {}
        val built = try { factory(token) } catch (_: Throwable) { null }
        if (built == null) {
            state = State(
                requested = requested().trim().lowercase(), engine = token, ready = false,
                issue = "$token is not available in this build", note = "",
            )
            warn("event=tts-unavailable reason=${state.issue} (call continues text-only)")
            publish()
            return
        }
        engine = built
        initializing = true
        state = State(
            requested = requested().trim().lowercase(),
            engine = token, ready = false, issue = "", note = note,
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
                state = state.copy(ready = true, issue = "")
                debug(
                    "event=tts-ready engine=${state.engine} " +
                        "reason=${state.note.ifBlank { "ok" }}"
                )
                publish()
                return
            }
            val why = e.warmReason.ifBlank { "engine unavailable" }
            val tried = state.engine
            if (tried != TtsPolicy.SYSTEM) {
                // A local voice that will not load never blocks the call: the guaranteed
                // engine speaks, and the log/status line say which and why.
                warn("event=tts-fallback from=$tried to=system reason=$why")
                install(TtsPolicy.SYSTEM, note = "$tried could not start ($why) — using the system voice")
                return
            }
            // The system engine itself is gone: there is nothing left to fall back to.
            state = state.copy(ready = false, issue = why)
            warn("event=tts-unavailable reason=$why (call continues text-only)")
            publish()
        }
    }

    /** A dying engine is replaced BEFORE the next utterance, within a per-call budget. */
    private fun recover(reason: String) {
        synchronized(lock) {
            if (state.released) return
            if (rebuilds >= MAX_REBUILDS) {
                val why = "voice engine failed repeatedly ($reason)"
                if (state.issue != why) {
                    state = state.copy(ready = false, issue = why)
                    warn("event=tts-unavailable reason=$why (call continues text-only)")
                    publish()
                }
                return
            }
            rebuilds++
            warn("event=tts-recover reason=$reason attempt=$rebuilds")
            installForPreference()
        }
    }

    private fun publish() {
        try { onChange(state) } catch (_: Throwable) {}
    }
}
