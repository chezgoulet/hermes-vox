package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #373: the voice-output engine's lifecycle, exercised with fake engines.
 *
 * These tests exist because the field report could not be settled from the record:
 * a fallback that spoke once and went quiet is either "the engine was released and
 * never rebuilt" or "an engine was still there and silent". Both are now testable
 * shapes, so the next round has a fact instead of a guess.
 *
 * The load-bearing one is [a_released_engine_is_never_used_without_reacquisition]:
 * after a hang-up the engine is shut down and dropped, and a speak must not reach it.
 */
class TtsEngineHostTest {

    /** A fake engine. [warm] = does init succeed; [end] = how every utterance ends
     *  (or [endAt] when an engine's first utterance works and later ones throw). */
    private class Fake(
        val token: String = "fake",
        private val warm: Boolean = true,
        private val end: TtsEnd = TtsEnd.SPOKEN,
        private val endAt: (Int) -> TtsEnd = { end },
        /** When set, this fake reports through the interface's default (SPOKEN). */
        private val plain: Boolean = false,
    ) : VoxTts {
        var inits = 0
        var shutdowns = 0
        var spoken = 0
        var lastText = ""
        override val name get() = token
        override val isWarm get() = warm
        override val warmReason get() = if (warm) "" else "$token will not load"
        override fun init(onReady: (Boolean) -> Unit) { inits++; onReady(warm) }
        override fun speak(text: String, onDone: () -> Unit) { spoken++; lastText = text; onDone() }
        override fun speakReporting(text: String, onDone: (TtsOutcome) -> Unit) {
            if (plain) return super.speakReporting(text, onDone)
            val out = endAt(spoken)
            spoken++; lastText = text
            onDone(TtsOutcome(out, "fake-$out"))
        }
        override fun stop() {}
        override fun shutdown() { shutdowns++ }
    }

    private val warns = ArrayList<String>()
    private val debugs = ArrayList<String>()

    private fun host(
        requested: String = "piper",
        installed: Set<String> = setOf("piper-lessac"),
        factory: (String) -> VoxTts? = { Fake(it) },
    ) = TtsEngineHost(
        requested = { requested },
        installed = { it in installed },
        factory = factory,
        warn = { warns += it },
        debug = { debugs += it },
    )

    // ---- the release rule --------------------------------------------------

    @Test fun a_released_engine_is_never_used_without_reacquisition() {
        val built = ArrayList<Fake>()
        val h = host { token -> Fake(token).also { built += it } }
        h.ensure()
        assertTrue("the installed Piper model loads", h.state.speaking)

        var completions = 0
        assertTrue(h.speak("hello") { completions++ })
        assertEquals(1, built[0].spoken)
        assertEquals(1, completions)

        // Hang-up.
        h.release()
        assertFalse("a released leg is not ready", h.state.speaking)
        assertEquals("the engine was shut down exactly once", 1, built[0].shutdowns)
        assertEquals("...and dropped", null, h.engine)

        // A speak after the release must NOT reach the old engine, and must not silently
        // build a new one either: the caller is the one that decides to re-acquire.
        completions = 0
        assertFalse(h.speak("after hang-up") { completions++ })
        assertEquals("the released engine never spoke again", 1, built[0].spoken)
        assertEquals("no completion is invented for a speak that did not happen", 0, completions)
        assertEquals("nothing was re-acquired behind the caller's back", 1, built.size)

        h.release()   // idempotent: never a second shutdown
        assertEquals(1, built[0].shutdowns)
    }

    @Test fun after_a_release_a_fresh_acquisition_is_a_new_engine() {
        val built = ArrayList<Fake>()
        val h = host { token -> Fake(token).also { built += it } }
        h.ensure()
        h.speak("one") {}
        h.release()
        // A new controller/leg asks to warm: that is an explicit new acquisition.
        val h2 = host { token -> Fake(token).also { built += it } }
        h2.ensure()
        assertTrue(h2.state.speaking)
        assertTrue(h2.speak("two") {})
        assertEquals(2, built.size)
        assertEquals(1, built[1].spoken)
    }

    // ---- a death mid-call is repaired before the next utterance ------------

    @Test fun a_failed_utterance_replaces_the_engine_before_the_next_one() {
        val built = ArrayList<Fake>()
        val seen = ArrayList<TtsEngineHost.State>()
        val h = TtsEngineHost(
            requested = { "piper" }, installed = { true },
            factory = { token ->
                // The first engine loads but dies on its first utterance; its replacement works.
                Fake(token, end = if (built.isEmpty()) TtsEnd.FAILED else TtsEnd.SPOKEN).also { built += it }
            },
            onChange = { seen += it },
        )
        h.ensure()
        assertEquals(1, built.size)

        h.speak("first") {}
        assertEquals("the dead engine was replaced at once", 2, built.size)
        assertEquals("the dead one was shut down", 1, built[0].shutdowns)
        assertTrue("the leg is up again", h.state.speaking)

        h.speak("second") {}
        assertEquals("the next utterance went to the replacement", "second", built[1].lastText)
        assertEquals("the first went to the dead engine", "first", built[0].lastText)
        assertTrue("the states include the recovery", seen.any { it.ready })
        assertTrue("...and end up speaking", seen.last().speaking)
        assertEquals("", h.state.issue)
    }

    @Test fun a_deliberate_cut_does_not_replace_a_healthy_engine() {
        val built = ArrayList<Fake>()
        val h = host { token -> Fake(token, end = TtsEnd.INTERRUPTED).also { built += it } }
        h.ensure()
        h.speak("cut me") {}
        assertEquals("a barge/hang-up is not an engine failure", 1, built.size)
        assertTrue(h.state.speaking)
    }

    @Test fun the_interface_default_still_reports_a_plain_engine_as_spoken() {
        val h = host { token -> Fake(token, plain = true) }
        h.ensure()
        var end: TtsEnd? = null
        assertTrue(h.speak("hi") { end = it.end })
        assertEquals(TtsEnd.SPOKEN, end)
    }

    // ---- the missing-model policy, applied --------------------------------

    @Test fun a_missing_model_speaks_through_the_system_voice_and_says_so() {
        val built = ArrayList<Fake>()
        val h = host(requested = "piper", installed = emptySet()) { token -> Fake(token).also { built += it } }
        h.ensure()
        assertEquals("system", h.state.engine)
        assertEquals("the local engine was never built", listOf("system"), built.map { it.token })
        assertTrue(h.state.speaking)
        assertTrue("the note names the model", h.state.note.contains("Piper model not installed"))
        assertTrue("...and says which voice speaks", h.state.note.contains("using the system voice"))
        assertEquals("", h.state.issue)
    }

    @Test fun a_local_engine_that_will_not_load_falls_back_to_the_system_voice() {
        val built = ArrayList<Fake>()
        val h = host(requested = "piper", installed = setOf("piper-lessac")) { token ->
            Fake(token, warm = token == "system").also { built += it }
        }
        h.ensure()
        assertEquals(listOf("piper", "system"), built.map { it.token })
        assertEquals("system", h.state.engine)
        assertTrue(h.state.speaking)
        assertTrue(h.state.note.contains("Piper could not start"))
    }

    @Test fun no_engine_at_all_is_refused_with_a_reason_and_speaks_nothing() {
        val h = host(requested = "system", installed = emptySet()) { token -> Fake(token, warm = false) }
        h.ensure()
        assertFalse(h.state.speaking)
        assertTrue("the status line gets a reason, not silence", h.state.issue.isNotBlank())
        var completed = false
        assertFalse(h.speak("hi") { completed = true })
        assertFalse("no completion is invented", completed)
    }

    // ---- lifecycle follow-up: one re-warm, one degradation, no loop ----------

    @Test fun an_engine_that_speaks_once_then_throws_is_rewarmed_once_then_degrades() {
        val built = ArrayList<Fake>()
        val h = TtsEngineHost(
            requested = { "piper" }, installed = { true },
            factory = { token ->
                val f = when (built.size) {
                    // The first engine speaks once, then throws on every later utterance.
                    0 -> Fake(token, endAt = { turn -> if (turn == 0) TtsEnd.SPOKEN else TtsEnd.FAILED })
                    // Its ONE re-warm also throws: this is the "fails again" case.
                    1 -> Fake(token, end = TtsEnd.FAILED)
                    // The other engine (system) speaks.
                    else -> Fake(token, end = TtsEnd.SPOKEN)
                }
                built += f
                f
            },
            warn = { warns += it },
            debug = { debugs += it },
        )
        h.ensure()
        assertTrue(h.speak("greeting") {})
        assertEquals("piper", h.state.engine)

        assertTrue(h.speak("the failing turn") {})
        assertEquals("one re-warm of the same engine", listOf("piper", "piper"), built.map { it.token })
        assertEquals("piper", h.state.engine)
        assertTrue("the re-warmed engine is live", h.state.speaking)

        assertTrue(h.speak("still failing") {})
        assertEquals("degraded exactly once, to the other engine", listOf("piper", "piper", "system"), built.map { it.token })
        assertEquals("system", h.state.engine)
        assertTrue("a voice is speaking", h.state.speaking)
        assertEquals("the leg is not reported off", "", h.state.issue)
        assertTrue(
            "the status line must name the voice now speaking: ${h.state.note}",
            h.state.note.contains("Piper stopped responding") &&
                h.state.note.contains("using the system voice"),
        )
        assertTrue(warns.any { it.startsWith("event=tts-degrade from=piper to=system") })

        // No third attempt: further turns use the degraded voice, they do not rebuild.
        assertTrue(h.speak("after degradation") {})
        assertTrue(h.speak("and again") {})
        assertEquals("no third attempt happens", 3, built.size)
        assertEquals("the degraded engine is the one speaking", "and again", built[2].lastText)
    }

    @Test fun a_successful_rewarm_clears_the_status_line() {
        val built = ArrayList<Fake>()
        val h = host { token ->
            // The first instance dies on its first utterance; its re-warm is healthy.
            Fake(token, end = if (built.isEmpty()) TtsEnd.FAILED else TtsEnd.SPOKEN).also { built += it }
        }
        h.ensure()
        h.speak("first") {}
        assertEquals("one re-warm", listOf("piper", "piper"), built.map { it.token })
        assertTrue(h.state.speaking)
        assertEquals("the status line clears", "", h.state.note)
        assertEquals("", h.state.issue)
    }

    @Test fun a_voice_that_keeps_failing_degrades_once_then_states_its_steady_reason() {
        val built = ArrayList<Fake>()
        val h = host { token -> Fake(token, end = TtsEnd.FAILED).also { built += it } }
        h.ensure()
        repeat(8) { h.speak("utterance $it") {} }
        assertEquals(
            "one acquire + one re-warm + one degradation, then no more attempts",
            listOf("piper", "piper", "system"),
            built.map { it.token },
        )
        assertFalse("the leg reports itself off", h.state.speaking)
        assertTrue(h.state.issue.contains("no other voice is available"))
    }

    @Test fun silence_where_audio_was_due_counts_as_a_failure() {
        var builds = 0
        val h = TtsEngineHost(
            requested = { "system" }, installed = { false },
            factory = { token ->
                builds++
                // Reports SPOKEN but never became audible (no onStart).
                object : VoxTts {
                    override val name = token
                    override val isWarm = true
                    override val warmReason = ""
                    override fun init(onReady: (Boolean) -> Unit) = onReady(true)
                    override fun speak(text: String, onDone: () -> Unit) = onDone()
                    override fun speakReporting(text: String, onStart: () -> Unit, onDone: (TtsOutcome) -> Unit) {
                        onDone(TtsOutcome(TtsEnd.SPOKEN))
                    }
                    override fun stop() {}
                    override fun shutdown() {}
                }
            },
            warn = { warns += it },
        )
        h.ensure()
        h.speak("silent") {}
        assertTrue("the silent engine was treated as failed", warns.any { it.startsWith("event=tts-fail engine=system code=silent") })
        assertEquals("one re-warm was attempted", 2, builds)
    }

    @Test fun an_unknown_preference_never_builds_a_local_engine() {
        val built = ArrayList<Fake>()
        val h = host(requested = "no-such-voice", installed = emptySet()) { token -> Fake(token).also { built += it } }
        h.ensure()
        assertEquals(listOf("system"), built.map { it.token })
        assertTrue(h.state.speaking)
    }

    // ---- the instrument ----------------------------------------------------

    @Test fun every_spoken_utterance_names_the_engine_and_the_reason() {
        val h = host()
        h.ensure()
        h.speak("hello") {}
        assertTrue(
            "a spoken line must name the engine: $debugs",
            debugs.any { it.startsWith("event=tts-speak engine=piper") && it.contains("reason=ok") },
        )
    }

    @Test fun a_fallback_utterance_names_the_engine_that_actually_spoke() {
        val h = host(requested = "piper", installed = emptySet())
        h.ensure()
        h.speak("hello") {}
        assertTrue(
            "the log must say the system voice spoke, and why: $debugs",
            debugs.any {
                it.startsWith("event=tts-speak engine=system") && it.contains("Piper model not installed")
            },
        )
        assertTrue(warns.any { it.startsWith("event=tts-fallback from=piper to=system") })
    }

    @Test fun a_failure_names_the_engine_and_its_own_error_code() {
        val h = host { token -> Fake(token, end = TtsEnd.FAILED) }
        h.ensure()
        h.speak("hello") {}
        assertTrue(
            "a failure must carry the engine's code: $warns",
            warns.any { it.startsWith("event=tts-fail engine=piper code=fake-FAILED") },
        )
        assertTrue(warns.any { it.startsWith("event=tts-recover engine=piper reason=speak-failed") })
    }

    @Test fun a_buffer_with_no_audible_sample_is_silence() {
        assertTrue(audioIsSilent(null))
        assertTrue(audioIsSilent(FloatArray(0)))
        assertTrue("an all-zero synth did not speak", audioIsSilent(FloatArray(4096)))
        assertFalse(audioIsSilent(floatArrayOf(0f, 0.001f, 0f)))
    }
}
