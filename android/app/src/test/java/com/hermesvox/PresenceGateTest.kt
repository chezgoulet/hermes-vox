package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #222/D16 — the migration is the part that matters.
 *
 * A stored `realtime` must become off and a stored `enhanced` must become on; an install
 * that never touched the setting gets on. A setting silently reversed is the failure this
 * issue exists to prevent, so the tests start from each stored value — including the unset
 * case — and then run the migration a second time to prove it flips nothing.
 */
class PresenceGateTest {

    /** The whole `hv` migration surface, in memory. */
    private class Fake : PresenceGate.Store {
        val values = linkedMapOf<String, Any?>()
        override fun contains(key: String) = values.containsKey(key)
        override fun getString(key: String) = values[key] as? String
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private fun migratedFrom(legacy: String?): Fake = Fake().apply {
        if (legacy != null) values[PresenceGate.LEGACY_KEY_VOICE_MODE] = legacy
        PresenceGate.migrate(this)
    }

    @Test fun `a stored realtime becomes off`() {
        val store = migratedFrom(PresenceGate.LEGACY_REALTIME)
        assertEquals(false, store.values[PresenceGate.KEY_PRESENCE_ENABLED])
    }

    @Test fun `a stored enhanced becomes on`() {
        val store = migratedFrom(PresenceGate.LEGACY_ENHANCED)
        assertEquals(true, store.values[PresenceGate.KEY_PRESENCE_ENABLED])
    }

    @Test fun `an unset install arrives with presence on`() {
        val store = migratedFrom(null)
        assertEquals(true, store.values[PresenceGate.KEY_PRESENCE_ENABLED])
    }

    @Test fun `a stale stored value is not a reversal — it falls back to on`() {
        assertEquals(true, PresenceGate.migrated("something-else"))
    }

    @Test fun `re-running the migration flips nothing`() {
        for (legacy in listOf(
            PresenceGate.LEGACY_REALTIME,
            PresenceGate.LEGACY_ENHANCED,
            null,
        )) {
            val store = migratedFrom(legacy)
            val first = store.values[PresenceGate.KEY_PRESENCE_ENABLED]

            PresenceGate.migrate(store)
            PresenceGate.migrate(store)

            assertEquals("migration reversed a $legacy install", first,
                store.values[PresenceGate.KEY_PRESENCE_ENABLED])
        }
    }

    @Test fun `the retired key is removed, not left to be read again`() {
        for (legacy in listOf(
            PresenceGate.LEGACY_REALTIME,
            PresenceGate.LEGACY_ENHANCED,
        )) {
            val store = migratedFrom(legacy)
            assertFalse("$legacy survived the migration",
                store.values.containsKey(PresenceGate.LEGACY_KEY_VOICE_MODE))
        }
    }

    // --- the runtime gate: the user's choice AND what the phone can supply ---

    @Test fun `presence is off when the user turned it off`() {
        assertEquals(PresenceGate.State.OFF,
            PresenceGate.state(enabled = false, installed = true, canStart = true))
        assertFalse(PresenceGate.active(false, true, true))
    }

    @Test fun `presence is off when the model is absent`() {
        assertEquals(PresenceGate.State.NO_MODEL,
            PresenceGate.state(enabled = true, installed = false, canStart = true))
        assertFalse(PresenceGate.active(true, false, true))
    }

    @Test fun `presence is off when the model cannot start`() {
        assertEquals(PresenceGate.State.CANNOT_START,
            PresenceGate.state(enabled = true, installed = true, canStart = false))
        assertFalse(PresenceGate.active(true, true, false))
    }

    @Test fun `presence runs only when the user asked and the phone can supply it`() {
        assertEquals(PresenceGate.State.ON,
            PresenceGate.state(enabled = true, installed = true, canStart = true))
        assertTrue(PresenceGate.active(true, true, true))
    }

    @Test fun `the user's off choice wins over a missing model`() {
        // Both facts are true at once; the row says "Off", and the absence of a model is
        // not a reason to re-enable something the user turned off.
        assertEquals(PresenceGate.State.OFF,
            PresenceGate.state(enabled = false, installed = false, canStart = false))
    }
}
