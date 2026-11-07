package com.hermesvox

/**
 * #329: what the phone's assistant gesture should do.
 *
 * Android hands an assist invocation to whichever app holds `android.app.role.ASSISTANT`
 * (RoleManager.ROLE_ASSISTANT); our entry point is a translucent AssistActivity that hands
 * off to the SAME call/being screen. This rule decides the hand-off, and it is pure JVM so
 * the one part that can be silently wrong — starting a second session over a live one — is
 * unit-tested instead of discovered on a phone.
 *
 * Order matters. A live call outranks everything: if the user is already talking to the
 * entity, the gesture must bring that screen forward, never dial a second conversation.
 * Onboarding outranks starting a call because a call with no gateway cannot listen — it
 * would fail at `startCall`'s first check and leave the user staring at an error instead of
 * the screen that fixes it.
 */
object AssistRoute {

    enum class Target {
        /** First run, or "Skip for now" with no endpoint: open onboarding, not a call. */
        ONBOARD,

        /** A call is already running: bring its screen forward; do not start another. */
        FOCUS_LIVE_CALL,

        /** Set up and idle: open the call screen ready to listen. */
        START_CALL,
    }

    /**
     * @param onboarding what [FirstRunRoute.shouldOnboard] says about this install
     * @param callLive whether a voice call is running app-wide (see [MainActivity.callIsLive])
     */
    fun target(onboarding: Boolean, callLive: Boolean): Target = when {
        callLive -> Target.FOCUS_LIVE_CALL
        onboarding -> Target.ONBOARD
        else -> Target.START_CALL
    }
}
