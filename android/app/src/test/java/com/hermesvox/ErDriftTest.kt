package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErDrift — the context-drift sync (Phase 7). Under test: the epilogue rides
 * the user text (cache-safe, gateway-agnostic), is EMPTY when there is
 * nothing to sync (non-ER turns byte-identical), never carries facts the
 * mind could adopt as its own answers, and instructs non-repetition.
 */
class ErDriftTest {

    @Test fun empty_when_nothing_to_sync() {
        assertEquals("", ErDrift.epilogue(emptyList(), ErDrift.Vibe()))
    }

    @Test fun epilogue_carries_the_soul_log() {
        val actions = listOf(
            ErDrift.SoulAction(1L, "ack", "Let me think —"),
            ErDrift.SoulAction(2L, "lag", "my mind's a bit slow right now, give me a sec"))
        val e = ErDrift.epilogue(actions, ErDrift.Vibe())
        assertTrue(e.contains("soul-sync:"))
        assertTrue(e.contains("ack=\"Let me think —\""))
        assertTrue(e.contains("lag=\"my mind's a bit slow"))
        assertTrue(e.contains("do not repeat or contradict"))
    }

    @Test fun epilogue_carries_the_vibe_when_off_neutral() {
        val e = ErDrift.epilogue(emptyList(), ErDrift.Vibe(mood = "frustrated", energy = "low"))
        assertTrue(e.contains("mood=frustrated"))
        assertTrue(e.contains("energy=low"))
    }

    @Test fun no_soul_actions_no_vibe_no_epilogue_noise() {
        // Actions present but vibe neutral -> log only. Vibe off-neutral but no
        // actions -> vibe only. Both empty -> empty (byte-identical turn).
        val logOnly = ErDrift.epilogue(listOf(ErDrift.SoulAction(1L, "ack", "Let me think —")), ErDrift.Vibe())
        assertTrue(logOnly.contains("soul already said"))
        assertTrue(!logOnly.contains("user vibe"))
    }

    @Test fun vibe_updates_from_classifier_routes() {
        // 0.8/M3c: the SOUL_DIRECT route is gone with the routing, so its "warm/high" branch
        // went with it. Two routes remain — the mind's lane marks the ack, the patient lane
        // changes nothing.
        val v = ErDrift.updateVibe(ErDrift.Vibe(), ErIntent.Route.ACK_AND_YIELD)
        assertEquals("let me think", v.lastAck)
        assertEquals(v, ErDrift.updateVibe(v, ErIntent.Route.HOLD_ONLY))   // patient: no change
    }

    @Test fun log_is_capped() {
        val many = (1..30).map { ErDrift.SoulAction(it.toLong(), "ack", "line $it") }
        assertEquals(12, ErDrift.cap(many).size)
        assertEquals("line 30", ErDrift.cap(many).last().text)
    }

    @Test fun epilogue_is_single_line_and_bracketed() {
        val actions = listOf(ErDrift.SoulAction(1L, "ack", "Let me think —"))
        val e = ErDrift.epilogue(actions, ErDrift.Vibe(mood = "warm"))
        assertTrue(e.startsWith(" [soul-sync:"))
        assertTrue(e.endsWith("]"))
        assertEquals(0, Regex("\n").findAll(e).count())
    }
}
