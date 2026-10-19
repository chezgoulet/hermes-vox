package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #59: the built-in-voice (speaker id) policy.
 *
 * The two engines have different id namespaces (Supertonic 0-9, Piper 0-903), so
 * every guarantee here is about the id being read by the RIGHT engine, clamped to
 * the range that engine reports, and surviving a process restart without leaking
 * into the other engine. Pure JVM — VoiceSpeakers has no Android deps.
 */
class VoiceSpeakersTest {

    /** The persisted side of SharedPreferences, shared across "restarts". */
    private class MapPrefs(val map: MutableMap<String, Int> = HashMap()) : SpeakerPrefs {
        override fun getInt(key: String): Int? = map[key]
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    @Test fun clamp_negative_is_zero() {
        assertEquals(0, VoiceSpeakers.clamp(-1, 904))
        assertEquals(0, VoiceSpeakers.clamp(-500, 10))
    }

    @Test fun clamp_oversized_is_the_last_speaker() {
        assertEquals(903, VoiceSpeakers.clamp(904, 904))
        assertEquals(903, VoiceSpeakers.clamp(5000, 904))
        assertEquals(9, VoiceSpeakers.clamp(10, 10))
        assertEquals(9, VoiceSpeakers.clamp(350, 10))
    }

    @Test fun clamp_zero_speakers_reads_as_zero() {
        // An engine that has not reported its speaker count (or reports none) must
        // not produce a negative id for the native generate() call.
        assertEquals(0, VoiceSpeakers.clamp(7, 0))
        assertEquals(0, VoiceSpeakers.clamp(-7, 0))
    }

    @Test fun clamp_in_range_is_untouched() {
        assertEquals(0, VoiceSpeakers.clamp(0, 904))
        assertEquals(350, VoiceSpeakers.clamp(350, 904))
        assertEquals(9, VoiceSpeakers.clamp(9, 10))
    }

    @Test fun per_engine_default_is_used_when_nothing_is_stored() {
        assertEquals(0, VoiceSpeakers.defaultId(SherpaVoice.PIPER))
        assertEquals(1, VoiceSpeakers.defaultId(SherpaVoice.SUPERTONIC))
        val store = MapPrefs()
        assertEquals(0, VoiceSpeakers.resolve(store, SherpaVoice.PIPER, 904))
        assertEquals(1, VoiceSpeakers.resolve(store, SherpaVoice.SUPERTONIC, 10))
    }

    @Test fun default_is_clamped_by_a_tiny_engine() {
        // Defensive: an engine that reports a single speaker still gets a valid id.
        assertEquals(0, VoiceSpeakers.resolve(MapPrefs(), SherpaVoice.SUPERTONIC, 1))
    }

    @Test fun a_stored_id_survives_a_restart() {
        val map = HashMap<String, Int>()
        VoiceSpeakers.save(MapPrefs(map), SherpaVoice.PIPER, 350)

        // "Restart": a fresh store over the same persisted map.
        val afterRestart = MapPrefs(map)
        assertEquals(350, VoiceSpeakers.resolve(afterRestart, SherpaVoice.PIPER, 904))
        assertEquals(350, VoiceSpeakers.read(afterRestart, SherpaVoice.PIPER))
    }

    @Test fun a_stored_id_does_not_leak_across_engines() {
        val map = HashMap<String, Int>()
        VoiceSpeakers.save(MapPrefs(map), SherpaVoice.PIPER, 350)

        // Supertonic has only 10 voices. 350 must NOT be clamped to 9 (a voice the
        // user never picked) — it belongs to the other engine, so Supertonic reads
        // its own default.
        assertEquals(1, VoiceSpeakers.resolve(MapPrefs(map), SherpaVoice.SUPERTONIC, 10))

        // ...and the reverse: a Supertonic id must not become a Piper voice.
        val other = HashMap<String, Int>()
        VoiceSpeakers.save(MapPrefs(other), SherpaVoice.SUPERTONIC, 7)
        assertEquals(0, VoiceSpeakers.resolve(MapPrefs(other), SherpaVoice.PIPER, 904))
        // Each engine keeps its own choice across the switch (same install).
        VoiceSpeakers.save(MapPrefs(map), SherpaVoice.SUPERTONIC, 7)
        assertEquals(350, VoiceSpeakers.resolve(MapPrefs(map), SherpaVoice.PIPER, 904))
        assertEquals(7, VoiceSpeakers.resolve(MapPrefs(map), SherpaVoice.SUPERTONIC, 10))
    }

    @Test fun legacy_shared_key_migrates_for_supertonic_only() {
        // Pre-#59 installs stored the Supertonic voice under one shared key. That id
        // is a Supertonic id; Piper never honoured it, so it must not become a Piper
        // selection after the upgrade.
        val map = hashMapOf(VoiceSpeakers.LEGACY_KEY to 7)
        assertEquals(7, VoiceSpeakers.resolve(MapPrefs(map), SherpaVoice.SUPERTONIC, 10))
        assertEquals(0, VoiceSpeakers.resolve(MapPrefs(map), SherpaVoice.PIPER, 904))
        assertNull(VoiceSpeakers.read(MapPrefs(map), SherpaVoice.PIPER))
    }

    @Test fun an_out_of_range_stored_id_is_clamped_and_written_back() {
        val map = HashMap<String, Int>()
        VoiceSpeakers.save(MapPrefs(map), SherpaVoice.PIPER, 5000)
        assertEquals(903, VoiceSpeakers.resolve(MapPrefs(map), SherpaVoice.PIPER, 904))

        // noteCount = the engine loaded and reported its real count: a model swapped
        // for a smaller one clamps the stored id instead of silently applying it.
        VoiceSpeakers.noteCount(MapPrefs(map), SherpaVoice.PIPER, 100)
        assertEquals(99, VoiceSpeakers.read(MapPrefs(map), SherpaVoice.PIPER))
        assertEquals(100, VoiceSpeakers.speakerCount(MapPrefs(map), SherpaVoice.PIPER))
    }

    @Test fun speaker_count_falls_back_to_the_shipped_model() {
        val store = MapPrefs()
        assertEquals(904, VoiceSpeakers.speakerCount(store, SherpaVoice.PIPER))
        assertEquals(10, VoiceSpeakers.speakerCount(store, SherpaVoice.SUPERTONIC))
        VoiceSpeakers.noteCount(store, SherpaVoice.PIPER, 903)
        assertEquals(903, VoiceSpeakers.speakerCount(store, SherpaVoice.PIPER))   // observed wins
    }

    @Test fun curated_piper_ids_are_eight_distinct_in_range_ids() {
        val ids = VoiceSpeakers.PIPER_CURATED.map { it.first }
        assertEquals(8, ids.size)
        assertEquals(8, ids.toSet().size)                       // no duplicate voice
        assertTrue(ids.all { it in 0 until VoiceSpeakers.knownCount(SherpaVoice.PIPER) })
        assertFalse(ids.contains(VoiceSpeakers.defaultId(SherpaVoice.PIPER)))   // not the bare default
        assertTrue(VoiceSpeakers.PIPER_CURATED.all { it.second.isNotBlank() })
    }

    @Test fun restore_defaults_clears_both_engines_and_the_legacy_key() {
        val store = MapPrefs()
        VoiceSpeakers.save(store, SherpaVoice.PIPER, 350)
        VoiceSpeakers.save(store, SherpaVoice.SUPERTONIC, 7)
        store.putInt(VoiceSpeakers.LEGACY_KEY, 3)
        VoiceSpeakers.allKeys().forEach { store.map.remove(it) }
        assertTrue(store.map.isEmpty())
        assertEquals(0, VoiceSpeakers.resolve(store, SherpaVoice.PIPER, 904))
        assertEquals(1, VoiceSpeakers.resolve(store, SherpaVoice.SUPERTONIC, 10))
    }
}
