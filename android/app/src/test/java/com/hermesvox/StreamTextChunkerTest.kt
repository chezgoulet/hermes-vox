package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary rule of [StreamTextChunker].
 *
 * The rule replaced the `indexOfSentenceEnd` helper (deleted with this change): that
 * helper treated a full stop at the END OF THE BUFFER as a sentence end, so a delta
 * boundary right after `.` split numbers, emails and "Dr." — production buffers are
 * prefixes of a live stream, so that is most of the time. A buffer-end dot now waits
 * for the next delta; the end-of-turn flush speaks a genuine trailing full stop.
 *
 * Kept from Pierre's #515 test: short fragments are not cut early, and a decimal,
 * an email or an abbreviation is never a seam.
 */
class StreamTextChunkerTest {

    @Test
    fun a_comma_is_a_seam_once_the_buffer_holds_enough_text() {
        val long = "Voici la première étape, puis la deuxième"
        assertEquals(long.indexOf(',') + 1, StreamTextChunker.nextChunkEnd(long))
        // Pierre's anti-fragment rule survives: a clause too short to be worth its
        // own synthesis is merged with what follows instead of spoken alone.
        assertEquals(-1, StreamTextChunker.nextChunkEnd("Oui, je"))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("Oui, d'accord"))
    }

    @Test
    fun semicolon_colon_and_dash_are_clause_seams() {
        for (seam in listOf(';', ':', '—')) {
            val s = "Le serveur est prêt $seam on peut commencer"
            assertEquals("seam '$seam'", s.indexOf(seam) + 1, StreamTextChunker.nextChunkEnd(s))
        }
        val hyphen = "Le serveur est prêt - on peut commencer"
        assertEquals(hyphen.indexOf(" - ") + 2, StreamTextChunker.nextChunkEnd(hyphen))
        // A hyphen inside a token is not a seam.
        assertEquals(-1, StreamTextChunker.nextChunkEnd("un serveur bien-known ici"))
    }

    @Test
    fun sentence_terminators_are_seams_but_a_buffer_end_dot_is_not() {
        val bang = "Le serveur est prêt ! On continue"
        assertEquals(bang.indexOf('!') + 1, StreamTextChunker.nextChunkEnd(bang))
        val question = "Le serveur est prêt ? On continue"
        assertEquals(question.indexOf('?') + 1, StreamTextChunker.nextChunkEnd(question))

        // The whole bug: "…prêt." is a buffer prefix, not a finished sentence.
        assertEquals(-1, StreamTextChunker.nextChunkEnd("Le serveur est prêt."))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("Le serveur est prêt. "))

        // The next delta settles it: whitespace + a letter makes it a sentence end.
        val settled = "Le serveur est prêt. On continue"
        assertEquals(settled.indexOf('.') + 1, StreamTextChunker.nextChunkEnd(settled))
    }

    @Test
    fun a_decimal_an_email_and_an_abbreviation_are_never_seams() {
        assertEquals(-1, StreamTextChunker.nextChunkEnd("CPU à 71.5% ici"))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("écris à a.b@c.fr"))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("Le Dr. Chen arrive"))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("Voir M. Dupont demain"))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("1,000 dollars ici"))
        assertEquals(-1, StreamTextChunker.nextChunkEnd("la version 1.2.3 ici"))
    }

    @Test
    fun a_chunk_never_exceeds_the_cap_unless_it_is_one_token() {
        val unpunctuated = "Un texte sans ponctuation qui continue encore et encore"
        val chunks = StreamTextChunker.chunkAll(unpunctuated)
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) {
            if (c.length > StreamTextChunker.CHUNK_CAP_CHARS) {
                assertFalse("over the cap with a break inside: <$c>", c.any(Char::isWhitespace))
            }
        }

        // A token longer than the cap is taken whole — a URL or email is never split.
        val email = "pierre.cosson@a-very-long-domain.example.com"
        assertTrue("fixture must exceed the cap", email.length > StreamTextChunker.CHUNK_CAP_CHARS)
        val withEmail = StreamTextChunker.chunkAll("Écris à $email demain")
        assertTrue("the email must be one chunk: $withEmail", withEmail.any { it == email })
        assertEquals("demain", withEmail.last())
    }

    @Test
    fun a_chunk_without_a_letter_or_digit_is_never_emitted() {
        val chunks = StreamTextChunker.chunkAll("— ! ... Bonjour tout le monde")
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) assertTrue("punctuation-only chunk <$c>", c.any { it.isLetterOrDigit() })
        assertEquals(emptyList<String>(), StreamTextChunker.flush(StringBuilder("— ... !")))
    }

    @Test
    fun the_end_of_turn_flush_speaks_a_trailing_full_stop() {
        assertEquals(listOf("Le serveur est prêt."), StreamTextChunker.chunkAll("Le serveur est prêt."))
        assertEquals(emptyList<String>(), StreamTextChunker.chunkAll("   "))
        assertEquals(emptyList<String>(), StreamTextChunker.chunkAll(""))
    }

    @Test
    fun drain_consumes_what_it_emits_and_leaves_the_tail() {
        val acc = StringBuilder("Voici la première étape, puis la suite")
        val out = ArrayList<String>()
        StreamTextChunker.drain(acc) { out += it }
        assertEquals(listOf("Voici la première étape,"), out)
        assertEquals("puis la suite", acc.toString().trim())
    }
}
