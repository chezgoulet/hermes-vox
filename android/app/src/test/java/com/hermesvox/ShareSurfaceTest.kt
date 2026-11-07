package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * #370's pure rules: the clipboard is a file with a generated name, a mime chooses the
 * extension, an unreadable share is refused in words, and the share row inherits one of the
 * six verbs instead of growing a seventh.
 *
 * The Android edges — reading `ClipData`, the document picker, the content resolver's stream —
 * are device work; see the PR's "needs a device" list.
 */
class ShareSurfaceTest {

    /** 2026-11-02T14:31:00Z — the issue's own example clock. */
    private val now = 1_793_629_860_000L
    private val utc = TimeZone.getTimeZone("UTC")

    // ---- the clipboard-to-filename rule ----

    @Test fun aClipboardStringBecomesAFileNamedFromTheClock() {
        val plan = ShareSurface.clipboardPlan(
            text = "https://github.com/chezgoulet/hermes-vox/issues/370",
            uriMime = null,
            hasUri = false,
            nowMs = now,
            zone = utc,
        )
        assertEquals(ShareSurface.ClipKind.TEXT, plan.kind)
        assertEquals("clipboard-2026-11-02T14-31.txt", plan.name)
        assertEquals("text/plain", plan.mime)
        // The body is the clipboard exactly; the name is the only thing invented.
        assertEquals("https://github.com/chezgoulet/hermes-vox/issues/370", plan.text)
    }

    @Test fun aUrlAndACodeBlockAreBothTxtFiles() {
        val url = ShareSurface.clipboardPlan("https://example.com/a?b=c", null, false, now, utc)
        val code = ShareSurface.clipboardPlan("fun main() {\n  println(1)\n}\n", null, false, now, utc)
        assertEquals("clipboard-2026-11-02T14-31.txt", url.name)
        assertEquals("clipboard-2026-11-02T14-31.txt", code.name)
        assertEquals("text/plain", url.mime)
        assertEquals("text/plain", code.mime)
        // Multi-line text is preserved byte-for-byte; only the name is generated.
        assertTrue(code.text!!.contains("\n  println(1)\n"))
    }

    @Test fun anImageOnTheClipboardIsARealImageFile() {
        val plan = ShareSurface.clipboardPlan(
            text = null,
            uriMime = "image/png",
            hasUri = true,
            nowMs = now,
            zone = utc,
        )
        assertEquals(ShareSurface.ClipKind.URI, plan.kind)
        assertEquals("clipboard-2026-11-02T14-31.png", plan.name)
        assertEquals("image/png", plan.mime)
        assertNull("an image's bytes come from the resolver, not from a string", plan.text)
    }

    @Test fun aClipboardWithTextAndAUriStillTreatsTheTextAsTheArtifact() {
        // A provider that offers both is unusual; text wins because it needs no read grant.
        val plan = ShareSurface.clipboardPlan("hello", "image/png", true, now, utc)
        assertEquals(ShareSurface.ClipKind.TEXT, plan.kind)
        assertEquals("text/plain", plan.mime)
    }

    // ---- the mime-to-extension table ----

    @Test fun mimeToExtensionIsOneTable() {
        val expected = mapOf(
            "text/plain" to "txt",
            "text/markdown" to "md",
            "text/html" to "html",
            "text/csv" to "csv",
            "application/json" to "json",
            "application/xml" to "xml",
            "application/pdf" to "pdf",
            "application/zip" to "zip",
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/svg+xml" to "svg",
            "audio/mpeg" to "mp3",
            "video/mp4" to "mp4",
        )
        for ((mime, ext) in expected) assertEquals(mime, ext, ShareSurface.extensionFor(mime))
    }

    @Test fun aMimeParameterDoesNotBecomePartOfTheExtension() {
        assertEquals("txt", ShareSurface.extensionFor("text/plain; charset=utf-8"))
        assertEquals("txt", ShareSurface.extensionFor("TEXT/PLAIN"))
    }

    @Test fun structuredSuffixesAndUnknownTypesAreSafe() {
        assertEquals("json", ShareSurface.extensionFor("application/ld+json"))
        assertEquals("xml", ShareSurface.extensionFor("application/atom+xml"))
        assertEquals("bin", ShareSurface.extensionFor("application/octet-stream"))
        // An explicitly known long vendor type still gets its real extension…
        assertEquals("docx", ShareSurface.extensionFor("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        // …but an unknown vendor type's subtype is not a file name; do not let it invent one.
        assertEquals("bin", ShareSurface.extensionFor("application/vnd.example.thing"))
        assertEquals("bin", ShareSurface.extensionFor(""))
    }

    // ---- the refuse-when-unreadable path ----

    @Test fun anEmptyClipboardIsRefusedInWordsAndInventedNoName() {
        val plan = ShareSurface.clipboardPlan(null, null, false, now, utc)
        assertEquals(ShareSurface.ClipKind.EMPTY, plan.kind)
        assertEquals("", plan.name)
        assertEquals(ShareSurface.EMPTY_CLIPBOARD, plan.line)
        assertFalse("an empty clip must not look like a text file", plan.line.contains("clipboard-"))
    }

    @Test fun whitespaceOnlyTextIsStillEmpty() {
        val plan = ShareSurface.clipboardPlan("  \n\t ", "image/png", false, now, utc)
        assertEquals(ShareSurface.ClipKind.EMPTY, plan.kind)
        assertEquals(ShareSurface.EMPTY_CLIPBOARD, plan.line)
    }

    @Test fun aShareWithNothingIsRefusedAndAMultipleShareSaysTheCount() {
        assertEquals("this share had nothing Vox could read · nothing was sent", ShareSurface.NOTHING_SHARED)
        assertEquals(
            "one file at a time · you shared 3 files; share them one by one",
            ShareSurface.multipleLine(3),
        )
        assertEquals(
            "Vox can open files and web links · this is a tel: link",
            ShareSurface.unsupportedView("tel"),
        )
    }

    // ---- names for the other two entry points ----

    @Test fun aSharedStreamAndALinkGetTheirOwnPrefixes() {
        assertEquals("shared-2026-11-02T14-31.pdf", ShareSurface.sharedName("application/pdf", now, utc))
        assertEquals("link-2026-11-02T14-31.txt", ShareSurface.linkName(now, utc))
    }

    @Test fun theCardNamesTheFileTypeAndSizeNotTheBytes() {
        val file = FileOffer.File("meeting-notes.md", "text/markdown", 8_241, "abc")
        assertEquals("meeting-notes.md · text/markdown · 8.2 KB", ShareSurface.previewLine(file))
        assertEquals("You: shared meeting-notes.md · text/markdown · 8.2 KB",
            FileOffer.transcriptLine(file, inline = false))
        assertEquals("You: shared meeting-notes.md · text/markdown · 8.2 KB · inline",
            FileOffer.transcriptLine(file, inline = true))
    }

    // ---- the row keeps the six-verb grammar ----

    @Test fun theShareRowIsAnArtifactActionNotASlashCommand() {
        val row = CommandCatalog.SHARE
        assertEquals("Share with agent", row.name)
        assertFalse("a row outside the command catalogue", CommandCatalog.ALL.contains(row))
        assertEquals(CommandCatalog.Category.CHOOSE, row.category)
        assertEquals(CommandMotion.Verb.PULSE, CommandMotion.verbFor(row.category))
        assertEquals(row, CommandCatalog.find("share with agent"))
        assertEquals(row, CommandCatalog.find("Share with agent"))
        // It is findable by what a person wants, not only by its name.
        assertTrue(CommandCatalog.search("share").contains(row))
        assertTrue(CommandCatalog.search("clipboard").contains(row))
    }
}
