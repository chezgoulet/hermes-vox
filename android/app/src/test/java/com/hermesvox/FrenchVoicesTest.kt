package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #477 — Pierre's French Piper voices, re-landed against this tree.
 *
 * The failure this file exists to prevent is a label that drifts from the card. Every
 * French voice here was claimed once (Tom as CC0) and the claim was wrong, so the licence
 * text, the speaker count and the model-card URL are pinned against the upstream
 * `MODEL_CARD` files under `rhasspy/piper-voices`, read on 2026-11-06:
 *
 *  - Siwis — CC BY 4.0, one speaker;
 *  - Tom   — AGPLv3, one speaker (NOT CC0);
 *  - UPMC  — CC BY-SA 4.0, two speakers (`num_speakers: 2`).
 *
 * Nothing here touches a device: the catalogue, the pure policies and NOTICE are read
 * as data. The download sizes and the multilingual STT side live in
 * `ModelSizesTest` and `SpokenLanguageTest`.
 */
class FrenchVoicesTest {

    /** What the upstream MODEL_CARD and `.onnx.json` say, transcribed once, checked here. */
    private data class Card(
        val licence: String,
        val cardUrl: String,
        val speakers: Int,
        val dataset: String,
    )

    private val cards = linkedMapOf(
        "piper-siwis" to Card(
            licence = "CC BY 4.0",
            cardUrl = "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/siwis/medium/MODEL_CARD",
            speakers = 1,
            dataset = "https://datashare.is.ed.ac.uk/handle/10283/2353",
        ),
        "piper-tom" to Card(
            // AGPLv3. It was labelled CC0 in the original branch; the card says AGPLv3,
            // and a permissive label on a copyleft voice is the one error this test must
            // never allow back in.
            licence = "AGPLv3",
            cardUrl = "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/tom/medium/MODEL_CARD",
            speakers = 1,
            dataset = "https://git.bksp.space/Tjiho/French-tts-model-piper",
        ),
        "piper-upmc" to Card(
            licence = "CC BY-SA 4.0",
            cardUrl = "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/upmc/medium/MODEL_CARD",
            speakers = 2,
            dataset = "https://github.com/marytts/upmc-pierre-data",
        ),
    )

    private fun spec(id: String) = ModelCatalog.blessed.first { it.id == id }

    // ---- the licence claim -------------------------------------------------------------

    @Test fun each_french_voice_names_the_licence_its_card_states() {
        for ((id, card) in cards) {
            val s = spec(id)
            assertTrue("$id desc must name ${card.licence}: ${s.desc}", s.desc.contains(card.licence))
            assertTrue("$id desc must carry its model card: ${s.desc}", s.desc.contains(card.cardUrl))
            assertEquals("$id cardUrl", card.cardUrl, s.cardUrl)
            assertTrue("$id is a fr-FR Piper artifact", s.url.contains("fr_FR"))
            // The claim is checkable on the phone: the store row shows a card chip, and
            // the download is not gated behind an acceptance dialog (CC/AGPL are not
            // revocable licences the app has to ask about).
            assertEquals("Licence & model card — tap to read", ModelRowState.cardBadge(s.cardUrl))
            assertNull("$id needs no licence acceptance", s.licence)
        }
        assertNull(ModelRowState.cardBadge(""))
    }

    /** The label may not drift into a *weaker* licence, which is what CC0 for Tom was. */
    @Test fun tom_is_not_labelled_cc0_and_the_labels_are_distinct() {
        val tom = spec("piper-tom")
        assertFalse("Tom is AGPLv3; CC0 must not reappear", tom.desc.contains("CC0"))
        assertFalse(tom.desc.contains("CC-BY-NC", ignoreCase = true))
        assertEquals("AGPLv3", cards.getValue("piper-tom").licence)
        // The three labels are three different licences, so a copy-paste between rows
        // cannot pass as "the same claim".
        assertEquals(3, cards.values.map { it.licence }.toSet().size)
        for ((id, card) in cards) {
            for ((other, otherCard) in cards) {
                if (other == id) continue
                assertNotEquals("$id and $other must not claim the same licence",
                    card.licence, otherCard.licence)
            }
        }
    }

    /** Verified by downloading each artifact (2026-11-06) and hashing it: a wrong pin
     *  fails on a user's phone, after the download. */
    @Test fun every_french_voice_is_pinned_with_a_checked_sha256_and_an_https_url() {
        val pins = mapOf(
            "piper-siwis" to "375909aa30842b3a4efa10b1beb1d761af792960ae6873b4d53889f96c66195b",
            "piper-tom" to "3d8258ef8466b5d5f2cc46c06e1415d3d01c9cbaeed8b6d5b3d152073672472c",
            "piper-upmc" to "e9830a331a16f6cc5ef3116a287065e015d3495c3f56b974889a266da7f89a7f",
        )
        assertEquals(cards.keys, pins.keys)
        for ((id, pin) in pins) {
            val s = spec(id)
            assertEquals(id, pin, s.sha256)
            val url = s.url.ifBlank { ModelCatalog.DEFAULT_SOURCE + "/" + s.file }
            assertTrue("$id must come from an https release ($url)", url.startsWith("https://"))
            assertFalse("$id is an opt-in download", s.recommended)
            assertTrue("$id has a plain-language purpose", s.purpose.isNotBlank())
            assertEquals("$id is a tts model", "tts", s.kind)
        }
    }

    // ---- speakers ----------------------------------------------------------------------

    /** UPMC ships two speakers, so the Speaker row has to appear and offer both. The row
     *  is gated on the count (Settings hides it at <= 1), and both ids are reachable. */
    @Test fun upmc_reports_two_speakers_and_its_speaker_row_is_reachable() {
        val upmc = SherpaVoice.fromModelId("piper-upmc")
        assertEquals(SherpaVoice.PIPER_UPMC, upmc)
        assertEquals(2, VoiceSpeakers.knownCount(SherpaVoice.PIPER_UPMC))
        assertEquals(cards.getValue("piper-upmc").speakers, VoiceSpeakers.knownCount(SherpaVoice.PIPER_UPMC))

        val store = object : SpeakerPrefs {
            val map = HashMap<String, Int>()
            override fun getInt(key: String): Int? = map[key]
            override fun putInt(key: String, value: Int) { map[key] = value }
        }
        val ids = (0 until VoiceSpeakers.speakerCount(store, SherpaVoice.PIPER_UPMC)).toList()
        assertEquals(listOf(0, 1), ids)
        for (id in ids) {
            VoiceSpeakers.save(store, SherpaVoice.PIPER_UPMC, id)
            assertEquals("speaker $id survives", id,
                VoiceSpeakers.resolve(store, SherpaVoice.PIPER_UPMC, 2))
        }

        // The screen really shows the row for it: the gate is "more than one speaker",
        // and UPMC's picker offers the two named voices.
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        assertTrue("the Speaker row must hide only at <= 1 speaker",
            settings.contains("VoiceSpeakers.speakerCount(speakerStore(), engine) <= 1"))
        assertTrue("UPMC must route to its own speaker picker",
            settings.contains("SherpaVoice.PIPER_UPMC -> \"Speaker (UPMC · French)\""))
        assertTrue(settings.contains("VoiceSpeakers.UPMC_SPEAKERS"))
    }

    @Test fun single_speaker_french_voices_claim_one_and_their_row_stays_hidden() {
        assertEquals(1, VoiceSpeakers.knownCount(SherpaVoice.PIPER_SIWIS))
        assertEquals(1, VoiceSpeakers.knownCount(SherpaVoice.PIPER_TOM))
        // One voice: the row's own condition (count <= 1) hides it, and both ids clamp to 0.
        assertEquals(0, VoiceSpeakers.resolve(
            object : SpeakerPrefs {
                override fun getInt(key: String): Int? = 9
                override fun putInt(key: String, value: Int) {}
            }, SherpaVoice.PIPER_SIWIS, 1))
    }

    // ---- attribution --------------------------------------------------------------------

    /** Every voice in the catalogue that is not public domain says who made it, under what
     *  licence, and where the card is. */
    @Test fun every_non_public_domain_voice_has_a_notice_entry() {
        val notice = File("../../NOTICE").readText()
        val marker = mapOf(
            "supertonic" to "Supertonic",
            "piper-lessac" to "Piper voices",
            "piper-siwis" to cards.getValue("piper-siwis").cardUrl,
            "piper-tom" to cards.getValue("piper-tom").cardUrl,
            "piper-upmc" to cards.getValue("piper-upmc").cardUrl,
        )
        val voices = ModelCatalog.blessed.filter { it.kind == "tts" }
        assertEquals("a new TTS voice must be added to this table", voices.map { it.id }.toSet(),
            marker.keys)
        for (s in voices) {
            val m = marker.getValue(s.id)
            assertTrue("NOTICE has no entry for ${s.id}", notice.contains(m))
        }
        // The French entries carry the licence, its link, the card and the no-modification
        // line — not just a name.
        for ((id, card) in cards) {
            assertTrue("$id licence in NOTICE", notice.contains(card.licence))
            assertTrue("$id card in NOTICE", notice.contains(card.cardUrl))
            assertTrue("$id dataset in NOTICE", notice.contains(card.dataset))
        }
        assertTrue(notice.contains("does not modify them or redistribute"))
        assertTrue(notice.contains("https://creativecommons.org/licenses/by/4.0/"))
        assertTrue(notice.contains("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertTrue(notice.contains("https://www.gnu.org/licenses/agpl-3.0.html"))
    }

    // ---- the TTS engine tokens ----------------------------------------------------------

    @Test fun each_french_voice_is_a_selectable_engine_that_builds_and_is_named() {
        for (voice in listOf(SherpaVoice.PIPER_SIWIS, SherpaVoice.PIPER_TOM, SherpaVoice.PIPER_UPMC)) {
            assertEquals("the token Settings writes is the model id",
                voice, SherpaVoice.fromEngineToken(voice.modelId))
            assertEquals(voice.modelId, TtsPolicy.modelIdOf(voice.modelId))
            assertEquals(voice.modelId, TtsPolicy.localEngineOf(voice.modelId))
            assertEquals(voice.displayName, TtsPolicy.displayName(voice.modelId))
            assertEquals("kind", "tts", spec(voice.modelId).kind)
        }
        // The en-US token stays "piper" (stored on every upgraded install).
        assertEquals("piper", TtsPolicy.localEngineOf("piper"))
        assertEquals("piper-lessac", TtsPolicy.modelIdOf("piper"))
        assertEquals("Piper", TtsPolicy.displayName("piper"))
    }
}
