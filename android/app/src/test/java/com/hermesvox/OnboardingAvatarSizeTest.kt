package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #130: how big the presence may be on the first-run screen.
 *
 * The column scrolls, so the avatar is no longer a reachability question — it is a
 * "how much scrolling does first run cost" question, and the answer has to hold at
 * text scales the app never sees in the office. The presence yields; the copy and
 * the button never do.
 *
 * Device-free JVM test (same shape as SessionScopeTest / ModelCatalogSourceTest).
 */
class OnboardingAvatarSizeTest {

    @Test fun default_configuration_is_unchanged() {
        // Pixel 8 at Font size Default / Display size Default: the layout keeps the
        // presence exactly as it is declared, so nothing about the default screen moves.
        assertEquals(OnboardingLayout.AVATAR_FULL_DP, OnboardingLayout.avatarSizeDp(1.0f, 891))
        assertEquals(OnboardingLayout.AVATAR_FULL_DP, OnboardingLayout.avatarSizeDp(1.0f, 914))
        // Font size "Large" (1.15) is a stock setting, and the fix is the scroll
        // container — it must not cost the user their presence.
        assertEquals(OnboardingLayout.AVATAR_FULL_DP, OnboardingLayout.avatarSizeDp(1.15f, 891))
    }

    @Test fun large_text_yields_space() {
        assertEquals(205, OnboardingLayout.avatarSizeDp(1.3f, 891))    // Font size "Largest"
        assertEquals(170, OnboardingLayout.avatarSizeDp(1.5f, 891))    // the reporter's scale
        assertEquals(170, OnboardingLayout.avatarSizeDp(1.5f, 835))    // reporter's display size too
        assertEquals(OnboardingLayout.AVATAR_MIN_DP, OnboardingLayout.avatarSizeDp(2.0f, 891))
    }

    @Test fun short_windows_yield_space_even_at_default_text() {
        assertEquals(OnboardingLayout.AVATAR_MIN_DP, OnboardingLayout.avatarSizeDp(1.0f, 639))
        assertEquals(180, OnboardingLayout.avatarSizeDp(1.0f, 640))
        assertEquals(180, OnboardingLayout.avatarSizeDp(1.0f, 700))
        assertEquals(180, OnboardingLayout.avatarSizeDp(1.0f, 719))
        assertEquals(210, OnboardingLayout.avatarSizeDp(1.0f, 720))
        assertEquals(210, OnboardingLayout.avatarSizeDp(1.0f, 780))
        assertEquals(210, OnboardingLayout.avatarSizeDp(1.0f, 799))
        assertEquals(OnboardingLayout.AVATAR_FULL_DP, OnboardingLayout.avatarSizeDp(1.0f, 800))
    }

    @Test fun the_text_rule_wins_when_the_window_is_roomy() {
        // A tall window does not license a big avatar when the text is huge.
        assertEquals(170, OnboardingLayout.avatarSizeDp(1.5f, 1000))
        assertEquals(OnboardingLayout.AVATAR_MIN_DP, OnboardingLayout.avatarSizeDp(2.0f, 1000))
    }

    @Test fun size_never_grows_with_larger_text_or_a_shorter_window() {
        var prev = Int.MAX_VALUE
        var f = 0.85f
        while (f <= 2.2f) {
            val s = OnboardingLayout.avatarSizeDp(f, 891)
            assertTrue("size grew at fontScale=$f ($prev -> $s)", s <= prev)
            prev = s
            f += 0.05f
        }

        var prevH = Int.MAX_VALUE
        for (h in 1000 downTo 400 step 10) {
            val s = OnboardingLayout.avatarSizeDp(1.0f, h)
            assertTrue("size grew as the window shrank to ${h}dp", s <= prevH)
            prevH = s
        }
    }

    @Test fun size_stays_within_the_declared_bounds() {
        var f = 0.0f
        while (f <= 3.0f) {
            for (h in intArrayOf(0, 320, 480, 640, 720, 800, 900, 1200)) {
                val s = OnboardingLayout.avatarSizeDp(f, h)
                assertTrue("out of bounds at fontScale=$f height=$h: $s",
                    s in OnboardingLayout.AVATAR_MIN_DP..OnboardingLayout.AVATAR_FULL_DP)
            }
            f += 0.1f
        }
    }
}
