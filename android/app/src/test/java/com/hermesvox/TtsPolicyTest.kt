package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #373: the voice-output policy. Three outcomes, decided from plain inputs so the
 * question "what does the app do when the Piper model is not installed?" has an
 * answer that can be read here instead of inferred from a field recording.
 *
 * The rule from #330 is a property of this table: a plan may refuse only when NO
 * engine is available. A missing local model is never a reason to refuse the call.
 */
class TtsPolicyTest {

    private fun plan(
        requested: String = "piper",
        installed: Boolean = true,
        ready: Boolean = true,
        issue: String = "",
        systemReady: Boolean = true,
    ) = TtsPolicy.plan(requested, installed, ready, issue, systemReady)

    @Test fun an_installed_local_voice_that_loaded_speaks() {
        val p = plan()
        assertEquals(TtsPlan.Action.SPEAK, p.action)
        assertEquals("piper", p.engine)
        assertEquals("", p.reason)
        assertTrue(p.speaks)
        assertFalse(p.degraded)
        assertEquals("nothing to announce", "", TtsPolicy.statusLine(p))
    }

    @Test fun a_missing_model_falls_back_to_the_system_voice_with_the_reason() {
        val p = plan(installed = false)
        assertEquals(TtsPlan.Action.FALL_BACK, p.action)
        assertEquals(TtsPolicy.SYSTEM, p.engine)
        assertTrue("the reason must name the model", p.reason.contains("piper model not installed"))
        assertTrue(p.speaks)
        assertTrue(p.degraded)
        assertEquals(p.reason, TtsPolicy.statusLine(p))
    }

    @Test fun a_local_engine_that_will_not_start_falls_back_too() {
        val p = plan(ready = false, issue = "piper engine threw OOM")
        assertEquals(TtsPlan.Action.FALL_BACK, p.action)
        assertEquals(TtsPolicy.SYSTEM, p.engine)
        assertTrue(p.reason.contains("piper could not start"))
        assertTrue("the engine's own reason travels", p.reason.contains("OOM"))
    }

    @Test fun no_engine_at_all_refuses_with_a_reason() {
        val systemOnly = plan(requested = "system", systemReady = false, issue = "no TTS service")
        assertEquals(TtsPlan.Action.REFUSE, systemOnly.action)
        assertFalse(systemOnly.speaks)
        assertTrue(systemOnly.reason.contains("no TTS service"))

        val bothDown = plan(installed = false, systemReady = false)
        assertEquals(TtsPlan.Action.REFUSE, bothDown.action)
        assertTrue(bothDown.reason.contains("piper model not installed"))
        assertTrue(bothDown.reason.contains("system voice is unavailable"))
    }

    @Test fun the_system_voice_when_ready_speaks_and_when_not_refuses() {
        assertEquals(TtsPlan.Action.SPEAK, plan(requested = "system").action)
        assertEquals(TtsPlan.Action.REFUSE, plan(requested = "system", systemReady = false).action)
    }

    @Test fun an_unknown_or_blank_token_is_the_system_voice_never_silence() {
        for (token in listOf("", "  ", "system", "Kokoro-maybe", "unknown")) {
            val p = plan(requested = token, installed = false)
            assertEquals("token=$token", TtsPolicy.SYSTEM, p.engine)
            assertTrue("token=$token must never refuse while the system voice works", p.speaks)
        }
    }

    @Test fun kokoro_has_no_shipped_runtime_so_it_falls_back() {
        assertEquals("kokoro", TtsPolicy.localEngineOf("KOKORO"))
        org.junit.Assert.assertNull(TtsPolicy.modelIdOf("kokoro"))
        val p = plan(requested = "kokoro", installed = false)
        assertEquals(TtsPlan.Action.FALL_BACK, p.action)
    }

    @Test fun a_refusal_is_the_only_plan_that_may_not_speak() {
        // Exhaustive over the small input space: nothing stays silent while an engine exists.
        for (requested in listOf("system", "piper", "supertonic", "kokoro", "garbage")) {
            for (installed in listOf(true, false)) {
                for (ready in listOf(true, false)) {
                    for (systemReady in listOf(true, false)) {
                        val p = plan(requested, installed, ready, "why-not", systemReady)
                        val localOk = TtsPolicy.localEngineOf(requested) != null && installed && ready
                        val label = "requested=$requested installed=$installed ready=$ready system=$systemReady"
                        if (p.speaks) {
                            assertTrue("$label -> ${p.action} with no engine that can speak", localOk || systemReady)
                        } else {
                            assertFalse("$label refused while an engine exists", localOk || systemReady)
                            assertTrue("$label must name why it refused", p.reason.isNotBlank())
                        }
                    }
                }
            }
        }
    }
}
