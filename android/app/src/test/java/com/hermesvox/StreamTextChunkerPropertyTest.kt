package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The property that makes the boundary bug impossible to reintroduce: the chunking
 * of a reply is a function of its TEXT, not of where the network deltas happened to
 * fall.
 *
 * Pierre's #515 chunker passed its own tests because they fed whole strings. In
 * production the chunker sees prefixes of a live stream, so a cut after `.` inside
 * "71.5", "Dr." or an email was decided by the arrival boundary rather than by the
 * text — replayed, 78–99% of token-shaped streams split inside a token, 0% when the
 * text arrived whole.
 *
 * Every text below is chunked twice: once as one string, once fed as 2,000 random
 * delta patterns (1, 2, 4 and 8 characters). The two results must be identical.
 */
class StreamTextChunkerPropertyTest {

    private val texts = listOf(
        "Bonjour. Comment vas-tu aujourd'hui ? J'ai vérifié le serveur NUC1 : la charge " +
            "CPU est de 71.5%, la mémoire va bien.",
        "Le Dr. Chen a envoyé un courriel à pierre.cosson@gmail.com avant 9h30. " +
            "Peux-tu le rappeler, et confirmer la version 1.2.3 ?",
        "C'est exact — la version 1.2.3 est stable ; il reste deux points à voir, dont " +
            "le déploiement et les tests.",
        "Oui.",
        "Un texte sans aucune ponctuation qui continue longtemps et qui doit tout de " +
            "même être découpé par le plafond de trente caractères.",
        "M. et Mme Dupont, 1,000 euros, 3.14 %, e.g. this example; next: done!",
        "a".repeat(60) + " fin",
        "— ! ... Bonjour tout le monde",
        "   ",
        "",
    )

    @Test
    fun chunking_does_not_depend_on_where_the_deltas_fall() {
        for (text in texts) {
            val whole = StreamTextChunker.chunkAll(text)
            val rnd = Random(0x515)
            var patterns = 0
            for (maxPart in listOf(1, 2, 4, 8)) {
                repeat(500) {
                    assertEquals(
                        "text=<$text> maxPart=$maxPart",
                        whole,
                        chunkIncremental(text, rnd, maxPart),
                    )
                    patterns++
                }
            }
            assertEquals("every random cut pattern ran for <$text>", 2000, patterns)
        }
    }

    @Test
    fun chunks_are_speakable_and_bounded() {
        for (text in texts) {
            for (chunk in StreamTextChunker.chunkAll(text)) {
                assertTrue(
                    "punctuation-only chunk <$chunk> from <$text>",
                    chunk.any { it.isLetterOrDigit() },
                )
                if (chunk.length > StreamTextChunker.CHUNK_CAP_CHARS) {
                    assertFalse(
                        "over the cap with a boundary inside it: <$chunk>",
                        chunk.any(Char::isWhitespace),
                    )
                }
            }
        }
    }

    @Test
    fun chunks_rebuild_the_text_so_no_token_was_split() {
        for (text in texts) {
            val want = text.trim().replace(Regex("\\s+"), " ")
            if (want.isEmpty()) continue
            assertEquals("<$text>", want, StreamTextChunker.chunkAll(text).joinToString(" "))
        }
    }

    @Test
    fun no_boundary_falls_inside_an_abbreviation_a_number_or_an_email() {
        val text = "Le Dr. Chen a écrit à pierre.cosson@gmail.com avant 9h30, " +
            "avec 71.5% de réussite et 1,000 essais."
        val chunks = StreamTextChunker.chunkAll(text)
        assertTrue("Dr. Chen split: $chunks", chunks.any { it.contains("Dr. Chen") })
        assertTrue("email split: $chunks", chunks.any { it.contains("pierre.cosson@gmail.com") })
        assertTrue("decimal split: $chunks", chunks.any { it.contains("71.5%") })
        assertTrue("thousands split: $chunks", chunks.any { it.contains("1,000") })
        assertFalse("boundary right after an abbreviation: $chunks", chunks.any { it.trimEnd().endsWith("Dr.") })
    }

    /** Feed [text] in random-sized deltas through the production drain. */
    private fun chunkIncremental(text: String, rnd: Random, maxPart: Int): List<String> {
        val acc = StringBuilder()
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val end = minOf(text.length, i + 1 + rnd.nextInt(maxPart))
            acc.append(text, i, end)
            StreamTextChunker.drain(acc) { out += it }
            i = end
        }
        out += StreamTextChunker.flush(acc)
        return out
    }
}
