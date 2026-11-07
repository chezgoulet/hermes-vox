package com.hermesvox

import android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.app.NotificationManager.INTERRUPTION_FILTER_NONE
import android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #325 — silence during a call, and put back exactly what was changed. */
class CallQuietTest {

    @Test fun `off by default`() {
        assertFalse(CallQuiet.DEFAULT_ON)
    }

    @Test fun `a call goes to priority only when on, granted and not already quiet`() {
        assertEquals(INTERRUPTION_FILTER_PRIORITY, CallQuiet.plan(true, true, INTERRUPTION_FILTER_ALL))
        assertNull("off", CallQuiet.plan(false, true, INTERRUPTION_FILTER_ALL))
        assertNull("no access", CallQuiet.plan(true, false, INTERRUPTION_FILTER_ALL))
        assertNull("the user's own DND stands", CallQuiet.plan(true, true, INTERRUPTION_FILTER_ALARMS))
        assertNull(CallQuiet.plan(true, true, INTERRUPTION_FILTER_NONE))
    }

    @Test fun `the end restores what was there before`() {
        assertEquals(INTERRUPTION_FILTER_ALL,
            CallQuiet.restoreTo(INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_ALL, INTERRUPTION_FILTER_PRIORITY))
    }

    @Test fun `a change the user made during the call wins`() {
        assertNull(CallQuiet.restoreTo(INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_ALL, INTERRUPTION_FILTER_NONE))
        assertNull(CallQuiet.restoreTo(INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_ALL, INTERRUPTION_FILTER_ALL))
    }

    @Test fun `nothing set, nothing restored`() {
        assertNull(CallQuiet.restoreTo(null, null, INTERRUPTION_FILTER_ALL))
    }

    @Test fun `it is its own setting, not an alpha set`() {
        val prefs = mapOf(CallQuiet.KEY to true, AlphaGate.KEY_MASTER to false)
        val read: (String, Boolean) -> Boolean = { k, d -> prefs[k] ?: d }
        assertTrue("the feature works with the master switch off", CallQuiet.enabled(read))
        assertFalse("and is still off until its own switch is on",
            CallQuiet.enabled { _, _ -> false })
        assertFalse(AlphaGate.FeatureSet.entries.any { it.key == "alpha_set_quiet_calls" })
        assertFalse("the old set is gone, so the reader cannot name it",
            AlphaGate.FeatureSet.entries.any { it.name == "QUIET_CALLS" })
    }
}
