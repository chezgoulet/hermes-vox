package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #285: the path from "the repo has a skill" to "the agent has it" is one tap or one paste. */
class AppSkillTest {

    @Test fun aReleaseInstallsItsOwnTagsSkillANightlyTesting() {
        assertEquals("https://raw.githubusercontent.com/chezgoulet/hermes-vox/v0.9.1/skills/hermes-vox-app/SKILL.md", AppSkill.url("0.9.1"))
        assertEquals("https://raw.githubusercontent.com/chezgoulet/hermes-vox/testing/skills/hermes-vox-app/SKILL.md", AppSkill.url("0.9.0.1-nightly-ca0e66f"))
        assertEquals("https://raw.githubusercontent.com/chezgoulet/hermes-vox/testing/skills/hermes-vox-app/SKILL.md", AppSkill.url(""))
        // Never main: on the device, main had no skill (it arrives at the next release).
        assertTrue(!AppSkill.url("0.9.1").contains("/main/"))
        assertEquals("hermes skills install ${AppSkill.url("0.9.1")} --yes", AppSkill.command("0.9.1"))
    }

    @Test fun aNamedProfileComesFirst() {
        assertEquals("hermes -p work skills install ${AppSkill.url("0.9.1")} --yes", AppSkill.command("0.9.1", "work"))
        assertEquals("work", AppSkill.profileOf("https://gw.example/p/work"))
        assertEquals("work", AppSkill.profileOf("https://gw.example/p/work/"))
        assertEquals("", AppSkill.profileOf("https://gw.example"))
    }

    @Test fun theTurnSaysWhatItRuns() {
        assertTrue(AppSkill.installTurn("0.9.1", "work").contains(AppSkill.command("0.9.1", "work")))
    }

    @Test fun theNameIsTheSkillsOwn() {
        val skill = File("../../skills/hermes-vox-app/SKILL.md").readText()
        assertTrue(skill.contains("name: ${AppSkill.NAME}"))
    }

    @Test fun theSkillCarriesTheOfferKeyTheAppStores() {
        // #285: the skill's settings map must name the preference the app actually writes, or
        // an agent reading it points the user at a key nothing stores.
        val skill = File("../../skills/hermes-vox-app/SKILL.md").readText()
        assertTrue(skill.contains("`${AppSkillInstall.PREF_OFFERED}`"))
    }
}
