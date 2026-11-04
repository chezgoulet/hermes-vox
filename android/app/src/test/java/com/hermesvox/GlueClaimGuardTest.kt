package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M2 (0.9.1 review): D29 keeps exactly one rail on what Presence says — the
 * false-action-claim guard — and it must hold on every lane, not two of five.
 *
 * `speakGlue` is Android-bound, so the placement is pinned at source level (the guard runs
 * before the repeat guard and the arbiter, and every lane reaches the voice through
 * `speakGlue`); the guard itself is the pure [ErSoulTurn.claimsResult], exercised here with
 * the false claim each lane is likeliest to make, and a line each lane legitimately says.
 */
class GlueClaimGuardTest {

    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
    private val main = File("src/main/java/com/hermesvox/MainActivity.kt").readText()
    private val presence = File("src/main/java/com/hermesvox/ErPresence.kt").readText()

    private fun refused(line: String) = assertTrue(line, ErSoulTurn.claimsResult(line))
    private fun passes(line: String) = assertFalse(line, ErSoulTurn.claimsResult(line))

    // ---- the guard, per source ----------------------------------------------

    @Test fun `tool-narration`() {
        refused("I've deleted that for you.")
        refused("I have sent the email.")
        passes("Let me look through your files.")
    }

    @Test fun `soul-answer`() {
        refused("Done — I've set up the reminder.")
        refused("You're all set.")
        passes("I'm doing well, thanks for asking.")
    }

    @Test fun `soul-greeting`() {
        refused("Hi! I've already booked your table.")
        passes("Hey, good to hear you.")
    }

    @Test fun `soul-beat`() {
        refused("It's done — I've cancelled it.")
        passes("Hmm, let me think about that.")
    }

    @Test fun `soul-working`() {
        refused("Your meeting is booked.")
        passes("Still working on it.")
    }

    @Test fun `presence fillers`() {
        passes("okay — take the time you need")
        passes("still working on it — the connection's a little slow")
    }

    // ---- the placement --------------------------------------------------------

    private val speakGlue = vc.substringAfter("fun speakGlue(text: String").substringBefore("\n    fun ")

    @Test fun `speakGlue refuses a claim before the repeat guard and the arbiter`() {
        val guard = speakGlue.indexOf("ErSoulTurn.claimsResult(text)")
        assertTrue("speakGlue must apply the claim guard", guard > 0)
        assertTrue(speakGlue.indexOf("ErSoulTurn.isRepeat(") > guard)
        assertTrue(speakGlue.indexOf("ErArbiter.arbitrate(") > guard)
        val reject = speakGlue.substring(guard, speakGlue.indexOf("return", guard))
        assertTrue(reject, reject.contains("event=er-glue-reject reason=result-unbacked source=\$source"))
    }

    @Test fun `the soul's answer is not recorded as spoken when the guard would refuse it`() {
        val deliver = vc.substringAfter("private fun canDeliverSoulLine(text: String): Boolean {").substringBefore("\n    }")
        assertTrue(deliver, deliver.contains("ErSoulTurn.claimsResult(text)"))
    }

    @Test fun `every speaking lane reaches the voice through speakGlue`() {
        for (src in listOf("soul-greeting", "Transcript.SOURCE_SOUL_ANSWER", "SOURCE_WORKING")) {
            assertTrue(src, Regex("speakGlue\\([^\\n]*$src").containsMatchIn(vc))
        }
        assertTrue(main.contains("speakGlue(said, source = \"tool-narration\")"))
        assertTrue(presence.contains("speakGlue(\"okay — take the time you need\", \"presence-hold\")"))
    }

    // ---- D29: the prompt no longer bans numbers --------------------------------

    @Test fun `the soul prompt carries no number rule`() {
        val prompt = VoxSoul.soulPrompt("")
        assertFalse(prompt, prompt.contains("number", ignoreCase = true))
        assertTrue(prompt.contains("never claim actions"))
    }
}
