package com.hermesvox.wake

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolmentTest {

    private fun tone(seconds: Double, amp: Int): ShortArray =
        ShortArray((16_000 * seconds).toInt()) { i -> (amp * sin(2 * PI * 220 * i / 16_000)).toInt().toShort() }

    @Test
    fun the_script_asks_for_5_to_10_distinct_takes() {
        val p = EnrolmentPrompts.prompts
        assertTrue(p.size in 5..10)
        assertEquals(p.size, p.toSet().size)
        // openWakeWord's verifier needs >= 3 positives; D6 asks for >= 5.
        assertTrue(EnrolmentPrompts.MIN_TAKES in 5..p.size)
    }

    @Test
    fun ready_only_from_the_minimum_take_count() {
        assertFalse(EnrolmentPrompts.isReady(EnrolmentPrompts.MIN_TAKES - 1))
        assertTrue(EnrolmentPrompts.isReady(EnrolmentPrompts.MIN_TAKES))
    }

    @Test
    fun a_clear_take_measures_and_passes() {
        val m = TakeMeasure.of(tone(1.0, 8_000))
        assertEquals(1000, m.durationMs)
        // A sine at amplitude A has RMS A/sqrt(2): 8000 -> about -15.3 dBFS.
        assertEquals(-15.3, m.rmsDbfs, 0.2)
        assertEquals(-12.2, m.peakDbfs, 0.2)
        assertNull(m.problem)
    }

    @Test
    fun silence_is_too_quiet() {
        val m = TakeMeasure.of(ShortArray(16_000))
        assertEquals(Double.NEGATIVE_INFINITY, m.rmsDbfs, 0.0)
        assertTrue(m.problem!!.startsWith("Too quiet"))
    }

    @Test
    fun a_clipped_take_is_too_loud() {
        // Driven 2x past full scale, then flattened the way a saturated ADC would.
        val clipped = ShortArray(16_000) { i ->
            (65_000 * sin(2 * PI * 220 * i / 16_000)).toInt().coerceIn(-32_767, 32_767).toShort()
        }
        val m = TakeMeasure.of(clipped)
        assertTrue(m.clippedFraction > TakeMeasure.MAX_CLIPPED)
        assertTrue(m.problem!!.startsWith("Too loud"))
    }

    @Test
    fun duration_bounds() {
        assertTrue(TakeMeasure.of(tone(0.3, 8_000)).problem!!.startsWith("Too short"))
        assertTrue(TakeMeasure.of(tone(4.5, 8_000)).problem!!.startsWith("Too long"))
        assertNull(TakeMeasure.of(tone(0.5, 8_000)).problem)
        assertNull(TakeMeasure.of(tone(4.0, 8_000)).problem)
    }

    @Test
    fun an_empty_take_does_not_crash() {
        val m = TakeMeasure.of(ShortArray(0))
        assertEquals(0, m.durationMs)
        assertFalse(m.ok)
    }

    @Test
    fun the_steps_are_the_phrase_takes_then_free_speech_then_room() {
        val steps = EnrolmentPrompts.steps
        assertEquals(EnrolmentPrompts.takes + 2, steps.size)
        assertTrue(steps.dropLast(2).all { it.kind == TakeKind.PHRASE })
        assertEquals(steps.dropLast(2).map { it.index }, (0 until EnrolmentPrompts.takes).toList())
        assertEquals(TakeKind.SPEECH, steps[steps.size - 2].kind)
        assertEquals(TakeKind.ROOM, steps.last().kind)
    }

    @Test
    fun the_long_takes_meet_the_verifier_floors() {
        // Upstream's custom verifier: >= ~10 s of the speaker saying other things,
        // ~5 s of background. Both are asked for at 15 s.
        assertEquals(10_000, TakeKind.SPEECH.minMs)
        assertEquals(5_000, TakeKind.ROOM.minMs)
        assertEquals(15_000, TakeKind.SPEECH.maxMs)
        assertEquals(15_000, TakeKind.ROOM.maxMs)
    }

    @Test
    fun free_speech_duration_and_level() {
        assertTrue(TakeMeasure.of(tone(9.0, 8_000)).problemFor(TakeKind.SPEECH)!!.startsWith("Too short"))
        assertNull(TakeMeasure.of(tone(10.0, 8_000)).problemFor(TakeKind.SPEECH))
        assertNull(TakeMeasure.of(tone(15.0, 8_000)).problemFor(TakeKind.SPEECH))
        assertTrue(TakeMeasure.of(tone(16.0, 8_000)).problemFor(TakeKind.SPEECH)!!.startsWith("Too long"))
        assertTrue(TakeMeasure.of(tone(12.0, 50)).problemFor(TakeKind.SPEECH)!!.startsWith("Too quiet"))
    }

    @Test
    fun a_quiet_room_passes_where_a_phrase_take_would_be_too_quiet() {
        // About -60 dBFS: a quiet room, far below a voice.
        val quiet = TakeMeasure.of(tone(15.0, 45))
        assertNull(quiet.problemFor(TakeKind.ROOM))
        assertTrue(quiet.problemFor(TakeKind.PHRASE)!!.startsWith("Too long"))
        assertTrue(TakeMeasure.of(tone(2.0, 45)).problemFor(TakeKind.PHRASE)!!.startsWith("Too quiet"))
        assertTrue(TakeMeasure.of(tone(4.0, 45)).problemFor(TakeKind.ROOM)!!.startsWith("Too short"))
        assertTrue(TakeMeasure.of(ShortArray(16_000 * 15)).problemFor(TakeKind.ROOM)!!.startsWith("Silent"))
        assertTrue(TakeMeasure.of(tone(15.0, 8_000)).problemFor(TakeKind.ROOM)!!.startsWith("Too loud for a quiet take"))
    }

    @Test
    fun the_inert_engine_never_fires_and_wants_80ms_frames() {
        assertEquals(1280, WakeWordEngine.FRAME_SAMPLES)
        assertFalse(InertWakeWordEngine.available)
        repeat(100) { assertNull(InertWakeWordEngine.process(ShortArray(1280) { 20_000 })) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun the_inert_engine_rejects_a_wrong_sized_frame() {
        InertWakeWordEngine.process(ShortArray(1000))
    }
}
