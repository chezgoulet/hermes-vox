package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErDrift — the context-drift sync (Phase 7, reshaped by A2/F4). Under test: the sync rides the
 * request's ephemeral `instructions` (never the caller's message), is EMPTY when there is
 * nothing to sync, never carries facts the mind could adopt as its own answers, and instructs
 * non-repetition.
 */
class ErDriftTest {

    @Test fun empty_when_nothing_to_sync() {
        assertEquals("", ErDrift.sync(emptyList(), ErDrift.Vibe()))
    }

    @Test fun the_sync_carries_the_soul_log() {
        val actions = listOf(
            ErDrift.SoulAction(1L, "ack", "Let me think —"),
            ErDrift.SoulAction(2L, "lag", "my mind's a bit slow right now, give me a sec"))
        val e = ErDrift.sync(actions, ErDrift.Vibe())
        assertTrue(e.contains("already said to the caller"))
        assertTrue(e.contains("ack=\"Let me think —\""))
        assertTrue(e.contains("lag=\"my mind's a bit slow"))
        assertTrue(e.lowercase().contains("do not repeat or contradict"))
    }

    @Test fun the_sync_carries_the_vibe_when_off_neutral() {
        val e = ErDrift.sync(emptyList(), ErDrift.Vibe(mood = "frustrated", energy = "low"))
        assertTrue(e.contains("mood=frustrated"))
        assertTrue(e.contains("energy=low"))
    }

    @Test fun no_soul_actions_no_vibe_no_sync_noise() {
        // Actions present but vibe neutral -> log only. Vibe off-neutral but no
        // actions -> vibe only. Both empty -> empty (the turn's instructions are then
        // the voice register alone).
        val logOnly = ErDrift.sync(listOf(ErDrift.SoulAction(1L, "ack", "Let me think —")), ErDrift.Vibe())
        assertTrue(logOnly.contains("already said to the caller"))
        assertTrue(!logOnly.contains("mood="))
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

    @Test fun the_sync_is_system_prompt_prose_not_an_envelope() {
        // A2: this is a paragraph of instructions now, not a bracketed blob in the user's
        // message — no bracket envelope, no protocol tag, no "user vibe:" shorthand.
        val actions = listOf(ErDrift.SoulAction(1L, "ack", "Let me think —"))
        val e = ErDrift.sync(actions, ErDrift.Vibe(mood = "warm"))
        assertTrue(e.startsWith("The phone's voice"))
        assertTrue(e.endsWith("."))
        assertTrue(!e.contains("soul-sync:"))
        assertTrue(!e.startsWith(" ["))
        assertEquals(0, Regex("\n").findAll(e).count())
    }
}
