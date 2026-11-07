package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #220 — the VOX.md resync corpus.
 *
 * Every file under `testdata/vox-resync` is one plausible entity reply to the authoring
 * directive. The table below is the contract: which replies are mirrored as-is, which are
 * repaired (the document was inside a fence / behind a preamble / in CRLF) and which are
 * refused, with the reason the dialog will show. `docs/VOX-RESYNC-FIXTURES.md` explains
 * each case in one line and must name every one of them — a corpus only its author can
 * read proves nothing.
 *
 * A third of the corpus is replies the client must REFUSE. A corpus that only holds cases
 * the code passes cannot fail, so it would not be a test.
 */
class VoxResyncCorpusTest {

    private enum class Verdict { ACCEPTED, REPAIRED, REJECTED }

    private class Case(
        val file: String,
        val verdict: Verdict,
        /** Must appear in the document (ACCEPTED) or in the repair/reason text. */
        val mentions: String = "",
    )

    private val corpus = listOf(
        // ---- the document arrives exactly as asked ----
        Case("01-clean.txt", Verdict.ACCEPTED),
        Case("02-clean-extra-soul-field.txt", Verdict.ACCEPTED, "Voice:"),
        Case("03-clean-blank-lines-in-contract.txt", Verdict.ACCEPTED, "# Contract"),

        // ---- the document arrives, wrapped in something the model added ----
        Case("04-fenced-plain.txt", Verdict.REPAIRED, "code fence"),
        Case("05-fenced-markdown.txt", Verdict.REPAIRED, "code fence"),
        Case("06-fenced-md-prose-both.txt", Verdict.REPAIRED, "code fence"),
        Case("07-crlf.txt", Verdict.REPAIRED, "CRLF"),
        Case("08-heading-level.txt", Verdict.REPAIRED, "heading"),
        Case("09-bold-fields.txt", Verdict.REPAIRED, "Soul line"),
        Case("10-one-line-preamble.txt", Verdict.REPAIRED, "prose before the document"),
        Case("11-title-line.txt", Verdict.REPAIRED, "prose before the document"),
        Case("12-unterminated-fence.txt", Verdict.REPAIRED, "never closed"),
        Case("13-trailing-commentary.txt", Verdict.REPAIRED, "trailing commentary"),
        Case("14-draft-then-final.txt", Verdict.REPAIRED, "code fence"),
        Case("15-tilde-fence.txt", Verdict.REPAIRED, "code fence"),

        // ---- no usable document: these must be refused, and say why ----
        Case("16-prose-only.txt", Verdict.REJECTED, "no VOX.md"),
        Case("17-missing-register.txt", Verdict.REJECTED, "no 'Register' line"),
        Case("18-truncated-soul.txt", Verdict.REJECTED, "no 'Register' line"),
        Case("19-truncated-contract.txt", Verdict.REJECTED, "no '# Soul' heading"),
        Case("20-reworded-contract.txt", Verdict.REJECTED, "Contract rule 3 drifted"),
        Case("21-empty-register.txt", Verdict.REJECTED, "'Register' is empty"),
        Case("22-soul-before-contract.txt", Verdict.REJECTED, "before the Contract"),
        Case("23-secret.txt", Verdict.REJECTED, "credential"),
        Case("24-essence-too-long.txt", Verdict.REJECTED, "'Essence' field is"),
        Case("25-non-document-fence.txt", Verdict.REJECTED, "no VOX.md"),
        Case("26-empty-reply.txt", Verdict.REJECTED, "reply was empty"),
        Case("27-headings-only.txt", Verdict.REJECTED, "0 numbered rule"),
        Case("28-long-register.txt", Verdict.REJECTED, "'Register' is"),
    )

    private fun corpusDir(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "testdata/vox-resync")
            if (f.isDirectory) return f
            d = d.parentFile
        }
        error("testdata/vox-resync not found above ${System.getProperty("user.dir")}")
    }

    private fun repoFile(path: String): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, path)
            if (f.isFile) return f
            d = d.parentFile
        }
        error("$path not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `every fixture on disk is in the table, and every case has a fixture`() {
        val onDisk = corpusDir().listFiles { f -> f.name.endsWith(".txt") }!!
            .map { it.name }.sorted()
        val inTable = corpus.map { it.file }.sorted()
        assertEquals("a fixture with no expectation is not a test", onDisk, inTable)
        assertTrue("the corpus must hold at least 20 replies", corpus.size >= 20)
        assertTrue(
            "a corpus that only holds cases the code passes proves nothing: at least six " +
            "must be refusals",
            corpus.count { it.verdict == Verdict.REJECTED } >= 6)
    }

    @Test
    fun `the CRLF fixture is still a CRLF fixture on disk`() {
        // .gitattributes pins this one file as `-text`; if a checkout ever normalized it,
        // the case would pass as a plain LF document and stop testing anything.
        assertTrue(
            "07-crlf.txt lost its CRLF line endings — see .gitattributes",
            File(corpusDir(), "07-crlf.txt").readText().contains("\r\n"))
    }

    @Test
    fun `each reply gets the verdict the table promises`() {
        val failures = ArrayList<String>()
        for (c in corpus) {
            val reply = File(corpusDir(), c.file).readText()
            val outcome = VoxSoul.resync(reply)
            val got = when (outcome) {
                is VoxSoul.Outcome.Accepted -> Verdict.ACCEPTED
                is VoxSoul.Outcome.Repaired -> Verdict.REPAIRED
                is VoxSoul.Outcome.Rejected -> Verdict.REJECTED
            }
            if (got != c.verdict) {
                failures += "${c.file}: want ${c.verdict}, got $got (${describe(outcome)})"
                continue
            }
            when (outcome) {
                is VoxSoul.Outcome.Rejected ->
                    if (!outcome.reason.contains(c.mentions)) {
                        failures += "${c.file}: reason should mention '${c.mentions}' but said " +
                            "\"${outcome.reason}\""
                    }
                is VoxSoul.Outcome.Repaired -> {
                    val why = outcome.repairs.joinToString("; ")
                    if (c.mentions.isNotEmpty() && !why.contains(c.mentions)) {
                        failures += "${c.file}: repairs should mention '${c.mentions}' but said \"$why\""
                    }
                    // A repaired document must be a document: what we mirror is valid.
                    assertTrue(
                        "${c.file}: a repaired document must validate",
                        VoxSoul.validate(outcome.document) is VoxSoul.Valid.Ok)
                }
                is VoxSoul.Outcome.Accepted ->
                    if (c.mentions.isNotEmpty() && !outcome.document.contains(c.mentions)) {
                        failures += "${c.file}: document should carry '${c.mentions}'"
                    }
            }
        }
        assertEquals("verdicts that did not match the corpus:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    /** The repaired cases must land on the FIRST attempt: one resync call, no retry. */
    @Test
    fun `a wrapped or prefaced document needs no retry`() {
        val firstAttempt = corpus.filter {
            it.verdict == Verdict.ACCEPTED || it.verdict == Verdict.REPAIRED
        }
        for (c in firstAttempt) {
            val outcome = VoxSoul.resync(File(corpusDir(), c.file).readText())
            assertTrue("${c.file} must not need the retry button", outcome !is VoxSoul.Outcome.Rejected)
        }
        assertEquals(
            "only the wrapped/prefaced shapes are repaired; the clean ones are untouched",
            3, firstAttempt.count { it.verdict == Verdict.ACCEPTED })
    }

    /** Every case is explained in docs/VOX-RESYNC-FIXTURES.md, or the doc is already stale. */
    @Test
    fun `the fixtures doc explains every case`() {
        val doc = repoFile("docs/VOX-RESYNC-FIXTURES.md").readText()
        val missing = corpus.filter { !doc.contains(it.file.removeSuffix(".txt")) }
        assertEquals(
            "docs/VOX-RESYNC-FIXTURES.md is missing: " + missing.joinToString { it.file },
            0, missing.size)
        for (v in Verdict.values()) {
            assertTrue(
                "the doc must name the $v verdict",
                doc.contains(v.name.lowercase(), ignoreCase = true))
        }
    }

    private fun describe(o: VoxSoul.Outcome): String = when (o) {
        is VoxSoul.Outcome.Accepted -> "accepted"
        is VoxSoul.Outcome.Repaired -> "repaired: ${o.repairs.joinToString("; ")}"
        is VoxSoul.Outcome.Rejected -> "rejected: ${o.reason}"
    }
}
