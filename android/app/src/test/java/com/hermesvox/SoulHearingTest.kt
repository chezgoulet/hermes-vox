package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The soul's ears: the caller's audio as a WAV, the tone tag it answers with, and what the tone
 *  changes (the voice's delivery, the vibe the mind is told). */
class SoulHearingTest {

    // ---- the tone tag ----

    @Test fun a_leading_tag_is_split_from_the_decision() {
        val (m, rest) = VoiceMood.split("{lively} Ha, that's brilliant!")
        assertEquals(VoiceMood.LIVELY, m)
        assertEquals(ErSoulTurn.Outcome.Spoken("Ha, that's brilliant!"), ErSoulTurn.parse(rest))
    }

    @Test fun the_tag_composes_with_the_beat() {
        val (m, rest) = VoiceMood.split("{ Tense }<<ESCALATE>> Okay, let me look")
        assertEquals(VoiceMood.TENSE, m)
        assertEquals(ErSoulTurn.Outcome.Beat("Okay, let me look..."), ErSoulTurn.parse(rest))
    }

    @Test fun no_tag_or_an_unknown_one_leaves_the_text_alone() {
        assertEquals(null to "Hello!", VoiceMood.split("Hello!"))
        assertEquals(null to "{sleepy} hi", VoiceMood.split("{sleepy} hi"))
        assertEquals("a brace later is content", null to "I said {calm} earlier", VoiceMood.split("I said {calm} earlier"))
        assertEquals(null to "", VoiceMood.split(null))
    }

    @Test fun a_tense_caller_gets_a_steadier_voice_not_a_mirrored_one() {
        assertTrue(VoiceMood.TENSE.speed < VoiceMood.WARM.speed)
        assertTrue(VoiceMood.LIVELY.speed > VoiceMood.WARM.speed)
        assertEquals("warm is today's delivery exactly", 1.0f, VoiceMood.WARM.speed)
    }

    @Test fun the_heard_mood_becomes_the_vibe_the_mind_is_told() {
        val e = ErDrift.epilogue(emptyList(), ErDrift.Vibe(VoiceMood.TENSE.vibeMood, VoiceMood.TENSE.vibeEnergy))
        assertTrue(e.contains("mood=frustrated"))
    }

    // ---- the directive ----

    @Test fun the_heard_directive_asks_for_the_tone_first() {
        val d = ErSoulTurn.directive("hey", heard = true)
        assertTrue(d.startsWith("You can hear the caller"))
        for (t in listOf("{calm}", "{warm}", "{lively}", "{tense}")) assertTrue(d.contains(t))
        assertFalse("text-only turns never ask for a tag", ErSoulTurn.directive("hey").contains("{calm}"))
    }

    // ---- the WAV ----

    @Test fun the_wav_is_16k_mono_pcm16_and_keeps_only_the_tail() {
        val sr = 16000
        val samples = FloatArray(sr * 12) { if (it < sr * 12 - 10) 0f else 0.5f }   // 12 s, loud tail
        val wav = SoulAudio.wav(samples, sr)!!
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(22).toInt())          // mono
        assertEquals(sr, b.getInt(24))                   // 16 kHz
        assertEquals(16, b.getShort(34).toInt())         // 16-bit
        val data = b.getInt(40)
        assertEquals("capped at MAX_SECONDS", sr * SoulAudio.MAX_SECONDS * 2, data)
        assertEquals("the tail is what is kept", (0.5f * 32767f).toInt().toShort(), b.getShort(44 + data - 2))
    }

    @Test fun too_little_voice_is_not_sent() {
        assertNull(SoulAudio.wav(FloatArray(1600), 16000))   // 0.1 s
        assertNull(SoulAudio.wav(null))
    }
}
