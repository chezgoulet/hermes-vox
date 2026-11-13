package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421: the setup sheet's permission state is a model, not a sentence written at the call site.
 *
 * It says what Android says — granted or not — and never turns a missing special access into a
 * success. The engine has its own refusal test in [PhoneEngineTest]; this one pins the words the
 * owner reads beside them.
 */
class PhoneToolsViewTest {

    @Test fun dnd_access_is_reported_as_granted_only_when_it_is() {
        assertTrue(PhoneToolsView.dnd(true).contains("Granted"))
        assertFalse(PhoneToolsView.dnd(true).contains("Not granted"))
        assertTrue(PhoneToolsView.dnd(false).contains("Not granted"))
        assertFalse(PhoneToolsView.dnd(false).contains("may change Do Not Disturb"))
    }

    @Test fun the_token_state_never_carries_the_token() {
        for (state in PhoneMcp.TokenState.values()) {
            val line = PhoneToolsView.token(state)
            assertFalse(line, line.contains("Bearer"))
            assertFalse(line, Regex("[0-9a-f]{32}").containsMatchIn(line))
        }
    }

    @Test fun the_server_line_distinguishes_no_tailnet_from_stopped() {
        assertTrue(PhoneToolsView.server(null, false, null).contains("no tailnet"))
        assertTrue(PhoneToolsView.server("100.1.2.3", false, null).contains("opens when a call starts"))
        assertTrue(PhoneToolsView.server("100.1.2.3", true, "http://100.1.2.3:8765/mcp").contains("Listening"))
    }

    @Test fun the_settings_row_never_says_connected_without_the_handshake() {
        assertEquals("set up", PhoneToolsView.rowValue(PhoneSetup.State.IDLE))
        assertEquals("waiting", PhoneToolsView.rowValue(PhoneSetup.State.WAITING))
        assertEquals("connected", PhoneToolsView.rowValue(PhoneSetup.State.CONNECTED))
        assertEquals("retry", PhoneToolsView.rowValue(PhoneSetup.State.TIMED_OUT))
    }
}
