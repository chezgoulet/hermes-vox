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
