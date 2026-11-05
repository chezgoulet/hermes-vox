package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClipboardRules] is the whole decision behind `clip` and `clip_read`, kept pure so the rule can be
 * proven without a phone.
 *
 * The rule Android actually has: the clipboard restriction is on **reads** (the focused app or the
 * default IME), while a write through `setPrimaryClip` is permitted without focus. The regression
 * these tests pin is the old one that had it backwards and refused a write whenever no activity was
 * resumed — which in picture-in-picture is the entire call.
 *
 * The fakes here stand in for `ClipboardManager`: a write lambda that records that it ran, and a
 * read lambda that returns what the platform answered.
 */
class ClipboardRulesTest {

    @Test fun a_write_is_attempted_with_nothing_resumed() {
        AppFocus.reset()
        var attempts = 0
        val result = ClipboardRules.write { attempts++ }
        assertEquals(PhoneEngine.PhonePlatform.ClipWrite.Done, result)
        assertEquals("setPrimaryClip is called even in the background", 1, attempts)
    }

    @Test fun a_write_refusal_is_the_platforms_reason_not_a_focus_guess() {
        val result = ClipboardRules.write { throw SecurityException("no focus") }
        val refused = result as PhoneEngine.PhonePlatform.ClipWrite.Refused
        assertTrue("the platform's reason is carried", refused.detail.contains("SecurityException"))
        assertTrue("and nothing is claimed to have been written", refused.detail.contains("nothing was written"))
        assertFalse("a write is never blamed on focus", refused.detail.contains("focus"))
    }

    @Test fun a_read_is_asked_even_without_focus() {
        var asked = 0
        val result = ClipboardRules.read(inFront = false) {
            asked++
            ClipboardRules.Reading.Text("hello")
        }
        assertEquals("the platform's answer is used, not a guess", "hello", (result as PhoneEngine.PhonePlatform.ClipRead.Text).text)
        assertEquals("the platform was asked", 1, asked)
    }

    @Test fun a_null_read_without_focus_names_focus_as_the_likely_cause() {
        val result = ClipboardRules.read(inFront = false) { ClipboardRules.Reading.NoClip }
        val refused = result as PhoneEngine.PhonePlatform.ClipRead.Unavailable
        assertTrue("focus is named", refused.detail.contains("not the app on screen"))
        assertTrue("as the likely cause, not a certainty", refused.detail.contains("likely"))
        assertTrue("and the relevant rule is stated", refused.detail.contains("focused"))
        assertTrue("nothing is passed off as the clipboard", refused.detail.contains("nothing was read"))
    }

    @Test fun a_null_read_in_front_is_an_empty_clipboard() {
        val result = ClipboardRules.read(inFront = true) { ClipboardRules.Reading.NoClip }
        assertEquals(PhoneEngine.PhonePlatform.ClipRead.Empty, result)
    }

    @Test fun a_read_exception_is_the_platforms_reason() {
        val result = ClipboardRules.read(inFront = true) { throw SecurityException("denied") }
        val refused = result as PhoneEngine.PhonePlatform.ClipRead.Unavailable
        assertTrue(refused.detail.contains("SecurityException"))
        assertTrue(refused.detail.contains("nothing was read"))
    }

    @Test fun a_clip_with_no_text_is_refused_rather_than_returned_empty() {
        val result = ClipboardRules.read(inFront = true) { ClipboardRules.Reading.NoText }
        val refused = result as PhoneEngine.PhonePlatform.ClipRead.Unavailable
        assertTrue(refused.detail.contains("no text"))
    }

    /**
     * PiP is the case the old gate broke: the activity is paused, so [AppFocus] says "not in front",
     * while the call is still live. Both actions must behave exactly as they do in the background —
     * the write is attempted, and the read reports what the platform answered.
     */
    @Test fun in_pip_both_actions_behave_as_in_the_background() {
        AppFocus.reset()
        AppFocus.onActivityResumed()
        AppFocus.onActivityPaused()   // PiP: paused, call still live
        assertFalse(AppFocus.inFront)
        try {
            var wrote = false
            assertEquals(
                PhoneEngine.PhonePlatform.ClipWrite.Done,
                ClipboardRules.write { wrote = true },
            )
            assertTrue("a PiP clip writes", wrote)

            val read = ClipboardRules.read(AppFocus.inFront) { ClipboardRules.Reading.NoClip }
            assertTrue(
                "a PiP read reports the platform's answer",
                read is PhoneEngine.PhonePlatform.ClipRead.Unavailable,
            )
        } finally {
            AppFocus.reset()
        }
    }
}
