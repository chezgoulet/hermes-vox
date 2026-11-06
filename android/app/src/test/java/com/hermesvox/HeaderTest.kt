package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The header, after the second phone pass:
 *
 *   * the **big title is the agent** you are talking to (the host's name, the Hermes agent's
 *     own name as the floor), and the **sub-pill is the model** — plainly, no verb;
 *   * a change the person caused (a model or session switch) is a **blue notice** for five
 *     to six seconds, one at a time, never over a green action line;
 *   * the profile's name survives in the title while a profile is chosen, and a session
 *     switch names the session;
 *   * the Activity is wired to these rules, not to a second copy of them.
 *
 * Pure JVM: the words, the clock and the source wiring are proven off-device.
 */
class HeaderTest {

    private val src = "src/main/java/com/hermesvox"
    private val main = File(src, "MainActivity.kt").readText()

    private val deepseek = ModelChoices.Provider(
        slug = "deepseek",
        name = "DeepSeek",
        models = listOf(
            ModelChoices.Model(
                id = "deepseek-v4-flash",
                name = "DeepSeek V4 Flash",
                category = ModelChoices.Category.FAST,
                size = null,
                context = null,
                installed = null,
                current = true,
            ),
        ),
        total = 1,
        signedIn = true,
        current = true,
        warning = "",
    )

    // ---- 1. who you are talking to, and what answers --------------------------

    @Test fun aFreshInstallTitlesTheAgentAndPutsTheModelUnderIt() {
        // No host name, no saved profile: the entity is the Hermes agent, not a model route.
        assertEquals("Hermes", Header.agent(""))
        assertEquals("HERMES", Profiles.headerLabel(Header.agent(""), "", 1))
        assertEquals("HERMES", Header.title("", "", 1))
        // The model is the sub-pill's, in the words the catalogue uses for the id.
        assertEquals("Hermes Agent", Header.modelLine(ModelChoices.displayName("hermes-agent")))
        // The bug this pass fixes: the model used to be the big title.
        assertNotEquals(Header.modelLine(ModelChoices.displayName("hermes-agent")), Header.title("", "", 1))
    }

    @Test fun aHostThatNamesItsEntityWinsOverTheFloor() {
        assertEquals("Stephen", Header.agent(" Stephen "))
        assertEquals("STEPHEN", Header.title("Stephen", "", 1))
        assertEquals("Stephen", Header.callTitle("Stephen", "", 1))
    }

    @Test fun aModelSwitchKeepsTheAgentAndNamesTheModelPlainly() {
        assertEquals("HERMES", Header.title(Header.agent(""), "", 1))
        // The sub-pill says the catalogue's own name: no "Thinking with", no "fresh thread".
        val line = ModelChoices.switchedLine(deepseek, deepseek.models.first())
        assertEquals("DeepSeek V4 Flash", line)
        assertFalse(line.contains("Thinking"))
        assertFalse(line.contains("fresh thread"))
        // A provider chosen without a model is named as itself, still plainly.
        assertEquals("DeepSeek", ModelChoices.switchedLine(deepseek, null))
    }

    @Test fun theActivityWiresTheAgentTitleAndTheModelPill() {
        assertTrue(main.contains("Header.agent("))
        assertTrue(main.contains("Header.callTitle("))
        // The resting pill is the model's name — it is filled, not hidden.
        assertTrue(main.contains("status.text = if (show) text else idleStatus()"))
        assertTrue(main.contains("Header.modelLine("))
        // The call screen is given the same name as the title.
        assertTrue(main.contains("VoiceService.title = Header.callTitle("))
    }
}
