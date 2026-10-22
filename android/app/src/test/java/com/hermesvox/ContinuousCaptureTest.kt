package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #228 / #229, proven off-device: the switches' gates, the capture cadence and the wiring of
 * the semantic endpointer to the live partials. The loop that uses them needs an AudioRecord;
 * everything decided here is pure.
 */
class ContinuousCaptureTest {

    // ---- #228: the switch -------------------------------------------------------------

    @Test fun continuousCaptureNeedsBothTheSwitchAndALiveStream() {
        // Off by default: a call with the switch off is the call it always was.
        assertFalse(ContinuousCapture.active(switchOn = false, hasLiveStream = true))
        // On, but the recogniser is a whole-clip leg: there is no stream to keep open, and
        // pretending otherwise would run the loop at a cadence nothing can answer.
        assertFalse(ContinuousCapture.active(switchOn = true, hasLiveStream = false))
        assertTrue(ContinuousCapture.active(switchOn = true, hasLiveStream = true))
    }

    @Test fun cadenceIsTheSwitch() {
        assertEquals(100L, ContinuousCapture.cadenceMs(continuous = true))
        assertEquals(900L, ContinuousCapture.cadenceMs(continuous = false))
        // The two cadences are the numbers the issues name, not round-number guesses:
        // #229's partials are ~100 ms, and 900 ms is the shipped legacy worker's period.
        assertEquals(ContinuousCapture.PARTIAL_MS, ContinuousCapture.cadenceMs(true))
        assertEquals(ContinuousCapture.LEGACY_PARTIAL_MS, ContinuousCapture.cadenceMs(false))
    }

    // ---- #228: the cadence itself -----------------------------------------------------

    @Test fun partialCadenceTicksEveryIntervalAndDoesNotDrift() {
        val c = PartialCadence(100L, startMs = 0L)
        assertFalse(c.due(99L))          // one tick short
        assertTrue(c.due(100L))
        assertFalse(c.due(150L))         // the same tick
        assertTrue(c.due(200L))
        // A decode that overran must not let the next tick fire immediately after it: the
        // clock restarts at the tick that was GRANTED.
        assertTrue(c.due(1_000L))
        assertFalse(c.due(1_050L))
        assertTrue(c.due(1_100L))
    }

    @Test fun theLegacyCadenceIsOnePartialInFourTicks() {
        val c = PartialCadence(ContinuousCapture.LEGACY_PARTIAL_MS, startMs = 0L)
        var granted = 0
        for (t in 100L..800L step 100L) if (c.due(t)) granted++
        assertEquals(0, granted)
        assertTrue(c.due(900L))
        assertEquals(1, granted + 1)
    }

    @Test fun aSegmentStartsOnItsOwnClock() {
        // The tick clock starts at the segment's open, so an utterance that has just begun is
        // fed one cadence later, not immediately (there is nothing to decode yet).
        val c = PartialCadence(100L, startMs = 5_000L)
        assertFalse(c.due(5_050L))
        assertTrue(c.due(5_100L))
        c.reset(7_000L)
        assertFalse(c.due(7_050L))
        assertTrue(c.due(7_100L))
    }

    // ---- #229: the switch is downstream of #228 ---------------------------------------

    @Test fun semanticEndpointingOnlyEngagesOnFreshPartials() {
        assertFalse(ContinuousCapture.semanticReady(semanticSwitchOn = true, continuous = false))
        assertFalse(ContinuousCapture.semanticReady(semanticSwitchOn = false, continuous = true))
        assertTrue(ContinuousCapture.semanticReady(semanticSwitchOn = true, continuous = true))
    }

    // ---- #229: the wiring -------------------------------------------------------------

    private val E = SemanticEndpoint.Eagerness.BALANCED

    @Test fun stabilityIsTheClockTheEndpointerCannotKeepItself() {
        val w = EndpointWatch(E)
        assertEquals(0L, w.stableMs(1_000L))      // nothing heard yet
        w.onSpeech(1_000L)
        w.onPartial("what time is it", 1_100L)
        assertEquals(200L, w.stableMs(1_300L))
        // The recogniser re-sending the same hypothesis is not a revision.
        w.onPartial("what time is it", 1_300L)
        assertEquals(200L, w.stableMs(1_300L))
        // A revision restarts the clock — the corpus' "…nor" → "…northern".
        w.onPartial("what time is it in oslo", 1_400L)
        assertEquals(100L, w.stableMs(1_500L))
    }

    @Test fun aFinishedSentenceCommitsBeforeTheFixedPause() {
        val w = EndpointWatch(E)
        w.onSpeech(1_000L)
        w.onPartial("what time is it", 1_100L)
        // 400 ms of silence at this tick: the words are finished but have only held for
        // 200 ms, and BALANCED wants 300 — an unrevised-looking partial is not yet a promise.
        assertEquals(SemanticEndpoint.Verdict.CONTINUE, w.verdict(350L, 1_300L))
        // Once the hypothesis has held, 400 ms of silence is plenty (the fixed pause is 800).
        assertEquals(SemanticEndpoint.Verdict.FINAL, w.verdict(400L, 1_450L))
    }

    @Test fun aTrailingWordKeepsTheTurnOpen() {
        val w = EndpointWatch(E)
        w.onSpeech(1_000L)
        w.onPartial("i need to check the", 1_200L)   // "the" cannot end a sentence
        // The same 400 ms that committed above is only a hint here.
        assertEquals(SemanticEndpoint.Verdict.CONTINUE, w.verdict(400L, 1_700L))
        assertEquals(SemanticEndpoint.Verdict.PROVISIONAL, w.verdict(1_500L, 2_700L))
        // A dangling word does not hold the turn past the ceiling.
        assertEquals(SemanticEndpoint.Verdict.FINAL, w.verdict(2_200L, 3_400L))
    }

    @Test fun aPartialStillBeingRevisedIsNotEvidenceOfFinishing() {
        val w = EndpointWatch(E)
        w.onSpeech(1_000L)
        w.onPartial("open the", 1_000L)
        w.onPartial("open the settings", 1_900L)     // revised 0 ms before this tick
        // A finished-looking sentence that changed a moment ago waits: the endpointer needs
        // the words to have held still, not merely to read as complete.
        assertEquals(SemanticEndpoint.Verdict.PROVISIONAL, w.verdict(900L, 1_900L))
        // Give it the stability BALANCED asks for and the same pause commits.
        assertEquals(SemanticEndpoint.Verdict.FINAL, w.verdict(900L, 2_200L))
    }

    @Test fun theCeilingEndsATurnWhateverTheWordsSay() {
        val w = EndpointWatch(E)
        w.onSpeech(1_000L)                       // speech detected, no partial at all
        assertEquals(SemanticEndpoint.Verdict.FINAL, w.verdict(E.ceilingMs, 1_000L + E.ceilingMs))
    }

    @Test fun withNothingRecognisedTheUnfinishedTimeoutEndsTheTurn() {
        val w = EndpointWatch(E)
        w.onSpeech(1_000L)
        // A turn nobody could recognise still ends: it is silence, so it takes the long
        // timeout rather than the short one a finished sentence earns.
        assertEquals(SemanticEndpoint.Verdict.CONTINUE, w.verdict(700L, 2_000L))
        assertEquals(SemanticEndpoint.Verdict.FINAL, w.verdict(E.unfinishedMs, 2_600L))
    }

    @Test fun anUnfinishedSegmentDoesNotLeakIntoTheNextTurn() {
        val w = EndpointWatch(E)
        w.onSpeech(1_000L)
        w.onPartial("what time is it", 1_100L)
        w.reset()
        assertEquals(0L, w.stableMs(5_000L))
        assertEquals("", w.text())
        // The next turn hears speech again, and its own first partial opens the clock.
        w.onSpeech(5_000L)
        w.onPartial("and the weather", 5_200L)
        assertEquals(200L, w.stableMs(5_400L))
    }

    @Test fun everyEagernessPresetIsReachableByItsKey() {
        for (e in SemanticEndpoint.Eagerness.values())
            assertEquals(e, SemanticEndpoint.Eagerness.of(e.key))
        // The shipping default, and what an unreadable pref falls back to.
        assertEquals(SemanticEndpoint.Eagerness.BALANCED, SemanticEndpoint.Eagerness.of(null))
        assertEquals(SemanticEndpoint.Eagerness.BALANCED, SemanticEndpoint.Eagerness.of("nonsense"))
    }
}
