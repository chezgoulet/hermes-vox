package com.hermesvox

import com.hermesvox.core.ApprovalVoice
import com.hermesvox.core.ApprovalNudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated tests for native French localization, language resolution,
 * and multi-language spoken prompt behaviors across Hermes Vox.
 */
class LanguageResolutionTest {

    @Test
    fun turn_repair_resolves_correctly_per_language() {
        // English (default)
        assertEquals(TurnRepair.LINE, TurnRepair.line(TurnRepair.REASON_EMPTY, workStarted = false, lang = "en"))
        assertEquals(TurnRepair.LINE_GAVE_UP, TurnRepair.line(TurnRepair.REASON_TIMEOUT, workStarted = false, lang = "en"))
        assertEquals(TurnRepair.LINE_UNCERTAIN, TurnRepair.line(TurnRepair.REASON_EMPTY, workStarted = true, lang = "en"))

        // French
        val frEmpty = TurnRepair.line(TurnRepair.REASON_EMPTY, workStarted = false, lang = "fr")
        assertTrue("French empty repair contains Pardon: $frEmpty", frEmpty.contains("Pardon"))
        assertTrue("French empty repair offers retry: $frEmpty", frEmpty.contains("réessaie"))

        val frTimeout = TurnRepair.line(TurnRepair.REASON_TIMEOUT, workStarted = false, lang = "fr")
        assertTrue("French timeout says stopped waiting: $frTimeout", frTimeout.contains("arrêté d'attendre"))

        val frUncertain = TurnRepair.line(TurnRepair.REASON_EMPTY, workStarted = true, lang = "fr")
        assertTrue("French workStarted says outcome unknown: $frUncertain", frUncertain.contains("ne peux donc pas confirmer"))
        assertFalse("French workStarted must not invite retry: $frUncertain", frUncertain.contains("réessaie"))

        // Unsupported language (e.g. "de", "es") falls back cleanly to English default
        assertEquals(TurnRepair.LINE, TurnRepair.line(TurnRepair.REASON_EMPTY, workStarted = false, lang = "de"))
        assertEquals(TurnRepair.LINE_GAVE_UP, TurnRepair.line(TurnRepair.REASON_TIMEOUT, workStarted = false, lang = "es"))
    }

    @Test
    fun wait_prompt_lines_and_cards_resolve_per_language() {
        // English
        assertEquals(WaitPrompt.TITLE, WaitPrompt.title("en"))
        assertEquals(WaitPrompt.QUESTION, WaitPrompt.question("en"))
        assertEquals(WaitPrompt.LINE_KEPT, WaitPrompt.lineKept("en"))
        assertEquals(WaitPrompt.LINE_STOPPED, WaitPrompt.lineStopped("en"))
        assertEquals(WaitPrompt.LINE_NO_ANSWER, WaitPrompt.lineNoAnswer("en"))
        assertEquals(WaitPrompt.LINE_EXTENDED, WaitPrompt.lineExtended("en"))

        // French
        assertEquals(WaitPrompt.TITLE_FR, WaitPrompt.title("fr"))
        assertEquals(WaitPrompt.QUESTION_FR, WaitPrompt.question("fr"))
        assertEquals(WaitPrompt.LINE_KEPT_FR, WaitPrompt.lineKept("fr"))
        assertEquals(WaitPrompt.LINE_STOPPED_FR, WaitPrompt.lineStopped("fr"))
        assertEquals(WaitPrompt.LINE_NO_ANSWER_FR, WaitPrompt.lineNoAnswer("fr"))
        assertEquals(WaitPrompt.LINE_EXTENDED_FR, WaitPrompt.lineExtended("fr"))

        // Card labels
        val enCard = WaitPromptCard(lang = "en")
        assertEquals("KEEP WAITING", enCard.label(WaitPrompt.Answer.KEEP))
        assertEquals("STOP", enCard.label(WaitPrompt.Answer.STOP))

        val frCard = WaitPromptCard(
            title = WaitPrompt.title("fr"),
            question = WaitPrompt.question("fr"),
            lang = "fr",
        )
        assertEquals("CONTINUER", frCard.label(WaitPrompt.Answer.KEEP))
        assertEquals("ARRÊTER", frCard.label(WaitPrompt.Answer.STOP))
        assertEquals(WaitPrompt.TITLE_FR, frCard.title)
        assertEquals(WaitPrompt.QUESTION_FR, frCard.question)

        // Unsupported language falls back to English
        assertEquals(WaitPrompt.TITLE, WaitPrompt.title("it"))
        assertEquals(WaitPrompt.QUESTION, WaitPrompt.question("ja"))
    }

    @Test
    fun wait_prompt_speech_recognizes_french_and_english_utterances() {
        // English Keep
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("keep waiting"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("yes please"))

        // French Keep
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("continuer"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("continue"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("attends"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("on attend"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("oui continue"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("d'accord"))

        // English Stop
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("stop"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("no thanks"))

        // French Stop
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("non"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("arrete"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("arrête"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("annule"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("non merci"))

        // French hedges are NOT answers (return null -> treated as new turn)
        assertNull(WaitPrompt.resolve("peut-être"))
        assertNull(WaitPrompt.resolve("je ne sais pas"))
        assertNull(WaitPrompt.resolve("pas sûr"))

        // French questions are NOT answers
        assertNull(WaitPrompt.resolve("pourquoi c'est si long ?"))
        assertNull(WaitPrompt.resolve("qu'est-ce que tu fais ?"))
    }

    @Test
    fun approval_voice_recognizes_french_decisions() {
        val choices = listOf("once", "always")

        // French approvals
        assertEquals("once", ApprovalVoice.resolve("oui", choices))
        assertEquals("once", ApprovalVoice.resolve("approuve", choices))
        assertEquals("once", ApprovalVoice.resolve("vas-y", choices))
        assertEquals("once", ApprovalVoice.resolve("fais-le", choices))
        assertEquals("once", ApprovalVoice.resolve("d'accord", choices))

        // French denials
        assertEquals("deny", ApprovalVoice.resolve("non", choices))
        assertEquals("deny", ApprovalVoice.resolve("refuse", choices))
        assertEquals("deny", ApprovalVoice.resolve("annule", choices))
        assertEquals("deny", ApprovalVoice.resolve("arrête", choices))

        // French negations block approvals
        assertNull(ApprovalVoice.resolve("ne fais pas ça", choices))
        assertNull(ApprovalVoice.resolve("non pas maintenant", choices))
    }

    @Test
    fun approval_voice_normalization_strips_french_accents() {
        assertEquals("arrete", ApprovalVoice.normalize("Arrête"))
        assertEquals("d'accord", ApprovalVoice.normalize("D’accord"))
        assertEquals("s'il te plait", ApprovalVoice.normalize("S'il te plaît"))
        assertEquals("deja fait", ApprovalVoice.normalize("Déjà fait"))
    }

    @Test
    fun approval_nudge_line_is_localized() {
        assertEquals("say approve or no", ApprovalNudge.line("en"))
        assertEquals("dites approuver ou non", ApprovalNudge.line("fr"))
        assertEquals("say approve or no", ApprovalNudge.line("es"))
    }

    @Test
    fun turn_wait_check_in_resolves_per_language() {
        // English (default)
        assertEquals(TurnWait.LINE_FIRST, TurnWait.checkIn(15_000, 0, replyStarted = false, presenceSpeaks = false, approvalWaiting = false, callerQuietMs = 2_000, lang = "en"))
        assertEquals(TurnWait.LINE_SECOND, TurnWait.checkIn(40_000, 1, replyStarted = false, presenceSpeaks = false, approvalWaiting = false, callerQuietMs = 2_000, lang = "en"))

        // French
        assertEquals(TurnWait.LINE_FIRST_FR, TurnWait.checkIn(15_000, 0, replyStarted = false, presenceSpeaks = false, approvalWaiting = false, callerQuietMs = 2_000, lang = "fr"))
        assertEquals(TurnWait.LINE_SECOND_FR, TurnWait.checkIn(40_000, 1, replyStarted = false, presenceSpeaks = false, approvalWaiting = false, callerQuietMs = 2_000, lang = "fr"))

        // Fallback to English for other languages
        assertEquals(TurnWait.LINE_FIRST, TurnWait.checkIn(15_000, 0, replyStarted = false, presenceSpeaks = false, approvalWaiting = false, callerQuietMs = 2_000, lang = "de"))
    }

    @Test
    fun er_presence_auto_line_resolves_per_language() {
        assertEquals(ErPresence.HOLD_LINE, ErPresence.autoLine(ErIntent.Route.HOLD_ONLY, atTurnStart = false, lang = "en"))
        assertEquals(ErPresence.HOLD_LINE_FR, ErPresence.autoLine(ErIntent.Route.HOLD_ONLY, atTurnStart = false, lang = "fr"))
        assertEquals(ErPresence.HOLD_LINE, ErPresence.autoLine(ErIntent.Route.HOLD_ONLY, atTurnStart = false, lang = "it"))
    }

    @Test
    fun sherpa_voices_declare_accurate_acoustic_languages() {
        assertEquals("en", SherpaVoice.PIPER.language)
        assertEquals("en", SherpaVoice.SUPERTONIC.language)
        assertEquals("fr", SherpaVoice.PIPER_SIWIS.language)
        assertEquals("fr", SherpaVoice.PIPER_TOM.language)
        assertEquals("fr", SherpaVoice.PIPER_UPMC.language)
    }

    @Test
    fun er_fillers_lag_line_resolves_per_language() {
        val now = 10_000L + ErFillers.LAG_AFTER_MS
        val t0 = 10_000L

        val outEn = ErFillers.tick(now, t0, recentCount = 0, userGoneMs = 0L, lang = "en")
        assertEquals(ErFillers.State.LAG_ACK, outEn.state)
        assertEquals("my mind's a bit slow right now, give me a sec", outEn.speak)

        val outFr = ErFillers.tick(now, t0, recentCount = 0, userGoneMs = 0L, lang = "fr")
        assertEquals(ErFillers.State.LAG_ACK, outFr.state)
        assertEquals("mon esprit est un peu lent en ce moment, donnez-moi une seconde", outFr.speak)

        val outDe = ErFillers.tick(now, t0, recentCount = 0, userGoneMs = 0L, lang = "de")
        assertEquals(ErFillers.State.LAG_ACK, outDe.state)
        assertEquals("my mind's a bit slow right now, give me a sec", outDe.speak)
    }

    @Test
    fun dial_handoff_line_resolves_per_language() {
        assertEquals("dialling — ending our call", DialHandoff.line("en"))
        assertEquals("numérotation en cours — fin de notre appel", DialHandoff.line("fr"))
        assertEquals("dialling — ending our call", DialHandoff.line("es"))
    }

    @Test
    fun phone_approval_card_line_resolves_per_language() {
        val card = PhoneApproval.Card(
            id = "test-1",
            action = PhoneApproval.ACTION,
            subject = "org.mozilla.firefox",
            label = "Firefox",
            detail = "",
            canonical = "uninstall\npackage=org.mozilla.firefox",
            digest = "test-digest",
            raisedAtMs = 1000L,
            expiresAtMs = 120_000L,
            phrase = "remove Firefox",
        )
        assertEquals("Your agent wants to remove Firefox. Approve?", card.line("en"))
        assertEquals("Votre agent souhaite remove Firefox. Approuver ?", card.line("fr"))
        assertEquals("Your agent wants to remove Firefox. Approve?", card.line)
    }
}
