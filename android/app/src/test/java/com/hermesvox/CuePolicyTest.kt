package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the cue policy matrix (#156, D7): ringer × DND × media volume × system
 * haptics × a speaking TTS, with the two user gates (on/off, the silent opt-in) on top.
 *
 * The invariant the whole feature rests on is asserted over EVERY combination: a cue
 * that would be audible is never emitted while TTS speaks, and a silent ringer stays
 * silent unless the user opted in. The individual tests then pin the interesting
 * cells, so a future "simplification" of the order in [CuePolicy.decide] fails here
 * rather than in a pocket.
 */
class CuePolicyTest {

    private fun world(
        enabled: Boolean = true,
        silentOptIn: Boolean = false,
        levelPercent: Int = 70,
        mediaVolume: Int = 9,
        ringerMode: Int = CuePolicy.RINGER_NORMAL,
        dnd: Boolean = false,
        hapticsEnabled: Boolean = true,
        ttsSpeaking: Boolean = false,
        preview: Boolean = false,
    ) = CuePolicy.World(
        enabled, silentOptIn, levelPercent, mediaVolume, ringerMode, dnd,
        hapticsEnabled, ttsSpeaking, preview)

    private fun decide(w: CuePolicy.World) = CuePolicy.decide(w)

    // ---- the rule that can never be broken ------------------------------------------

    @Test fun never_audible_while_tts_speaks() {
        for (ringer in 0..2) for (dnd in booleanArrayOf(false, true))
        for (vol in intArrayOf(0, 1, 9)) for (hap in booleanArrayOf(false, true))
        for (optIn in booleanArrayOf(false, true)) for (preview in booleanArrayOf(false, true)) {
            val d = decide(world(ringerMode = ringer, dnd = dnd, mediaVolume = vol,
                hapticsEnabled = hap, silentOptIn = optIn, preview = preview, ttsSpeaking = true))
            assertEquals("ringer=$ringer dnd=$dnd vol=$vol hap=$hap optIn=$optIn preview=$preview",
                CuePolicy.Action.SILENT, d.action)
            assertEquals("tts", d.reason)
            assertEquals(0f, d.volume, 0f)
        }
    }

    // ---- the full matrix invariants --------------------------------------------------

    @Test fun every_combination_obeys_its_gate() {
        for (ringer in 0..2) for (dnd in booleanArrayOf(false, true))
        for (vol in intArrayOf(0, 5, 15)) for (hap in booleanArrayOf(false, true))
        for (tts in booleanArrayOf(false, true)) {
            val d = decide(world(ringerMode = ringer, dnd = dnd, mediaVolume = vol,
                hapticsEnabled = hap, ttsSpeaking = tts))
            val tag = "ringer=$ringer dnd=$dnd vol=$vol hap=$hap tts=$tts"
            when {
                // TTS always wins.
                tts -> assertEquals(tag, CuePolicy.Action.SILENT, d.action)
                // DND outranks the ringer: the user asked for no interruptions.
                dnd -> assertEquals(tag, CuePolicy.Action.SILENT, d.action)
                // A silent ringer with no opt-in is silent, haptics or not.
                ringer == CuePolicy.RINGER_SILENT -> assertEquals(tag, CuePolicy.Action.SILENT, d.action)
                // Vibrate-only is felt, never heard.
                ringer == CuePolicy.RINGER_VIBRATE -> {
                    assertEquals(tag, if (hap) CuePolicy.Action.HAPTIC else CuePolicy.Action.SILENT, d.action)
                }
                // Normal ringer: audible while the media stream can carry it.
                vol > 0 -> assertEquals(tag, CuePolicy.Action.AUDIO, d.action)
                // Otherwise the being stays legible by touch, if the phone can feel.
                else -> assertEquals(tag, if (hap) CuePolicy.Action.HAPTIC else CuePolicy.Action.SILENT, d.action)
            }
            if (d.action == CuePolicy.Action.AUDIO) {
                assertTrue("$tag volume=${d.volume}", d.volume > 0f && d.volume <= 1f)
            } else {
                assertEquals("$tag non-audio volume", 0f, d.volume, 0f)
            }
        }
    }

    // ---- the cells worth naming ------------------------------------------------------

    @Test fun sound_cues_are_on_by_default_for_a_new_install() {
        assertTrue(CuePolicy.DEFAULT_ENABLED)
        assertEquals(CuePolicy.Action.AUDIO, decide(CuePolicy.World()).action)
        assertEquals(0.70f, decide(CuePolicy.World()).volume, 0.001f)
    }

    @Test fun toggle_off_is_silent_but_preview_is_not() {
        assertEquals(CuePolicy.Action.SILENT, decide(world(enabled = false)).action)
        assertEquals("off", decide(world(enabled = false)).reason)
        // The user tapped preview on purpose: it is the ONE rule a preview bypasses.
        assertEquals(CuePolicy.Action.AUDIO, decide(world(enabled = false, preview = true)).action)
        assertEquals("preview", decide(world(enabled = false, preview = true)).reason)
    }

    @Test fun dnd_silences_even_the_vibrate_ringer() {
        val d = decide(world(ringerMode = CuePolicy.RINGER_VIBRATE, dnd = true))
        assertEquals(CuePolicy.Action.SILENT, d.action)
        assertEquals("dnd", d.reason)
    }

    @Test fun silent_ringer_stays_silent_unless_the_user_opts_in() {
        assertEquals(CuePolicy.Action.SILENT, decide(world(ringerMode = CuePolicy.RINGER_SILENT)).action)
        // Opted in: heard iff the media stream can carry it.
        assertEquals(CuePolicy.Action.AUDIO,
            decide(world(ringerMode = CuePolicy.RINGER_SILENT, silentOptIn = true)).action)
        // Opted in on a phone that is also at zero media volume: felt, not heard.
        assertEquals(CuePolicy.Action.HAPTIC,
            decide(world(ringerMode = CuePolicy.RINGER_SILENT, silentOptIn = true, mediaVolume = 0)).action)
    }

    @Test fun vibrate_ringer_is_felt_not_heard() {
        val d = decide(world(ringerMode = CuePolicy.RINGER_VIBRATE))
        assertEquals(CuePolicy.Action.HAPTIC, d.action)
        assertEquals("vibrate-haptic", d.reason)
        // ...and it is silent when the system haptics are switched off.
        assertEquals(CuePolicy.Action.SILENT,
            decide(world(ringerMode = CuePolicy.RINGER_VIBRATE, hapticsEnabled = false)).action)
    }

    @Test fun zero_media_volume_falls_back_to_haptics() {
        val d = decide(world(mediaVolume = 0))
        assertEquals(CuePolicy.Action.HAPTIC, d.action)
        assertEquals("inaudible-haptic", d.reason)
        assertEquals(CuePolicy.Action.SILENT, decide(world(mediaVolume = 0, hapticsEnabled = false)).action)
    }

    @Test fun level_zero_is_a_mute_and_falls_back_to_haptics() {
        assertEquals(CuePolicy.Action.HAPTIC, decide(world(levelPercent = 0)).action)
        assertEquals(CuePolicy.Action.SILENT, decide(world(levelPercent = 0, hapticsEnabled = false)).action)
    }

    @Test fun level_is_the_audio_volume_and_is_clamped() {
        assertEquals(0.30f, decide(world(levelPercent = 30)).volume, 0.001f)
        assertEquals(1.0f, decide(world(levelPercent = 250)).volume, 0.001f)
        assertEquals(0.0f, decide(world(levelPercent = -20)).volume, 0.001f)
    }
}
