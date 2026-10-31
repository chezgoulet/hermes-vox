package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #222/D16 — the selector and the wording that described two modes are gone.
 *
 * A stranded string in a resource file is the usual leftover, so this is a resource and
 * source assertion rather than a grep in a pull-request body. The only place the retired
 * `voice_mode` value is allowed to appear is [PresenceGate], where it is the one-shot
 * migration input.
 */
class RetiredModeStringsTest {

    private val root = File("src/main")
    private val resources = File(root, "res")
    private val sources = File(root, "java/com/hermesvox")

    private fun filesUnder(dir: File, vararg suffixes: String): List<File> =
        dir.walkTopDown()
            .filter { it.isFile && suffixes.any { s -> it.name.endsWith(s) } }
            .toList()

    /** The exact wording the retired UI used. Case-sensitive on purpose: "realtime" in
     *  `elapsedRealtime()` is a clock, not a mode. */
    private val retired = listOf(
        "Enhanced Realtime",
        "VOICE MODE",
        "android:text=\"Voice mode\"",
        "voice_mode",
        "two voice modes",
        "two modes",
        "both modes",
        "MODE_ENHANCED",
        "MODE_REALTIME",
        "KEY_VOICE_MODE",
    )

    @Test fun `no resource or layout still names a mode`() {
        val offenders = mutableListOf<String>()
        for (f in filesUnder(resources, ".xml")) {
            val text = f.readText()
            for (token in retired) {
                if (text.contains(token)) offenders += "${f.path}: $token"
            }
        }
        assertEquals("retired mode wording left in resources", emptyList<String>(), offenders)
    }

    @Test fun `the mode selector rows are gone from the layouts`() {
        val layouts = filesUnder(File(resources, "layout"), ".xml")
        val text = layouts.joinToString("\n") { it.readText() }
        // Exact ids: `row_models` merely contains the substring `row_mode`.
        listOf(
            "@+id/row_mode\"", "@+id/set_mode_val\"",
            "@+id/ob_mode_realtime\"", "@+id/ob_mode_enhanced\"",
        ).forEach {
            assertTrue("layout still carries the retired selector id $it", !text.contains(it))
        }
        assertTrue("the Presence switch is missing",
            text.contains("sw_presence_enabled"))
    }

    @Test fun `no Kotlin source outside the migration reads or names the old choice`() {
        val offenders = mutableListOf<String>()
        for (f in filesUnder(sources, ".kt")) {
            if (f.name == "PresenceGate.kt") continue   // the migration input lives here
            val text = f.readText()
            for (token in listOf(
                "MODE_ENHANCED", "MODE_REALTIME", "KEY_VOICE_MODE",
                "\"voice_mode\"", "Enhanced Realtime",
            )) {
                if (text.contains(token)) offenders += "${f.path}: $token"
            }
        }
        assertEquals("retired mode wording left in Kotlin", emptyList<String>(), offenders)
    }
}
