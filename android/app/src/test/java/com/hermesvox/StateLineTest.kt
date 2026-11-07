package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/**
 * #281 / STATE-AUDIT §1 — the one line must survive the three configurations.
 *
 * The being cannot carry *what* (F4), so the line is the only carrier of a tool step,
 * its target and a failure. These tests pin the two things that can break that promise:
 *  - visibility is tied to the call again (F8), or
 *  - the line's colour disappears on the light theme (F6).
 *
 * The line itself has no being/sound input — that is the design, not an accident — so
 * "being off" and "sound off" are asserted where they used to bite: a step or a held
 * failure must be shown even when no call is live.
 */
class StateLineTest {

    private val running = "Searching the web · trail conditions"
    private val failed = "✕ Sending an email failed — permission denied"
    private val ambient = "Listening…"

    @Test fun beingOffStillCarriesARunningStep() {
        // Pocketed / screen off / not looking: no being, no call — the step is still named.
        assertEquals(running, StateLine.words(running, null, ambient))
        assertTrue(StateLine.visible(running = true, heldFailure = false,
            state = "listening", callLive = false))
    }

    @Test fun soundOffStillCarriesAHeldFailure() {
        // Cues off removes the edge, not the words: the failure stays in the line.
        assertEquals(failed, StateLine.words(null, failed, ambient))
        assertTrue(StateLine.visible(running = false, heldFailure = true,
            state = "listening", callLive = false))
    }

    @Test fun ambientWordsKeepTheirOldRule() {
        // The fix is narrow: only a real operation forces the line up outside a call.
        assertFalse(StateLine.visible(running = false, heldFailure = false,
            state = "listening", callLive = false))
        assertTrue(StateLine.visible(running = false, heldFailure = false,
            state = "thinking", callLive = false))
        assertTrue(StateLine.visible(running = false, heldFailure = false,
            state = "listening", callLive = true))
    }

    @Test fun lightModeKeepsTheWordsAndTheChipMeetsAA() {
        // The words do not change with the theme; only their colour does. Both themes
        // ship a semantic pair (normal / on-call) that is AA against the chip.
        val light = colors(light = true)
        val dark = colors(light = false)
        for ((name, c) in listOf("light" to light, "dark" to dark)) {
            val chip = c["hv_surface_high"] ?: error("$name: no hv_surface_high")
            for (key in listOf("hv_status", "hv_status_live")) {
                val fg = c[key] ?: error("$name: no $key")
                val ratio = contrast(fg, chip)
                assertTrue("$name $key on the chip is $ratio:1, below AA 4.5:1",
                    ratio >= 4.5)
            }
        }
        // Same line, both configurations.
        assertEquals(running, StateLine.words(running, null, ambient))
        assertEquals(failed, StateLine.words(null, failed, ambient))
    }

    // --- resource colours, parsed from the shipped files ------------------------------

    private fun colors(light: Boolean): Map<String, String> {
        val rel = if (light) "src/main/res/values/colors.xml" else "src/main/res/values-night/colors.xml"
        val candidates = listOf(
            File(rel),                    // module dir (android/app) — Gradle's test cwd
            File("app/$rel"),             // project dir (android/)
            File("android/app/$rel")      // repo root
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("$rel not found from ${File(".").absolutePath}; tried ${candidates.joinToString { it.path }}")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("color")
        val out = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            out[el.attributes.getNamedItem("name").nodeValue] =
                el.textContent.trim()
        }
        return out
    }

    /** WCAG relative luminance / contrast for two #RRGGBB colours. */
    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val h = hex.removePrefix("#")
        require(h.length == 6) { "expected #RRGGBB, got $hex" }
        fun channel(i: Int): Double {
            val v = h.substring(i, i + 2).toInt(16) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }
}
