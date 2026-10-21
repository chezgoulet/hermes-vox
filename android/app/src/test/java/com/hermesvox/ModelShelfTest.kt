package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #219: the models list is grouped by the job a model does, in the order a person
 * needs it (Hearing, Voice, Being). These tests pin the shelves, their order, and
 * the promise that an unknown kind is kept rather than hidden.
 */
class ModelShelfTest {

    private fun spec(
        id: String,
        kind: String,
        order: Int,
        recommended: Boolean = false
    ) = ModelSpec(
        id = id,
        name = id,
        kind = kind,
        file = "$id.bin",
        sizeMB = 1.0,
        desc = "catalogue internal description",
        blessed = true,
        order = order,
        sha256 = "00",
        recommended = recommended
    )

    private fun catalogue() = listOf(
        spec("gemma", "express", 5),
        spec("supertonic", "tts", 4, recommended = true),
        spec("piper", "tts", 2),
        spec("whisper", "stt", 3, recommended = true),
        spec("vad", "vad", 1, recommended = true)
    )

    @Test
    fun shelves_come_in_the_order_a_person_needs_them() {
        val groups = ModelShelf.groups(catalogue())
        assertEquals(listOf("Hearing", "Voice", "Being"), groups.map { it.title })
    }

    @Test
    fun hearing_holds_both_recognition_and_the_voice_activity_detector() {
        val hearing = ModelShelf.groups(catalogue()).first { it.key == ModelShelf.HEARING }
        assertEquals(setOf("whisper", "vad"), hearing.specs.map { it.id }.toSet())
    }

    @Test
    fun the_recommended_model_comes_first_inside_a_shelf() {
        val voice = ModelShelf.groups(catalogue()).first { it.key == ModelShelf.VOICE }
        assertEquals(listOf("supertonic", "piper"), voice.specs.map { it.id })
    }

    @Test
    fun an_unknown_kind_gets_its_own_shelf_instead_of_being_hidden() {
        val groups = ModelShelf.groups(catalogue() + spec("mystery", "llm", 9))
        val other = groups.last()
        assertEquals("llm", other.key)
        assertTrue(other.title.contains("llm"))
        assertEquals(listOf("mystery"), other.specs.map { it.id })
    }

    @Test
    fun every_catalogue_entry_appears_exactly_once() {
        val input = catalogue() + spec("mystery", "llm", 9) + spec("mystery-2", "other", 10)
        val shown = ModelShelf.groups(input).flatMap { it.specs }.map { it.id }
        assertEquals(input.map { it.id }.sorted(), shown.sorted())
        assertEquals(input.size, shown.size)
    }

    @Test
    fun the_shipped_catalogue_fits_the_three_named_shelves() {
        val keys = ModelShelf.groups(ModelCatalog.blessed).map { it.key }
        assertEquals(listOf(ModelShelf.HEARING, ModelShelf.VOICE, ModelShelf.BEING), keys)
    }
}
