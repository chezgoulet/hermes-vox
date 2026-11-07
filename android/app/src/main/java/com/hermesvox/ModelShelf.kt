package com.hermesvox

/**
 * ModelShelf — groups the catalogue by the JOB a model does for the user (#219),
 * not by download order: Hearing (what you say becomes text, and it hears you
 * start/stop), Voice (it speaks), Being (the optional presence layer).
 *
 * Pure JVM so the grouping is testable without a device. A kind that is not one
 * of those three is NOT hidden: it gets its own group named after its catalogue
 * kind, after the three known shelves.
 */
object ModelShelf {

    const val HEARING = "hearing"
    const val VOICE = "voice"
    const val BEING = "being"

    data class Group(
        val key: String,
        val title: String,
        val blurb: String,
        val specs: List<ModelSpec>
    )

    /** The shelf a catalogue kind belongs to; unknown kinds are their own shelf. */
    fun keyFor(kind: String): String = when (kind) {
        "stt", "vad" -> HEARING
        "tts" -> VOICE
        "express" -> BEING
        else -> kind
    }

    private fun titleFor(key: String): String = when (key) {
        HEARING -> "Hearing"
        VOICE -> "Voice"
        BEING -> "Being"
        else -> "Other — $key"
    }

    private fun blurbFor(key: String): String = when (key) {
        HEARING -> "Turns your speech into text, and hears when you start and stop talking."
        VOICE -> "Speaks Hermes' replies aloud, fully on-device."
        BEING -> "Runs the optional on-device Presence layer — the being, its asides and its voice."
        else -> "Not part of the voice pipeline; kept here so nothing is hidden."
    }

    /**
     * Shelve [specs] in the order a person needs them: Hearing, Voice, Being, then
     * any other kinds alphabetically. Within a shelf the recommended models come
     * first, then the catalogue's own order. Stable: every input appears once.
     */
    fun groups(specs: List<ModelSpec>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<ModelSpec>>()
        for (spec in specs) {
            buckets.getOrPut(keyFor(spec.kind)) { mutableListOf() }.add(spec)
        }
        val known = listOf(HEARING, VOICE, BEING)
        val orderedKeys = known.filter { buckets.containsKey(it) } +
            buckets.keys.filter { it !in known }.sorted()
        return orderedKeys.map { key ->
            val shelf = buckets.getValue(key).sortedWith(
                compareBy<ModelSpec>({ !it.recommended }, { it.order })
            )
            Group(key, titleFor(key), blurbFor(key), shelf)
        }
    }
}
