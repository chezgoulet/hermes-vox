package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoxSoul — the VOX.md bridge contract (ER Phases 2+3), proven pure-JVM.
 * The sacrosanct guarantee: Contract bytes identical, Soul fields real,
 * no secrets mirrored — validated BEFORE a document ever reaches the soul
 * prompt, and the pull-only invariant (device never pushes).
 */
class VoxSoulTest {

    // ---- The beat stems (0.8/M3d) ----

    private val nl = System.lineSeparator()

    @Test fun the_authoring_directive_asks_for_the_stems() {
        val d = VoxSoul.AUTHOR_DIRECTIVE
        assertTrue("names the beat field", d.contains("Stems:"))
        assertTrue("names the patience field", d.contains("Patience:"))
        assertTrue("and marks them optional", d.contains("optional"))
    }

    @Test fun a_legacy_mirror_without_stems_still_validates_and_has_none() {
        // The back-compat guarantee, and the reason the fields are optional: every VOX.md already
        // mirrored in the field must keep working. The caller falls back to its own presence lines.
        val doc = canonical()
        assertTrue(VoxSoul.validate(doc) is VoxSoul.Valid.Ok)
        assertEquals(emptyList<String>(), VoxSoul.beatStems(doc))
        assertEquals(emptyList<String>(), VoxSoul.patienceStems(doc))
    }

    @Test fun stems_are_parsed_in_the_entitys_own_voice() {
        val doc = canonical() + nl + "Stems: mm, right, yeah okay" + nl +
            "Patience: take your time, still digging into that"
        assertEquals(listOf("mm", "right", "yeah okay"), VoxSoul.beatStems(doc))
        assertEquals(listOf("take your time", "still digging into that"), VoxSoul.patienceStems(doc))
        assertTrue("and the mirror still validates", VoxSoul.validate(doc) is VoxSoul.Valid.Ok)
    }

    @Test fun a_bad_stem_costs_a_stem_not_a_soul() {
        // The Contract is sacred and enforced at mirror time. Stems are advisory data: a stem is
        // spoken with no mind in the loop, so it must be safe by construction — but a sloppy stem
        // line should cost you a stem, never your soul.
        val doc = canonical() + nl +
            "Stems: mm, I will check your inbox and read it back to you, what time is it?, " +
            "the email arrived at 14:32, [laughs], right"
        assertTrue("a sloppy stem line must not invalidate the mirror",
            VoxSoul.validate(doc) is VoxSoul.Valid.Ok)
        val stems = VoxSoul.beatStems(doc)
        assertTrue("the good ones survive", stems.contains("mm") && stems.contains("right"))
        assertTrue("an over-long stem is dropped",
            stems.none { it.length > VoxSoul.MAX_STEM_CHARS })
        assertTrue("a question is dropped", stems.none { it.contains('?') })
        assertTrue("a number is dropped", stems.none { s -> s.any { it.isDigit() } })
        assertTrue("a stage direction is dropped",
            stems.none { it.contains('[') || it.contains(']') })
        assertTrue("the cap holds", stems.size <= VoxSoul.MAX_STEMS)
    }

    /** A canonical document, exactly as the authoring directive prescribes. */
    private fun canonical(soul: Map<String, String> = mapOf(
        "Name" to "Torc",
        "Essence" to "Steward of the House — I see what is missing and what should not be there.",
        "Register" to "plain, direct, warm; no fluff",
        "Relationship" to "Christopher; we build the House together",
        "Memory" to "maple shortbread; the move to Quebec",
        "Humor" to "dry, structural",
        "Proud" to "the library",
    )): String {
        val soulLines = listOf("Name", "Essence", "Register", "Relationship", "Memory", "Humor", "Proud")
            .joinToString("\n") { "${it}: ${soul[it] ?: ""}" }
        return "# Contract\n" + VoxSoul.CONTRACT_LINES.joinToString("\n") + "\n\n# Soul\n" + soulLines
    }

    @Test fun canonical_document_validates() {
        val v = VoxSoul.validate(canonical())
        assertTrue("canonical must validate: $v", v is VoxSoul.Valid.Ok)
    }

    @Test fun contract_byte_drift_is_rejected() {
        // Rule 3 reworded — the sacrosanct failure the validator exists for.
        val drifted = canonical().replace("hold smalltalk, emotion, and presence only", "chat about anything")
        val v = VoxSoul.validate(drifted)
        assertTrue(v is VoxSoul.Valid.Bad)
        assertTrue((v as VoxSoul.Valid.Bad).reason.contains("drift"))
    }

    @Test fun missing_rule_is_rejected() {
        val short = canonical().lines().filterNot { it.startsWith("6.") }.joinToString("\n")
        val v = VoxSoul.validate(short)
        assertTrue(v is VoxSoul.Valid.Bad)
    }

    @Test fun empty_soul_field_is_rejected() {
        // Blank ONLY Register; the other keys keep their defaults. (mapOf("Register" to "")
        // would null out every other key — the validator would correctly fail on Name first.)
        val doc = canonical().replace("Register: plain, direct, warm; no fluff", "Register: ")
        val v = VoxSoul.validate(doc)
        assertTrue(v is VoxSoul.Valid.Bad)
        assertTrue((v as VoxSoul.Valid.Bad).reason.contains("Register"))
    }

    @Test fun missing_soul_heading_is_rejected() {
        val v = VoxSoul.validate(canonical().substringBefore("# Soul"))
        assertTrue(v is VoxSoul.Valid.Bad)
    }

    @Test fun secret_pattern_is_rejected() {
        val leaky = canonical() + "\nMemory: the key is sk-abcdefghijklmnop1234"
        val v = VoxSoul.validate(leaky)
        assertTrue(v is VoxSoul.Valid.Bad)
        assertTrue((v as VoxSoul.Valid.Bad).reason.contains("secret"))
    }

    @Test fun extract_pulls_fenced_document() {
        val reply = "Here is my voice-export:\n```markdown\n${canonical()}\n```\nLet me know if you need anything else."
        val got = VoxSoul.extract(reply)
        assertEquals(canonical(), got)
    }

    @Test fun extract_pulls_unfenced_document() {
        val reply = "Of course.\n\n${canonical()}"
        assertEquals(canonical(), VoxSoul.extract(reply))
    }

    @Test fun extract_rejects_non_document_reply() {
        assertEquals(null, VoxSoul.extract("Sure — what would you like me to do?"))
        assertEquals(null, VoxSoul.extract(null))
        assertEquals(null, VoxSoul.extract(""))
    }

    @Test fun extract_takes_the_last_fence() {
        val reply = "Old draft:\n```markdown\n# Contract\nwrong\n```\n\nFinal:\n``markdown\n```\n" + canonical() + "\n```"
        val got = VoxSoul.extract(reply)
        assertEquals(canonical(), got)
    }

    @Test fun mirror_status_carries_the_name() {
        val m = VoxSoul.mirrorStatus(canonical())
        assertTrue(m is VoxSoul.Mirror.Present)
        assertEquals("Torc", (m as VoxSoul.Mirror.Present).name)
        assertTrue(m.chars > 200)
    }

    @Test fun invalid_mirror_reads_absent() {
        assertTrue(VoxSoul.mirrorStatus(null) is VoxSoul.Mirror.Absent)
        assertTrue(VoxSoul.mirrorStatus("garbage") is VoxSoul.Mirror.Absent)
        assertTrue(VoxSoul.mirrorStatus(canonical().replace("# Soul", "# Sole")) is VoxSoul.Mirror.Absent)
    }

    @Test fun the_prelude_tells_the_voice_to_decide_and_how_to_hand_over() {
        // The prelude used to describe a RENDERER ("You express, hold presence, and voice the
        // mind's replies in its register… One or two sentences") while the router's directive
        // asked it to DECIDE. The persona is the stronger instruction, so the decision contract
        // could not land. These assertions are the contract that fixes it.
        val p = VoxSoul.soulPrompt(canonical())
        assertTrue("the prelude must name the decision job", p.contains("DECIDE"))
        assertTrue("…and give the model a way to hand a turn over", p.contains(ErSoulTurn.ESCALATE))
        assertTrue("…and forbid explaining instead of escalating", p.contains("Never write a sentence"))
        assertTrue("…and keep the answer short", p.contains("one short warm sentence"))
        assertTrue("…and bound its length", p.contains("twenty words"))
    }

    @Test fun the_authoring_directive_carries_the_contract_exactly_once() {
        // The directive used to re-type the Contract while CONTRACT_LINES held a second copy —
        // a two-copy drift, in a document whose entire guarantee is byte-identity. It is now
        // composed from CONTRACT_LINES. This pins it.
        val d = VoxSoul.AUTHOR_DIRECTIVE
        assertEquals("the contract must appear ONCE, composed from CONTRACT_LINES",
            1, Regex(Regex.escape(VoxSoul.CONTRACT_LINES[0])).findAll(d).count())
        for (line in VoxSoul.CONTRACT_LINES) assertTrue("missing contract line: $line", d.contains(line))
    }

    @Test fun the_authoring_directive_says_what_the_client_does_with_the_voice() {
        // The author is told the voice is now asked to DECIDE — an author who knows the use
        // writes different Soul fields.
        assertTrue(VoxSoul.AUTHOR_DIRECTIVE.contains("SPEAKS FIRST AND BRIEFLY"))
    }

    @Test fun soul_prompt_declares_the_voice_not_the_mind() {
        val p = VoxSoul.soulPrompt(canonical())
        assertTrue(p.contains("You are the VOICE"))
        assertTrue(p.contains("NOT the mind"))
        assertTrue(p.contains(canonical()))   // the mirror rides verbatim
        assertTrue(p.contains("Never invent facts"))
    }

    @Test fun directive_carries_the_contract_and_pull_only_framing() {
        // The authoring directive must: name the file location, require the
        // canonical Contract, ask for content-only reply (the pull), and never
        // ask the device to write back (pull-only invariant).
        val d = VoxSoul.AUTHOR_DIRECTIVE
        assertTrue(d.contains("VOX.md"))
        assertTrue(d.contains("\$HERMES_HOME/VOX.md"))
        assertTrue(d.contains("FULL CONTENT ONLY"))
        assertTrue(d.contains("1. Never invent facts"))
        assertTrue(d.contains("6. Don't fabricate shared history"))
        assertTrue(d.contains("No secrets, no family private data"))
    }
}
