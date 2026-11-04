package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #417 — the block a report pastes is a privacy boundary, so its exact text is pinned:
 * the shipped defaults are `companion`, a changed setting makes it `custom` and names only
 * that setting, and a number the app did not measure is absent rather than a zero.
 */
class PresenceDiagnosticTest {

    private val pixel = PresenceDiagnostic.Device("Google", "Pixel 7", "14")

    private fun input(
        build: String = "0.9.1-nightly-abc1234",
        settings: PresenceDiagnostic.Settings = PresenceDiagnostic.Settings(),
        mind: String? = "lan",
        session: PresenceDiagnostic.Session = PresenceDiagnostic.Session(
            e2P50Ms = 3120, turns = 12, answeredByVoice = 3, escalated = 4),
        model: String? = "gemma-e2b",
    ) = PresenceDiagnostic.Input(build, settings, pixel, mind, session, model)

    @Test fun the_shipped_defaults_are_companion_with_no_detail() {
        assertEquals(PresenceDiagnostic.Preset.COMPANION, PresenceDiagnostic.preset(PresenceDiagnostic.Settings()))
        assertNull(PresenceDiagnostic.detail(PresenceDiagnostic.Settings()))
    }

    @Test fun the_block_is_the_documented_shape() {
        assertEquals(
            listOf(
                "vox-diag",
                "build: 0.9.1-nightly-abc1234",
                "preset: companion",
                "device: Google Pixel 7, Android 14",
                "mind: lan",
                "e2_p50_ms: 3120",
                "turns: 12  answered_by_voice: 3  escalated: 4",
                "model: gemma-e2b",
            ),
            PresenceDiagnostic.block(input()).lines(),
        )
    }

    @Test fun a_changed_setting_makes_custom_and_names_only_the_change() {
        val s = PresenceDiagnostic.Settings(
            presenceEnabled = false,
            voice = "spoken",
            density = "chatty",
            hears = false,
            semanticBarge = false,
            reasoning = "high",
            echoMs = 1500,
        )
        assertEquals(PresenceDiagnostic.Preset.CUSTOM, PresenceDiagnostic.preset(s))
        assertEquals(
            "presence=off voice=spoken density=chatty hears=off barge=off reasoning=high echo=1500ms",
            PresenceDiagnostic.detail(s),
        )
        val block = PresenceDiagnostic.block(input(settings = s))
        assertTrue(block, block.contains("preset: custom"))
        assertTrue(block, block.contains("preset_detail: presence=off voice=spoken"))
    }

    @Test fun an_unrecognised_pref_value_is_not_echoed() {
        // The setting dialogs only write closed values, but a restored/hand-edited pref must
        // never smuggle free text into a block meant to be pasted in public.
        val s = PresenceDiagnostic.Settings(voice = "call me at 555-0100", density = "whatever")
        assertEquals("voice=other density=other", PresenceDiagnostic.detail(s))
    }

    @Test fun an_unmeasured_metric_is_absent_not_zero() {
        val block = PresenceDiagnostic.block(
            input(session = PresenceDiagnostic.Session(e2P50Ms = null, turns = 4)))
        assertFalse("an empty e2 line is worse than none", block.contains("e2_p50_ms"))
        assertFalse("lost is not measured by this build, so it is not claimed as 0",
            block.contains("lost:"))
        assertTrue(block, block.contains("turns: 4  answered_by_voice: 0  escalated: 0"))
    }

    @Test fun lost_is_reported_when_a_build_can_measure_it() {
        val block = PresenceDiagnostic.block(
            input(session = PresenceDiagnostic.Session(lost = 2, turns = 5, answeredByVoice = 1, escalated = 1)))
        assertTrue(block, block.contains("turns: 5  lost: 2  answered_by_voice: 1  escalated: 1"))
    }

    @Test fun no_gateway_means_no_mind_line_and_no_hostname_ever() {
        val url = "https://secret-host.example.com:8642/p/private?token=abc"
        assertEquals("remote", PresenceDiagnostic.mindFor(url))
        val block = PresenceDiagnostic.block(input(mind = PresenceDiagnostic.mindFor(url)))
        for (leak in listOf("secret-host", "example.com", "8642", "/p/", "token=", "private")) {
            assertFalse("the block leaked $leak", block.contains(leak))
        }
        val unset = PresenceDiagnostic.block(input(mind = PresenceDiagnostic.mindFor("")))
        assertFalse(unset, unset.contains("mind:"))
    }

    @Test fun mind_is_classified_from_the_host_alone() {
        assertEquals(null, PresenceDiagnostic.mindFor(""))
        assertEquals("local", PresenceDiagnostic.mindFor("http://localhost:8642"))
        assertEquals("local", PresenceDiagnostic.mindFor("http://127.0.0.1:8642"))
        assertEquals("local", PresenceDiagnostic.mindFor("http://10.0.2.2:8642"))   // emulator host
        assertEquals("local", PresenceDiagnostic.mindFor("https://[::1]:8642"))
        assertEquals("lan", PresenceDiagnostic.mindFor("http://192.168.1.20:8642"))
        assertEquals("lan", PresenceDiagnostic.mindFor("http://10.0.0.5:8642"))
        assertEquals("lan", PresenceDiagnostic.mindFor("http://172.20.1.4:8642"))
        assertEquals("lan", PresenceDiagnostic.mindFor("http://thelio:8642"))
        assertEquals("lan", PresenceDiagnostic.mindFor("http://thelio.local:8642"))
        assertEquals("lan", PresenceDiagnostic.mindFor("https://[fd7a:115c:a1e0::1]:8642"))
        assertEquals("remote", PresenceDiagnostic.mindFor("https://vox.example.com/p/name"))
        assertEquals("remote", PresenceDiagnostic.mindFor("http://100.101.102.103:8642"))  // tailnet
        assertEquals("remote", PresenceDiagnostic.mindFor("https://[2606:4700::1]:8642"))
        assertEquals("remote", PresenceDiagnostic.mindFor("8.8.8.8"))
    }

    @Test fun the_device_line_is_system_facts_only() {
        assertEquals("Google Pixel 7, Android 14",
            PresenceDiagnostic.deviceLine(PresenceDiagnostic.Device("Google", "Pixel 7", "14")))
        assertEquals("Pixel 7, Android 14",
            PresenceDiagnostic.deviceLine(PresenceDiagnostic.Device("", "Pixel  7", "14")))
        assertEquals("Acme X1, Android unknown",
            PresenceDiagnostic.deviceLine(PresenceDiagnostic.Device("Acme ", " X1", "")))
    }

    @Test fun unknown_build_and_absent_model_still_say_something_true() {
        val block = PresenceDiagnostic.block(input(build = "", model = null))
        assertTrue(block, block.contains("build: unknown"))
        assertTrue(block, block.contains("model: none"))
    }
}
