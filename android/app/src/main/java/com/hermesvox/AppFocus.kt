package com.hermesvox

import java.util.concurrent.atomic.AtomicInteger

/**
 * AppFocus — whether an activity of this app is in front right now.
 *
 * Two callers need it: the clipboard write (`clip`) and the clipboard read (`clip_read`). Android
 * only lets an app the owner can see touch the clipboard, and from Android 10 a backgrounded call is
 * dropped — silently for a write, with no clipboard for a read. So the phone asks before it acts,
 * rather than reporting a success it cannot check or returning an empty clipboard as if it were the
 * answer.
 *
 * The signal is the resumed-activity count, maintained by [VoxApplication]'s lifecycle callbacks: a
 * resumed activity is one the owner can see. Multi-window can resume an activity without window
 * focus, so this is a close approximation of Android's own rule rather than a proof of it. The
 * platform can still refuse a clipboard call from an app in front, and that refusal is reported as
 * `unavailable` too.
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
