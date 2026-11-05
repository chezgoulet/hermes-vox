package com.hermesvox

/**
 * Pip — what the picture-in-picture window says, decided without an Activity.
 *
 * The owner's decision is the whole design: **the compact view is the being and nothing
 * else.** No text, no crawl, no status line, no buttons painted in the view. At ~240 dp a
 * line of speech is unreadable, and the being *is* the presence. The only controls are the
 * system's own [android.app.RemoteAction]s — Stop (always) and Mute (only when the
 * controller actually has one), so the window has no caption a later change could grow
 * into a status bar.
 *
 * WHY A PURE OBJECT
 * -----------------
 * Everything here is plain data: the aspect ratio, the actions and the labels, and the
 * API/feature rules. `PipTest` pins them off-device. [PipWindow] is the thin Android
 * adapter that turns [Params] into a `PictureInPictureParams`; it decides nothing.
 *
 * THE GUARDS
 * ----------
 * The app's minSdk is 24 and picture-in-picture arrived in 26, and a device may lack the
 * feature at any API. [supported] is the single answer to "may this window float?", and
 * [enterOnLeave] is the one place the "swipe home" behaviour differs by platform: 31+
 * enters itself ([autoEnters]), 26–30 needs `onUserLeaveHint`. A device without the
 * feature takes exactly the code path it took before this file existed — nothing enters,
 * nothing is set, no call is affected.
 */
object Pip {

    /** Picture-in-picture exists from API 26, and only where the feature is present. */
    const val MIN_API = 26

    /**
     * The window is square. The being is a centred shape with a symmetric aura, so a
     * square wastes no field on either axis; 4:3 or 16:9 would crop the halo horizontally
     * or strand two dark bands, and a portrait card (9:16) is the worst of the three at a
     * PiP window's size. See the report for the on-device look.
     */
    const val ASPECT_NUMERATOR = 1
    const val ASPECT_DENOMINATOR = 1


    /**
     * A control the system draws in the PiP window's menu. [id] is the action token the
     * host maps to a `PendingIntent`; [label] is what the person and a screen reader see.
     * NOTHING here is drawn inside the view.
     */
    enum class Action(val id: String, val label: String) {
        STOP("stop", "Stop"),
        MUTE("mute", "Mute"),
        UNMUTE("unmute", "Unmute"),
    }

    /**
     * The immutable description of one compact window. Deliberately no title and no
     * caption: the system draws the window chrome, and the only words a person sees are
     * the [Action] labels in its menu.
     */
    data class Params(
        val aspectNumerator: Int,
        val aspectDenominator: Int,
        val actions: List<Action>,
    )

    /**
     * The parameters for the call in progress, or **null when there is no live call**.
     *
     * The null is the rule, not a convenience: an idle app in a floating window is a
     * nuisance, so every entry point asks this first and does nothing when it answers null.
     *
     * [canMute] is whether the controller has a mute at all. It does not today, so the live
     * app offers Stop alone; the model still describes both so the plumbing is proven on
     * the day a mute exists. A muted controller's action says "Unmute", because a label
     * that lies about what the tap will do is worse than one word too many.
     */
    fun params(callLive: Boolean, canMute: Boolean = false, muted: Boolean = false): Params? {
        if (!callLive) return null
        val actions = buildList {
            add(Action.STOP)
            if (canMute) add(if (muted) Action.UNMUTE else Action.MUTE)
        }
        return Params(ASPECT_NUMERATOR, ASPECT_DENOMINATOR, actions)
    }

    /** May this window float at all? Both halves are required; neither alone is enough. */
    fun supported(sdk: Int, featurePresent: Boolean): Boolean = sdk >= MIN_API && featurePresent

    /** Android 12+ shrinks a live call on its own when the caller leaves (`autoEnter`). */
    fun autoEnters(sdk: Int): Boolean = sdk >= 31

    /**
     * The pre-12 fallback: `onUserLeaveHint` asks for PiP, but only during a live call and
     * only where the feature exists. On 31+ the system owns the gesture, so this is false
     * and the two mechanisms can never both fire.
     */
    fun enterOnLeave(sdk: Int, callLive: Boolean, supported: Boolean): Boolean =
        callLive && supported && sdk in MIN_API..30
}
