package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #191: a slow Presence load says what it is waiting for, and offers a way out. */
class PresenceWaitTest {

    @Test fun theVoiceLoadingIsTheOrdinaryPill() {
        assertNull(PresenceWait.line(voiceReady = false, waitingOnPresence = true, waitedMs = 30_000))
        assertNull(PresenceWait.line(voiceReady = true, waitingOnPresence = false, waitedMs = 30_000))
    }

    @Test fun waitingOnPresenceNamesItAndCounts() {
        assertEquals("Loading Presence…", PresenceWait.line(true, true, 1_000))
        assertEquals("Loading Presence · 12 s", PresenceWait.line(true, true, 12_400))
    }

    @Test fun aLongWaitOffersToStartWithoutIt() {
        assertEquals("Loading Presence · 20 s · tap to start without it", PresenceWait.line(true, true, PresenceWait.OFFER_SKIP_MS))
        assertFalse(PresenceWait.canSkip(true, true, PresenceWait.OFFER_SKIP_MS - 1))
        assertTrue(PresenceWait.canSkip(true, true, 50_000))
        assertFalse("not while the voice itself loads", PresenceWait.canSkip(false, true, 50_000))
    }
}
