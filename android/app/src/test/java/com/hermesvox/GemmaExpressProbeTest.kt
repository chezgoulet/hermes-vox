package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefill probe's validity condition — and nothing else, because the probe itself needs a
 * device and the engine.
 *
 * Why this test exists at all: the probe compares a full-persona render against a minimal-persona
 * render, and the ONLY thing that makes that comparison meaningful is the size difference. If
 * MINIMAL_PERSONA ever drifts toward a real persona, the probe still runs, still logs two numbers,
 * and still looks like evidence — while measuring nothing. The beat's design would then be decided
 * on a number that means nothing, which is worse than having no number.
 */
class GemmaExpressProbeTest {

    @Test fun the_minimal_persona_stays_minimal() {
        val p = GemmaExpress.MINIMAL_PERSONA
        assertTrue(
            "the fast-lane persona must stay a few dozen characters",
            p.length <= GemmaExpress.MAX_MINIMAL_PERSONA_CHARS
        )
        assertTrue(
            "and must be dwarfed by a real persona, or the probe compares nothing",
            p.length * 3 < VoxSoul.soulPrompt("").length
        )
    }
}
