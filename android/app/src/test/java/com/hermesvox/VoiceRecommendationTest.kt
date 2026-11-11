package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #559: Piper is the recommended voice and the fresh-install default; Supertonic is
 *  experimental but still selectable, and a stored choice is never touched. */
class VoiceRecommendationTest {
    private fun spec(id: String) = ModelCatalog.blessed.first { it.id == id }

    @Test
    fun piper_is_recommended_and_supertonic_is_not() {
        assertTrue(spec("piper-lessac").recommended)
        assertFalse(spec("supertonic").recommended)
        assertTrue(spec("supertonic").desc.contains("experimental"))
        assertTrue(spec("piper-lessac").desc.contains("most consistent in the field"))
    }

    @Test
    fun the_required_set_still_holds_exactly_one_voice() {
        val voices = ModelCatalog.required.filter { it.kind == "tts" }.map { it.id }
        assertEquals(listOf("piper-lessac"), voices)
    }

    @Test
    fun an_unset_pref_prefers_piper_when_both_are_installed() {
        assertEquals("piper", ModelCatalog.defaultTtsOf { it == "piper-lessac" || it == "supertonic" })
    }

    @Test
    fun supertonic_alone_is_still_used_rather_than_the_system_voice() {
        assertEquals("supertonic", ModelCatalog.defaultTtsOf { it == "supertonic" })
    }

    @Test
    fun nothing_installed_is_the_system_voice() {
        assertEquals("system", ModelCatalog.defaultTtsOf { false })
    }

    @Test
    fun a_stored_supertonic_choice_still_resolves_to_supertonic() {
        // The default is consulted only when the pref is unset; a stored token is read as-is.
        assertEquals(SherpaVoice.SUPERTONIC, SherpaVoice.fromEngineToken("supertonic"))
    }
}
