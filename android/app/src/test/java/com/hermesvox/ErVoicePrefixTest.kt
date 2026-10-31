package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ER Phase 1 — the voice-mode signal (the "the agent knows it's on a call"
 * mechanism). The prefix lives in core.ResponsesClient.USER_TURN_PREFIX and rides each
 * voice turn's user text; these tests pin the CONTRACT the app relies on:
 *
 *  - the prefix is call-register: concise + spoken + no markdown, and carries
 *    the anti-tool-spiral clause (the field multi-turn bug fix);
 *  - it is a per-turn client-side prefix, NOT a system-prompt or past-history
 *    mutation — per-conversation prompt caching stays intact (KEEP-list);
 *  - it is gateway-agnostic: no `voice:` request field, no gateway patch — it
 *    reaches the model on any stock gateway (BYOG universality);
 *  - it applies to VOICE-originated turns while Presence runs only — typed sends
 *    (sendText) and voice turns with the layer off stay prefix-free.
 */
class ErVoicePrefixTest {

    /** What the app sends (core.HermesSession.voiceTurn builds from this). */
    private val expectedCore =
        "[Voice input — the app adds its own spoken acknowledgments like \"let me think\" while you work, so do NOT re-state that you are thinking or working. Greet the caller naturally first if they greet you. Then respond conversationally, 2-4 sentences. " +
            "Speak plainly, no code or markdown. Answer from what you know; do NOT do " +
            "exhaustive tool work or re-audit your own docs/state unless the caller " +
            "explicitly asks.] "

    @Test
    fun `the session sends exactly this prefix`() {
        assertEquals(expectedCore, com.hermesvox.core.ResponsesClient.USER_TURN_PREFIX)
    }

    @Test
    fun `prefix carries the call-register + anti-tool-spiral contract`() {
        // The exact locked wording from docs/BUILD-ER-enhanced-realtime.md Phase 1.
        // Brackets make the instruction self-evidently an envelope, not user speech.
        assertTrue(expectedCore.startsWith("[Voice input"))
        assertTrue(expectedCore.endsWith("] "))
        assertTrue(expectedCore.contains("2-4 sentences"))
        assertTrue(expectedCore.contains("do NOT re-state that you are thinking or working"))
        assertTrue(expectedCore.contains("no code or markdown"))
        assertTrue(expectedCore.contains("do NOT do exhaustive tool work"))
        assertTrue(expectedCore.contains("unless the caller explicitly asks"))
    }

    @Test
    fun `prefix is per-turn user-text only — never a system or history mutation`() {
        // Cache-safety framing: nothing here may look like a system-prompt edit
        // or a rewrite of prior turns. One prefix, once, in front of user text.
        assertTrue(!expectedCore.contains("system"))
        assertEquals(0, Regex("\n").findAll(expectedCore).count()) // single line
    }

    @Test
    fun `prefix mentions no gateway-specific request field`() {
        // BYOG universality: the mechanism must not depend on a voice:true
        // schema field or any gateway patch. It is ordinary user text.
        assertTrue(!expectedCore.contains("voice:"))
        assertTrue(!expectedCore.contains("voice=true"))
    }

    @Test
    fun `the presence contract is one boolean, default on`() {
        // The wiring gate: voiceTurn = fromVoice && presenceActive, and the host derives
        // that from PresenceGate (the user's choice AND the model downloaded AND able to
        // start). If the key or its default drifts, the prefix silently leaks into normal
        // operation or stops applying — pin both.
        assertEquals("presence_enabled", PresenceGate.KEY_PRESENCE_ENABLED)
        assertTrue(PresenceGate.DEFAULT_ENABLED)
        assertTrue(PresenceGate.active(enabled = true, installed = true, canStart = true))
        assertTrue(!PresenceGate.active(enabled = false, installed = true, canStart = true))
    }
}
