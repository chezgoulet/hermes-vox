package com.hermesvox.core

/**
 * L3 (#413 second pass): how long a `/v1/runs` reader waits before following a run again.
 *
 * Exponential, bounded and jittered ("equal jitter"): the step doubles from [delayMs]'s
 * `baseMs` up to `capMs`, and the wait is half the step plus a random share of the other
 * half. Half the step is always kept, so a flapping socket can never become a hot loop.
 * The random half spreads the phones that lost the same gateway at the same instant.
 *
 * Pure: the random draw is passed in, so the bounds are pinned off-device.
 */
internal object Backoff {

    /**
     * The wait before retry number [attempt] (0-based). [unit] is a draw in `[0, 1]`
     * (clamped). Never more than [capMs]; never less than half the step when [baseMs] > 0.
     */
    fun delayMs(baseMs: Long, attempt: Int, capMs: Long, unit: Double): Long {
        if (baseMs <= 0L || capMs <= 0L) return 0L
        // `shl` past 62 bits overflows; anything that large is already at the cap.
        val step = if (attempt >= 30) capMs else minOf(capMs, baseMs shl maxOf(0, attempt))
        val half = step / 2
        return half + (half * unit.coerceIn(0.0, 1.0)).toLong()
    }
}
