package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 0.9.1 — the off-device barge replay: (level, vad) sequences through the production
 * [BargeAudioPath] (RMS -> BargeGate's leaky sustains -> decide -> BargeCarry), one
 * 64 ms read at a time, clock = read index. No microphone, no VAD model, no device.
 *
 * WHAT THE INPUTS ARE. Every level is per 64 ms read. Two kinds of input:
 *
 *  - FIELD (0.9.0 Pixel 7 log, 2026-10-20): the three near-miss run peaks 0.113 /
 *    0.160 / 0.197 at floor 0.100, bar 0.130, all `vad=false`; the residual-echo band
 *    measured on this single-capture AEC path, 0.081-0.095 (BargeGate class doc).
 *  - MODELLED: syllabic speech envelopes at 3-6 Hz (the conversational syllable-rate
 *    range), peak in the field's barge band, mixed in power with a reply-echo bed at
 *    the measured residual level. Two shapes: a raised cosine (only the crest frames
 *    clear a bar — the pessimistic case for detection) and a vowel-plateau envelope
 *    (45% at the peak, 15% cosine ramps, 25% trough — closer to voiced speech).
 *    Ten phase offsets against the 64 ms read grid per case. These are models, not
 *    recordings; they encode the field's AMPLITUDES, not a field waveform.
 *
 * VAD is held false throughout the speech cases: that is the field's state at every
 * near-miss (echo-dominant frames read as non-speech to Silero), so only the
 * level-only escape can fire. Any VAD agreement only makes firing easier.
 */
class BargeReplayTest {

    private val sr = 16000
    private val read = 1024
    private val frameMs = read * 1000L / sr    // 64
    private val oldLeak = 96L                  // 0.4.0.3 .. 0.9.0

    // ---- signal models (per-read level, seconds -> level) ----

    private fun cosEnv(t: Double, rate: Double, peak: Double, trough: Double) =
        trough + (peak - trough) * (0.5 + 0.5 * cos(2 * PI * rate * t))

    private fun plateauEnv(t: Double, rate: Double, peak: Double, trough: Double): Double {
        val ph = (t * rate) % 1.0
        return when {
            ph < 0.45 -> peak
            ph < 0.60 -> trough + (peak - trough) * (0.5 + 0.5 * cos(PI * (ph - 0.45) / 0.15))
            ph < 0.85 -> trough
            else -> trough + (peak - trough) * (0.5 - 0.5 * cos(PI * (ph - 0.85) / 0.15))
        }
    }

    /** The reply's residual echo: TTS syllables at ~4.3 Hz, crest at the measured top. */
    private fun echoBed(t: Double, peak: Double = 0.090) = cosEnv(t + 0.37, 4.3, peak, 0.03)

    private enum class Shape { COS, PLATEAU }

    /** [leadReads] of echo-only playback, then the user's voice over the echo. */
    private fun bargeLevels(shape: Shape, rate: Double, peak: Double, phase: Double,
                            leadReads: Int, speechReads: Int): List<Double> =
        List(leadReads + speechReads) { i ->
            val t = i * frameMs / 1000.0 + phase
            val e = echoBed(t)
            if (i < leadReads) e else {
                val s = when (shape) {
                    Shape.COS -> cosEnv(t, rate, peak, 0.02)
                    Shape.PLATEAU -> plateauEnv(t, rate, peak, 0.02)
                }
                sqrt(s * s + e * e)
            }
        }

    /** One 64 ms read whose RMS is exactly [level] (a +/- level square wave). */
    private fun frameAt(level: Double) = FloatArray(read) { if (it % 2 == 0) level.toFloat() else -level.toFloat() }

    private class Outcome(val fireRead: Int?, val leadMs: Long)

    private fun replay(levels: List<Double>, leakMs: Long, vad: (Int) -> Boolean = { false }): Outcome {
        val carry = BargeCarry(sr)   // production defaults: 300 ms pre-roll, 2 s history
        val path = BargeAudioPath(sr, BargeGate.DEFAULT_RMS_MIN, vadAvailable = true,
            levelOnlyMs = BargeGate.DEFAULT_LEVEL_ONLY_MS, carry = carry, leakMs = leakMs)
        levels.forEachIndexed { i, lv ->
            if (path.process(frameAt(lv), vad(i), nowMs = 1 + i * frameMs).fired) return Outcome(i, carry.leadMs)
        }
        return Outcome(null, 0L)
    }

    private val lead = 20                                  // 1.28 s of echo-only playback first
    private val speech = (3000 / frameMs).toInt()          // 3 s of the user talking over it

    /** Fires/10 and the worst time-to-fire (ms from speech onset) for one case. */
    private fun sweep(shape: Shape, rate: Double, peak: Double, leakMs: Long,
                      checkCarry: Boolean = true): Pair<Int, Long> {
        var fired = 0
        var worst = 0L
        for (p in 0 until 10) {
            val o = replay(bargeLevels(shape, rate, peak, phase = p / 10.0, lead, speech), leakMs)
            val at = o.fireRead ?: continue
            assertTrue("fired on echo before the user spoke ($shape $rate Hz $peak, read $at)", at >= lead)
            val ttf = (at - lead + 1) * frameMs
            // The carry must still hold the utterance's onset: lead >= time since onset.
            if (checkCarry)
                assertTrue("carry lost the onset ($shape $rate Hz $peak: ttf=$ttf leadMs=${o.leadMs})", o.leadMs >= ttf)
            fired++
            worst = maxOf(worst, ttf)
        }
        return fired to worst
    }

    private val rates = listOf(3.0, 4.0, 5.0, 6.0)

    // ---- 1. the field near-misses ----

    @Test fun field_near_misses_as_logged_cannot_fire_and_must_not() {
        // What the log actually shows: a run's FIRST read (sustainedMs=64), then the
        // probe is rate-limited for 2 s. Read literally — one crest read, then the level
        // falls back into the echo band — it is indistinguishable from an echo crest, and
        // no leaky 200 ms sustain fires on it, at either leak. So these three lines alone
        // do NOT prove a missed barge; what the gate does with a voice whose crests reach
        // those peaks is the envelope sweep below.
        for (peak in listOf(0.113, 0.160, 0.197)) {
            val levels = List(5) { 0.05 } + List(4) { listOf(peak, 0.08, 0.07, 0.06, 0.09, 0.05) }.flatten()
            for (leak in listOf(oldLeak, BargeGate.SUSTAIN_LEAK_MS))
                assertNull("lone crest $peak fired at leak $leak", replay(levels, leak).fireRead)
        }
    }

    // ---- 2. a person talking over the reply ----

    @Test fun plateau_speech_in_the_field_band_fires_every_time_within_1500ms() {
        for (peak in listOf(0.13, 0.16, 0.21)) for (rate in rates) {
            val (fired, worst) = sweep(Shape.PLATEAU, rate, peak, BargeGate.SUSTAIN_LEAK_MS)
            assertEquals("plateau $rate Hz peak $peak fired $fired/10", 10, fired)
            assertTrue("plateau $rate Hz peak $peak worst ttf $worst ms", worst <= 1500L)
        }
    }

    @Test fun crest_only_speech_at_the_fired_level_fires_every_time() {
        // The field's one playback barge fired at rms=0.208. A voice at that level whose
        // frames clear the bar only at crests (raised cosine) must fire at every rate.
        for (rate in rates) {
            val (fired, worst) = sweep(Shape.COS, rate, 0.21, BargeGate.SUSTAIN_LEAK_MS)
            assertEquals("cos $rate Hz peak 0.21 fired $fired/10", 10, fired)
            assertTrue("cos $rate Hz peak 0.21 worst ttf $worst ms", worst <= 2000L)
        }
    }

    @Test fun the_old_96ms_leak_misses_the_same_speech() {
        // The regression record: the identical inputs through the 0.9.0 constant.
        // Plateau speech at 0.16 (the middle near-miss) at 3 and 6 Hz, and crest-only
        // speech at 0.21 at 3/4/6 Hz, mostly never fire inside 3 s.
        assertTrue(sweep(Shape.PLATEAU, 3.0, 0.16, oldLeak, checkCarry = false).first <= 2)
        assertTrue(sweep(Shape.PLATEAU, 6.0, 0.16, oldLeak, checkCarry = false).first <= 5)
        for (rate in listOf(3.0, 4.0, 6.0))
            assertTrue(sweep(Shape.COS, rate, 0.21, oldLeak, checkCarry = false).first <= 2)
    }

    @Test fun a_late_fire_still_carries_the_onset() {
        // The 2 s history ring lost the start of the sentence whenever the fire came
        // more than ~1.7 s after the onset (seen above on the old leak: ttf 2,688 ms,
        // carry 2,000 ms). The 4 s ring keeps it for any fire this replay produces.
        val (fired, worst) = sweep(Shape.PLATEAU, 3.0, 0.16, oldLeak)   // checkCarry = true
        assertTrue("expected at least one late fire to exercise the ring", fired >= 1 && worst > 1700L)
    }

    @Test fun crest_only_speech_at_016_is_still_unreliable_and_this_says_so() {
        // Not fixed, by choice: a voice that clears the 0.130 bar ONLY at 0.16 crests
        // with VAD false sits at the edge of the new 0.43 break-even. Lowering the leak
        // further (32 ms) would fix it but halves the echo margin (stress test below).
        val fired = rates.sumOf { sweep(Shape.COS, it, 0.16, BargeGate.SUSTAIN_LEAK_MS).first }
        assertTrue("cos 0.16 fired $fired/40", fired in 10..35)
    }

    // ---- 3. the reply's own echo must never fire ----

    private fun echoOnly(shape: Shape, peak: Double, phase: Double, reads: Int): List<Double> =
        List(reads) { i ->
            val t = i * frameMs / 1000.0 + phase
            when (shape) {
                Shape.COS -> cosEnv(t, 4.3, peak, 0.03)
                Shape.PLATEAU -> plateauEnv(t, 4.3, peak, 0.03)
            }
        }

    @Test fun measured_echo_band_never_fires_in_two_minutes() {
        val reads = (120_000 / frameMs).toInt()
        for (peak in listOf(0.081, 0.095, 0.12)) for (shape in Shape.values()) for (p in 0 until 7)
            assertNull("echo $shape $peak fired", replay(echoOnly(shape, peak, p / 7.0, reads), BargeGate.SUSTAIN_LEAK_MS).fireRead)
    }

    @Test fun crest_echo_well_above_the_measured_band_still_never_fires() {
        // Stress: crest-shaped echo up to 0.17 — 1.8x the measured top, the AEC-less
        // second-recorder era's level — at three TTS syllable rates.
        val reads = (120_000 / frameMs).toInt()
        for (peak in listOf(0.13, 0.15, 0.17)) for (rate in listOf(3.5, 4.3, 5.5)) for (p in 0 until 7) {
            val levels = List(reads) { cosEnv(it * frameMs / 1000.0 + p / 7.0, rate, peak, 0.03) }
            assertNull("cos echo $peak @ $rate Hz fired", replay(levels, BargeGate.SUSTAIN_LEAK_MS).fireRead)
        }
    }

    @Test fun break_even_duty_is_documented() {
        // 48 ms leak vs 64 ms reads: break-even duty 48/(64+48) = 0.43.
        val duty = BargeGate.SUSTAIN_LEAK_MS.toDouble() / (frameMs + BargeGate.SUSTAIN_LEAK_MS)
        assertEquals(0.43, duty, 0.005)
        // Sanity on the model: a raised cosine from 0.02 to 0.21 spends
        // acos(2*(0.13-0.02)/0.19 - 1)/pi = 0.45 of its time over the 0.130 bar — under
        // the old 0.6 break-even (why the old gate could not see it), over the new 0.43.
        val crestDuty = acos(2 * (0.13 - 0.02) / 0.19 - 1) / PI
        assertEquals(0.45, crestDuty, 0.005)
        assertTrue(crestDuty < 0.6 && crestDuty > duty)
    }

    // ---- 4. 0.9.1: the floor measured from the reply's own echo (EchoFloor) ----
    //
    // docs/BARGE-WHY-0.7-WORKED.md. The fixed 0.10 floor was derived from a 0.081-0.095
    // echo measured under Piper; under Supertonic the 0.9.0 field barge-stat lines read
    // 0.000-0.008 at the mic during playback, and the owner's voice, ducked by the AEC,
    // 0.04-0.17. These cases add the third input the production drain now passes: the
    // reply's level at the playback head (SherpaTts.playbackRms).
    //
    // MODEL. The reply (ref): 6 s phrases with 0.7 s gaps, syllables at 4.3 Hz, a
    // 128 ms word pause every 0.9 s; active peak 0.10 (Supertonic) or 0.18 (Piper),
    // the levels the two voices synthesize at with the app's settings. The echo: the
    // reply delayed by [delay] reads (negative = the echo arrives BEFORE the head says
    // the audio played), scaled to [echoPeak], optionally compressive/expansive
    // ([expo]). A 0.004 noise bed throughout — the gap level the field shows between
    // chunks. The voice: the envelopes above, VAD true while it is over 0.04 (Silero
    // follows the voice) or never (Silero deaf). The first 8 reads are the playback
    // grace window (hold), as in the drain.

    private fun replyRef(t: Double, peak: Double): Double {
        val c = t % 6.7
        if (c >= 6.0) return 0.0                       // the gap between phrases
        if ((c % 0.9) < 0.128) return 0.0              // a word pause
        return cosEnv(c, 4.3, peak, 0.2 * peak)
    }

    private class Reply(val refPeak: Double, val echoPeak: Double, val delay: Int = 2, val expo: Double = 1.0,
                        val stall: IntRange = IntRange.EMPTY)

    private val supertonic = Reply(0.10, 0.008)            // today: L2's 0.000-0.008
    private val headset = Reply(0.10, 0.0)                 // no acoustic path at all
    private fun piper(echoPeak: Double = 0.095, delay: Int = 2, expo: Double = 1.0) = Reply(0.18, echoPeak, delay, expo)

    private class EchoOutcome(val fireRead: Int?, val leadMs: Long, val floor: Float)

    private fun replayReply(reply: Reply, reads: Int, withEcho: Boolean = true, phase: Double = 0.0,
                            echoVad: (Int, Double) -> Boolean = { _, _ -> false },
                            voice: ((Double) -> Double)? = null, voiceFrom: Int = 0, voiceVad: Boolean = true,
                            voiceUntil: Int = Int.MAX_VALUE, echoModel: EchoFloor = EchoFloor(),
                            leakMs: Long = BargeGate.SUSTAIN_LEAK_MS): EchoOutcome {
        val carry = BargeCarry(sr)
        val path = BargeAudioPath(sr, BargeGate.DEFAULT_RMS_MIN, vadAvailable = true,
            levelOnlyMs = BargeGate.DEFAULT_LEVEL_ONLY_MS, carry = carry, leakMs = leakMs,
            echo = if (withEcho) echoModel else null)
        for (i in 0 until reads) {
            val t = i * frameMs / 1000.0 + phase
            val stalled = i in reply.stall
            val ref = if (stalled) 0.0 else replyRef(t, reply.refPeak)
            val heard = if (i - reply.delay in reply.stall) 0.0 else replyRef(t - reply.delay * frameMs / 1000.0, reply.refPeak)
            val e = reply.echoPeak * (heard / reply.refPeak).pow(reply.expo)
            val v = if (voice != null && i >= voiceFrom && i < voiceUntil) voice(t) else 0.0
            val mic = sqrt(e * e + 0.004 * 0.004 + v * v)
            val vad = echoVad(i, e) || (voiceVad && v >= 0.04)
            if (i < 8) { path.hold(frameAt(mic), ref.toFloat()); continue }
            if (path.process(frameAt(mic), vad, 1 + i * frameMs, ref.toFloat()).fired) return EchoOutcome(i, carry.leadMs, path.floor)
        }
        return EchoOutcome(null, 0L, path.floor)
    }

    private val voiceAt = 40                                // 2.56 s of reply before the user talks
    private val voiceReads = (3000 / frameMs).toInt()

    private fun voiceEnv(shape: Shape, rate: Double, peak: Double): (Double) -> Double = when (shape) {
        Shape.COS -> { t -> cosEnv(t, rate, peak, 0.02) }
        Shape.PLATEAU -> { t -> plateauEnv(t, rate, peak, 0.02) }
    }

    /** Fires/40 (4 rates x 10 phases), mean and worst ms from the voice onset. */
    private fun voiceSweep(reply: Reply, shape: Shape, peak: Double, withEcho: Boolean = true,
                           voiceVad: Boolean = true, leakMs: Long = BargeGate.SUSTAIN_LEAK_MS,
                           checkCarry: Boolean = true,
                           echoVad: (Int, Double) -> Boolean = { _, _ -> false }): Triple<Int, Long, Long> {
        var fired = 0; var sum = 0L; var worst = 0L
        for (rate in rates) for (p in 0 until 10) {
            val o = replayReply(reply, voiceAt + voiceReads, withEcho, p / 10.0, echoVad = echoVad,
                voice = voiceEnv(shape, rate, peak), voiceFrom = voiceAt, voiceVad = voiceVad, leakMs = leakMs)
            val at = o.fireRead ?: continue
            assertTrue("fired on the reply before the user spoke ($shape $peak, read $at)", at >= voiceAt)
            val ttf = (at - voiceAt + 1) * frameMs
            if (checkCarry) assertTrue("carry lost the onset ($shape $peak: ttf=$ttf leadMs=${o.leadMs})", o.leadMs >= ttf)
            fired++; sum += ttf; worst = maxOf(worst, ttf)
        }
        return Triple(fired, if (fired > 0) sum / fired else 0L, worst)
    }

    private val ownerBand = listOf(0.07, 0.08, 0.12, 0.17)

    @Test fun the_owners_voice_over_todays_reply_fires_every_time() {
        // His voice under playback, 0.07-0.17 peaks, VAD following it (and not the echo —
        // L2's pure-playback seconds read vadPct=0): every rate, every phase, both shapes,
        // on the speaker (Supertonic) and on a headset. Measured: mean 302-404 ms from the
        // onset, worst 832 ms.
        for (reply in listOf(supertonic, headset)) for (shape in Shape.values()) for (peak in ownerBand) {
            val (fired, mean, worst) = voiceSweep(reply, shape, peak)
            assertEquals("echo ${reply.echoPeak} $shape $peak fired $fired/40", 40, fired)
            assertTrue("echo ${reply.echoPeak} $shape $peak mean ttf $mean ms", mean <= 420L)
            assertTrue("echo ${reply.echoPeak} $shape $peak worst ttf $worst ms", worst <= 900L)
        }
    }

    @Test fun the_fixed_floor_misses_the_same_voice() {
        // The regression record: the identical inputs without the echo model.
        for (shape in Shape.values()) for (peak in listOf(0.07, 0.08))
            assertEquals("fixed floor $shape $peak", 0, voiceSweep(supertonic, shape, peak, withEcho = false).first)
        assertEquals(0, voiceSweep(supertonic, Shape.COS, 0.12, withEcho = false).first)
    }

    @Test fun the_same_voice_fired_over_the_piper_echo_bed() {
        // Why 0.7.x worked with the same gate: through the FIXED floor, a voice cresting at
        // 0.12 fires on top of the Piper-era echo (the echo lifts it over 0.10) and never on
        // top of today's. The voice did not change; the bed it stood on did.
        val overPiper = voiceSweep(piper(), Shape.COS, 0.12, withEcho = false).first
        val overToday = voiceSweep(supertonic, Shape.COS, 0.12, withEcho = false).first
        assertTrue("over the Piper bed $overPiper/40", overPiper >= 35)
        assertEquals("over today's echo", 0, overToday)
        // 0.7.3's own constants (the 96 ms leak): the same split. 0.7.3's gate, fed today's
        // voice, would miss this speaker exactly as 0.8.1 and 0.9.0 do.
        val v073 = { r: Reply, sh: Shape, pk: Double -> voiceSweep(r, sh, pk, withEcho = false, leakMs = oldLeak, checkCarry = false).first }
        // Measured: crest speech at 0.12/0.14 fires 6 and 18 of 40 over the Piper bed, 0 over
        // today's; plateau speech at 0.10-0.14 fires 17/30/37 against 5/20/24.
        var piperSum = 0; var todaySum = 0
        for (sh in Shape.values()) for (pk in listOf(0.10, 0.12, 0.14)) {
            val p = v073(piper(), sh, pk); val t = v073(supertonic, sh, pk)
            assertTrue("0.7.3 gate $sh $pk: over Piper $p, over today $t", p >= t)
            piperSum += p; todaySum += t
        }
        assertTrue("0.7.3 gate: $piperSum fires over the Piper bed vs $todaySum over today's", piperSum >= 2 * todaySum)
    }

    @Test fun silero_deaf_the_level_escape_still_catches_a_clear_voice() {
        // VAD false throughout: only the level-only escape (bar >= 0.080) can fire. Measured
        // on today's reply: plateau 0.12 / 0.17 and crest 0.17 fire 40/40, crest 0.12 20/40,
        // plateau 0.08 35/40 — where the fixed floor fires 0, 40, 8, 0 and 0.
        for (reply in listOf(supertonic, headset)) {
            assertEquals(40, voiceSweep(reply, Shape.PLATEAU, 0.12, voiceVad = false).first)
            assertEquals(40, voiceSweep(reply, Shape.PLATEAU, 0.17, voiceVad = false).first)
            assertEquals(40, voiceSweep(reply, Shape.COS, 0.17, voiceVad = false).first)
            assertTrue(voiceSweep(reply, Shape.COS, 0.12, voiceVad = false).first >= 15)
        }
        assertEquals(0, voiceSweep(supertonic, Shape.PLATEAU, 0.12, withEcho = false, voiceVad = false).first)
    }

    @Test fun never_worse_than_the_fixed_floor_over_a_loud_echo() {
        // Piper-era echo: the measured floor stays at barge_rms_min, so the voice fares
        // exactly as it did through the fixed floor.
        for (shape in Shape.values()) for (peak in ownerBand)
            assertEquals("$shape $peak", voiceSweep(piper(), shape, peak, withEcho = false).first,
                voiceSweep(piper(), shape, peak).first)
    }

    private val echoVadModes: List<(Int, Double) -> Boolean> = listOf(
        { _, _ -> false },                       // Silero hears no speech in the echo
        { i, _ -> i % 3 == 0 },                  // ... on a third of the reads
        { _, e -> e > 0.05 },                    // ... on every loud echo read (the crests)
    )

    @Test fun the_replys_own_echo_never_fires_in_two_minutes() {
        val reads = (120_000 / frameMs).toInt()
        val replies = listOf(supertonic, headset) +
            listOf(0.067, 0.081, 0.095).flatMap { pk -> listOf(-2, 0, 2, 4).flatMap { d -> listOf(0.7, 1.0, 1.4).map { piper(pk, d, it) } } }
        for (reply in replies) for (vad in echoVadModes) for (p in 0 until 3) {
            val o = replayReply(reply, reads, phase = p / 3.0, echoVad = vad)
            assertNull("echo ${reply.echoPeak} delay ${reply.delay} expo ${reply.expo} fired at read ${o.fireRead}", o.fireRead)
        }
    }

    @Test fun the_floor_is_measured_not_assumed() {
        val reads = (20_000 / frameMs).toInt()
        // Today's echo: the floor goes to its minimum, 0.030.
        assertEquals(0.030f, replayReply(supertonic, reads).floor, 1e-4f)
        assertEquals(0.030f, replayReply(headset, reads).floor, 1e-4f)
        // Piper-era echo: 1.5x its measured level is over barge_rms_min — the floor stays there.
        assertEquals(BargeGate.DEFAULT_RMS_MIN, replayReply(piper(0.081), reads).floor, 1e-6f)
        // An echo of 0.04: in between, 1.5x the block-p90 level.
        val mid = replayReply(Reply(0.10, 0.04), reads).floor
        assertTrue("floor over a 0.04 echo = $mid", mid > 0.055f && mid < 0.065f)
    }

    @Test fun a_reply_stall_does_not_lower_the_floor() {
        // 15 s of silence mid-reply (the mind is slow, the track idles), then the reply
        // resumes with its echo 128 ms AHEAD of the head: nothing learnt during the stall,
        // so the floor that resumes is the one measured over the echo.
        val stall = 200 until 200 + (15_000 / frameMs).toInt()
        for (vad in echoVadModes) {
            val o = replayReply(Reply(0.18, 0.095, delay = -2, stall = stall), stall.last + 600, echoVad = vad)
            assertNull("fired at ${o.fireRead} after the stall", o.fireRead)
        }
    }

    @Test fun vad_on_the_echo_does_not_hide_it_from_the_measurement() {
        // Silero calling the loud echo reads (or every read) speech must not lower the
        // floor under a Piper-era echo: the measurement does not consult the VAD.
        for (vad in listOf<(Int, Double) -> Boolean>({ _, _ -> true }, { _, e -> e > 0.05 })) {
            val o = replayReply(piper(), (30_000 / frameMs).toInt(), echoVad = vad)
            assertNull(o.fireRead)
            assertEquals(BargeGate.DEFAULT_RMS_MIN, o.floor, 1e-6f)
        }
    }

    @Test fun talking_through_the_learning_cannot_lock_the_floor_high() {
        // Worst case for the measurement: the user talks from the reply's first read with
        // Silero deaf (every read is learnt), crest 0.12 so nothing fires, for 3 s. The
        // floor that results is barge_rms_min at most — and it relaxes to the minimum once
        // the reply has played on its own for a few seconds.
        val model = EchoFloor()
        val talk = replayReply(supertonic, (3000 / frameMs).toInt(), voice = voiceEnv(Shape.COS, 4.0, 0.09),
            voiceFrom = 0, voiceVad = false, echoModel = model)
        assertTrue(talk.floor <= BargeGate.DEFAULT_RMS_MIN)
        val after = replayReply(supertonic, (6000 / frameMs).toInt(), echoModel = model)
        assertEquals(0.030f, after.floor, 1e-4f)
    }
}
