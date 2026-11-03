package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC-conversational-bridge §B, proven off-device: the decision, the grammar, the token.
 *
 * Everything here is pure JVM. What is *not* here: the picker, the content resolver, the
 * socket bind on a tailnet interface — those need a device and are named in the PR.
 */
class FileOfferTest {

    private val now = 1_793_650_000_000L

    private fun file(
        name: String = "meeting-notes.md",
        mime: String = "text/markdown",
        bytes: Long = 8_123L,
        sha: String = "9f2c4a71d0b3e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abc",
    ) = FileOffer.File(name, mime, bytes, sha)

    // ---- the cap: at, under and over 100 MB ----

    @Test fun oneByteUnderTheCapIsOffered() {
        val p = FileOffer.plan(file(bytes = FileOffer.MAX_BYTES - 1), null, "100.64.0.7")
        assertEquals(FileOffer.Route.OFFER, p.route)
    }

    @Test fun exactlyTheCapIsStillOffered() {
        val p = FileOffer.plan(file(bytes = FileOffer.MAX_BYTES), null, "100.64.0.7")
        assertEquals("the cap is a ceiling, not a wall one byte early", FileOffer.Route.OFFER, p.route)
    }

    @Test fun oneByteOverTheCapIsRefusedWithTheNumberInTheLine() {
        val over = FileOffer.MAX_BYTES + 1
        val p = FileOffer.plan(file(bytes = over), null, "100.64.0.7")
        assertEquals(FileOffer.Route.REFUSED, p.route)
        assertTrue("the line must carry the size: ${p.line}", p.line.contains("100 MB"))
        assertTrue("the line must carry the cap: ${p.line}", p.line.contains("the most I can offer"))
        // 100,000,001 bytes is 100.0 MB to one decimal, so the two numbers read the same —
        // that is why the real case is tested with a size that differs visibly.
        assertFalse(p.line.contains("100,000,001"))
    }

    @Test fun aHundredAndFiveMegabytesIsRefusedWithThatNumber() {
        val p = FileOffer.plan(file(bytes = 104_857_600), null, "100.64.0.7")
        assertEquals(FileOffer.Route.REFUSED, p.route)
        assertEquals(
            "this file is 104.9 MB · the most I can offer is 100 MB",
            p.line,
        )
    }

    // ---- inline vs offer: by size and by type ----

    @Test fun textUnderEightKilobytesIsInlineAndNeedsNoServer() {
        val text = "one small note\n"
        val p = FileOffer.plan(file(mime = "text/plain", bytes = 100), text, "100.64.0.7")
        assertEquals(FileOffer.Route.INLINE, p.route)
        assertNotNull(p.inlineTurn)
        assertTrue("the inline line names the size: ${p.line}", p.line.contains("100 bytes"))
        assertTrue("no server was ever needed: ${p.line}", p.line.contains("no server"))
    }

    @Test fun exactlyEightKilobytesIsNotInline() {
        val text = "x".repeat(8_000)
        val p = FileOffer.plan(file(mime = "text/plain", bytes = 8_000), text, "100.64.0.7")
        assertEquals(FileOffer.Route.OFFER, p.route)
        assertNull(p.inlineTurn)
    }

    @Test fun eightKilobytesMinusOneIsInline() {
        val text = "x".repeat(7_999)
        val p = FileOffer.plan(file(mime = "text/plain", bytes = 7_999), text, "100.64.0.7")
        assertEquals(FileOffer.Route.INLINE, p.route)
    }

    @Test fun aSmallBinaryIsNeverInlinedEvenWhenMisreadAsText() {
        val p = FileOffer.plan(
            file(name = "logo.png", mime = "image/png", bytes = 900), null, "100.64.0.7",
        )
        assertEquals(FileOffer.Route.OFFER, p.route)
    }

    @Test fun aSmallFileTypeWithBinaryBytesIsNotInlined() {
        // The type says text, the bytes disagree (a NUL / a failed UTF-8 decode): the caller
        // passes inlineText = null, and the offer path is chosen. No silent garbage.
        val p = FileOffer.plan(file(mime = "text/plain", bytes = 200), null, "100.64.0.7")
        assertEquals(FileOffer.Route.OFFER, p.route)
    }

    @Test fun theTextTypeListIsNarrowAndRecognisable() {
        assertTrue(FileOffer.isTextType("text/markdown"))
        assertTrue(FileOffer.isTextType("text/plain; charset=utf-8"))
        assertTrue(FileOffer.isTextType("application/json"))
        assertTrue(FileOffer.isTextType("application/vnd.api+json"))
        assertFalse(FileOffer.isTextType("image/png"))
        assertFalse(FileOffer.isTextType("application/pdf"))
        assertFalse(FileOffer.isTextType("application/octet-stream"))
    }

    // ---- Tailscale down: no server nobody can reach ----

    @Test fun tailscaleDownRefusesToStartAServer() {
        val p = FileOffer.plan(file(mime = "application/pdf", bytes = 900_000), null, null)
        assertEquals(FileOffer.Route.UNREACHABLE, p.route)
        assertNull("there is nothing to inline for a PDF", p.inlineTurn)
        assertTrue(p.line.startsWith("Tailscale is not connected"))
        assertFalse(p.line.contains("http"))
    }

    @Test fun tailscaleDownStillOffersTheInlinePathForASmallNote() {
        val p = FileOffer.plan(file(mime = "text/plain", bytes = 42), "hello\n", null)
        assertEquals(FileOffer.Route.UNREACHABLE, p.route)
        assertNotNull("a small note can still travel inline", p.inlineTurn)
        assertTrue(p.line.contains("send it inline instead"))
    }

    @Test fun aBlankTailnetHostIsDownToo() {
        assertEquals(FileOffer.Route.UNREACHABLE, FileOffer.plan(file(), null, "  ").route)
    }

    // ---- token and URL shape ----

    @Test fun theTokenIs128BitsOfLowercaseHex() {
        val t = FileOffer.token()
        assertEquals(FileOffer.TOKEN_HEX, t.length)
        assertEquals("16 bytes", 16, t.toByteArray().size / 2)
        assertTrue(t.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("128 bits", 128, t.length * 4)
    }

    @Test fun tokensDoNotRepeat() {
        val seen = (1..256).map { FileOffer.token() }.toSet()
        assertEquals(256, seen.size)
    }

    @Test fun theUrlIsOnePathAndAnEphemeralPort() {
        val u = FileOffer.url("100.82.156.1", 49152, "6b1f")
        assertEquals("http://100.82.156.1:49152/6b1f", u)
        // One path segment, no query, no fragment, no trailing slash: the server compares the
        // whole target literally, so anything longer can never match.
        val path = u.substringAfter("49152")
        assertEquals("/6b1f", path)
        assertFalse(path.contains('?'))
        assertFalse(path.contains('#'))
        assertEquals(1, path.trimStart('/').split('/').size)
    }

    @Test fun anIpv6TailnetAddressIsBracketed() {
        assertEquals(
            "http://[fd7a:115c:a1e0::1]:5000/ab",
            FileOffer.url("fd7a:115c:a1e0::1", 5000, "ab"),
        )
        assertEquals(
            "http://[fd7a:115c:a1e0::1]:5000/ab",
            FileOffer.url("[fd7a:115c:a1e0::1]", 5000, "ab"),
        )
    }

    @Test fun expiryIsFiveMinutesFromNowInEpochSeconds() {
        assertEquals(1_793_650_300L, FileOffer.expiresAt(now))
        assertEquals(300L, FileOffer.expiresAt(now) - now / 1000L)
    }

    // ---- the turn: a name with spaces, non-ASCII and an apostrophe survives ----

    /** The marker line as the agent's parser sees it: tokens split on single spaces. */
    private fun fields(line: String): Map<String, String> {
        val parts = line.split(' ')
        assertEquals("the marker is the first token", FileOffer.MARKER, parts[0])
        return parts.drop(1).associate { p ->
            val i = p.indexOf('=')
            assertTrue("every field is key=value: $p", i > 0)
            p.substring(0, i) to p.substring(i + 1)
        }
    }

    @Test fun aNameWithSpacesSurvivesTheMarkerGrammar() {
        val turn = FileOffer.offerTurn(file(name = "meeting notes for monday.md"), "100.64.0.7", 49152,
            "0123456789abcdef0123456789abcdef", 1_793_650_300L)
        val line = turn.lineSequence().first()
        val f = fields(line)
        assertEquals("meeting notes for monday.md", FileOffer.decode(f["name"]!!))
        assertEquals("meeting%20notes%20for%20monday.md", f["name"])
        assertFalse("no field may contain a space", f.values.any { it.contains(' ') })
    }

    @Test fun aNonAsciiNameSurvivesTheMarkerGrammar() {
        val name = "réunion privée — 議事録.txt"
        val turn = FileOffer.offerTurn(file(name = name), "100.64.0.7", 49152,
            "0123456789abcdef0123456789abcdef", 1_793_650_300L)
        val f = fields(turn.lineSequence().first())
        assertEquals(name, FileOffer.decode(f["name"]!!))
        assertTrue("non-ASCII bytes are percent-encoded", f["name"]!!.contains('%'))
        assertFalse(f["name"]!!.any { it.code > 126 })
    }

    @Test fun anApostropheAndAPlusSurviveTheMarkerGrammar() {
        val name = "Bob's notes (final) + v2.txt"
        val encoded = FileOffer.encode(name)
        assertEquals(name, FileOffer.decode(encoded))
        assertFalse("form encoding would turn a space into '+': $encoded", encoded.contains('+'))
        assertTrue(encoded.contains("%27"))   // the apostrophe
        assertTrue(encoded.contains("%28"))
        assertEquals("Bob%27s%20notes%20%28final%29%20%2B%20v2.txt", encoded)
    }

    @Test fun theTurnCarriesExactlyTheSpecsFields() {
        val f = fields(
            FileOffer.offerTurn(file(), "100.64.0.7", 49152, "0123456789abcdef0123456789abcdef",
                1_793_650_300L).lineSequence().first(),
        )
        assertEquals(
            setOf("name", "type", "bytes", "sha256", "url", "expires"),
            f.keys,
        )
        assertEquals("text/markdown", f["type"])
        assertEquals("8123", f["bytes"])
        assertEquals("0123456789abcdef0123456789abcdef", f["url"]!!.substringAfterLast('/'))
        assertEquals("1793650300", f["expires"])
        assertTrue(f["url"]!!.startsWith("http://100.64.0.7:49152/"))
    }

    @Test fun theTurnEndsWithTheOneSentence() {
        val turn = FileOffer.offerTurn(file(), "100.64.0.7", 49152,
            "0123456789abcdef0123456789abcdef", 1_793_650_300L)
        val lines = turn.lines()
        assertEquals(2, lines.size)
        assertEquals(FileOffer.OFFER_SENTENCE, lines[1])
        assertTrue(lines[1].startsWith("I am sharing one file with you."))
    }

    @Test fun aMimeWithWhitespaceCannotBreakTheLine() {
        val f = fields(
            FileOffer.offerTurn(file(mime = "text/plain; charset=utf-8 bad"), "100.64.0.7", 1,
                "0123456789abcdef0123456789abcdef", 1L).lineSequence().first(),
        )
        assertEquals("application/octet-stream", f["type"])
    }

    // ---- the inline turn: a fence that cannot be broken out of ----

    @Test fun theInlineFenceIsLabelledWithTheName() {
        val turn = FileOffer.inlineTurn(file(name = "notes.txt", mime = "text/plain", bytes = 12), "hello\n")
        assertTrue(turn.contains("```notes.txt"))
        assertTrue(turn.contains("\nhello\n"))
        assertTrue(turn.contains("\n```notes.txt") && turn.trimEnd().endsWith("```"))
        assertTrue(turn.lineSequence().first().endsWith("inline=1"))
        assertFalse("no url for an inline file", turn.contains("url="))
        assertFalse("no expiry for an inline file", turn.contains("expires="))
    }

    @Test fun aBodyWithAFenceWidensTheFence() {
        val body = "before\n```\ncode\n```\nafter\n"
        val turn = FileOffer.inlineTurn(file(name = "readme.md", mime = "text/markdown", bytes = 30), body)
        assertTrue("the fence must be four backticks, got:\n$turn", turn.contains("\n````readme.md\n"))
        assertTrue(turn.contains("\n````\n") || turn.trimEnd().endsWith("````"))
    }

    @Test fun aNameWithABacktickFallsBackToTheEncodedLabel() {
        val turn = FileOffer.inlineTurn(file(name = "we`ird.txt", mime = "text/plain", bytes = 2), "hi")
        assertFalse("a backtick in the info string would close the fence", turn.contains("```we`ird"))
        assertTrue(turn.contains("name=we%60ird.txt"))
    }

    // ---- status lines, verbatim ----

    @Test fun theCollectedLineIsTheSpecsLine() {
        val line = FileOffer.collected(
            file(name = "meeting-notes.md", bytes = 8_123), "~/.hermes/inbox/meeting-notes.md",
        )
        assertEquals(
            "collected · meeting-notes.md (8.1 KB) · ~/.hermes/inbox/meeting-notes.md",
            line,
        )
    }

    @Test fun collectedWithoutAPathInventsNothing() {
        val line = FileOffer.collected(file(bytes = 8_123), "")
        assertEquals(
            "collected · meeting-notes.md (8.1 KB) · the agent did not say where it kept it",
            line,
        )
    }

    @Test fun expiredAndMismatchAreTheSpecsLines() {
        assertEquals("the agent did not collect the file · tap to try again", FileOffer.expired())
        assertEquals(
            "the agent received a different file than we sent · nothing was kept",
            FileOffer.mismatch(),
        )
    }

    @Test fun theOfferingLineNamesTheFile() {
        val p = FileOffer.plan(file(name = "meeting-notes.md"), null, "100.64.0.7")
        assertEquals("sharing meeting-notes.md · waiting for the agent to collect it", p.line)
    }

    // ---- sizes, decimal, as the spec's example fixes them ----

    @Test fun sizesReadTheWayTheSpecsExampleDoes() {
        assertEquals("8123 bytes shown as 8.1 KB", "8.1 KB", FileOffer.size(8_123))
        assertEquals("1.0 MB", FileOffer.size(1_000_000))
        assertEquals("100 MB", FileOffer.size(100_000_000))
        assertEquals("104.9 MB", FileOffer.size(104_857_600))
        assertEquals("812 bytes", FileOffer.size(812))
        assertEquals("1 byte", FileOffer.size(1))
    }

    // ---- the agent's reply is a report, never our own claim ----

    @Test fun aStatedMismatchIsAMismatch() {
        val replies = listOf(
            "the sha256 does not match — nothing was kept",
            "I fetched it, but the hash didn't match.",
            "checksum failed",
            "the file was truncated at 4 KB",
            "I could not verify the file",
            "that is a different file than the one you offered",
        )
        for (r in replies) {
            assertEquals(r, FileOffer.Reply.Mismatch, FileOffer.readReply(r))
        }
    }

    @Test fun aNegatedMismatchIsNotAFailure() {
        // Neither sentence reports a keep, so neither is a collection — but neither may be read
        // as a failure either.
        assertFalse(
            FileOffer.readReply("no mismatch — it is fine") is FileOffer.Reply.Mismatch,
        )
        assertFalse(
            FileOffer.readReply("verified, without a mismatch") is FileOffer.Reply.Mismatch,
        )
        assertEquals(FileOffer.Reply.Silent, FileOffer.readReply("verified, without a mismatch"))
    }

    @Test fun theKeptPathIsReadFromTheReply() {
        assertEquals(
            FileOffer.Reply.Kept("~/.hermes/inbox/meeting-notes.md"),
            FileOffer.readReply("Collected and verified. Kept at ~/.hermes/inbox/meeting-notes.md"),
        )
        assertEquals(
            FileOffer.Reply.Kept("/srv/hermes/inbox/notes.md"),
            FileOffer.readReply("it is in /srv/hermes/inbox/notes.md."),
        )
    }

    @Test fun aUrlInTheReplyIsNotAPath() {
        // A URL is not a path the agent kept, and "fetched" alone is not a keep: nothing is
        // claimed from this reply.
        assertEquals(
            FileOffer.Reply.Silent,
            FileOffer.readReply("fetched http://100.64.0.7:49152/abc successfully"),
        )
        assertEquals(
            FileOffer.Reply.Kept("~/.hermes/inbox/abc"),
            FileOffer.readReply("kept at ~/.hermes/inbox/abc (fetched from http://100.64.0.7:49152/abc)"),
        )
    }

    @Test fun aReplyThatSaysNothingAboutTheFileClaimsNothing() {
        // A reply is evidence, not a mood: a sentence that does not say the file was kept
        // cannot become the success line, however friendly it is.
        val replies = listOf(
            "Which file did you mean?",
            "Thanks — noted.",
            "I see the offer, one moment.",
            "Let me know if you need anything else.",
        )
        for (r in replies) assertEquals(r, FileOffer.Reply.Silent, FileOffer.readReply(r))
    }

    @Test fun aKeepWithoutAPathIsStillACollection() {
        assertEquals(FileOffer.Reply.Kept(""), FileOffer.readReply("Got it and kept it."))
        assertEquals(FileOffer.Reply.Kept(""), FileOffer.readReply("Saved into the inbox."))
    }

    @Test fun aDeniedKeepIsNotACollection() {
        assertEquals(FileOffer.Reply.Silent, FileOffer.readReply("I could not save it after all."))
        assertEquals(FileOffer.Reply.Silent, FileOffer.readReply("nothing was kept"))
    }

    @Test fun anEmptyReplySaysNothing() {
        assertEquals(FileOffer.Reply.Silent, FileOffer.readReply(null))
        assertEquals(FileOffer.Reply.Silent, FileOffer.readReply("   "))
    }
}
