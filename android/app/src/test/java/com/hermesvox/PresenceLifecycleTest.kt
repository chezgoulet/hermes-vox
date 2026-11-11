package com.hermesvox

import com.hermesvox.PresenceLifecycle.Load
import com.hermesvox.PresenceLifecycle.Phase
import com.hermesvox.PresenceLifecycle.Unload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #560: the presence engine's load and unload are one guarded transition. */
class PresenceLifecycleTest {
    private fun resident() = PresenceLifecycle<String>().also {
        assertEquals(Load.START, it.requestLoad("boot"))
        it.loadFinished(true)
        assertEquals(Phase.LOADED, it.phase)
    }

    /**
     * The field interleaving: `presence-unload-due reason=idle` at .324, `presence-load
     * phase=start` at .347, the release reported at 46.251, the load ending at 57.474 with
     * the engine resident. Now the load queues behind the release and is cancelled by it.
     */
    @Test
    fun the_field_interleaving_leaves_the_engine_unloaded_after_an_idle_unload() {
        val lc = resident()
        assertEquals(Unload.START, lc.requestUnload())          // .324 unload-due (idle)
        assertEquals(Load.QUEUED, lc.requestLoad("resume"))     // .347 load asked for
        assertTrue("a queued load is still a load the caller must not double", lc.busy)
        val cancelled = lc.unloadFinished()                      // 46.251 released=true
        assertEquals(listOf("resume"), cancelled)
        assertEquals(Phase.UNLOADED, lc.phase)
        assertFalse(lc.busy)
        // Nothing can bring it back except a fresh request (a call start asks again).
        lc.loadFinished(true)                                    // a stale "load ended" is ignored
        assertEquals(Phase.UNLOADED, lc.phase)
    }

    @Test
    fun an_unload_during_a_load_releases_nothing_and_cannot_say_it_did() {
        val lc = PresenceLifecycle<String>()
        assertEquals(Load.START, lc.requestLoad("a"))
        assertEquals(Unload.SKIPPED_LOADING, lc.requestUnload())
        assertEquals(Phase.LOADING, lc.phase)
        assertTrue(lc.unloadFinished().isEmpty())               // no teardown was granted
        assertEquals(Phase.LOADING, lc.phase)
        lc.loadFinished(true)
        assertEquals(Phase.LOADED, lc.phase)
    }

    @Test
    fun one_load_at_a_time() {
        val lc = PresenceLifecycle<String>()
        assertEquals(Load.START, lc.requestLoad("a"))
        assertEquals(Load.CHAINED, lc.requestLoad("b"))
        lc.loadFinished(true)
        assertEquals(Load.RESIDENT, lc.requestLoad("c"))
    }

    @Test
    fun one_unload_at_a_time_and_nothing_to_release_when_empty() {
        assertEquals(Unload.NOTHING, PresenceLifecycle<String>().requestUnload())
        val lc = resident()
        assertEquals(Unload.START, lc.requestUnload())
        assertEquals(Unload.ALREADY, lc.requestUnload())
        lc.unloadFinished()
        assertEquals(Unload.NOTHING, lc.requestUnload())
    }

    @Test
    fun a_failed_load_leaves_it_unloaded_and_loadable() {
        val lc = PresenceLifecycle<String>()
        lc.requestLoad("a"); lc.loadFinished(false)
        assertEquals(Phase.UNLOADED, lc.phase)
        assertEquals(Load.START, lc.requestLoad("b"))
    }

    @Test
    fun after_a_cancelled_load_a_fresh_request_starts_a_clean_load() {
        val lc = resident()
        lc.requestUnload(); lc.requestLoad("resume"); lc.unloadFinished()
        assertEquals(Load.START, lc.requestLoad("call-start"))
        assertEquals(Phase.LOADING, lc.phase)
    }

    @Test
    fun the_interleaving_holds_under_threads() {
        // Many rounds of an unload and a load racing: whenever the unload was granted, the
        // engine must end the round either unloaded, or loaded by a load that STARTED after
        // the teardown finished — never by one that overlapped it.
        repeat(500) {
            val lc = resident()
            var overlapped = false
            val t1 = Thread {
                if (lc.requestUnload() == Unload.START) lc.unloadFinished()
            }
            val t2 = Thread {
                val r = lc.requestLoad("x")
                if (r == Load.START) {
                    if (lc.phase != Phase.LOADING) overlapped = true
                    lc.loadFinished(true)
                }
            }
            t1.start(); t2.start(); t1.join(); t2.join()
            assertFalse(overlapped)
            assertTrue(lc.phase == Phase.LOADED || lc.phase == Phase.UNLOADED)
        }
    }
}
