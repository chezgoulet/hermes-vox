package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #329 — the assistant gesture routes into the EXISTING call screen. The report is a
 * long press of the side button opening Vox; the promise the app must keep is that the
 * press never starts a second session over a live one, and never drops a fresh install
 * into a call it cannot hold.
 */
class AssistRouteTest {

    @Test fun a_live_call_is_focused_never_redialled() {
        assertEquals(
            AssistRoute.Target.FOCUS_LIVE_CALL,
            AssistRoute.target(onboarding = false, callLive = true),
        )
    }

    @Test fun an_idle_set_up_app_opens_ready_to_listen() {
        assertEquals(
            AssistRoute.Target.START_CALL,
            AssistRoute.target(onboarding = false, callLive = false),
        )
    }

    @Test fun a_fresh_install_goes_to_onboarding_not_into_a_call() {
        assertEquals(
            AssistRoute.Target.ONBOARD,
            AssistRoute.target(onboarding = true, callLive = false),
        )
    }

    @Test fun a_live_call_outranks_a_stale_first_run_state() {
        // Endpoint was blanked in Settings while a call kept running: the running call is
        // real and the screen must come back, not vanish into onboarding mid-conversation.
        assertEquals(
            AssistRoute.Target.FOCUS_LIVE_CALL,
            AssistRoute.target(onboarding = true, callLive = true),
        )
    }
}
