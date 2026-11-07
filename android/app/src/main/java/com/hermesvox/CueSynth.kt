package com.hermesvox

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * CueSynth — the being's procedural cue vocabulary (#156, D7).
 *
 * There are no samples: the app ships no .wav/.ogg cue assets and no per-cue file is
 * downloaded. Every cue is a handful of milliseconds of additive synthesis, generated
 * on the phone from the SAME vocabulary the avatar moves in ([MotionState.Motion]) —
 * so the sound belongs to this being by construction, not to a stock library. A cue is
 * therefore:
 *
 *   a MOTIVE    a few notes (start, length, pitch glide, gain) that say what happened
 *   a TIMBRE    the additive partials + transposition, picked from the being's idle
 *               ARCHETYPE (`particles_theme`) so two installs can feel different while
 *               every family stays recognisable
 *
 * THE FOUR FAMILIES (distinct on purpose — a glance at the phone, or a listen from a
 * pocket, should name one of four things, never "some beep"):
 *
 *   LISTENING    rising E5 -> A5, soft, ~170 ms. Receptive and *questioning*: the ear
 *                opens, it is your turn. Rises, because the turn is being handed over.
 *   THINKING     low B3 -> G3, dark, two short ticks, ~165 ms. Muted and downward: work
 *                has begun and nothing is expected of you. Never bright enough to read
 *                as an answer.
 *   REPLY_READY  G5 -> C6 -> E6, bright, ~205 ms. The only cue that glitters upward —
 *                it can only mean "here it comes". Deliberately unlike LISTENING
 *                (two notes, soft) in both pitch and colour.
 *   ERROR        A#4 -> C4, a detuned falling minor, ~280 ms. The glide plus the
 *                uneven partials read as "something went wrong" without a klaxon.
 *
 * The archetype variants are transposition + partial weights only, never a different
 * motive: SOFT (sine-leaning), BRIGHT (odd-harmonic-rich), GLASS (inharmonic bell
 * partial), DEEP (an octave down, strong second partial). This keeps the family
 * legible while the being's body-chosen archetype decides its colour.
 *
 * Pure JVM — no Android deps — so the whole vocabulary, the envelopes and the haptic
 * patterns are proven off-device, the same way MotionState / ReplySettleRule are.
 */
object CueSynth {

    /** The sample rate of every generated buffer. 22.05 kHz is more than enough for
     *  < 1.4 kHz partials and halves the buffer the AudioTrack must copy. */
    const val SAMPLE_RATE = 22050

    /** Peak a rendered buffer is normalised to, so every cue leaves headroom under
     *  the stream volume and no cue clips. */
    const val PEAK = 0.85f

    /** The four cue families. The name is the log token. */
    enum class Cue { LISTENING, THINKING, REPLY_READY, ERROR, PING, IDLE }

    /** A timbre family, chosen from the being's idle archetype. */
    enum class Timbre { SOFT, BRIGHT, GLASS, DEEP }

    /** One additive partial: [ratio] × the note's current frequency, at [amp]. */
    private data class Partial(val ratio: Float, val amp: Float)

    /** A voice = a transposition plus its partial stack. */
    private data class Voice(val transpose: Float, val partials: List<Partial>)

    private fun voice(t: Timbre): Voice = when (t) {
        Timbre.SOFT -> Voice(1f, listOf(Partial(1f, 1f), Partial(2f, 0.10f), Partial(3f, 0.04f)))
        Timbre.BRIGHT -> Voice(1f, listOf(Partial(1f, 1f), Partial(2f, 0.42f), Partial(3f, 0.22f), Partial(5f, 0.08f)))
        // Inharmonic: a struck-glass bell, not a tone.
        Timbre.GLASS -> Voice(1f, listOf(Partial(1f, 1f), Partial(2.76f, 0.30f), Partial(5.40f, 0.12f)))
        Timbre.DEEP -> Voice(0.5f, listOf(Partial(1f, 1f), Partial(2f, 0.34f), Partial(3f, 0.10f)))
    }

    /**
     * Archetype token (`particles_theme`) -> timbre. Grouped by the matter the
     * archetype is made of, never one timbre per archetype: 28 archetypes would become
     * 28 cues nobody could tell apart, and the four families above are the contract.
     */
    fun timbreFor(archetype: String?): Timbre = when (archetype?.lowercase()?.trim()) {
        // airy / wet / rounded bodies: pure, slow-decaying tones
        "aura", "iris", "vortex", "water", "sphere", "nucleus", "aurora", "breath" -> Timbre.SOFT
        // fire / electricity / scanning: bright, odd-harmonic
        "flame", "arc", "radar", "helix", "soundwave", "waveform", "scan", "bracket" -> Timbre.BRIGHT
        // crystalline / ordered: inharmonic bell
        "constellation", "nodes", "mandala", "harmonograph", "tesseract", "bloom", "knot" -> Timbre.GLASS
        // heavy / deep time: an octave down
        "infall", "hourglass", "held", "ribbon", "octopus", "taku", "butterfly", "eye", "seeker" -> Timbre.DEEP
        else -> Timbre.SOFT
    }

    /**
     * The motives. Frequencies are equal-tempered pitches; [Note.fromHz] > [Note.toHz]
     * means a falling note (THINKING, ERROR), the reverse a rising one (LISTENING,
     * REPLY_READY). Gains are relative inside the cue and normalised on render.
     */
    private data class Note(
        val atMs: Int,
        val ms: Int,
        val fromHz: Float,
        val toHz: Float,
        val gain: Float,
    )

    private fun motive(cue: Cue): List<Note> = when (cue) {
        Cue.LISTENING -> listOf(
            Note(0, 70, 659.25f, 659.25f, 0.85f),     // E5
            Note(70, 100, 880.00f, 880.00f, 1.0f),    // A5 — up: your turn
        )
        Cue.THINKING -> listOf(
            Note(0, 70, 246.94f, 220.00f, 1.0f),      // B3 -> A3
            Note(95, 70, 196.00f, 185.00f, 0.75f),    // G3 -> F#3 — down: working
        )
        Cue.REPLY_READY -> listOf(
            Note(0, 55, 783.99f, 783.99f, 0.80f),     // G5
            Note(55, 55, 1046.50f, 1046.50f, 0.90f),  // C6
            Note(110, 95, 1318.51f, 1318.51f, 1.0f),  // E6 — up and out: it is here
        )
        Cue.ERROR -> listOf(
            Note(0, 110, 466.16f, 440.00f, 1.0f),     // A#4 -> A4
            Note(130, 150, 293.66f, 261.63f, 0.90f),  // D4 -> C4 — down, rough
        )
        // #251: a single soft, low note — a pulse while the agent works, not an event.
        Cue.PING -> listOf(
            Note(0, 60, 329.63f, 329.63f, 1.0f),      // E4, once
        )
        // #251: the check-in after a long silence — a question: up, a breath, up again.
        Cue.IDLE -> listOf(
            Note(0, 80, 523.25f, 587.33f, 0.85f),     // C5 -> D5
            Note(240, 120, 587.33f, 698.46f, 1.0f),   // D5 -> F5 — "still there?"
        )
    }

    /** How long the cue is, in ms (the last note's tail is part of it). */
    fun durationMs(cue: Cue): Int = motive(cue).maxOf { it.atMs + it.ms }

    /** The first note's starting pitch — the family's direction begins here. */
    internal fun firstHz(cue: Cue): Float = motive(cue).first().fromHz

    /** The last note's ending pitch — where the family's direction lands. */
    internal fun lastHz(cue: Cue): Float = motive(cue).last().toHz

    /**
     * Render [cue] as 16-bit mono PCM at [SAMPLE_RATE] — the exact buffer handed to
     * AudioTrack. Cached by the player; never written to a file.
     */
    fun pcm(cue: Cue, timbre: Timbre = Timbre.SOFT): ShortArray {
        val v = voice(timbre)
        val notes = motive(cue)
        val total = (durationMs(cue) * SAMPLE_RATE) / 1000
        val mix = FloatArray(total)

        notes.forEach { note ->
            val count = (note.ms * SAMPLE_RATE) / 1000
            if (count <= 0) return@forEach
            val start = (note.atMs * SAMPLE_RATE) / 1000
            val f0 = note.fromHz * v.transpose
            val f1 = note.toHz * v.transpose
            val attack = (ATTACK_MS * SAMPLE_RATE) / 1000
            val release = (RELEASE_MS * SAMPLE_RATE) / 1000
            var phase = 0.0
            for (i in 0 until count) {
                val idx = start + i
                if (idx >= total) break
                // Pitch glide, exponential so an interval is heard as equal steps.
                val u = i.toFloat() / count
                val hz = f0 * Math.pow((f1 / f0).toDouble(), u.toDouble()).toFloat()
                phase += 2.0 * PI * hz / SAMPLE_RATE
                // Envelope: short linear attack, exponential body, hard release ramp.
                // The release ramp is what keeps every cue from clicking at its end.
                val body = exp(-3.2 * u).toFloat()
                val env = when {
                    i < attack -> i.toFloat() / attack
                    i > count - release -> (count - i).toFloat() / release
                    else -> body
                }
                var s = 0f
                for (p in v.partials) {
                    // A partial above Nyquist would alias; drop it rather than fold it.
                    if (hz * p.ratio >= SAMPLE_RATE / 2f) continue
                    s += p.amp * sin(phase * p.ratio).toFloat()
                }
                mix[idx] += s * env * note.gain
            }
        }

        // Normalise to [PEAK] so one timbre's denser partial stack is not louder than
        // another's; a silent mix (impossible by construction) stays silent.
        var peak = 0f
        for (s in mix) peak = maxOf(peak, kotlin.math.abs(s))
        val scale = if (peak > 0f) PEAK / peak else 0f
        return ShortArray(total) { (mix[it] * scale * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /**
     * The haptic pattern for [cue], as (timings, amplitudes) in the shape
     * `VibrationEffect.createWaveform` wants: timings alternate wait/vibrate starting
     * with a wait, amplitudes are 0..255 per vibrate segment. Distinct per family for
     * the same reason the audio is: a phone on the table should tap the identity of the
     * cue, not one generic buzz.
     *
     *   LISTENING    one short tick                  (the ear opens)
     *   THINKING     two light ticks                 ("still working")
     *   REPLY_READY  a two-beat tap, the strongest   ("here it comes")
     *   ERROR        two fuller beats                ("that failed")
     */
    fun haptic(cue: Cue): Pair<LongArray, IntArray> = when (cue) {
        Cue.LISTENING -> longArrayOf(0, 14) to intArrayOf(0, 150)
        Cue.THINKING -> longArrayOf(0, 9, 55, 9) to intArrayOf(0, 110, 0, 90)
        Cue.REPLY_READY -> longArrayOf(0, 16, 80, 26) to intArrayOf(0, 200, 0, 255)
        Cue.ERROR -> longArrayOf(0, 40, 60, 40) to intArrayOf(0, 230, 0, 230)
        Cue.PING -> longArrayOf(0, 6) to intArrayOf(0, 70)
        Cue.IDLE -> longArrayOf(0, 20, 220, 20) to intArrayOf(0, 160, 0, 160)
    }

    private const val ATTACK_MS = 5
    private const val RELEASE_MS = 8
}
