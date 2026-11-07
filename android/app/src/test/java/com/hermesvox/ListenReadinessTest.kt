package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readiness state machine behind the eye (ListenReadiness).
 *
 * The rule under test is the one the cold-start bench measured the absence of: the eye is a
 * claim that the leg is hearing, so it may not be shown until the recogniser is warm AND the
 * recorder is recording AND a read has returned samples. Before the fix the eye was emitted
 * from `listenOffline` two lines after `listening = true`, i.e. ~0.2 s before the microphone
 * delivered anything (scripts/cold-start-stt.sh, Pixel 7: 12/12 runs).
 */
class ListenReadinessTest {

    @Test fun a_cold_recogniser_never_shows_the_eye() {
        val r = ListenReadiness()
        assertEquals(ListenReadiness.Phase.COLD, r.phase)
        assertFalse("no recogniser yet", r.capture(recording = true, samples = 1024))
        assertFalse(r.listening)
        assertEquals(ListenReadiness.Phase.COLD, r.phase)
    }

    @Test fun ready_is_not_listening_until_the_mic_delivers_samples() {
        val r = ListenReadiness()
        r.recognizer(true)
        assertEquals(ListenReadiness.Phase.READY, r.phase)
        assertFalse("ready alone is not hearing", r.listening)

        // Recording but no samples yet: the window the bench measured (~0.2 s).
        assertFalse("recording, nothing read", r.capture(recording = true, samples = 0))
        assertFalse("not recording", r.capture(recording = false, samples = 1024))
        assertFalse("negative read", r.capture(recording = true, samples = -1))
        assertFalse("still not listening", r.listening)

        // The first real samples earn the eye, exactly once.
        assertTrue("warm + recording + audio", r.capture(recording = true, samples = 1024))
        assertTrue(r.listening)
        assertFalse("one eye per segment", r.capture(recording = true, samples = 1024))
    }

    @Test fun a_failed_recogniser_drops_the_eye_rather_than_leaving_it_up() {
        val r = ListenReadiness()
        r.recognizer(true)
        r.capture(recording = true, samples = 512)
        assertTrue(r.listening)

        r.recognizer(false)   // init failed / the leg was shut down
        assertEquals(ListenReadiness.Phase.COLD, r.phase)
        assertFalse("a cold leg cannot hear", r.listening)
        assertFalse("and mic samples do not change that", r.capture(recording = true, samples = 512))
    }

    @Test fun a_duplicate_ready_does_not_take_the_eye_back_down() {
        val r = ListenReadiness()
        r.recognizer(true)
        r.capture(recording = true, samples = 512)
        r.recognizer(true)    // a second init in the same call
        assertTrue("the eye is up because audio is flowing", r.listening)
    }

    @Test fun each_segment_earns_its_own_eye() {
        val r = ListenReadiness()
        r.recognizer(true)
        r.capture(recording = true, samples = 512)
        assertTrue(r.listening)

        r.rearm()             // the turn ended; the loop restarts the recorder
        assertFalse("a reopened recorder is not audible yet", r.listening)
        assertFalse("samples are what put it back up", r.capture(recording = false, samples = 512))
        assertTrue(r.capture(recording = true, samples = 256))
        assertTrue(r.listening)
    }

    @Test fun stop_returns_to_cold() {
        val r = ListenReadiness()
        r.recognizer(true)
        r.capture(recording = true, samples = 512)
        r.stop()
        assertEquals(ListenReadiness.Phase.COLD, r.phase)
        assertFalse(r.listening)
        // A new call's recogniser must answer before the eye can be shown again.
        assertFalse(r.capture(recording = true, samples = 512))
        r.recognizer(true)
        assertTrue(r.capture(recording = true, samples = 512))
    }
}
