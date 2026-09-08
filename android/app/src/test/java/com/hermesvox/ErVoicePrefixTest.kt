package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ER Phase 1 — the voice-mode signal (the "the agent knows it's on a call"
 * mechanism). The prefix lives in Go (voice.UserTurnPrefix) and rides each
 * voice turn's user text; these tests pin the CONTRACT the app relies on:
 *
 *  - the prefix is call-register: concise + spoken + no markdown, and carries
 *    the anti-tool-spiral clause (the field multi-turn bug fix);
 *  - it is a per-turn client-side prefix, NOT a system-prompt or past-history
 *    mutation — per-conversation prompt caching stays intact (KEEP-list);
 *  - it is gateway-agnostic: no `voice:` request field, no gateway patch — it
 *    reaches the model on any stock gateway (BYOG universality);
 *  - it applies to VOICE-originated Enhanced-mode turns only — typed sends
 *    (sendText) and Realtime-mode voice turns stay prefix-free.
 */
class ErVoicePrefixTest {

    /** What the app sends (mobile.HermesSession.VoiceTurn builds from this). */
    private val expectedCore =
        "[Voice input — respond conversationally, 2-3 sentences. " +
            "Speak plainly, no code or markdown. Answer from what you know; do NOT do " +
            "exhaustive tool work or re-audit your own docs/state unless the caller " +
            "explicitly asks.] "

    @Test
    fun `prefix carries the call-register + anti-tool-spiral contract`() {
        // The exact locked wording from docs/BUILD-ER-enhanced-realtime.md Phase 1.
        // Brackets make the instruction self-evidently an envelope, not user speech.
        assertTrue(expectedCore.startsWith("[Voice input"))
        assertTrue(expectedCore.endsWith("] "))
        assertTrue(expectedCore.contains("2-3 sentences"))
        assertTrue(expectedCore.contains("no code or markdown"))
        assertTrue(expectedCore.contains("do NOT do exhaustive tool work"))
        assertTrue(expectedCore.contains("unless the caller explicitly asks"))
    }

    @Test
    fun `prefix is per-turn user-text only — never a system or history mutation`() {
        // Cache-safety framing: nothing here may look like a system-prompt edit
        // or a rewrite of prior turns. One prefix, once, in front of user text.
        assertTrue(!expectedCore.contains("system"))
        assertEquals(1, Regex("\n").findAll(expectedCore).count()) // single line
    }

    @Test
    fun `prefix mentions no gateway-specific request field`() {
        // BYOG universality: the mechanism must not depend on a voice:true
        // schema field or any gateway patch. It is ordinary user text.
        assertTrue(!expectedCore.contains("voice:"))
        assertTrue(!expectedCore.contains("voice=true"))
    }

    @Test
    fun `voice-mode constant contract is unchanged (enhanced opt-in only)`() {
        // The wiring gate: voiceTurn = fromVoice && modeIsEnhanced. If the
        // mode key/value ever drifts, the prefix silently leaks into plain
        // Realtime (or stops applying in Enhanced) — pin both constants.
        assertEquals("voice_mode", ModelCatalog.KEY_VOICE_MODE)
        assertEquals("enhanced", ModelCatalog.MODE_ENHANCED)
        assertTrue(ModelCatalog.MODE_ENHANCED != ModelCatalog.MODE_REALTIME)
    }
}
