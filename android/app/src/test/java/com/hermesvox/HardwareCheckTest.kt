package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #403/D29 — the hardware check: 8 GB total RAM and a free-memory floor at first run, then a real
 * load on the first call that can demote. Acceptance: a phone under the bar is told why and sees
 * the path to turn Presence on; a phone over it is advised; nothing is switched on without the user.
 */
class HardwareCheckTest {

    private class Fake(val m: MutableMap<String, Any> = HashMap()) : HardwareCheck.Store {
        override fun contains(key: String) = m.containsKey(key)
        override fun getLong(key: String) = (m[key] as? Long) ?: -1L
        override fun getString(key: String) = m[key] as? String
        override fun putLong(key: String, value: Long) { m[key] = value }
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    }

    @Test fun an_eight_gb_phone_reports_less_than_eight_gb_and_still_meets_the_bar() {
        // A Pixel-class "8 GB" phone reports ~7.5 GB of totalMem.
        assertEquals(8, HardwareCheck.nominalGb(7_680))
        assertEquals(HardwareCheck.Verdict.CARRIES, HardwareCheck.verdict(7_680, 4_000, null))
        assertEquals(6, HardwareCheck.nominalGb(5_600))
        assertEquals(HardwareCheck.Verdict.BELOW_BAR, HardwareCheck.verdict(5_600, 3_500, null))
        assertEquals(8, HardwareCheck.BAR_GB)
    }

    @Test fun the_free_floor_is_checked_on_a_phone_over_the_bar() {
        assertEquals(HardwareCheck.Verdict.LOW_FREE, HardwareCheck.verdict(11_500, 2_000, null))
        assertEquals(HardwareCheck.Verdict.CARRIES, HardwareCheck.verdict(11_500, HardwareCheck.FREE_FLOOR_MB, null))
    }

    @Test fun an_unmeasured_phone_gives_no_advice() {
        assertEquals(HardwareCheck.Verdict.UNKNOWN, HardwareCheck.verdict(Fake()))
        assertNull(HardwareCheck.advice(Fake()))
    }

    @Test fun stage_one_runs_once() {
        val s = Fake()
        assertTrue(HardwareCheck.runOnce(s, 5_600, 3_000))
        assertFalse(HardwareCheck.runOnce(s, 11_000, 9_000))
        assertEquals(5_600L, s.m[HardwareCheck.KEY_TOTAL_MB])
    }

    @Test fun stage_one_never_enables_anything() {
        for ((t, a) in listOf(5_600L to 3_000L, 11_500L to 8_000L, 11_500L to 1_000L)) {
            val s = Fake()
            HardwareCheck.runOnce(s, t, a)
            assertFalse(s.m.keys.any { it == AlphaGate.KEY_MASTER || it.startsWith("alpha_set_") })
        }
    }

    @Test fun a_phone_under_the_bar_is_told_why_and_where_to_turn_it_on() {
        val s = Fake().also { HardwareCheck.runOnce(it, 5_600, 3_000) }
        val first = HardwareCheck.firstRunLine(s)
        assertTrue(first, first.contains("stays off"))
        assertTrue(first, first.contains("6 GB") && first.contains("8 GB"))
        assertTrue(first, first.contains(HardwareCheck.WHERE))
        assertTrue(HardwareCheck.advice(s)!!.contains("needs 8 GB"))
    }

    @Test fun a_phone_over_the_bar_is_advised_and_still_off() {
        val s = Fake().also { HardwareCheck.runOnce(it, 7_680, 4_096) }
        val advice = HardwareCheck.advice(s)!!
        assertTrue(advice, advice.contains("meets Presence's bar"))
        assertTrue(advice, advice.contains("off until you select it"))
        assertTrue(HardwareCheck.firstRunLine(s).contains("off until you turn it on"))
    }

    @Test fun a_failed_first_load_demotes_the_selection_and_says_so() {
        val s = Fake(mutableMapOf(AlphaGate.FeatureSet.PRESENCE.key to true))
        HardwareCheck.runOnce(s, 7_680, 4_096)
        assertTrue(HardwareCheck.noteFirstLoad(s, ok = false))
        assertEquals(false, s.m[AlphaGate.FeatureSet.PRESENCE.key])
        assertEquals(HardwareCheck.Verdict.LOAD_FAILED, HardwareCheck.verdict(s))
        assertTrue(HardwareCheck.advice(s)!!.contains("turned off"))
    }

    @Test fun only_the_first_load_is_the_check() {
        val s = Fake(mutableMapOf(AlphaGate.FeatureSet.PRESENCE.key to true))
        assertTrue(HardwareCheck.noteFirstLoad(s, ok = true))
        // A later failure is an ordinary one: the user re-selected Presence knowing the phone.
        assertFalse(HardwareCheck.noteFirstLoad(s, ok = false))
        assertEquals(true, s.m[AlphaGate.FeatureSet.PRESENCE.key])
    }

    @Test fun a_successful_first_load_never_enables_anything() {
        val s = Fake()
        HardwareCheck.noteFirstLoad(s, ok = true)
        assertFalse(s.m.containsKey(AlphaGate.FeatureSet.PRESENCE.key))
    }

    @Test fun first_run_names_the_connection_the_voice_and_the_being() {
        for (name in listOf("The connection", "The voice", "The being")) {
            assertTrue(name, FIRST_RUN_THREE_THINGS.contains(name))
        }
    }
}
