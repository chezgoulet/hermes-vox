package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B2's wiring, pinned at source level the way [GlueClaimGuardTest] pins the glue lanes: the parts
 * that matter are Android-bound (the capture loop's latch, the TTS completion), so what can be
 * checked off-device is the ORDER and the ownership.
 *
 * Three properties, each of which was a real way to get this wrong:
 *  1. the grammar is consulted BEFORE the turn is submitted, so no network round trip happens;
 *  2. only a voice turn (never a typed or pasted one) can be a local action;
 *  3. the local turn owns the gate it will release — armed on the way in, released when the one
 *     confirmation line ends. Without the arm, `release()` is a duplicate no-op and the capture
 *     loop waits out its ceiling with the mic shut.
 */
class LocalActionWiringTest {

    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()

    @Test fun the_grammar_is_checked_before_the_mind_is_asked() {
        val parse = vc.indexOf("LocalActionGrammar.parse(text)")
        val submit = vc.indexOf("session.voiceTurn(")
        assertTrue("the grammar must be consulted", parse > 0)
        assertTrue("the action must be taken before the submit", submit > 0 && parse < submit)
        assertTrue("and it must end the turn", vc.contains("takeLocalAction(text, local, gen); return"))
    }

    @Test fun only_a_voice_turn_can_be_a_local_action() {
        assertTrue(vc.contains("if (fromVoice && !pasted) {"))
    }

    @Test fun the_local_turn_arms_the_gate_it_releases() {
        val body = vc.substringAfter("private fun takeLocalAction(")
            .substringBefore("\n    /**")
        assertTrue("the turn gate must be armed", body.contains("voiceState.arm()"))
        assertTrue("the confirmation releases it", body.contains("releaseTurnGate(gen, \"local-action\")"))
        assertTrue("and the in-flight flag is cleared before the line plays", body.contains("turnInFlight = false"))
        assertTrue("the receipt names the path", body.contains("ErHandoff.REASON_LOCAL_ACTION"))
    }

    @Test fun the_runner_is_lazy_so_a_probe_never_touches_the_alarm_services() {
        assertTrue(vc.contains("private val localActions: LocalActions by lazy"))
    }

    /**
     * M5: Android restricts clipboard **reads**, not writes. `clip` must ask the platform, never
     * [AppFocus]; only `clip_read` may read the focus counter, and only to explain a missing clip.
     * The pure decisions live in ClipboardRules (and are proven there); this pins the wiring so a
     * re-added pre-emptive gate fails the build.
     */
    @Test fun the_clipboard_write_is_not_gated_on_focus() {
        val la = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        val write = la.substringAfter("override fun clip(text: String)")
            .substringBefore("// ---- handoffs")
        assertTrue("the write goes through the pure rule", write.contains("ClipboardRules.write"))
        assertTrue("and reaches the platform's own call", write.contains("setPrimaryClip"))
        assertFalse("with no pre-emptive focus gate", write.contains("AppFocus"))

        val read = la.substringAfter("override fun readClip()")
            .substringBefore("private data class BatteryReadings")
        assertTrue("the read is asked for", read.contains("ClipboardRules.read"))
        assertTrue("with focus only shaping the answer", read.contains("AppFocus.inFront"))
    }
}
