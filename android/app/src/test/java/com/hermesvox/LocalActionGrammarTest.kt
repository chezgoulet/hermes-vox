package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 (#421 step 2): the deterministic grammar for the four actions that must be instant.
 *
 * The properties under test are the ones that make a grammar safe to put in front of the mind:
 *
 *  - a command it recognises is recognised EXACTLY (the whole utterance, not a fragment);
 *  - a question about the same words is not a command ("what does do not disturb do" must reach
 *    the mind, not toggle the phone);
 *  - a compound or unknown sentence falls through, and falling through is today's behaviour;
 *  - it is English only, and another language is not half-parsed.
 */
class LocalActionGrammarTest {

    private fun parse(t: String) = LocalActionGrammar.parse(t)

    // ---- timer ----

    @Test fun timers_in_every_common_shape() {
        val five = LocalActionGrammar.Intent.Timer(5 * 60_000L)
        for (t in listOf(
            "set a timer for 5 minutes",
            "set a timer for five minutes",
            "timer 5 minutes",
            "timer for 5 minutes",
            "start a timer for 5 minutes",
            "5 minute timer",
            "set timer for 5 minutes please",
        )) assertEquals("'$t'", five, parse(t))

        assertEquals(LocalActionGrammar.Intent.Timer(90_000L), parse("set a timer for 90 seconds"))
        assertEquals(LocalActionGrammar.Intent.Timer(90_000L), parse("set a timer for ninety seconds"))
        assertEquals(LocalActionGrammar.Intent.Timer(3_600_000L), parse("timer for an hour"))
        assertEquals(LocalActionGrammar.Intent.Timer(1_800_000L), parse("set a timer for half an hour"))
        assertEquals(LocalActionGrammar.Intent.Timer(3_900_000L),
            parse("set a timer for 1 hour and 5 minutes"))
    }

    @Test fun a_timer_is_bounded_and_refuses_what_it_cannot_time() {
        assertNull("nothing at all", parse("set a timer"))
        assertNull("a year is not a timer", parse("set a timer for 365 days"))
        assertNull("no unit, no timer", parse("set a timer for 5"))
    }

    // ---- alarm ----

    @Test fun alarms_at_a_wall_clock() {
        assertEquals(LocalActionGrammar.Intent.Alarm(7, 30), parse("set an alarm for 7:30"))
        assertEquals(LocalActionGrammar.Intent.Alarm(19, 30), parse("set an alarm for 7:30 pm"))
        assertEquals(LocalActionGrammar.Intent.Alarm(7, 30), parse("set an alarm for 7:30 am"))
        assertEquals(LocalActionGrammar.Intent.Alarm(7, 0), parse("set an alarm for 7"))
        assertEquals(LocalActionGrammar.Intent.Alarm(6, 15), parse("wake me up at 6:15"))
        assertEquals(LocalActionGrammar.Intent.Alarm(0, 30), parse("set an alarm for 12:30 am"))
        assertEquals(LocalActionGrammar.Intent.Alarm(12, 0), parse("alarm for 12 pm"))
        assertEquals(LocalActionGrammar.Intent.Alarm(19, 5), parse("set an alarm for 19:05"))
    }

    @Test fun a_time_it_cannot_read_is_not_an_alarm() {
        assertNull(parse("set an alarm"))
        assertNull(parse("set an alarm for 25:00"))
        assertNull(parse("set an alarm for 7:75"))
        assertNull(parse("set an alarm for sometime"))
    }

    // ---- do not disturb ----

    @Test fun dnd_on_and_off_in_the_shapes_people_say() {
        for (t in listOf("turn on do not disturb", "do not disturb on", "enable dnd", "turn on dnd"))
            assertEquals("'$t'", LocalActionGrammar.Intent.Dnd(true), parse(t))
        for (t in listOf("turn off do not disturb", "do not disturb off", "disable dnd", "turn off dnd"))
            assertEquals("'$t'", LocalActionGrammar.Intent.Dnd(false), parse(t))
    }

    @Test fun a_QUESTION_about_dnd_is_not_a_command() {
        // The exact failure a regex-shaped grammar invites, pinned: this must reach the mind.
        for (t in listOf(
            "what does do not disturb do",
            "is do not disturb on",
            "do not disturb until seven",
            "turn down the volume and turn on do not disturb",
        )) assertNull("'$t' must fall through to the mind", parse(t))
    }

    // ---- volume ----

    @Test fun volume_steps_and_levels() {
        assertEquals(LocalActionGrammar.Intent.VolumeStep(1), parse("turn the volume up"))
        assertEquals(LocalActionGrammar.Intent.VolumeStep(1), parse("louder"))
        assertEquals(LocalActionGrammar.Intent.VolumeStep(1), parse("turn it up"))
        assertEquals(LocalActionGrammar.Intent.VolumeStep(-1), parse("volume down"))
        assertEquals(LocalActionGrammar.Intent.VolumeStep(-1), parse("quieter"))
        assertEquals(LocalActionGrammar.Intent.SetVolume(40), parse("set the volume to 40"))
        assertEquals(LocalActionGrammar.Intent.SetVolume(40), parse("volume 40 percent"))
        assertEquals(LocalActionGrammar.Intent.SetVolume(0), parse("volume to 0"))
    }

    @Test fun a_level_it_cannot_set_falls_through() {
        assertNull(parse("volume to 140"))
        assertNull(parse("set the volume"))
        assertNull(parse("volume of the room is too high"))
    }

    // ---- the whole utterance, and the language ----

    @Test fun a_compound_sentence_is_the_minds_not_the_phones() {
        for (t in listOf(
            "set a timer for 5 minutes and then email Bob",
            "remind me to set a timer for 5 minutes when I get home",
        )) assertNull("'$t' must reach the mind", parse(t))
    }

    @Test fun another_language_is_not_half_parsed() {
        // English only for now (the brief). A letter outside A-Z is enough to fall through, so a
        // French "règle un minuteur" never becomes a timer the caller did not ask for.
        assertNull(parse("règle un minuteur de 5 minutes"))
        assertNull(parse("setz einen timer für 5 minuten"))
        assertNull(parse("タイマーを5分にセット"))
        assertTrue(LocalActionGrammar.english("set a timer for 5 minutes"))
        assertTrue(!LocalActionGrammar.english("règle un minuteur"))
    }

    @Test fun nothing_is_a_command() {
        for (t in listOf("", "   ", "what's the weather", "thanks", "stop")) {
            assertNull("'$t'", parse(t))
        }
        assertNull(LocalActionGrammar.parse(null))
    }

    @Test fun every_intent_names_its_kind_for_the_log() {
        assertEquals("timer", LocalActionGrammar.Intent.Timer(60_000).kind)
        assertEquals("alarm", LocalActionGrammar.Intent.Alarm(7, 0).kind)
        assertEquals("dnd", LocalActionGrammar.Intent.Dnd(true).kind)
        assertEquals("volume", LocalActionGrammar.Intent.VolumeStep(1).kind)
        assertEquals("volume", LocalActionGrammar.Intent.SetVolume(10).kind)
    }
}
