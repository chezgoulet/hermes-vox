package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias M = MotionState.Motion
private typealias S = MotionState.Signal

/**
 * Proves the 0.5.0-previewB motion vocabulary: the being's body must REPORT its
 * state, so every live signal has exactly one motion and the two precedence rules
 * hold under the signals that actually repeat in the field.
 *
 * The behaviour these rows defend:
 *  - a provider that goes quiet stays VISIBLY waiting even though "thinking" keeps
 *    arriving every poll (without the precedence row, the being flips back to
 *    gathering ~30 times a second and the stall is invisible — the dead-air bug);
 *  - an edge event (a tool call, a result, the mic reopening, a barge) is proof the
 *    silence ended, so it breaks the wait;
 *  - a barge is never swallowed, from any motion;
 *  - and the drive params are a real amplitude lock, not a constant.
 *
 * Pure JVM — no Android deps.
 */
class MotionStateTest {

    private fun t(from: MotionState.Motion, sig: MotionState.Signal) = MotionState.transition(from, sig)

    // ---- ambient state -> motion ------------------------------------------

    @Test fun ambient_signals_map_to_their_motion() {
        assertEquals(M.LISTENING, t(M.IDLE, S.LISTEN))
        assertEquals(M.THINKING, t(M.LISTENING, S.THINK))
        assertEquals(M.SPEAKING, t(M.THINKING, S.SPEAK))
        assertEquals(M.IDLE, t(M.SPEAKING, S.REST))
    }

    @Test fun a_natural_end_settles_from_anywhere() {
        for (m in M.values()) assertEquals(M.SETTLE, t(m, S.RETIRE))
    }

    // ---- STALL precedence: the presence lever ------------------------------

    @Test fun stall_takes_over_work_in_flight() {
        assertEquals(M.STALL, t(M.THINKING, S.STALL_ON))
        assertEquals(M.STALL, t(M.SPEAKING, S.STALL_ON))
        assertEquals(M.STALL, t(M.TOOL, S.STALL_ON))
        assertEquals(M.STALL, t(M.TOOL_RESULT, S.STALL_ON))
    }

    @Test fun stall_is_meaningless_with_nothing_in_flight() {
        // Nothing is pending, so a stall signal must not change what is on screen.
        for (m in listOf(M.LISTENING, M.IDLE, M.DRIFT, M.SETTLE, M.RECOIL, M.STALL)) {
            assertEquals(m, t(m, S.STALL_ON))
        }
    }

    @Test fun repeating_ambient_signals_cannot_clear_a_stall() {
        // THE regression this table exists for: onState keeps pushing "thinking" (and,
        // on the one-shot path, "speaking") while the provider is silent. If either
        // were allowed through, the waiting constellation would never be seen.
        assertEquals(M.STALL, t(M.STALL, S.THINK))
        assertEquals(M.STALL, t(M.STALL, S.SPEAK))
        assertEquals(M.STALL, t(M.STALL, S.REST))
    }

    @Test fun only_real_bytes_end_the_wait() {
        assertEquals(M.THINKING, t(M.STALL, S.RESUME))
    }

    @Test fun resume_is_a_noop_when_nothing_is_stalled() {
        for (m in M.values().filter { it != M.STALL }) assertEquals(m, t(m, S.RESUME))
    }

    @Test fun edge_events_break_a_stall() {
        // These fire only when something actually happened, so they are their own
        // proof the silence is over.
        assertEquals(M.TOOL, t(M.STALL, S.TOOL_CALL))
        assertEquals(M.TOOL_RESULT, t(M.STALL, S.TOOL_DONE))
        assertEquals(M.LISTENING, t(M.STALL, S.LISTEN))
        assertEquals(M.RECOIL, t(M.STALL, S.BARGE))
        assertEquals(M.SETTLE, t(M.STALL, S.RETIRE))
    }

    // ---- RECOIL: one-shot, never swallowed --------------------------------

    @Test fun a_cut_is_heard_from_every_motion() {
        for (m in M.values()) assertEquals(M.RECOIL, t(m, S.BARGE))
    }

    @Test fun recoil_hands_back_to_the_next_real_signal() {
        // The ~350ms hold lives in the caller (a pure table has no clock); the table's
        // job is only to let the next signal through once the window is spent.
        assertEquals(M.LISTENING, t(M.RECOIL, S.LISTEN))
        assertEquals(M.IDLE, t(M.RECOIL, S.REST))
        assertEquals(M.THINKING, t(M.RECOIL, S.THINK))
        assertTrue("the one-shot needs a real window", MotionState.RECOIL_MS in 150..800)
    }

    // ---- tools + rest ------------------------------------------------------

    @Test fun tool_signals_are_their_own_motions() {
        assertEquals(M.TOOL, t(M.THINKING, S.TOOL_CALL))
        assertEquals(M.TOOL_RESULT, t(M.TOOL, S.TOOL_DONE))
        assertEquals(M.TOOL, t(M.TOOL_RESULT, S.TOOL_CALL))
    }

    @Test fun quiet_widens_only_what_is_already_at_rest() {
        for (m in listOf(M.IDLE, M.SETTLE, M.DRIFT)) assertEquals(M.DRIFT, t(m, S.QUIET))
        // Busy motions must never drift away mid-work.
        for (m in listOf(M.LISTENING, M.THINKING, M.SPEAKING, M.STALL, M.TOOL, M.TOOL_RESULT, M.RECOIL)) {
            assertEquals(m, t(m, S.QUIET))
        }
    }

    @Test fun the_table_is_total_and_has_no_invalid_transitions() {
        val valid = M.values().toSet()
        for (m in M.values()) for (sig in S.values()) {
            assertTrue("$m + $sig", t(m, sig) in valid)
        }
    }

    // ---- renderParams: one source of truth for the drive ------------------

    @Test fun every_motion_renders_something_drivable() {
        for (m in M.values()) {
            val p = MotionState.renderParams(m, 0.5f, 0.5f)
            assertTrue("$m shape", p.shape.isNotBlank())
            assertTrue("$m radius", p.radius > 0f)
            assertTrue("$m speed", p.speed > 0f)
            assertTrue("$m bright", p.bright > 0f && p.bright <= 1f)
            assertTrue("$m theme", p.theme.isNotBlank())
        }
    }

    @Test fun speaking_is_locked_to_the_real_amplitude() {
        // The whole point of the amp lock: silence and a loud syllable must NOT render
        // the same. Every drive value moves with the level, monotonically.
        val quiet = MotionState.renderParams(M.SPEAKING, 0f, 0f)
        val mid = MotionState.renderParams(M.SPEAKING, 0f, 0.5f)
        val loud = MotionState.renderParams(M.SPEAKING, 0f, 1f)
        assertTrue(quiet.radius < mid.radius && mid.radius < loud.radius)
        assertTrue(quiet.speed < mid.speed && mid.speed < loud.speed)
        assertTrue(quiet.bright < mid.bright && mid.bright < loud.bright)
        assertNotEquals(quiet, loud)
    }

    @Test fun amp_and_workload_are_clamped() {
        assertEquals(MotionState.renderParams(M.SPEAKING, 0f, 1f),
            MotionState.renderParams(M.SPEAKING, 0f, 9f))
        assertEquals(MotionState.renderParams(M.THINKING, 0f, 0f),
            MotionState.renderParams(M.THINKING, -3f, 0f))
    }

    @Test fun thinking_gathers_inward_and_ramps_with_the_work() {
        val idle = MotionState.renderParams(M.THINKING, 0f, 0f)
        val busy = MotionState.renderParams(M.THINKING, 1f, 0f)
        assertTrue("gathering draws inward", idle.orbit < 0f)
        assertTrue("effort ramps the motion", busy.speed > idle.speed)
        assertTrue("effort brightens", busy.bright > idle.bright)
    }

    @Test fun waiting_and_drifting_are_the_unhurried_motions() {
        val wait = MotionState.renderParams(M.STALL, 0.5f, 0f)
        val drift = MotionState.renderParams(M.DRIFT, 0f, 0f)
        val think = MotionState.renderParams(M.THINKING, 0.5f, 0f)
        val recoil = MotionState.renderParams(M.RECOIL, 0f, 0f)
        // A stall must read as patience, not as work — and never as nothing at all.
        assertTrue(wait.speed < think.speed)
        assertTrue(drift.speed < think.speed)
        assertTrue("never frozen", wait.speed > 0f && drift.speed > 0f)
        // The flinch is the fastest, brightest thing the being does.
        assertTrue(recoil.speed > think.speed)
        assertTrue(recoil.orbit > 0f)   // outward
    }

    @Test fun each_motion_has_its_own_shape_or_its_own_theme() {
        // TOOL/TOOL_RESULT deliberately share the existing "thinking" motif geometry;
        // they are still told apart by their theme. Nothing else may collide.
        val seen = HashMap<Pair<String, String>, MotionState.Motion>()
        for (m in M.values()) {
            val p = MotionState.renderParams(m, 0.5f, 0.5f)
            val key = p.shape to p.theme
            assertEquals("$m collides with ${seen[key]}", null, seen.put(key, m))
        }
    }

    @Test fun the_stall_threshold_shows_before_the_log_calls_it_a_problem() {
        // Above the normal inter-token cadence, under the controller's own 5s
        // stream-stall log threshold.
        assertTrue(MotionState.STALL_MS in 1000..4999)
    }
}
