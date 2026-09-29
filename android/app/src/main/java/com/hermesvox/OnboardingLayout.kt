package com.hermesvox

/**
 * #130 — geometry rule for the first-run screen.
 *
 * The onboarding column is a scroll container now, so nothing on it is unreachable.
 * But "reachable after scrolling" is not the same as "findable": the column is
 * ~790dp on a Pixel 8 at default settings and ~1080dp at the text scale the
 * reporter was running, against a window of ~760dp — so the further the presence
 * pushes the only forward button down the page, the harder first run is to finish.
 *
 * The presence therefore yields space as the text grows or the window shrinks. The
 * copy and the action never shrink; only the decoration does.
 *
 * This is code and not a `values-hXXXdp` resource qualifier on purpose: the screen's
 * dp height does not change with font scale (sp does), so a qualifier can see a short
 * phone but not large text — and the reported case was large text on a normal phone.
 */
object OnboardingLayout {
    /** The avatar as declared in activity_onboarding.xml — the size at defaults. */
    const val AVATAR_FULL_DP = 250

    /** Never smaller than this: the presence must still read as a presence. */
    const val AVATAR_MIN_DP = 140

    /**
     * Avatar edge in dp for a device configuration.
     *
     * @param fontScale `Configuration.fontScale` (1.0 = default; the accessibility
     *   slider reaches ~2.0, and Android's non-linear curve means small text grows
     *   while a 30sp heading does not grow at all below ~1.8).
     * @param screenHeightDp `Configuration.screenHeightDp` — available height, so
     *   split-screen and small phones are covered too.
     */
    fun avatarSizeDp(fontScale: Float, screenHeightDp: Int): Int {
        val byText = when {
            fontScale >= 1.8f -> AVATAR_MIN_DP
            fontScale >= 1.45f -> 170
            fontScale >= 1.25f -> 205
            else -> AVATAR_FULL_DP
        }
        val byWindow = when {
            screenHeightDp < 640 -> AVATAR_MIN_DP
            screenHeightDp < 720 -> 180
            screenHeightDp < 800 -> 210
            else -> AVATAR_FULL_DP
        }
        return minOf(byText, byWindow).coerceIn(AVATAR_MIN_DP, AVATAR_FULL_DP)
    }
}
