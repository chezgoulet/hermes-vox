package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The per-profile pass: a profile carries its own voice, model and look, a profile that has no
 * value of its own falls back to the device-wide one, and an existing profile inherits the
 * device-wide values exactly once.
 *
 * The device-wide store is a plain map here: [ProfileScope] takes a reader, not an Android
 * `SharedPreferences`, so the rules are proved off-device. [ScopedPrefs] is the three typed
 * reads and one typed write that connect the two on the phone.
 */
class ProfileScopeTest {

    /** The live `hv` prefs, as the phone would hold them. */
    private val live = linkedMapOf(
        "tts" to "supertonic",
        "voice" to "bright",
        "tts_speaker_supertonic" to "4",
        "tts_speaker_piper-lessac" to "739",
        VisualStyle.KEY_CATEGORY to "ember",
        VisualStyle.KEY_ENERGY to "1.2",
        VisualStyle.KEY_GLOW to "0.9",
        VisualStyle.KEY_DENSITY to "0.8",
        VisualStyle.KEY_SPEED to "1.1",
        VisualStyle.KEY_TRAIL to "0.2",
        VisualStyle.KEY_HUE to "15.0",
        VisualStyle.KEY_CYCLE_ALL to "true",
        AvatarView.KEY_SHAPE_SPEAKING to "soundwave",
        AvatarView.KEY_SHAPE_LISTENING to "eye",
        AvatarView.KEY_SHAPE_THINKING to "radar",
        "particles_theme" to "aura",
        "particles_cycle" to "false",
        // Not scoped: the phone's own settings, never a profile's.
        "theme" to "dark",
        "layout_mode" to "presence",
        "keep_screen_on" to "true",
    )

    private fun reader(): (ProfileScope.Scoped) -> String? = { k -> live[k.key] }

    /** A profile as an older build saved it: connection only, no values of its own. */
    private fun legacy(name: String, url: String = "https://gw.example/p/$name") =
        Profiles.Profile(name, url, "enc:$name", model = "hermes-agent", agentName = name)

    // ---- the migration --------------------------------------------------------------------

    @Test fun firstLoadGivesEveryProfileTheDeviceWideValuesOnce() {
        val list = listOf(legacy("home"), legacy("work"))
        val migrated = ProfileScope.migrate(list, reader())

        for (p in migrated) {
            assertEquals("home's own voice", "bright", p.own["voice"])
            assertEquals("Supertonic", "supertonic", p.own["tts"])
            assertEquals("registration is per engine", "4", p.own["tts_speaker_supertonic"])
            assertEquals("the look is inherited too", "ember", p.own[VisualStyle.KEY_CATEGORY])
            assertEquals("a float keeps its value", "1.2", p.own[VisualStyle.KEY_ENERGY])
            assertEquals("a flag keeps its value", "true", p.own[VisualStyle.KEY_CYCLE_ALL])
            assertNull("a scoped key that was never set stays unset", p.own["er_presence_voice"])
        }
        // Nothing about the phone became a profile's.
        for (p in migrated) for (k in listOf("theme", "layout_mode", "keep_screen_on", "er_presence_voice"))
            assertNull("$k is the phone's, not a profile's", p.own[k])
        // The migration only adds: the connection is exactly as it was.
        assertEquals(list.map { it.url }, migrated.map { it.url })
        assertEquals(list.map { it.keyEnc }, migrated.map { it.keyEnc })
    }

    @Test fun migrationDoesNotOverwriteAProfileThatAlreadyHasItsOwn() {
        val mine = legacy("work").copy(own = mapOf("voice" to "deep", VisualStyle.KEY_CATEGORY to "frost"))
        val migrated = ProfileScope.migrate(listOf(mine), reader())
        assertEquals("deep", migrated[0].own["voice"])
        assertEquals("frost", migrated[0].own[VisualStyle.KEY_CATEGORY])
        // And a profile that owns *something* is left entirely alone: inheriting again would
        // undo an edit, which is the failure this rule exists to prevent.
        assertNull(migrated[0].own["tts"])
    }

    @Test fun anInheritedValueIsTheProfilesOwnAfterwards() {
        // The migration runs; the device-wide voice then changes. The profile keeps its own.
        val migrated = ProfileScope.migrate(listOf(legacy("home")), reader())
        val after = migrated.map { if (it.own.isEmpty()) ProfileScope.adopt(it, { k -> "changed" }) else it }
        assertEquals("bright", after[0].own["voice"])
    }

    // ---- the fallback ---------------------------------------------------------------------

    @Test fun aValueAProfileDoesNotOwnIsTheDeviceWideOne() {
        val bare = legacy("work")
        val adopted = ProfileScope.adopt(bare, reader())
        assertEquals("it inherits what the phone says", "bright", adopted.own["voice"])
        // …and once adopted it is its own: a later device-wide change does not move it.
        val later = ProfileScope.adopt(adopted, { k -> "changed" })
        assertEquals("bright", later.own["voice"])
    }

    @Test fun aKeyThePhoneNeverSetIsNotWrittenAtAll() {
        val quiet = live.filterKeys { it != "tts" && it != "particles_theme" }
        val adopted = ProfileScope.adopt(legacy("work"), { k -> quiet[k.key] })
        assertFalse("a key nobody set has no value to inherit", adopted.own.containsKey("tts"))
        val writes = ProfileScope.switchWrites(adopted, { k -> quiet[k.key] })
        assertFalse("a switch must not write a key the phone never set", writes.containsKey("tts"))
    }

    // ---- what a switch does ---------------------------------------------------------------

    @Test
    fun aSwitchWritesTheVoiceTheModelAndTheLook() {
        val work = legacy("work").copy(
            model = "deepseek-v4-flash", provider = "openrouter", scope = "phone",
            own = mapOf(
                "tts" to "piper", "voice" to "deep", "tts_speaker_piper-lessac" to "750",
                VisualStyle.KEY_CATEGORY to "ink", VisualStyle.KEY_ENERGY to "0.7",
                VisualStyle.KEY_CYCLE_ALL to "false", AvatarView.KEY_SHAPE_THINKING to "radar",
            ),
        )
        val w = ProfileScope.switchWrites(work, reader())

        // the connection — that *is* the profile
        assertEquals("https://gw.example/p/work", w["url"])
        assertEquals("enc:work", w["key"])
        assertEquals("phone", w["session_scope"])
        // the model
        assertEquals("deepseek-v4-flash", w["model"])
        assertEquals("openrouter", w["provider"])
        // the voice
        assertEquals("piper", w["tts"])
        assertEquals("deep", w["voice"])
        assertEquals("750", w["tts_speaker_piper-lessac"])
        // the look
        assertEquals("ink", w[VisualStyle.KEY_CATEGORY])
        assertEquals("0.7", w[VisualStyle.KEY_ENERGY])
        assertEquals("false", w[VisualStyle.KEY_CYCLE_ALL])
        // The profile's own values win over the device-wide ones for every scoped key…
        assertEquals("piper", ProfileScope.capture { k -> w[k.key] }["tts"])
        // …and nothing of the phone's is written by a switch.
        for (k in listOf("theme", "layout_mode", "keep_screen_on", "er_presence_voice"))
            assertNull("a switch must not touch $k", w[k])
    }

    @Test fun aSwitchBackRestoresWhatWasChangedWhileAway() {
        // home owns the device-wide bright Supertonic voice and an ember look; work owns a deep
        // Piper voice and took the device-wide look (frost, here) for its own.
        val home = ProfileScope.adopt(legacy("home"), reader())
        val work = ProfileScope.adopt(
            legacy("work").copy(own = mapOf("tts" to "piper", "voice" to "deep")),
            { k -> if (k.group == ProfileScope.Group.LOOK) "frost" else live[k.key] },
        )
        assertEquals("ember", home.own[VisualStyle.KEY_CATEGORY])
        assertEquals("frost", work.own[VisualStyle.KEY_CATEGORY])

        // Switch to work: the live prefs become work's.
        live.putAll(ProfileScope.switchWrites(work, reader()))
        assertEquals("frost", live[VisualStyle.KEY_CATEGORY])
        assertEquals("piper", live["tts"])

        // On work, change the register; the change is work's, recorded as we leave it.
        live["voice"] = "bright"
        val workLater = work.copy(own = ProfileScope.capture(reader()))

        // Switch back to home: home's own values are what the prefs get.
        val back = ProfileScope.switchWrites(home, reader())
        assertEquals("ember", back[VisualStyle.KEY_CATEGORY])
        assertEquals("supertonic", back["tts"])
        // …and work kept the edit, so returning to it returns to what was chosen there.
        assertEquals("bright", workLater.own["voice"])
        assertEquals("piper", workLater.own["tts"])
    }

    // ---- the switcher's line --------------------------------------------------------------

    @Test fun theSwitcherLineSaysWhoItIsWhatItThinksWithAndHowItSpeaks() {
        val p = legacy("work", url = "https://gw.example/p/work").copy(
            model = "deepseek-v4-flash", agentName = "Hermes",
            own = mapOf("tts" to "supertonic", "voice" to "bright"),
        )
        val line = ProfileScope.detail(p)
        assertTrue(line, line.contains("Hermes"))
        assertTrue(line, line.contains(ModelChoices.displayName("deepseek-v4-flash")))
        assertTrue(line, line.contains("Supertonic"))
        assertTrue(line, line.contains("bright"))
        // An agent with no name of its own is named by its model, once — not twice.
        val unnamed = ProfileScope.detail(p.copy(agentName = ""))
        assertTrue(unnamed, unnamed.startsWith(ModelChoices.displayName("deepseek-v4-flash") + " · "))
        assertTrue(unnamed, unnamed.contains("Supertonic"))
    }

    @Test fun theVoiceLineReadsTheRegisterTheWaySettingsDoes() {
        assertEquals("System voice · natural", ProfileScope.voiceLine(mapOf("tts" to "system", "voice" to "system")))
        assertEquals("Piper · deep", ProfileScope.voiceLine(mapOf("tts" to "piper", "voice" to "deep")))
        assertEquals("Supertonic", ProfileScope.voiceLine(mapOf("tts" to "supertonic")))
        assertEquals("", ProfileScope.voiceLine(emptyMap()))
    }

    // ---- the record itself ----------------------------------------------------------------

    @Test fun theRecordCarriesItsOwnValuesAcrossASave() {
        val p = legacy("home").copy(own = mapOf("voice" to "deep", VisualStyle.KEY_ENERGY to "1.4"))
        val back = Profiles.decode(Profiles.encode(listOf(p)))
        assertEquals(listOf(p), back)
        assertEquals("deep", back[0].own["voice"])
    }

    @Test fun aRecordWrittenBeforeThisPassStillReads() {
        // The shape an older build wrote: no `own` at all.
        val old = """[{"name":"home","url":"https://gw.example","key":"enc:a","model":"m","agent":"Hermes","scope":"","provider":""}]"""
        val p = Profiles.decode(old).single()
        assertEquals("home", p.name)
        assertTrue("an old record has no values of its own — that is the fallback", p.own.isEmpty())
        // …and adopting it gives it the device-wide ones without touching the connection.
        val adopted = ProfileScope.adopt(p, reader())
        assertEquals("bright", adopted.own["voice"])
        assertEquals("https://gw.example", adopted.url)
        assertEquals("enc:a", adopted.keyEnc)
    }

    // ---- the table itself -----------------------------------------------------------------

    @Test fun everyScopedKeyIsARealPrefAndTheModelIsTheRecords() {
        assertEquals(ProfileScope.KEYS.size, ProfileScope.KEYS.map { it.key }.toSet().size)
        // The model and provider are fields on the record, written with the connection, so a
        // switch applies them without a second copy that could drift.
        assertTrue(Profiles.toPrefs(legacy("home")).containsKey(Profiles.K_MODEL))
        assertTrue(Profiles.toPrefs(legacy("home")).containsKey(Profiles.K_PROVIDER))
        assertEquals(listOf("tts", "voice", "tts_speaker_piper-lessac", "tts_speaker_supertonic"),
            ProfileScope.keys(ProfileScope.Group.VOICE))
        assertTrue(ProfileScope.keys(ProfileScope.Group.LOOK).contains(VisualStyle.KEY_CATEGORY))

        // AvatarView's keys are the ones its own look reader uses. They are `const val`s, so
        // this file never loads an Android class — but the literals must still be the same ones.
        val avatar = File("src/main/java/com/hermesvox/AvatarView.kt").readText()
        for (k in listOf("visual_shape_speaking", "visual_shape_listening", "visual_shape_thinking"))
            assertTrue("$k is not a key AvatarView reads", avatar.contains("\"$k\""))
    }

    /** The rows that say which profile they apply to are the rows that write a scoped key. */
    @Test fun theSettingsRowsThatSayTheyAreTheProfilesAreTheScopedOnes() {
        val scoped = SettingsHints.SCOPED
        for (row in listOf("row_tts", "row_tts_speaker", "row_voice", "row_agent_model",
            "row_visual_category", "row_visual_energy", "row_visual_hue", "row_particle_theme"))
            assertTrue("$row is a profile's setting and must say so", row in scoped)
        for (row in listOf("row_theme", "row_layout", "row_keep_screen_on", "row_mic_vad",
            "row_sound_cues", "row_er_presence", "row_grp_alpha"))
            assertFalse("$row is the phone's, not a profile's", row in scoped)

        assertEquals("this profile", SettingsHints.scope("row_voice", ""))
        assertEquals("profile home", SettingsHints.scope("row_voice", "home"))
        assertNull("a device-wide row says nothing", SettingsHints.scope("row_theme", "home"))
        // One line: the row's own statement, then whose it is.
        assertEquals("The speaking register: natural, bright or deep · profile home",
            SettingsHints.line("row_voice", "home"))
        assertEquals(SettingsHints.HINTS.getValue("row_theme"), SettingsHints.line("row_theme", "home"))
    }
}
