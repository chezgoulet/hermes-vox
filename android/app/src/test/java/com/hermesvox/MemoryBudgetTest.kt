package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory-budget knobs (Phase 2 and Phase 4) are policy, and policy is pinned here so a
 * measurement run cannot be attributed to an arm the build was not actually in.
 *
 * The point of these tests is the DEFAULT: a phone that never opens the dev dialog must get
 * the shipped 8192-token cache and a never-unloaded engine, whatever a hand-edited pref says.
 */
class MemoryBudgetTest {

    @Test fun kv_default_is_the_shipped_8192() {
        assertEquals(8192, MemoryBudget.KV_DEFAULT)
        // no pref set (0 is what SharedPreferences' getInt default would be if someone passed
        // 0 through by mistake) — and every junk value — resolves to the shipped cache
        for (junk in intArrayOf(0, -1, 1, 1024, 3000, 8191, 16384, Int.MAX_VALUE))
            assertEquals("pref=$junk", 8192, MemoryBudget.kvTokens(junk))
    }

    @Test fun kv_arms_are_exactly_the_three_measured() {
        assertEquals(2048, MemoryBudget.kvTokens(2048))
        assertEquals(4096, MemoryBudget.kvTokens(4096))
        assertEquals(8192, MemoryBudget.kvTokens(8192))
        assertEquals(3, MemoryBudget.KV_CHOICES.size)
    }

    @Test fun unload_is_off_unless_asked() {
        assertFalse(MemoryBudget.shouldUnload(false))
        assertTrue(MemoryBudget.shouldUnload(true))
    }

    @Test fun idle_release_is_one_minute_by_default() {
        // D27: the shipped value, and the only one a phone that never opened the dev dialog has.
        assertEquals(1, MemoryBudget.DELAY_DEFAULT)
        assertEquals(0, MemoryBudget.unloadDelayMinutes(0))   // the off switch still works
        assertEquals(0L, MemoryBudget.unloadDelayMs(0))
        assertEquals(60_000L, MemoryBudget.unloadDelayMs(MemoryBudget.DELAY_DEFAULT))
        // junk falls back to the shipped minute, never to a schedule nobody chose
        for (junk in intArrayOf(-1, -60, MemoryBudget.MAX_DELAY_MINUTES + 1, Int.MIN_VALUE, Int.MAX_VALUE))
            assertEquals("pref=$junk", MemoryBudget.DELAY_DEFAULT, MemoryBudget.unloadDelayMinutes(junk))
        // and the arm the run measured is two minutes, exactly
        assertEquals(2, MemoryBudget.unloadDelayMinutes(2))
        assertEquals(120_000L, MemoryBudget.unloadDelayMs(2))
        assertEquals(0, MemoryBudget.DELAY_CHOICES[0])   // the off switch is offered first
        assertTrue(MemoryBudget.DELAY_CHOICES.contains(2))
    }

    @Test fun release_policy_prefers_the_delayed_arm_and_keeps_the_shipped_default() {
        // shipped: both prefs absent -> the engine is released a minute after hangup (D27)
        assertEquals(MemoryBudget.Release.AFTER_DELAY, MemoryBudget.release(false, MemoryBudget.DELAY_DEFAULT))
        // an explicit "never" is still the off switch
        assertEquals(MemoryBudget.Release.KEEP, MemoryBudget.release(false, 0))
        // the Phase 4 arm still works exactly as measured
        assertEquals(MemoryBudget.Release.ON_HANGUP, MemoryBudget.release(true, 0))
        // the Phase 5 arm, and its precedence: a delay is the gentler of the two, so it wins
        // over unload-on-every-hangup rather than being silently doubled up with it
        assertEquals(MemoryBudget.Release.AFTER_DELAY, MemoryBudget.release(false, 2))
        assertEquals(MemoryBudget.Release.AFTER_DELAY, MemoryBudget.release(true, 2))
        // junk delay with unload on is still the immediate arm, not "never"
        assertEquals(MemoryBudget.Release.ON_HANGUP, MemoryBudget.release(true, -5))
    }

    @Test fun the_dialog_offers_the_three_kv_arms_it_measures() {
        // The 0.9.1 dialog drew a message and no rows; the labels are the rows. Pinned so the
        // arms cannot disappear again without failing a test.
        val labels = MemoryBudget.KV_CHOICES.map { MemoryBudget.kvLabel(it) }
        assertEquals(3, labels.size)
        for (tokens in intArrayOf(2048, 4096, 8192))
            assertTrue("missing $tokens", labels.any { it.startsWith("$tokens ") })
        assertTrue(labels.last().contains("shipped"))
        assertTrue(MemoryBudget.delayLabel(0).contains("never"))
        assertTrue(MemoryBudget.delayLabel(2).contains("2 min"))
    }

    @Test fun rotation_follows_the_cache_the_engine_was_built_with() {
        // A 2048 cache with an 8192 rotation threshold would overrun the cache before the
        // soul ever rotated — the render would fail instead. Rotation must track the arm.
        assertTrue(MemoryBudget.mustRotate(1600, 2048))
        assertFalse(MemoryBudget.mustRotate(1600, 8192))
        // and the shipped arm keeps exactly the shipped rule
        assertFalse(MemoryBudget.mustRotate(SoulBudget.MAX_TOKENS - SoulBudget.HEADROOM_TOKENS - 1, 8192))
        assertTrue(MemoryBudget.mustRotate(SoulBudget.MAX_TOKENS - SoulBudget.HEADROOM_TOKENS, 8192))
    }

    @Test fun a_2048_cache_spends_half_its_budget_on_headroom() {
        // Recorded as a fact, not a hope: HEADROOM_TOKENS (1024) is a fixed shipped constant,
        // so the smallest arm can use at most 1024 tokens for persona + history before it
        // rotates. That is the reason 2048 is not "free" memory — it is a shorter memory.
        val usable = 2048 - SoulBudget.HEADROOM_TOKENS
        assertEquals(1024, usable)
        assertEquals(7168, 8192 - SoulBudget.HEADROOM_TOKENS)
    }
}
