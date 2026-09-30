package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 1 — the early start never sends a truncated transcript. The partial is a
 * trigger; the text comes from the whole segment, or from a partial ONLY when that
 * partial covered the whole utterance.
 */
class EarlyStartRuleTest {

    private val sr = 16000

    @Test fun complete_sentences_look_complete() {
        assertTrue(EarlyStartRule.looksComplete("What's the weather tomorrow?"))
        assertTrue(EarlyStartRule.looksComplete("Turn off the lights."))
        assertTrue(EarlyStartRule.looksComplete("Stop!"))
    }

    @Test fun trailing_off_or_dangling_speech_does_not() {
        assertFalse(EarlyStartRule.looksComplete("So I was thinking that"))        // no terminal punctuation
        assertFalse(EarlyStartRule.looksComplete("I want to go to the..."))        // ellipsis = trailing off
        assertFalse(EarlyStartRule.looksComplete("I want to go to the…"))
        assertFalse(EarlyStartRule.looksComplete("Remind me to call and."))        // ends on a conjunction
        assertFalse(EarlyStartRule.looksComplete("Let me think, um."))             // ends on a filler
        assertFalse(EarlyStartRule.looksComplete("First,"))
        assertFalse(EarlyStartRule.looksComplete(""))
    }

    @Test fun the_pause_floor_cannot_be_lowered_by_the_pref() {
        assertEquals(600L, EarlyStartRule.effectiveSilenceMs(450L))   // the old default is raised
        assertEquals(600L, EarlyStartRule.effectiveSilenceMs(0L))
        assertEquals(900L, EarlyStartRule.effectiveSilenceMs(900L))   // raising it is allowed
    }

    @Test fun short_segments_snapshot_whole_long_ones_snapshot_a_tail() {
        assertEquals(0, EarlyStartRule.snapshotStart(sr * 3, sr))
        assertEquals(0, EarlyStartRule.snapshotStart(sr * 8, sr))              // exactly 8 s: still whole
        assertEquals(sr * 6, EarlyStartRule.snapshotStart(sr * 12, sr))        // 12 s: the 6 s tail (trigger only)
    }

    @Test fun a_partial_is_reused_only_when_it_covered_the_whole_utterance() {
        // Decoded from sample 0 through the snapshot, no speech after it: reusable.
        assertTrue(EarlyStartRule.mayReusePartial(snapStart = 0, snapEnd = sr * 4, lastSpeechEnd = sr * 3))
        // The >6 s defect: a tail partial never stands in for the turn.
        assertFalse(EarlyStartRule.mayReusePartial(snapStart = sr * 6, snapEnd = sr * 12, lastSpeechEnd = sr * 11))
        // The user spoke again after the snapshot (the thinking-pause case): re-transcribe.
        assertFalse(EarlyStartRule.mayReusePartial(snapStart = 0, snapEnd = sr * 4, lastSpeechEnd = sr * 5))
        assertFalse(EarlyStartRule.mayReusePartial(snapStart = 0, snapEnd = 0, lastSpeechEnd = 0))
    }

    @Test fun a_long_utterance_with_a_thinking_pause_commits_its_whole_text() {
        // Simulate the capture loop's decision for a 12 s utterance with a pause at
        // 5 s: the 900 ms-cadence partial at the pause is a 6 s TAIL snapshot, so
        // whatever it says, the committed text must come from the whole segment.
        val segSamples = sr * 12
        val start = EarlyStartRule.snapshotStart(segSamples, sr)
        assertFalse(EarlyStartRule.mayReusePartial(start, segSamples, lastSpeechEnd = sr * 11))
    }
}
