package com.hermesvox

/**
 * VoiceSpeakers — the per-engine built-in-voice (speaker id) selection.
 *
 * Speaker ids are ENGINE-LOCAL. Supertonic's 0-9 (F1-F5, M1-M5) and Piper's
 * 0-903 (LibriTTS-R medium) are different namespaces: id 7 is M3 on one engine
 * and an arbitrary audiobook reader on the other. So the selection is stored
 * PER ENGINE (own key), which is what makes a chosen id survive a restart and
 * keeps it from leaking into the other engine when the user switches.
 *
 * Every read is clamped to the range the engine actually reports: prefs can
 * hold a negative value, a typed id above the model's speaker count, or a stale
 * id from a model that was replaced by a smaller one. A clamped id is written
 * back so the Settings row shows the voice that will really speak.
 *
 * Pure Kotlin (no Android deps) — the policy is unit-testable off-device.
 */
object VoiceSpeakers {

    /** The pre-#59 shared key. Supertonic honoured it, Piper ignored it. Kept so an
     *  upgraded install keeps its Supertonic voice (migrated on first read). */
    const val LEGACY_KEY = "tts_speaker"

    /** The engine's default voice: Piper 0 (the historical behaviour), Supertonic F2
     *  (DEFAULT_SPEAKER: clean in the bake-off's intelligibility check). */
    fun defaultId(engine: SherpaVoice): Int = when (engine) {
        SherpaVoice.PIPER -> 0
        SherpaVoice.SUPERTONIC -> 1
    }

    /** Per-engine storage keys. The modelId is stable and unique per engine. */
    fun key(engine: SherpaVoice): String = "tts_speaker_${engine.modelId}"
    fun countKey(engine: SherpaVoice): String = "tts_speaker_count_${engine.modelId}"

    /** Speaker counts shipped with the blessed models, used before an engine has
     *  ever loaded (the numeric-entry bounds). SherpaTts refines them at load. */
    fun knownCount(engine: SherpaVoice): Int = when (engine) {
        SherpaVoice.PIPER -> 904        // vits-piper-en_US-libritts_r-medium
        SherpaVoice.SUPERTONIC -> 10    // ten built-in voices
    }

    /** The curated Piper voices offered by the Settings picker (8 of the model's 904).
     *
     *  Chosen from the shipped LibriTTS-R medium model (`emb_g.weight`, 904x512):
     *  greedy farthest-point selection over the most typical 80% of speakers
     *  maximised the minimum pairwise cosine distance (0.90; a random 8 averages
     *  0.79), and the pitch estimate of each synthetic vowel utterance (median F0
     *  on a fixed phoneme sequence) splits them 4 lower / 4 higher so the row
     *  offers a real spread instead of eight neighbours. Labels say lower/higher,
     *  NOT female/male: F0 was measured, gender was not. */
    val PIPER_CURATED: List<Pair<Int, String>> = listOf(
        739 to "739 · lower",
        750 to "750 · lower",
        538 to "538 · lower",
        84 to "84 · lower",
        274 to "274 · higher",
        75 to "75 · higher",
        703 to "703 · higher",
        450 to "450 · higher",
    )

    /** Clamp to [0, numSpeakers-1]; an engine that reports no speakers reads as 0. */
    fun clamp(id: Int, numSpeakers: Int): Int =
        if (numSpeakers <= 0) 0 else id.coerceIn(0, numSpeakers - 1)

    /** The stored id for [engine], or null when this engine has never had one.
     *  The pre-#59 shared key migrates ONLY for Supertonic — the one engine that
     *  honoured it. A leftover value there is not a Piper selection. */
    fun read(store: SpeakerPrefs, engine: SherpaVoice): Int? {
        store.getInt(key(engine))?.let { return it }
        return if (engine == SherpaVoice.SUPERTONIC) store.getInt(LEGACY_KEY) else null
    }

    /** The id that will actually speak: stored (or the engine default), clamped. */
    fun resolve(store: SpeakerPrefs, engine: SherpaVoice, numSpeakers: Int): Int =
        clamp(read(store, engine) ?: defaultId(engine), numSpeakers)

    /** Persist [id] for [engine] (the picker has already validated it). */
    fun save(store: SpeakerPrefs, engine: SherpaVoice, id: Int) {
        store.putInt(key(engine), id)
    }

    /** The speaker count to show/validate against: the count the engine last
     *  reported, else the shipped count for the blessed model. */
    fun speakerCount(store: SpeakerPrefs, engine: SherpaVoice): Int {
        val observed = store.getInt(countKey(engine)) ?: 0
        return if (observed > 0) observed else knownCount(engine)
    }

    /** Record what the loaded engine actually reports. An id that no longer fits
     *  (a model swapped for a smaller one) is clamped and written back, so the
     *  row never claims a voice the engine will not use. */
    fun noteCount(store: SpeakerPrefs, engine: SherpaVoice, numSpeakers: Int) {
        if (numSpeakers <= 0) return
        store.putInt(countKey(engine), numSpeakers)
        val stored = read(store, engine) ?: return
        val fitted = clamp(stored, numSpeakers)
        if (fitted != stored) store.putInt(key(engine), fitted)
    }

    /** Every key the selection owns (Restore defaults clears all of them). */
    fun allKeys(): List<String> = SherpaVoice.entries.flatMap { listOf(key(it), countKey(it)) } + LEGACY_KEY
}

/** The narrow slice of Android SharedPreferences the speaker selection needs, so
 *  the policy above runs on a plain Map in unit tests. */
interface SpeakerPrefs {
    /** The stored int, or null when the key is absent (absent ≠ 0). */
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
}
