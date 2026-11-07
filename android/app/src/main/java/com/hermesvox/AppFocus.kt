package com.hermesvox

import java.util.concurrent.atomic.AtomicInteger

/**
 * AppFocus — whether an activity of this app is resumed right now.
 *
 * Android 10's clipboard rule is on **reads**: only the focused app (or the default IME) may read a
 * clip, while a write through `setPrimaryClip` is allowed without focus. So this signal has two
 * jobs, neither of them a gate on writing: the clipboard read uses it to explain a "no clip" answer
 * (where lack of focus is the likely cause), and the approval card uses it to decide whether the
 * owner can see a card or needs a notification. `clip` never asks it: it writes and reports the
 * platform's answer.
 *
 * The signal is the resumed-activity count, maintained by [VoxApplication]'s lifecycle callbacks: a
 * resumed activity is one the owner can see. Picture-in-picture pauses the activity, so PiP reads as
 * "not in front" — which is exactly the state where a clipboard read usually returns nothing. Multi-
 * window can resume an activity without window focus, so this is a close approximation of Android's
 * own rule rather than a proof of it; the platform makes the real decision and its answer is
 * reported.
 *
 * Pure JVM (a counter), so the rule is provable off-device.
 */
object AppFocus {

    private val resumed = AtomicInteger(0)

    /** An activity became visible. */
    fun onActivityResumed() {
        resumed.incrementAndGet()
    }

    /** An activity stopped being visible. Never goes below zero, however unbalanced the callbacks. */
    fun onActivityPaused() {
        resumed.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** True when at least one activity of this app is resumed. */
    val inFront: Boolean get() = resumed.get() > 0

    /** A fresh process, for tests. */
    fun reset() {
        resumed.set(0)
    }
}
