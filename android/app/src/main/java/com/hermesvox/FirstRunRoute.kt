package com.hermesvox

/**
 * Whether launching Main should route to onboarding instead.
 *
 * First run (no stored endpoint) goes to onboarding. "Skip for now" there is a
 * promise that the user can look around first, so a recorded skip must be
 * honored: before this rule, Main re-opened onboarding on the blank endpoint and
 * "Skip anyway" looped straight back to the screen it had just dismissed.
 * Main's not-connected state (and Settings → Entity) carries the user from
 * there. Pure JVM so the rule is unit-tested.
 */
object FirstRunRoute {
    /** Pref flag written by onboarding's "Skip anyway". */
    const val PREF_SKIPPED = "onboarding_skipped"

    fun shouldOnboard(storedUrl: String?, skipped: Boolean): Boolean =
        storedUrl.isNullOrBlank() && !skipped
}
