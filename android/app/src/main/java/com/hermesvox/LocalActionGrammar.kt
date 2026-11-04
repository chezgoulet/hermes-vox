package com.hermesvox

/**
 * LocalActionGrammar — B2 (#421 step 2): the handful of actions that must be instant, matched by a
 * **deterministic grammar, not a model**.
 *
 * Why a grammar: "set a timer for five minutes" cannot wait for a gateway round trip. The whole
 * value of the local path is that it is already done by the time a streamed turn would have opened
 * its connection. So the rule is narrow and enumerable on purpose — the same reasoning that retired
 * the 174-substring intent classifier (docs/DESIGN-enhanced-realtime-voice.md), inverted: this is
 * not a language judgement, it is a command syntax. Anything the grammar does not confidently
 * recognise falls through to the mind, which is exactly today's behaviour.
 *
 * Scope, deliberately:
 *  - **four actions**: timer, alarm, do-not-disturb, volume;
 *  - **Tier 0 only** — reversible, private, nothing outward-facing. No messages, no calls, no
 *    purchases, no app launches;
 *  - **English only**. An utterance carrying a letter outside A–Z falls through untouched, so a
 *    French or German caller is never half-parsed;
 *  - **bounded**: timers 1 s–24 h, alarms at a wall clock, volume 0–100 %.
 *
 * Pure JVM (no android.*), unit-tested in LocalActionGrammarTest.
 */
object LocalActionGrammar {

    /** What the phone can do by itself. */
    sealed class Intent {
        abstract val kind: String

        /** A countdown. [durationMs] is already bounded and validated. */
        data class Timer(val durationMs: Long) : Intent() {
            override val kind = "timer"
        }

        /** A wall-clock alarm at [hour]:[minute] (24 h), the next occurrence. */
        data class Alarm(val hour: Int, val minute: Int) : Intent() {
            override val kind = "alarm"
        }

        /** Do Not Disturb on or off. Needs the user's policy access; the runner says so. */
        data class Dnd(val on: Boolean) : Intent() {
            override val kind = "dnd"
        }

        /** The media stream one step up ([direction] = 1) or down (-1). */
        data class VolumeStep(val direction: Int) : Intent() {
            override val kind = "volume"
        }

        /** The media stream set to [percent] of its maximum. */
        data class SetVolume(val percent: Int) : Intent() {
            override val kind = "volume"
        }
    }

    const val MIN_DURATION_MS = 1_000L
    const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

    // ---- vocabulary (finite, and meant to stay finite) ----

    private val NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )

    private val UNITS = mapOf(
        "second" to 1_000L, "seconds" to 1_000L, "sec" to 1_000L, "secs" to 1_000L,
        "minute" to 60_000L, "minutes" to 60_000L, "min" to 60_000L, "mins" to 60_000L,
        "hour" to 3_600_000L, "hours" to 3_600_000L, "hr" to 3_600_000L, "hrs" to 3_600_000L,
    )

    /** Politeness and openers that carry no meaning. Removed once, from the front. */
    private val OPENERS = listOf(
        "hey hermes", "hermes", "okay", "ok", "please", "could you", "can you", "would you",
        "will you", "i want you to", "i need you to",
    )

    /** Trailing words that carry no meaning. Removed once, from the end. */
    private val TRAILERS = listOf("please", "now", "for me", "thanks", "thank you")

    /**
     * The parse. Null means "not a local action" and the caller falls through to the mind.
     *
     * The whole utterance must be consumed by one shape: "set a timer for 5 minutes" matches,
     * "set a timer for 5 minutes and then email Bob" does not (that one is the mind's).
     */
    fun parse(utterance: String?): Intent? {
        val raw = utterance?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!english(raw)) return null
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return parseTimer(t) ?: parseAlarm(t) ?: parseDnd(t) ?: parseVolume(t)
    }

    /**
     * English only, on purpose. A single letter outside A–Z means the utterance is in another
     * language (or is not words at all), and a half-understood "règle un minuteur" is worse than
     * falling through to the mind, which speaks every language the agent does.
     */
    fun english(text: String): Boolean =
        text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " .,:;'’-?!%/+" }

    private fun normalize(raw: String): String {
        var t = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        for (o in OPENERS) if (t.startsWith("$o ")) { t = t.removePrefix("$o ").trim(); break }
        for (tr in TRAILERS) if (t.endsWith(" $tr")) { t = t.removeSuffix(" $tr").trim(); break }
        return t
    }

    // ---- timer ----

    private fun parseTimer(t: String): Intent? {
        val body = when {
            t.startsWith("set a timer for ") -> t.removePrefix("set a timer for ")
            t.startsWith("set a timer ") -> t.removePrefix("set a timer ")
            t.startsWith("set timer for ") -> t.removePrefix("set timer for ")
            t.startsWith("start a timer for ") -> t.removePrefix("start a timer for ")
            t.startsWith("start a timer ") -> t.removePrefix("start a timer ")
            t.startsWith("timer for ") -> t.removePrefix("timer for ")
            t.startsWith("timer ") -> t.removePrefix("timer ")
            t.endsWith(" timer") -> t.removeSuffix(" timer")      // "five minute timer"
            else -> return null
        }
        val ms = duration(body.trim()) ?: return null
        return Intent.Timer(ms)
    }

    /**
     * "5 minutes", "ninety seconds", "an hour", "half an hour", "1 hour and 5 minutes".
     * Null when any part is not a number+unit this object knows.
     */
    fun duration(text: String): Long? {
        var t = text.trim()
        if (t.isEmpty()) return null
        if (t == "half an hour" || t == "half hour") return 30 * 60_000L
        var total = 0L
        // Split only on the conjunction: the parts are "5 minutes" / "1 hour".
        for (part in t.split(" and ", ", ")) {
            val m = Regex("^([a-z0-9-]+) ?(hours?|hrs?|minutes?|mins?|seconds?|secs?)$").find(part.trim())
                ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: NUMBERS[m.groupValues[1]] ?: return null
            if (n < 0) return null
            val unit = UNITS[m.groupValues[2]] ?: return null
            total += n * unit
        }
        if (total < MIN_DURATION_MS || total > MAX_DURATION_MS) return null
        return total
    }

    // ---- alarm ----

    private fun parseAlarm(t: String): Intent? {
        val body = when {
            t.startsWith("set an alarm for ") -> t.removePrefix("set an alarm for ")
            t.startsWith("set an alarm at ") -> t.removePrefix("set an alarm at ")
            t.startsWith("set alarm for ") -> t.removePrefix("set alarm for ")
            t.startsWith("alarm for ") -> t.removePrefix("alarm for ")
            t.startsWith("alarm at ") -> t.removePrefix("alarm at ")
            t.startsWith("wake me up at ") -> t.removePrefix("wake me up at ")
            t.startsWith("wake me at ") -> t.removePrefix("wake me at ")
            else -> return null
        }
        val (h, m) = clock(body.trim()) ?: return null
        return Intent.Alarm(h, m)
    }

    /**
     * "7", "7:30", "7 am", "7:30 pm", "19:30". A bare hour is the 12-hour reading (a caller who
     * says "alarm for 7" means 07:00 or 19:00 depending on the hour, and the runner resolves it to
     * the NEXT occurrence — see [Alarm]).
     */
    fun clock(text: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$").find(text.trim()) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toIntOrNull() ?: return null
        val pm = m.groupValues[3] == "pm"
        val am = m.groupValues[3] == "am"
        if (min !in 0..59) return null
        when {
            pm && h in 1..11 -> h += 12
            pm && h == 12 -> {}                        // 12 pm is noon
            am && h == 12 -> h = 0                     // 12 am is midnight
            am && h !in 1..12 -> return null
            !am && !pm && h !in 0..23 -> return null
            h !in 0..23 -> return null
        }
        return h to min
    }

    // ---- do not disturb ----

    private fun parseDnd(t: String): Intent? {
        if (!(t.contains("do not disturb") || Regex("\\bdnd\\b").containsMatchIn(t))) return null
        // Everything this shape may say must be consumed: "turn on do not disturb" is one action,
        // "turn down the volume and turn on do not disturb" is not this grammar's turn at all.
        var rest = t
        for (w in listOf(
            "turn on ", "switch on ", "turn off ", "switch off ", "enable ", "disable ",
            "do not disturb", "dnd", "on", "off", "set ", "turn ", "switch ",
        )) rest = rest.replace(w, " ")
        if (rest.replace(Regex("\\s+"), " ").trim().isNotEmpty()) return null
        return when {
            Regex("\\b(off|disable)\\b").containsMatchIn(t) -> Intent.Dnd(false)
            Regex("\\b(on|enable)\\b").containsMatchIn(t) -> Intent.Dnd(true)
            else -> null
        }
    }

    // ---- volume ----

    private fun parseVolume(t: String): Intent? {
        Regex("^(?:set |turn |put )?(?:the )?volume to (\\d{1,3})(?: percent|%)?$").find(t)?.let { m ->
            val p = m.groupValues[1].toIntOrNull() ?: return null
            return if (p in 0..100) Intent.SetVolume(p) else null
        }
        Regex("^(?:set |turn |put )?(?:the )?volume (\\d{1,3})(?: percent|%)?$").find(t)?.let { m ->
            val p = m.groupValues[1].toIntOrNull() ?: return null
            return if (p in 0..100) Intent.SetVolume(p) else null
        }
        val down = t in setOf(
            "volume down", "turn the volume down", "turn volume down", "put the volume down",
            "quieter", "turn it down", "lower the volume", "turn the volume lower", "softer",
        )
        if (down) return Intent.VolumeStep(-1)
        val up = t in setOf(
            "volume up", "turn the volume up", "turn volume up", "put the volume up",
            "louder", "turn it up", "raise the volume", "turn the volume up",
        )
        if (up) return Intent.VolumeStep(1)
        return null
    }
}
