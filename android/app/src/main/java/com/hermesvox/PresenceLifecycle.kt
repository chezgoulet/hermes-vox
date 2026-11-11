package com.hermesvox

/**
 * #560 — the presence engine's one guarded transition, both directions.
 *
 * The field log that motivated it:
 *
 *     04:48:44.324 event=presence-unload-due reason=idle
 *     04:48:44.347 event=presence-load phase=start
 *     04:48:46.251 event=presence-unload reason=idle released=true
 *     04:48:57.474 event=presence-load phase=end ok=true ms=13127
 *
 * The idle release and a reload were two unsynchronised paths: the unload thread passed its
 * "is a load running?" check, the load started 23 ms later, the unload reported
 * `released=true`, and the load then left a fresh ~2.6 GB engine resident on an idle phone.
 *
 * Here every request goes through one lock and one [Phase]:
 *
 *  - a load cancels a pending unload — the caller drops the idle timers before asking, and a
 *    load in flight makes an unload request [Unload.SKIPPED_LOADING] (the newer intent wins);
 *  - an unload cancels a queued load — a load asked for while the engine is being torn down
 *    waits, and when the teardown finishes it is cancelled, not started ([unloadFinished]
 *    hands the waiters back to be told so). A caller that truly needs the engine (a call
 *    start) asks again and gets a clean load from [Phase.UNLOADED];
 *  - `released=true` is only ever reported from [Phase.UNLOADING], which no load can be in.
 *
 * Pure: no Android, no threads, no clock — the interleavings are tested directly
 * (PresenceLifecycleTest). [W] is whatever the owner needs to answer a waiter.
 */
class PresenceLifecycle<W> {
    enum class Phase { UNLOADED, LOADING, LOADED, UNLOADING }

    /** What a load request turned into. */
    enum class Load {
        /** The caller owns the load: build the engine, then call [loadFinished]. */
        START,
        /** Already resident; nothing to do. */
        RESIDENT,
        /** A load is already in flight; this caller is not the one running it. */
        CHAINED,
        /** A teardown is running; the waiter is held and will be cancelled by it. */
        QUEUED,
    }

    /** What an unload request turned into. */
    enum class Unload {
        /** The caller owns the teardown: release the engine, then call [unloadFinished]. */
        START,
        /** A load is in flight: nothing is released, and nothing may say it was. */
        SKIPPED_LOADING,
        /** A teardown is already running. */
        ALREADY,
        /** Nothing is resident. */
        NOTHING,
    }

    private val lock = Any()
    private val queued = mutableListOf<W>()

    @Volatile var phase: Phase = Phase.UNLOADED
        private set

    /** True while a load is running or waiting on a teardown. */
    val busy: Boolean get() = phase == Phase.LOADING || (phase == Phase.UNLOADING && hasQueued)

    private val hasQueued: Boolean get() = synchronized(lock) { queued.isNotEmpty() }

    fun requestLoad(waiter: W): Load = synchronized(lock) {
        when (phase) {
            Phase.LOADED -> Load.RESIDENT
            Phase.LOADING -> Load.CHAINED
            Phase.UNLOADING -> { queued += waiter; Load.QUEUED }
            Phase.UNLOADED -> { phase = Phase.LOADING; Load.START }
        }
    }

    /** The owner of a [Load.START] reports how it ended. Ignored from any other phase. */
    fun loadFinished(ok: Boolean) = synchronized(lock) {
        if (phase == Phase.LOADING) phase = if (ok) Phase.LOADED else Phase.UNLOADED
    }

    fun requestUnload(): Unload = synchronized(lock) {
        when (phase) {
            Phase.LOADED -> { phase = Phase.UNLOADING; Unload.START }
            Phase.LOADING -> Unload.SKIPPED_LOADING
            Phase.UNLOADING -> Unload.ALREADY
            Phase.UNLOADED -> Unload.NOTHING
        }
    }

    /** The owner of an [Unload.START] reports the teardown done. Returns the loads that
     *  queued behind it — cancelled, never started: the unload wins. */
    fun unloadFinished(): List<W> = synchronized(lock) {
        if (phase != Phase.UNLOADING) return emptyList()
        phase = Phase.UNLOADED
        val cancelled = queued.toList()
        queued.clear()
        cancelled
    }
}
