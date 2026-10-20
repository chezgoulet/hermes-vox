package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Proves the procedural cue vocabulary (#156, D7) off-device: every family renders to
 * a well-formed, click-free, normalised buffer; the four families are genuinely
 * different sounds; the archetype mapping is total; and the haptic patterns are
 * well-formed for VibrationEffect.
 *
 * Nothing here needs a phone — which is the point of generating cues instead of
 * shipping samples: the whole sound design is a pure function with a test.
 */
class CueSynthTest {

    private val cues = CueSynth.Cue.values().toList()
    private val timbres = CueSynth.Timbre.values().toList()

    private fun peak(s: ShortArray): Int = s.fold(0) { acc, v -> max(acc, abs(v.toInt())) }

    private fun rms(s: ShortArray): Double {
        if (s.isEmpty()) return 0.0
        var acc = 0.0
        for (v in s) acc += v.toDouble() * v
        return sqrt(acc / s.size)
    }

    @Test fun every_family_renders_at_its_declared_duration() {
        for (cue in cues) for (t in timbres) {
            val pcm = CueSynth.pcm(cue, t)
            val expected = (CueSynth.durationMs(cue) * CueSynth.SAMPLE_RATE) / 1000
            assertEquals("$cue/$t", expected, pcm.size)
            assertTrue("$cue/$t is silent", rms(pcm) > 300)
            // Normalised: loud enough to hear under the media stream, never clipping.
            assertTrue("$cue/$t peak=${peak(pcm)}", peak(pcm) <= (CueSynth.PEAK * 32767f).toInt() + 1)
            assertTrue("$cue/$t peak=${peak(pcm)}", peak(pcm) > 20000)
        }
    }

    @Test fun every_family_is_click_free() {
        // The attack/release ramps exist so a cue begins and ends at (near) silence. A
        // hard edge is an audible click on a phone speaker, which reads as a bug.
        for (cue in cues) for (t in timbres) {
            val pcm = CueSynth.pcm(cue, t)
            assertTrue("$cue/$t start=${abs(pcm.first().toInt())}", abs(pcm.first().toInt()) < 200)
            assertTrue("$cue/$t end=${abs(pcm.last().toInt())}", abs(pcm.last().toInt()) < 200)
        }
    }

    @Test fun the_four_families_are_four_different_sounds() {
        for (t in timbres) {
            val rendered = cues.map { CueSynth.pcm(it, t) }
            for (i in cues.indices) for (j in i + 1 until cues.size) {
                assertNotEquals("${cues[i]} and ${cues[j]} sound identical", rendered[i].toList(), rendered[j].toList())
            }
        }
    }

    @Test fun archetypes_colour_the_same_family_differently() {
        for (cue in cues) {
            val soft = CueSynth.pcm(cue, CueSynth.Timbre.SOFT)
            assertNotEquals(cue.name, soft.toList(), CueSynth.pcm(cue, CueSynth.Timbre.BRIGHT).toList())
            assertNotEquals(cue.name, soft.toList(), CueSynth.pcm(cue, CueSynth.Timbre.GLASS).toList())
            assertNotEquals(cue.name, soft.toList(), CueSynth.pcm(cue, CueSynth.Timbre.DEEP).toList())
        }
    }

    @Test fun rendering_is_deterministic() {
        // The cache must be safe to key on (cue, timbre) alone.
        assertEquals(CueSynth.pcm(CueSynth.Cue.REPLY_READY, CueSynth.Timbre.GLASS).toList(),
            CueSynth.pcm(CueSynth.Cue.REPLY_READY, CueSynth.Timbre.GLASS).toList())
    }

    @Test fun rising_cues_rise_and_falling_cues_fall() {
        // The semantics of the family live in the pitch direction: listening and
        // reply-ready hand the turn over (up), thinking and error are withdrawal (down).
        // The renderer glides every note from its start to its end pitch, so the motive
        // table below IS the heard direction.
        for (cue in listOf(CueSynth.Cue.LISTENING, CueSynth.Cue.REPLY_READY)) {
            assertTrue("$cue rises", CueSynth.lastHz(cue) > CueSynth.firstHz(cue) * 1.2f)
        }
        for (cue in listOf(CueSynth.Cue.THINKING, CueSynth.Cue.ERROR)) {
            assertTrue("$cue falls", CueSynth.lastHz(cue) < CueSynth.firstHz(cue) * 0.98f)
        }
        // ...and the three upward families do not sit on the same pitches, or they
        // would be told apart by rhythm alone.
        assertTrue(CueSynth.firstHz(CueSynth.Cue.REPLY_READY) > CueSynth.firstHz(CueSynth.Cue.LISTENING))
        assertTrue(CueSynth.firstHz(CueSynth.Cue.THINKING) < CueSynth.firstHz(CueSynth.Cue.LISTENING))
    }

    @Test fun archetype_mapping_is_total_and_grouped() {
        assertEquals(CueSynth.Timbre.SOFT, CueSynth.timbreFor("aura"))
        assertEquals(CueSynth.Timbre.BRIGHT, CueSynth.timbreFor("soundwave"))
        assertEquals(CueSynth.Timbre.GLASS, CueSynth.timbreFor("mandala"))
        assertEquals(CueSynth.Timbre.DEEP, CueSynth.timbreFor("infall"))
        // Unknown / absent / odd-cased input never fails: the default is the calm voice.
        assertEquals(CueSynth.Timbre.SOFT, CueSynth.timbreFor(null))
        assertEquals(CueSynth.Timbre.SOFT, CueSynth.timbreFor("not-an-archetype"))
        assertEquals(CueSynth.Timbre.SOFT, CueSynth.timbreFor(""))
        assertEquals(CueSynth.Timbre.GLASS, CueSynth.timbreFor("  Mandala "))
    }

    @Test fun haptic_patterns_are_well_formed() {
        for (cue in cues) {
            val (timings, amplitudes) = CueSynth.haptic(cue)
            assertEquals("$cue", timings.size, amplitudes.size)
            assertEquals("$cue starts with a wait", 0L, timings.first())
            assertTrue("$cue has a segment", timings.size >= 2)
            for (a in amplitudes) assertTrue("$cue amplitude=$a", a in 0..255)
            // Exactly the vibrate segments are non-zero, and each one is non-zero.
            for (i in amplitudes.indices) {
                if (i % 2 == 0) assertEquals("$cue wait segment is silent", 0, amplitudes[i])
                else assertTrue("$cue vibrate segment is silent", amplitudes[i] > 0)
            }
            assertTrue("$cue is short", timings.sum() < 500L)
        }
    }
}
