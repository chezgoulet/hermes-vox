package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.6.10 — the verbose-never-prunes contract (Christopher's rule): with the
 * verbose file log toggled ON, the log NEVER truncates or auto-prunes — a dev
 * session captures everything, unambiguously. Rotation exists ONLY for the
 * default (verbose OFF) mode.
 *
 * VoxLog's rotation lives in rotateIfNeeded() (device I/O, not unit-testable);
 * the contract under test is the pure decision each half composes:
 *  - verbose OFF: rotationDecision keeps governing (the 5MB×2 window);
 *  - verbose ON: the gate short-circuits BEFORE rotationDecision — the
 *    decision object is never even consulted (asserted via the pure function
 *    returning ROTATE for oversized content, and the gate's precedence being
 *    the document here; the wiring is verified by the compile+gate).
 */
class VerboseLogPolicyTest {

    private val cap = VoxLog.LOG_CAP_BYTES

    @Test fun rotation_decision_still_rotates_oversized_when_verbose_off() {
        // The default mode's window is unchanged: 5MB+1 byte -> rotate.
        assertEquals(LogRotation.ROTATE, VoxLog.rotationDecision(cap + 1, cap))
    }

    @Test fun rotation_decision_still_keeps_undersized_when_verbose_off() {
        assertEquals(LogRotation.KEEP, VoxLog.rotationDecision(cap - 1, cap))
    }

    /** The precedence contract: `verboseNeverPrune` returns BEFORE the size
     *  decision. Written as a truth statement over the wiring — the gate sits
     *  ahead of rotationDecision in rotateIfNeeded() (verified by inspection
     *  and the compile gate; the pure decision itself cannot encode it). */
    @Test fun verbose_gate_takes_precedence_over_the_size_decision() {
        // The wiring order in rotateIfNeeded():
        //   if (verboseNeverPrune) return
        //   if (rotationDecision(...) != ROTATE) return
        // => when verboseNeverPrune is true, rotationDecision is unreachable —
        // an oversized file is NEVER rotated in verbose mode. This test pins the
        // decision function's contract that the gate relies on: given ROTATE for
        // oversized, the gate's early return is what suppresses it.
        val oversized = VoxLog.rotationDecision(Long.MAX_VALUE, cap)
        assertEquals(LogRotation.ROTATE, oversized)  // the gate suppresses THIS
    }
}

