package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2's spoken half — the confirmation, and the invariant that makes it safe: **the line must be
 * true**.
 *
 * The false-action-claim guard exists because a phone that says "I've set a timer" when it did not
 * is worse than silence. So a failure has its own words, and a permission the app does not have is
 * named as a permission (DND access is a special access only the user can grant; the app does not
 * ask for it, and does not pretend to have it).
 */
class LocalActionSpeechTest {

    @Test fun a_timer_is_read_back_as_a_person_says_it() {
        assertEquals("Timer set for 5 minutes.", LocalActionSpeech.timerSet(5 * 60_000L))
        assertEquals("Timer set for 1 minute.", LocalActionSpeech.timerSet(60_000L))
        assertEquals("Timer set for 1 minute and 30 seconds.", LocalActionSpeech.timerSet(90_000L))
        assertEquals("Timer set for 1 hour and 5 minutes.",
            LocalActionSpeech.timerSet(65 * 60_000L))
        assertEquals("Timer set for 1 hour, 2 minutes and 3 seconds.",
            LocalActionSpeech.timerSet(3_600_000L + 2 * 60_000L + 3_000L))
    }

    @Test fun an_alarm_is_read_back_in_the_callers_clock() {
        assertEquals("Alarm set for 7:30 am.", LocalActionSpeech.alarmSet(7, 30))
        assertEquals("Alarm set for 7 pm.", LocalActionSpeech.alarmSet(19, 0))
        assertEquals("Alarm set for 12 am.", LocalActionSpeech.alarmSet(0, 0))
        assertEquals("Alarm set for 12:05 pm.", LocalActionSpeech.alarmSet(12, 5))
    }

    @Test fun dnd_and_volume_state_their_state() {
        assertEquals("Do not disturb on.", LocalActionSpeech.dnd(true))
        assertEquals("Do not disturb off.", LocalActionSpeech.dnd(false))
        assertEquals("Volume 62 percent.", LocalActionSpeech.volume(62))
    }

    @Test fun a_failure_never_sounds_like_a_success() {
        for (kind in listOf("timer", "alarm", "volume")) {
            val fail = LocalActionSpeech.failed(kind)
            assertTrue(fail, fail.contains("couldn't"))
            assertTrue(fail, fail.contains(kind))
            assertNotEquals(fail, LocalActionSpeech.timerSet(60_000L))
            assertNotEquals(fail, LocalActionSpeech.volume(50))
        }
    }

    @Test fun a_permission_we_do_not_have_is_named_as_a_permission() {
        val denied = LocalActionSpeech.dndDenied()
        assertTrue(denied, denied.contains("can't"))
        assertTrue("the caller is told why, honestly: $denied", denied.contains("permission"))
        assertFalse("never a claim of success", denied.contains("Do not disturb on"))
        assertTrue(LocalActionSpeech.volumeDenied().contains("can't"))
    }

    @Test fun the_registry_forms_are_compact_enough_for_the_context_block() {
        // B1's whole block is under 200 characters and carries at most three actions.
        assertEquals("3m12s", LocalActionSpeech.remaining(192_000L))
        assertEquals("45s", LocalActionSpeech.remaining(45_000L))
        assertEquals("1h05m", LocalActionSpeech.remaining(65 * 60_000L))
        assertEquals("07:30", LocalActionSpeech.clock24(7, 30))
        assertEquals("19:05", LocalActionSpeech.clock24(19, 5))
    }
}
