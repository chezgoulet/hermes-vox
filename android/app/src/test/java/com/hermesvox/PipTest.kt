package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture-in-picture contract, pinned off-device.
 *
 * [Pip] is pure data, so the owner's decisions are testable without an Activity:
 *
 *  * the window is square;
 *  * Stop is always offered, Mute only when the controller has one;
 *  * no live call means no window at all;
 *  * the feature AND API 26 are both required, and 12+ auto-enters while 26–30 asks on
 *    the leave gesture.
 *
 * What a test cannot judge is the being at ~240 dp — that is the device checklist in the
 * report, deliberately not a claim made here.
 */
class PipTest {

    @Test
    fun the_window_is_square_because_the_being_is_a_centred_shape() {
        val p = Pip.params(callLive = true, canMute = true)!!
        assertEquals(Pip.ASPECT_NUMERATOR, p.aspectNumerator)
        assertEquals(Pip.ASPECT_DENOMINATOR, p.aspectDenominator)
        assertEquals("square means equal halves", p.aspectNumerator, p.aspectDenominator)
    }

    @Test
    fun a_live_call_with_mute_offers_stop_and_mute_with_their_right_labels() {
        val p = Pip.params(callLive = true, canMute = true, muted = false)!!
        assertEquals(listOf(Pip.Action.STOP, Pip.Action.MUTE), p.actions)
        assertEquals(listOf("Stop", "Mute"), p.actions.map { it.label })
    }

    @Test
    fun a_muted_controller_offers_unmute_because_the_label_is_the_next_tap() {
        val p = Pip.params(callLive = true, canMute = true, muted = true)!!
        assertEquals(listOf(Pip.Action.STOP, Pip.Action.UNMUTE), p.actions)
        assertEquals(listOf("Stop", "Unmute"), p.actions.map { it.label })
    }

    @Test
    fun a_controller_without_a_mute_offers_stop_alone_and_never_a_dead_button() {
        // VoiceController has no mute today; a Mute action that did nothing would be worse
        // than one action fewer.
        val p = Pip.params(callLive = true, canMute = false)!!
        assertEquals(listOf(Pip.Action.STOP), p.actions)
        assertEquals(listOf("Stop"), p.actions.map { it.label })
    }

    @Test
    fun no_live_call_builds_no_params_so_an_idle_app_never_floats() {
        assertNull(Pip.params(callLive = false))
        assertNull(Pip.params(callLive = false, canMute = true, muted = true))
    }

    @Test
    fun the_feature_and_api_26_are_both_required() {
        assertFalse("API 24/25 is the app's minSdk and has no PiP", Pip.supported(24, featurePresent = true))
        assertFalse(Pip.supported(25, featurePresent = true))
        assertTrue(Pip.supported(26, featurePresent = true))
        // A device can report a new API and still lack the window (TVs, some OEM builds).
        assertFalse(Pip.supported(34, featurePresent = false))
        assertFalse(Pip.supported(24, featurePresent = false))
    }

    @Test
    fun android_twelve_auto_enters_and_older_versions_ask_on_leave() {
        assertFalse(Pip.autoEnters(30))
        assertTrue(Pip.autoEnters(31))
        assertTrue(Pip.autoEnters(34))

        // 26–30: the leave hint is the mechanism.
        assertTrue(Pip.enterOnLeave(26, callLive = true, supported = true))
        assertTrue(Pip.enterOnLeave(30, callLive = true, supported = true))
        // 31+ owns it itself: the two mechanisms can never both fire.
        assertFalse(Pip.enterOnLeave(31, callLive = true, supported = true))
        assertFalse(Pip.enterOnLeave(34, callLive = true, supported = true))
        // Never without a call, and never without the feature.
        assertFalse(Pip.enterOnLeave(30, callLive = false, supported = true))
        assertFalse(Pip.enterOnLeave(30, callLive = true, supported = false))
        assertFalse(Pip.enterOnLeave(24, callLive = true, supported = true))
    }
}
