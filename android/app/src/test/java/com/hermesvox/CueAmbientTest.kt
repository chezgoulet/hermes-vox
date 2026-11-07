package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #251: the ambient cues — one channel at a time, a ping while working, one check-in per silence. */
class CueAmbientTest {

    private val heard = CuePolicy.World()

    @Test fun theDefaultIsCuesOnlyWhilePresenceIsOff() {
        assertEquals(CuePolicy.SCOPE_PRESENCE_OFF, CuePolicy.DEFAULT_SCOPE)
        assertEquals(CuePolicy.Action.AUDIO, CuePolicy.decide(heard.copy(presenceOn = false)).action)
        val d = CuePolicy.decide(heard.copy(presenceOn = true))
        assertEquals(CuePolicy.Action.SILENT, d.action)
        assertEquals("presence", d.reason)
    }

    @Test fun alwaysPlaysBesidePresence() {
        assertEquals(CuePolicy.Action.AUDIO,
            CuePolicy.decide(heard.copy(scope = CuePolicy.SCOPE_ALWAYS, presenceOn = true)).action)
    }

    @Test fun offIsOffWhateverTheScope() {
        assertEquals("off", CuePolicy.decide(heard.copy(enabled = false, scope = CuePolicy.SCOPE_ALWAYS)).reason)
    }

    @Test fun aPreviewIsHeardEvenWhilePresenceIsOn() {
        // The user tapped the button to hear it; the scope is about turns, not about that.
        assertEquals(CuePolicy.Action.AUDIO, CuePolicy.decide(heard.copy(presenceOn = true, preview = true)).action)
    }

    @Test fun theVoiceStillOutranksEverything() {
        assertEquals("tts", CuePolicy.decide(heard.copy(ttsSpeaking = true, scope = CuePolicy.SCOPE_ALWAYS)).reason)
    }

    @Test fun thePingRepeatsOnlyWhileWorking() {
        val p = CuePolicy.Ambient.PING_EVERY_MS
        assertFalse(CuePolicy.Ambient.pingDue(working = true, lastPingAt = 0, now = p - 1))
        assertTrue(CuePolicy.Ambient.pingDue(working = true, lastPingAt = 0, now = p))
        assertFalse(CuePolicy.Ambient.pingDue(working = false, lastPingAt = 0, now = 10 * p))
        assertTrue(CuePolicy.Ambient.PING_GAIN < 0.5f)   // a pulse, quieter than an event
    }

    @Test fun theCheckInFiresOncePerSilenceAndOnlyInACall() {
        val idle = 120
        assertFalse(CuePolicy.Ambient.idleDue(true, idle, lastActivityAt = 0, now = 119_999, firedThisStretch = false))
        assertTrue(CuePolicy.Ambient.idleDue(true, idle, lastActivityAt = 0, now = 120_000, firedThisStretch = false))
        assertFalse("not twice", CuePolicy.Ambient.idleDue(true, idle, 0, 500_000, firedThisStretch = true))
        assertFalse("not off a call", CuePolicy.Ambient.idleDue(false, idle, 0, 500_000, false))
        assertFalse("0 is never", CuePolicy.Ambient.idleDue(true, 0, 0, 500_000, false))
    }

    @Test fun thePingAndTheCheckInAreTheirOwnSounds() {
        val ping = CueSynth.pcm(CueSynth.Cue.PING)
        assertTrue(CueSynth.durationMs(CueSynth.Cue.PING) < CueSynth.durationMs(CueSynth.Cue.THINKING))
        assertTrue(CueSynth.firstHz(CueSynth.Cue.IDLE) < CueSynth.lastHz(CueSynth.Cue.IDLE))   // a question rises
        assertTrue(ping.isNotEmpty())
    }
}
