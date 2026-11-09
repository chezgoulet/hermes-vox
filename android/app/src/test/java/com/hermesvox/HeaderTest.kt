package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The header, after the profile pass:
 *
 *   * the **big title is the connection's name, once** — the built-in connection is
 *     *Hermes-Agent*, a named profile is its own label, and nothing is appended to either;
 *   * the **sub-pill is the model**, unless the model's name is the connection's name — then
 *     the pill rests empty rather than repeating it;
 *   * a change the person caused (a model or session switch) is a **blue notice** for five
 *     to six seconds, one at a time, never over a green action line;
 *   * the Activity is wired to these rules, not to a second copy of them.
 *
 * Pure JVM: the words, the equivalence and the source wiring are proven off-device.
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

    // ---- 1. the connection, named once --------------------------------------

    @Test fun theBuiltInConnectionIsCalledHermesAgentAndSaysItOnce() {
        // No host name, no saved profile: the entity is the Hermes agent, not a model route.
        assertEquals("Hermes-Agent", Header.agent(""))
        assertEquals("HERMES-AGENT", Profiles.headerLabel(Header.agent(""), ""))
        // The built-in profile is addressed by name in the host's list; it is not a choice.
        assertEquals("Hermes-Agent", Header.connection(Header.agent(""), "default"))
        assertEquals("HERMES-AGENT", Profiles.headerLabel(Header.agent(""), "default"))
        // The model under it is the same name to a person, so the pill stays out of the way.
        assertEquals("Hermes Agent", Header.modelLine(ModelChoices.displayName("hermes-agent")))
        assertTrue(Header.sameName(Header.modelLine(ModelChoices.displayName("hermes-agent")), "HERMES-AGENT"))
        // The bug this pass fixes: the model used to be the big title.
        assertNotEquals(Header.modelLine(ModelChoices.displayName("hermes-agent")), Header.title("Hermes-Agent"))
    }

    @Test fun aHostThatNamesItsEntityWinsOverTheFloor() {
        assertEquals("Stephen", Header.agent(" Stephen "))
        assertEquals("STEPHEN", Header.title(Header.connection("Stephen", "default")))
        assertEquals("Stephen", Header.callTitle(Header.connection("Stephen", "default")))
    }

    @Test fun aNamedProfileIsCalledByItsLabelAndNothingElse() {
        assertEquals("WORK", Profiles.headerLabel("Hermes-Agent", "work"))
        assertEquals("work", Header.callTitle(Header.connection("Hermes-Agent", "work")))
        // The old form appended the profile to the agent; that is the duplication this ends.
        assertFalse(Profiles.headerLabel("Hermes-Agent", "work").contains("HERMES"))
        // A blank active profile is the built-in connection, not a profile called "".
        assertEquals("HERMES-AGENT", Profiles.headerLabel("Hermes-Agent", "  "))
    }

    @Test fun twoSpellingsOfOneNameAreOneName() {
        assertTrue(Header.sameName("Hermes Agent", "HERMES-AGENT"))
        assertTrue(Header.sameName("hermes_agent", "Hermes-Agent"))
        assertFalse(Header.sameName("Hermes Agent", "Hermes Vox"))
        // Nothing is "the same as" a blank line, so an empty pill is never suppressed by accident.
        assertFalse(Header.sameName("", ""))
    }

    @Test fun aModelSwitchKeepsTheConnectionAndNamesTheModelPlainly() {
        assertEquals("HERMES-AGENT", Header.title(Header.connection(Header.agent(""), "default")))
        // The sub-pill says the catalogue's own name: no "Thinking with", no "fresh thread".
        val line = ModelChoices.switchedLine(deepseek, deepseek.models.first())
        assertEquals("DeepSeek V4 Flash", line)
        assertFalse(line.contains("Thinking"))
        assertFalse(line.contains("fresh thread"))
        // A different model is a different name, so it is still worth the pill.
        assertFalse(Header.sameName(line, "HERMES-AGENT"))
        // A provider chosen without a model is named as itself, still plainly.
        assertEquals("DeepSeek", ModelChoices.switchedLine(deepseek, null))
    }

    @Test fun theActivityWiresTheOneNameAndTheSuppressedPill() {
        assertTrue(main.contains("Header.agent("))
        assertTrue(main.contains("Header.connection("))
        assertTrue(main.contains("Header.callTitle(name)"))
        assertTrue(main.contains("Header.sameName(model, title)"))
        // The resting pill is filled unless it would repeat the title; then it is gone.
        assertTrue(main.contains("status.text = resting"))
        assertTrue(main.contains("if (resting.isBlank()) android.view.View.GONE"))
        // The call screen is given the same name as the title.
        assertTrue(main.contains("VoiceService.title = Header.callTitle("))
    }
}
