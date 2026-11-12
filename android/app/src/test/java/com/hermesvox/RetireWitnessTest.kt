package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression path 5: a reply is whole only when every queued chunk was handed over and wrote audio. */
class RetireWitnessTest {

    @Test fun `every queued chunk handed over and audible is full`() {
        assertTrue(RetireWitness.full(chunks = 5, queued = 5, wrote = 5))
        assertEquals("queued=5 wrote=5 full=true", RetireWitness.fields(5, 5, 5))
    }

    @Test fun `a chunk dropped before the engine is not full`() {
        // tts-drop reason=reply-ended skips a queued chunk without counting it as handed over.
        assertFalse(RetireWitness.full(chunks = 4, queued = 5, wrote = 4))
    }

    @Test fun `a chunk that produced no audio is not full`() {
        assertFalse(RetireWitness.full(chunks = 5, queued = 5, wrote = 4))
        assertEquals("queued=5 wrote=4 full=false", RetireWitness.fields(5, 5, 4))
    }

    @Test fun `an empty stream is not evidence of a whole reply`() {
        assertFalse(RetireWitness.full(chunks = 0, queued = 0, wrote = 0))
    }
}
