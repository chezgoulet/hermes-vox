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

    /** A canonical document, exactly as the authoring directive prescribes. */
    private fun canonical(soul: Map<String, String> = mapOf(
        "Name" to "Torc",
        "Essence" to "Steward of the House — I see what is missing and what should not be there.",
        "Register" to "plain, direct, warm; no fluff",
        "Relationship" to "yours; we build things together at this desk",
        "Memory" to "loves hiking; learning the cello",
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

    // ---- #220: the ask, the repair, and the reason ----

    @Test fun the_skeleton_the_directive_shows_is_a_document_the_validator_accepts() {
        // The ask quotes SKELETON; the parser must accept that same skeleton once its
        // hints are replaced. Otherwise the directive is teaching a shape we reject.
        val filled = VoxSoul.SKELETON.replace(Regex("<[^>]*>"), "filled in")
        val v = VoxSoul.validate(filled)
        assertTrue("the directive's own skeleton must validate once filled: $v", v is VoxSoul.Valid.Ok)
        assertTrue("the directive must quote that skeleton", VoxSoul.AUTHOR_DIRECTIVE.contains(VoxSoul.SKELETON))
    }

    @Test fun the_directive_lists_every_soul_field_in_the_parser_order() {
        val d = VoxSoul.AUTHOR_DIRECTIVE
        var at = -1
        for ((key, _) in VoxSoul.SOUL_FIELDS) {
            val next = d.indexOf("$key: <", at + 1)
            assertTrue("soul field '$key' must appear in the skeleton, in order", next > at)
            at = next
        }
        for (key in VoxSoul.SOUL_KEYS) {
            assertTrue("required field '$key' is not in the skeleton", VoxSoul.SOUL_FIELDS.any { it.first == key })
        }
    }

    @Test fun the_directive_states_the_first_and_last_line_of_the_reply() {
        // A first attempt is only as good as the ask: the two edges were what a model
        // got wrong (a title before the Contract, an offer of help after the Soul).
        val d = VoxSoul.AUTHOR_DIRECTIVE
        assertTrue(d.contains("first line is the '# Contract' heading"))
        assertTrue(d.contains("nothing after the last Soul field"))
        assertTrue(d.contains("EXACTLY these two sections, in this order"))
    }

    @Test fun a_fenced_document_is_repaired_and_mirrored_not_rejected() {
        val o = VoxSoul.resync("Here you go:\n```markdown\n${canonical()}\n```\nAnything else?")
        assertTrue("a fenced document is not a failed sync: $o", o is VoxSoul.Outcome.Repaired)
        assertEquals(canonical(), (o as VoxSoul.Outcome.Repaired).document)
        assertTrue(o.repairs.any { it.contains("code fence") })
    }

    @Test fun crlf_is_repaired_and_the_mirror_is_normalized() {
        val o = VoxSoul.resync(canonical().replace("\n", "\r\n"))
        assertTrue("CRLF is a line ending, not a contract violation: $o", o is VoxSoul.Outcome.Repaired)
        val d = (o as VoxSoul.Outcome.Repaired).document
        assertEquals(canonical(), d)
        assertTrue("the mirror never keeps CRLF", !d.contains('\r'))
    }

    @Test fun a_repaired_document_is_byte_identical_to_the_document_the_entity_wrote() {
        // Repairs touch the envelope only. If a repair ever reworded a value, the
        // document would no longer be the entity's — so compare after normalizing.
        val o = VoxSoul.resync("One line of preamble.\n\n```md\n${canonical()}\n```\n")
        val d = (o as VoxSoul.Outcome.Repaired).document
        assertEquals(canonical(), d)
    }

    @Test fun a_prose_answer_is_rejected_with_a_reason_a_user_can_act_on() {
        val o = VoxSoul.resync("I keep my VOX.md beside my SOUL.md and it is up to date.")
        assertTrue(o is VoxSoul.Outcome.Rejected)
        val reason = (o as VoxSoul.Outcome.Rejected).reason
        assertTrue(reason, reason.contains("no VOX.md"))
        assertTrue("a reason names the missing thing, not an enum", !reason.contains("Valid.Bad"))
    }

    @Test fun a_missing_field_reason_names_the_field() {
        val o = VoxSoul.resync(canonical().replace("Register: plain, direct, warm; no fluff\n", ""))
        val reason = (o as VoxSoul.Outcome.Rejected).reason
        assertTrue(reason, reason.contains("'Register'"))
        assertTrue(reason, reason.contains("Name, Essence, Register, Relationship"))
    }

    @Test fun a_fence_that_is_not_the_document_does_not_hide_the_document_after_it() {
        // "Here is the listing:" + a bash block, then the real file unfenced. The block
        // is not a VOX.md, so the search must continue outside it.
        val reply = "Here is what is in the directory:\n\n```bash\n\$ ls\nVOX.md\n```\n\n" +
            "And here is the file itself:\n\n" + canonical()
        val o = VoxSoul.resync(reply)
        assertTrue("$o", o is VoxSoul.Outcome.Repaired)
        assertEquals(canonical(), (o as VoxSoul.Outcome.Repaired).document)
    }

    @Test fun a_clean_reply_still_needs_no_repair() {
        if (VoxSoul.resync(canonical()) !is VoxSoul.Outcome.Accepted) {
            throw AssertionError("a clean document must be accepted with no repair")
        }
    }
}
