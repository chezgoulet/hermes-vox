package com.hermesvox

import java.util.concurrent.atomic.AtomicBoolean

/**
 * ColdFrame — regression path 2's witness: how long a cold start took to its first frame, in the
 * app's own log, so a field log carries the number `am start -W` prints on the bench.
 *
 * `docs/REGRESSION.md` path 2 named an "`event=lat` cold row" that nothing emitted (the 0.9.x
 * review, §3.0(d)); LatencyStats only ever writes `label=turn`. This is that row:
 *
 *     event=lat label=cold metric=first-frame ms=<process start -> first frame> createMs=<onCreate -> first frame>
 *
 * Exactly once per process ([take]), on the first frame the first MainActivity draws. `ms` is the
 * cold-start figure when the process was started to show the activity (a force-stop then a
 * launch, as `regression-smoke.sh start` does); if a service started the process earlier, `ms`
 * includes that idle time and `createMs` is the one to read. Pure JVM.
 */
internal object ColdFrame {

    private val taken = AtomicBoolean(false)

    /** True exactly once per process. */
    fun take(): Boolean = taken.compareAndSet(false, true)

    /** For tests: forget that the row was written. */
    internal fun reset() = taken.set(false)

    fun line(sinceProcessStartMs: Long, sinceCreateMs: Long): String =
        "event=lat label=cold metric=first-frame ms=${sinceProcessStartMs.coerceAtLeast(0L)} " +
            "createMs=${sinceCreateMs.coerceAtLeast(0L)}"
}
