package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * K2 rows for the pure log-rotation decision (VoxLog.rotationDecision): the active
 * log ROTATES when its size EXCEEDS the 5MB cap, KEEPs otherwise. Pure JVM — only
 * the decision function is exercised; VoxLog's Android append path is never touched.
 */
class VoxLogRotationTest {

    private val cap = VoxLog.LOG_CAP_BYTES   // 5MB

    @Test fun empty_or_small_logs_keep() {
        assertEquals(LogRotation.KEEP, VoxLog.rotationDecision(0L, cap))
        assertEquals(LogRotation.KEEP, VoxLog.rotationDecision(1024L, cap))
        assertEquals(LogRotation.KEEP, VoxLog.rotationDecision(cap - 1L, cap))
    }

    @Test fun exactly_at_cap_keeps() {
        // "cap the active log at 5MB" — only an EXCEED (> cap) triggers the rotation.
        assertEquals(LogRotation.KEEP, VoxLog.rotationDecision(cap, cap))
    }

    @Test fun over_cap_rotates() {
        assertEquals(LogRotation.ROTATE, VoxLog.rotationDecision(cap + 1L, cap))
        assertEquals(LogRotation.ROTATE, VoxLog.rotationDecision(cap + 4096L, cap))
        assertEquals(LogRotation.ROTATE, VoxLog.rotationDecision(2L * cap, cap))
    }

    @Test fun custom_caps_rotate() {
        // The decision is pure over (sizeBytes, capBytes) — a non-5MB cap behaves the same.
        assertEquals(LogRotation.KEEP, VoxLog.rotationDecision(99L, 100L))
        assertEquals(LogRotation.ROTATE, VoxLog.rotationDecision(100L + 1L, 100L))
    }
}
