package com.hermesvox

import com.hermesvox.MemoryBudget.ChargeWarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Keep presence warm while charging": off by default, says what it costs, and only holds
 *  the engine while every condition is true. */
class WarmChargingTest {
    @Test
    fun off_by_default() {
        assertFalse(MemoryBudget.warmWhileCharging(null))
        assertTrue(MemoryBudget.warmWhileCharging(true))
    }

    @Test
    fun loads_and_holds_only_when_on_charging_presence_running_and_no_call() {
        assertEquals(ChargeWarm.LOAD_AND_HOLD, MemoryBudget.chargeWarm(true, true, true, false))
        assertEquals(ChargeWarm.HOLD, MemoryBudget.chargeWarm(true, true, true, true))
    }

    @Test
    fun any_missing_condition_hands_back_to_the_idle_rules() {
        for (callLive in listOf(false, true)) {
            assertEquals(ChargeWarm.OFF, MemoryBudget.chargeWarm(false, true, true, callLive))   // setting off
            assertEquals(ChargeWarm.OFF, MemoryBudget.chargeWarm(true, false, true, callLive))   // unplugged
            assertEquals(ChargeWarm.OFF, MemoryBudget.chargeWarm(true, true, false, callLive))   // Presence off
        }
    }

    @Test
    fun the_row_names_its_cost_in_both_states() {
        assertTrue(MemoryBudget.warmChargingLabel(true).contains("≈2.6 GB"))
        assertTrue(MemoryBudget.warmChargingLabel(false).contains("≈2.6 GB"))
        assertTrue(MemoryBudget.warmChargingLabel(false).startsWith("Off"))
    }

    @Test
    fun the_setting_lives_under_presence_and_starts_off() {
        val layout = listOf(File("src/main/res/layout/activity_settings.xml"),
            File("app/src/main/res/layout/activity_settings.xml")).first { it.exists() }.readText()
        val sec = layout.indexOf("android:id=\"@+id/sec_presence\"")
        val row = layout.indexOf("android:id=\"@+id/row_presence_warm_charging\"")
        val adv = layout.indexOf("android:id=\"@+id/row_presence_advanced\"")
        assertTrue(sec in 0 until row && row < adv)
        assertTrue(layout.contains("Keep presence warm while charging"))
        val sw = layout.indexOf("android:id=\"@+id/sw_presence_warm_charging\"")
        assertTrue(layout.substring(sw, sw + 300).contains("android:checked=\"false\""))
    }
}
