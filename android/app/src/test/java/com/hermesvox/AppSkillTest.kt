package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #285: the path from "the repo has a skill" to "the agent has it" is one tap or one paste. */
class AppSkillTest {

    @Test fun theCommandInstallsTheReleasedSkill() {
        assertEquals("hermes skills install ${AppSkill.URL} --yes", AppSkill.command())
        assertTrue(AppSkill.URL.endsWith("/main/skills/hermes-vox-app/SKILL.md"))
    }

    @Test fun aNamedProfileComesFirst() {
        assertEquals("hermes -p work skills install ${AppSkill.URL} --yes", AppSkill.command("work"))
        assertEquals("work", AppSkill.profileOf("https://gw.example/p/work"))
        assertEquals("work", AppSkill.profileOf("https://gw.example/p/work/"))
        assertEquals("", AppSkill.profileOf("https://gw.example"))
    }

    @Test fun theTurnSaysWhatItRuns() {
        assertTrue(AppSkill.installTurn().contains(AppSkill.command()))
    }

    @Test fun theNameIsTheSkillsOwn() {
        val skill = File("../../skills/hermes-vox-app/SKILL.md").readText()
        assertTrue(skill.contains("name: ${AppSkill.NAME}"))
    }
}
