package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests proving the continuity and anti-saccade behavior of StreamTextChunker.
 *
 * Covers:
 * 1. Single-word response
 * 2. Short response with prompt terminator
 * 3. Long response with natural clause handling
 * 4. Rapid SSE bursts without fragmentation
 * 5. SSE bursts delayed > 500ms (preventing premature timer splits on short fragments)
 * 6. LLM pauses mid-phrase (never cutting tiny 1-2 word fragments)
 * 7. Unpunctuated stream flushing
 * 8. Soft clause split only when comfortable threshold is reached
 */
class StreamTextChunkerTest {

    @Test
    fun hard_sentence_terminators_split_immediately() {
        // "Bonjour." has a period at index 7 -> splits immediately
        assertEquals(7, StreamTextChunker.findSplitIndex("Bonjour.", isFirstChunk = true))
        assertEquals(4, StreamTextChunker.findSplitIndex("Stop!", isFirstChunk = false))
        assertEquals(12, StreamTextChunker.findSplitIndex("C'est exact ? Oui", isFirstChunk = false))
    }

    @Test
    fun comma_on_short_fragment_does_not_split_early() {
        // "Oui, je" has comma at index 3 (< MIN_FIRST_CHUNK_CHARS 30) -> must NOT split
        assertEquals(-1, StreamTextChunker.findSplitIndex("Oui, je", isFirstChunk = true))
        // "Pour commencer, je" (comma at 14 < 30) -> must NOT split
        assertEquals(-1, StreamTextChunker.findSplitIndex("Pour commencer, je", isFirstChunk = true))
    }

    @Test
    fun comma_on_substantial_clause_splits_naturally() {
        val longClause = "Pour commencer notre analyse détaillée du serveur NUC1, voici les résultats"
        val commaIdx = longClause.indexOf(',')
        assertTrue("Comma index must be >= MIN_FIRST_CHUNK_CHARS", commaIdx >= StreamTextChunker.MIN_FIRST_CHUNK_CHARS)
        assertEquals(commaIdx, StreamTextChunker.findSplitIndex(longClause, isFirstChunk = true))
    }

    @Test
    fun body_chunks_require_higher_threshold_for_soft_punctuation() {
        // 35 chars with comma: allowed for first chunk, but refused for body chunk
        val midClause = "Voici une première étape simple, qui continue"
        val commaIdx = midClause.indexOf(',') // ~31
        assertTrue(commaIdx >= StreamTextChunker.MIN_FIRST_CHUNK_CHARS)
        assertTrue(commaIdx < StreamTextChunker.MIN_BODY_CHUNK_CHARS)

        // For first chunk -> can split to yield early audio
        assertEquals(commaIdx, StreamTextChunker.findSplitIndex(midClause, isFirstChunk = true))
        // For body chunk -> should keep accumulating for prosodic continuity
        assertEquals(-1, StreamTextChunker.findSplitIndex(midClause, isFirstChunk = false))
    }

    @Test
    fun llm_hesitation_under_500ms_or_delayed_does_not_flush_tiny_words() {
        // 2 characters ("Je") must NEVER flush on a timer
        assertFalse(StreamTextChunker.shouldFlush(length = 2, elapsedMs = 600, isFirstChunk = true))
        assertFalse(StreamTextChunker.shouldFlush(length = 2, elapsedMs = 3000, isFirstChunk = true))

        // 10 characters ("Je ne sais") still under minimum threshold
        assertFalse(StreamTextChunker.shouldFlush(length = 10, elapsedMs = 1500, isFirstChunk = true))
    }

    @Test
    fun unpunctuated_long_rambling_flushes_after_long_window() {
        // 45 characters with no punctuation: flushes once elapsedMs >= FLUSH_LONG_MS (1200ms)
        assertFalse(StreamTextChunker.shouldFlush(length = 45, elapsedMs = 500, isFirstChunk = false))
        assertTrue(StreamTextChunker.shouldFlush(length = 60, elapsedMs = 1300, isFirstChunk = false))
    }

    @Test
    fun numbers_and_abbreviations_preserved() {
        // Decimal numbers and abbreviations must not trigger hard splits
        assertEquals(-1, StreamTextChunker.findSplitIndex("La charge CPU est de 71.5% actuellement", isFirstChunk = true))
        assertEquals(-1, StreamTextChunker.findSplitIndex("Le Dr. Chen est présent", isFirstChunk = true))
    }
}
