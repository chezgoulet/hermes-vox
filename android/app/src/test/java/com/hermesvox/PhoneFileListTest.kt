package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listing lane: `file_list`, and `file_read`'s byte window.
 *
 * The properties this file exists to keep:
 *
 *  * **listing is capable, not cautious** — a real folder is walked, a deep listing carries the
 *    relative path of every entry, a pattern hides files without hiding directories, and a folder
 *    with more entries than a page is **paged**, never refused and never silently cut;
 *  * **the tree is the only place it goes** — `..`, an absolute path, a separator-shaped name and a
 *    symlink that leaves the grant are each `bad_path`, before the platform is asked where that is
 *    possible and against the provider's answer where it is not;
 *  * **listing never reads a file** — a source assertion, because the property is an absence;
 *  * **a big file is readable** — `offset`/`length` return the right slice and `truncated` plus the
 *    total `bytes` tell the truth about what is still unread;
 *  * **the audit keeps the folder, the path and the count, never the names**.
 */
class PhoneFileListTest {

    /** A phone that answers what it is told, and records the arguments it was asked with. */
    private class Fake : PhoneEngine.PhonePlatform {
        var folders = emptyList<PhoneEngine.PhonePlatform.GrantedFolder>()
        var listing: PhoneFiles.ListResult = PhoneFiles.ListResult.MissingPath
        var text: PhoneEngine.PhonePlatform.FileText = PhoneEngine.PhonePlatform.FileText.Missing
        var window: PhoneEngine.PhonePlatform.FileText = PhoneEngine.PhonePlatform.FileText.Missing
        var seenList: List<Any?>? = null
        var seenWindow: List<Any?>? = null

        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean = true
        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean = true
        override fun cancelTimer(id: String): Boolean = true
        override fun dndGranted(): Boolean = true
        override fun currentDnd(): Boolean? = false
        override fun setDnd(on: Boolean): Boolean = true
        override fun currentVolume(): Int? = 30
        override fun setVolume(percent: Int): Int? = percent
        override fun adjustVolume(direction: Int): Int? = 30 + direction
        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean = true
        override fun deleteNote(id: String): Boolean = true
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean = true
        override fun playback(): PhoneEngine.PhonePlatform.Playback =
            PhoneEngine.PhonePlatform.Playback(false, null, null, null)
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                null, null, null, null, null, null, null, null, null, null, null,
            )
        override fun clip(text: String): Boolean = true

        override fun folders(): List<PhoneEngine.PhonePlatform.GrantedFolder> = folders

        override fun fileText(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileText = text

        override fun fileList(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            path: String,
            depth: Int,
            pattern: String?,
            offset: Int,
            limit: Int,
        ): PhoneFiles.ListResult {
            seenList = listOf(folder.label, path, depth, pattern, offset, limit)
            return listing
        }

        override fun fileWindow(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
            offset: Long,
            length: Long,
        ): PhoneEngine.PhonePlatform.FileText {
            seenWindow = listOf(folder.label, name, offset, length)
            return window
        }
    }

    private val fake = Fake()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L
    private val entries = ArrayList<PhoneAudit.Entry>()

    private fun only(id: String) = PhoneGrants.Source { g ->
        if (g.id == id) PhoneGrants.State.ASK else PhoneGrants.State.OFF
    }

    private fun engine(grants: PhoneGrants.Source) = PhoneEngine(
        fake,
        grants = grants,
        audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
    )

    private fun call(
        arguments: Map<String, Any?>,
        grants: PhoneGrants.Source = only("files"),
    ) = engine(grants).dispatch(arguments, t0, e0)

    private val folder = PhoneEngine.PhonePlatform.GrantedFolder("Download")

    private fun schemaEnum(grants: PhoneGrants.Source): List<String> {
        val schema = Json.parse(Json.encode(PhoneTool.schema(grants))) as JObj
        val input = schema["inputSchema"] as JObj
        val action = (input["properties"] as JObj)["action"] as JObj
        return (action["enum"] as JArr).items.map { (it as JStr).value }
    }

    /** A real directory tree on disk, plus the reader the SAF edge amounts to: metadata only. */
    private class Tree(val root: File) {
        fun dir(path: String): File = File(root, path).apply { mkdirs() }

        fun file(path: String, text: String = ""): File {
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeText(text)
            return f
        }

        /**
         * What a provider's child query yields: name, kind, size — never contents. A child whose
         * canonical path leaves [root] is [PhoneFiles.Dir.Outside], exactly as the primary-volume
         * check in `PhoneFileAccess` refuses it.
         */
        val reader = PhoneFiles.Reader { rel ->
            val dir = if (rel.isEmpty()) root else File(root, rel)
            val kids = dir.listFiles()
            if (kids == null) {
                PhoneFiles.Dir.Missing
            } else if (kids.any { !PhoneFiles.canonicalWithin(root, it) }) {
                PhoneFiles.Dir.Outside
            } else {
                PhoneFiles.Dir.Found(
                    kids.map { f ->
                        PhoneFiles.Entry(
                            name = f.name,
                            dir = f.isDirectory,
                            bytes = if (f.isDirectory) null else f.length(),
                            mime = if (f.isDirectory) null else "text/plain",
                        )
                    }
                )
            }
        }

        fun list(
            path: String = "",
            depth: Int = 1,
            pattern: String? = null,
            offset: Int = 0,
            limit: Int = PhoneFiles.DEFAULT_LIST_LIMIT,
        ): PhoneFiles.ListResult =
            PhoneFiles.list("Download", path, depth, pattern, offset, limit, reader)
    }

    private fun tree(build: Tree.() -> Unit): Tree {
        val root = Files.createTempDirectory("hv-list").toFile()
        val tree = Tree(root)
        tree.build()
        return tree
    }

    private fun ok(result: PhoneFiles.ListResult): PhoneFiles.Listing {
        assertTrue("expected a listing, got $result", result is PhoneFiles.ListResult.Ok)
        return (result as PhoneFiles.ListResult.Ok).listing
    }

    // ---- what a listing returns ----

    @Test
    fun a_listing_orders_directories_before_files_and_reports_size_and_kind() {
        val t = tree {
            dir("zeta")
            dir("alpha")
            file("b.txt", "12345")
            file("A.txt", "123")
        }
        try {
            val l = ok(t.list())
            assertEquals(listOf("alpha", "zeta", "A.txt", "b.txt"), l.entries.map { it.name })
            assertEquals(listOf("dir", "dir", "file", "file"), l.entries.map { it.kind })
            assertNull("a directory has no size", l.entries[0].bytes)
            assertNull("and no size", l.entries[1].bytes)
            assertEquals(3L, l.entries[2].bytes)
            assertEquals(5L, l.entries[3].bytes)
            assertEquals("each entry carries the path it was found at", "A.txt", l.entries[2].path)
            assertEquals(4, l.count)
            assertFalse(l.hasMore)
            assertNull(l.nextOffset)
            assertEquals(1, l.depth)
            assertEquals("Download", l.folder)
            assertEquals("the granted root reads as an empty path", "", l.path)
        } finally {
            t.root.deleteRecursively()
        }
    }

    @Test
    fun depth_two_adds_nested_entries_with_their_paths_and_depth_one_does_not() {
        val t = tree {
            dir("reports")
            file("reports/q3.pdf", "q3")
            dir("reports/2026")
            file("reports/2026/q4.pdf", "q4!")
            file("notes.txt")
        }
        try {
            val shallow = ok(t.list(depth = 1))
            assertEquals(
                listOf("reports", "notes.txt"),
                shallow.entries.map { it.path },
            )

            val deep = ok(t.list(depth = 2))
            assertEquals(
                listOf(
                    "reports",
                    "reports/2026",
                    "reports/q3.pdf",
                    "notes.txt",
                ),
                deep.entries.map { it.path },
            )
            assertEquals(2, deep.depth)
            assertEquals("dir", deep.entries[1].kind)
            assertEquals("file", deep.entries[2].kind)
            assertEquals(2L, deep.entries[2].bytes)
            assertEquals("still inside the depth, a directory's own children", "reports/2026", deep.entries[1].path)
            assertFalse(
                "depth 2 does not reach two subdirectories down",
                deep.entries.any { it.path == "reports/2026/q4.pdf" },
            )
            val deepest = ok(t.list(depth = 3))
            assertTrue(
                "depth 3 reaches it, with its path",
                deepest.entries.any { it.path == "reports/2026/q4.pdf" },
            )
        } finally {
            t.root.deleteRecursively()
        }
    }

    @Test
    fun a_pattern_filters_files_and_leaves_directories_visible() {
        val t = tree {
            dir("papers")
            file("papers/invoice.pdf")
            file("papers/notes.txt")
            dir("misc")
            file("report.PDF")
            file("draft.doc")
        }
        try {
            val l = ok(t.list(depth = 2, pattern = "*.pdf"))
            assertEquals(
                "matching files only, directories always, and the glob ignores case",
                listOf("misc", "papers", "papers/invoice.pdf", "report.PDF"),
                l.entries.map { it.path },
            )
            assertEquals("a directory is visible even when it does not match", "dir", l.entries[0].kind)
            assertTrue("the glob is case-insensitive", PhoneFiles.matches("invoice*", "INVOICE-2026.pdf"))
            assertTrue(PhoneFiles.matches("*", "anything"))
            assertFalse(PhoneFiles.matches("*.pdf", "notes.txt"))
        } finally {
            t.root.deleteRecursively()
        }
    }

    @Test
    fun a_folder_with_more_entries_than_a_page_is_paged_and_every_entry_is_seen_once() {
        val t = tree {
            repeat(250) { file("f%03d.txt".format(it), "x") }
            dir("sub")
            file("sub/one.txt", "y")
        }
        try {
            val everything = ok(t.list(depth = 2, limit = PhoneFiles.MAX_LIST_LIMIT))
            assertEquals("nothing was refused for being large", 252, everything.count)

            val seen = ArrayList<String>()
            var offset = 0
            var pages = 0
            while (true) {
                val page = ok(t.list(depth = 2, offset = offset, limit = 100))
                pages++
                seen.addAll(page.entries.map { it.path })
                if (!page.hasMore) {
                    assertNull("no more means no next offset", page.nextOffset)
                    break
                }
                assertNotNull("more means an offset to ask for", page.nextOffset)
                assertEquals(offset + page.count, page.nextOffset)
                assertEquals("a full page in the middle", 100, page.count)
                offset = page.nextOffset!!
            }
            assertEquals("250 files and a subdirectory with one", 3, pages)
            assertEquals(everything.entries.map { it.path }, seen)
            assertEquals("every entry exactly once", seen.size, seen.toSet().size)
            assertEquals(252, seen.size)

            val beyond = ok(t.list(depth = 2, offset = 10_000, limit = 100))
            assertEquals(0, beyond.count)
            assertFalse(beyond.hasMore)
            assertNull(beyond.nextOffset)
        } finally {
            t.root.deleteRecursively()
        }
    }

    @Test
    fun a_page_can_start_inside_the_walk_and_each_page_is_the_same_page_as_the_unpaged_walk() {
        val t = tree {
            dir("a")
            file("a/1.txt", "1")
            dir("b")
            file("b/2.txt", "22")
            file("c.txt", "333")
        }
        try {
            val all = ok(t.list(depth = 2, offset = 0, limit = 10)).entries.map { it.path }
            assertEquals(5, all.size)
            val second = ok(t.list(depth = 2, offset = 2, limit = 2))
            assertEquals(all.subList(2, 4), second.entries.map { it.path })
            assertEquals(true, second.hasMore)
            assertEquals(4, second.nextOffset)
            val last = ok(t.list(depth = 2, offset = 4, limit = 2))
            assertEquals(all.subList(4, 5), last.entries.map { it.path })
            assertFalse(last.hasMore)
        } finally {
            t.root.deleteRecursively()
        }
    }

    @Test
    fun an_empty_directory_is_an_empty_page_and_a_missing_one_says_so() {
        val t = tree { dir("empty") }
        try {
            val empty = ok(t.list(path = "empty"))
            assertEquals(0, empty.count)
            assertFalse(empty.hasMore)
            assertEquals(PhoneFiles.ListResult.MissingPath, t.list(path = "nowhere"))
        } finally {
            t.root.deleteRecursively()
        }
    }

    // ---- the tree is the only place it goes ----

    @Test
    fun a_listing_path_that_leaves_or_absolutises_is_bad_path_before_the_platform_is_asked() {
        for (path in listOf(
            "..",
            "../secret",
            "reports/../../secret",
            "/sdcard/Download",
            "\\Windows",
            "C:\\secret",
            "a\\b",
            "content://x/y",
            "a//b",
            "./a",
        )) {
            assertNotNull("$path must not be a listing path", PhoneFiles.badListPath(path))
            val r = call(mapOf("action" to "file_list", "path" to path))
            assertFalse(r.json(), r.ok)
            assertEquals("$path must be bad_path", PhoneReason.BAD_PATH, r.reason)
            assertNull("the platform was never asked", fake.seenList)
        }
        assertNull("the root is the empty path", PhoneFiles.badListPath(""))
        assertNull("a relative descent is the point", PhoneFiles.badListPath("reports/2026"))
    }

    @Test
    fun a_relative_path_is_checked_against_the_tree_id_as_well() {
        assertTrue(PhoneFiles.containedAt("primary:Download", "primary:Download", ""))
        assertTrue(
            PhoneFiles.containedAt(
                "primary:Download", "primary:Download/reports/2026", "reports/2026",
            )
        )
        assertFalse(
            "a sibling tree is not the granted one",
            PhoneFiles.containedAt("primary:Download", "primary:Documents/reports", "reports"),
        )
        assertFalse(
            "one segment more than the path says is not the path",
            PhoneFiles.containedAt("primary:Download", "primary:Download/reports/2026", "reports"),
        )
        assertFalse(
            "dot-dot never passes an id",
            PhoneFiles.containedAt("primary:Download", "primary:Download/../Documents", ".."),
        )
    }

    @Test
    fun a_symlink_that_leaves_the_granted_tree_is_bad_path() {
        val root = Files.createTempDirectory("hv-root").toFile()
        val outside = Files.createTempDirectory("hv-outside").toFile()
        try {
            File(root, "inside.txt").writeText("inside")
            val secret = File(outside, "secret.txt").apply { writeText("outside") }
            Files.createSymbolicLink(File(root, "escape.txt").toPath(), secret.toPath())

            val t = Tree(root)
            assertEquals(
                "a link out of the tree stops the listing",
                PhoneFiles.ListResult.Outside,
                t.list(),
            )

            // Through the tool, the platform's answer is the same bad_path as a name that is a path.
            fake.folders = listOf(folder)
            fake.listing = PhoneFiles.ListResult.Outside
            val r = call(mapOf("action" to "file_list"))
            assertFalse(r.ok)
            assertEquals(PhoneReason.BAD_PATH, r.reason)
            assertTrue("nothing was listed", r.facts.isEmpty())

            // The SAF edge applies the canonical check on the listing path itself.
            val code = rawCodeOf("src/main/java/com/hermesvox/PhoneFileAccess.kt")
            val listing = code.substringAfter("fun list(").substringBefore("// ---- bytes")
            assertTrue(
                "the listing path canonicalises a primary-volume child",
                listing.contains("canonicalWithin"),
            )
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun a_provider_that_calls_a_child_a_path_is_refused_rather_than_listed() {
        val pointed = PhoneFiles.list("Download", "", 1, null, 0, 200) { path ->
            if (path.isEmpty()) {
                PhoneFiles.Dir.Found(
                    listOf(PhoneFiles.Entry(name = "../secret.txt", dir = false, bytes = 3))
                )
            } else {
                PhoneFiles.Dir.Missing
            }
        }
        assertEquals(PhoneFiles.ListResult.Outside, pointed)
    }

    @Test
    fun no_granted_folder_is_no_folder_for_a_listing_too() {
        fake.folders = emptyList()
        val r = call(mapOf("action" to "file_list"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NO_FOLDER, r.reason)
        assertNull("the platform was never asked to list", fake.seenList)
        assertTrue("the refusal says how to grant one", r.detail!!.contains("Phone tools"))
    }

    @Test
    fun a_provider_failure_is_unavailable_and_never_a_partial_page() {
        fake.folders = listOf(folder)
        fake.listing = PhoneFiles.ListResult.Failed("the folder would not say what is in it")
        val r = call(mapOf("action" to "file_list"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertFalse(r.toMap().containsKey("entries"))
    }

    /**
     * The property is an absence, so a source assertion is the only honest test: the listing path
     * asks a provider which names are in a directory and never opens one. Comments are stripped
     * first so the explanation above can say "opens" without tripping its own check.
     */
    @Test
    fun the_listing_path_never_opens_a_file() {
        val code = rawCodeOf("src/main/java/com/hermesvox/PhoneFileAccess.kt")
        val listing = code.substringAfter("fun list(").substringBefore("// ---- bytes")
        assertTrue("the region is the listing edge", listing.contains("children("))
        for (forbidden in listOf(
            "openInputStream",
            "openOutputStream",
            "openFileDescriptor",
            "openAssetFileDescriptor",
            "readBytes",
            "FileInputStream",
        )) {
            assertFalse("listing must not open a file: $forbidden", listing.contains(forbidden))
        }
        // The pure walk cannot even ask: its whole interface is "which children does this hold".
        val pure = rawCodeOf("src/main/java/com/hermesvox/PhoneFiles.kt")
            .substringAfter("fun interface Reader")
            .substringBefore("private val ORDER")
        assertTrue("the reader returns metadata only", pure.contains("fun children(path: String): Dir"))
        assertFalse(pure.contains("InputStream"))
        assertFalse(pure.contains("readBytes"))
    }

    // ---- the result shape ----

    @Test
    fun the_listing_result_carries_the_documented_fields_in_order() {
        fake.folders = listOf(folder)
        fake.listing = PhoneFiles.ListResult.Ok(
            PhoneFiles.Listing(
                folder = "Download",
                path = "",
                depth = 1,
                count = 2,
                hasMore = false,
                nextOffset = null,
                entries = listOf(
                    PhoneFiles.Row("papers", "papers", true, null, null, null),
                    PhoneFiles.Row(
                        "a.txt", "a.txt", false, 3, "2026-11-05T09:30:00", "text/plain",
                    ),
                ),
            )
        )
        val r = call(mapOf("action" to "file_list"))
        assertTrue(r.json(), r.ok)
        assertEquals(
            listOf(
                "ok", "action", "tier", "folder", "path", "depth", "count", "has_more",
                "next_offset", "entries", "undoable",
            ),
            r.toMap().keys.toList(),
        )
        val wire = r.toMap()
        assertTrue("next_offset is present even when there is no next page", wire.containsKey("next_offset"))
        assertNull(wire["next_offset"])
        val rows = wire["entries"] as List<*>
        assertEquals(
            setOf("name", "path", "kind", "bytes", "modified", "mime"),
            (rows[0] as Map<*, *>).keys,
        )
        assertEquals("dir", (rows[0] as Map<*, *>)["kind"])
        assertNull("a directory has no bytes", (rows[0] as Map<*, *>)["bytes"])
        assertEquals("file", (rows[1] as Map<*, *>)["kind"])
        assertEquals("2026-11-05T09:30:00", (rows[1] as Map<*, *>)["modified"])
        assertFalse("a listing is not undoable", r.toMap().containsKey("undo"))
    }

    @Test
    fun the_listing_defaults_and_bounds_are_the_contract() {
        val plain = PhoneTool.parse(mapOf("action" to "file_list"), only("files"))
        val call = (plain as PhoneTool.Parsed.Run).call as PhoneTool.Call.FileList
        assertEquals("the root of the granted folder", "", call.path)
        assertEquals(PhoneFiles.DEFAULT_DEPTH, call.depth)
        assertEquals(PhoneFiles.DEFAULT_LIST_LIMIT, call.limit)
        assertEquals(0, call.offset)
        assertNull(call.pattern)
        assertNull("the default folder is resolved by the engine", call.folder)

        val top = PhoneTool.parse(
            mapOf("action" to "file_list", "folder" to "Documents", "path" to "a/b", "depth" to 5,
                "pattern" to "*.pdf", "offset" to 40, "limit" to 1000),
            only("files"),
        ) as PhoneTool.Parsed.Run
        val bounded = top.call as PhoneTool.Call.FileList
        assertEquals("Documents", bounded.folder)
        assertEquals("a/b", bounded.path)
        assertEquals(5, bounded.depth)
        assertEquals("*.pdf", bounded.pattern)
        assertEquals(40, bounded.offset)
        assertEquals(1000, bounded.limit)

        for (bad in listOf(
            mapOf("action" to "file_list", "depth" to 0),
            mapOf("action" to "file_list", "depth" to 6),
            mapOf("action" to "file_list", "limit" to 0),
            mapOf("action" to "file_list", "limit" to 1001),
            mapOf("action" to "file_list", "offset" to -1),
            mapOf("action" to "file_list", "pattern" to "x".repeat(PhoneFiles.MAX_PATTERN_CHARS + 1)),
        )) {
            val refused = PhoneTool.parse(bad, only("files"))
            assertEquals(
                "$bad must be invalid",
                PhoneReason.INVALID,
                (refused as PhoneTool.Parsed.Refuse).result.reason,
            )
        }
    }

    @Test
    fun file_list_is_reported_tier_and_is_absent_until_files_is_on() {
        assertEquals(PhoneTiers.REPORTED, PhoneTiers.of("file_list"))
        assertEquals(PhoneTiers.REPORTED, PhoneGrants.tierOf("file_list"))
        assertFalse("absent while Files is off", "file_list" in schemaEnum(PhoneGrants.ALL_OFF))
        assertTrue("advertised while Files is on", "file_list" in schemaEnum(only("files")))
    }

    @Test
    fun the_listing_is_audited_as_folder_path_and_count_and_never_the_names() {
        fake.folders = listOf(folder)
        fake.listing = PhoneFiles.ListResult.Ok(
            PhoneFiles.Listing(
                "Download", "", 1, 2, false, null,
                listOf(
                    PhoneFiles.Row("tax-return.pdf", "tax-return.pdf", false, 5, null, "application/pdf"),
                    PhoneFiles.Row("photo.png", "photo.png", false, 7, null, "image/png"),
                ),
            )
        )
        val r = call(mapOf("action" to "file_list"))
        assertTrue(r.json(), r.ok)
        val entry = entries.single()
        assertEquals("file_list", entry.action)
        assertEquals("the folder the call really listed", "Download", entry.arguments["folder"])
        assertEquals("the root reads as /", "/", entry.arguments["path"])
        assertEquals("2 entries", entry.arguments["count"])
        assertEquals("done", entry.outcome)
        assertNull(entry.undoId)
        assertFalse(
            "a listing's names are the owner's",
            entry.arguments.values.any { it.contains("tax") || it.contains("photo") },
        )
    }

    // ---- file_read windows ----

    @Test
    fun a_window_read_returns_the_slice_and_the_total_the_truth_needs() {
        fake.folders = listOf(folder)
        fake.window = PhoneEngine.PhonePlatform.FileText.Window(
            name = "big.log",
            totalBytes = 100_000,
            offset = 65_536,
            windowBytes = 5,
            text = "hello",
        )
        val r = call(
            mapOf("action" to "file_read", "name" to "big.log", "offset" to 65_536, "length" to 5),
        )
        assertTrue(r.json(), r.ok)
        assertEquals("hello", r.facts["text"])
        assertEquals(100_000L, r.facts["bytes"])
        assertEquals(65_536L, r.facts["offset"])
        assertEquals(5L, r.facts["length"])
        assertEquals(5, r.facts["chars"])
        assertEquals(true, r.facts["truncated"])
        assertEquals(listOf<Any?>("Download", "big.log", 65_536L, 5L), fake.seenWindow)
        assertFalse("a read has no undo", r.toMap().containsKey("undo"))
    }

    @Test
    fun a_window_that_is_the_whole_file_is_not_called_truncated() {
        fake.folders = listOf(folder)
        fake.window = PhoneEngine.PhonePlatform.FileText.Window(
            name = "small.txt", totalBytes = 5, offset = 0, windowBytes = 5, text = "hello",
        )
        val r = call(mapOf("action" to "file_read", "name" to "small.txt", "offset" to 0, "length" to 9))
        assertTrue(r.json(), r.ok)
        assertEquals(false, r.facts["truncated"])
        assertEquals(5L, r.facts["bytes"])
    }

    @Test
    fun a_window_defaults_to_one_cap_and_both_bounds_are_read_as_bytes() {
        val withOffset = PhoneTool.parse(
            mapOf("action" to "file_read", "name" to "big.log", "offset" to 10),
            only("files"),
        ) as PhoneTool.Parsed.Run
        val a = withOffset.call as PhoneTool.Call.FileRead
        assertEquals(10L, a.offset)
        assertEquals("an offset alone takes one cap-sized window", PhoneFiles.MAX_READ_BYTES, a.length)

        val withLength = PhoneTool.parse(
            mapOf("action" to "file_read", "name" to "big.log", "length" to 500),
            only("files"),
        ) as PhoneTool.Parsed.Run
        val b = withLength.call as PhoneTool.Call.FileRead
        assertEquals(0L, b.offset)
        assertEquals(500L, b.length)

        val whole = PhoneTool.parse(
            mapOf("action" to "file_read", "name" to "small.txt"), only("files"),
        ) as PhoneTool.Parsed.Run
        val c = whole.call as PhoneTool.Call.FileRead
        assertNull("no offset means the whole-file path", c.offset)
        assertNull(c.length)

        for (bad in listOf(
            mapOf("action" to "file_read", "name" to "x.txt", "offset" to -1),
            mapOf("action" to "file_read", "name" to "x.txt", "length" to 0),
            mapOf("action" to "file_read", "name" to "x.txt", "offset" to 1.5),
        )) {
            val refused = PhoneTool.parse(bad, only("files"))
            assertEquals(
                "$bad must be invalid",
                PhoneReason.INVALID,
                (refused as PhoneTool.Parsed.Refuse).result.reason,
            )
        }
    }

    @Test
    fun a_window_snaps_to_character_boundaries_instead_of_splitting_one() {
        val bytes = "héllo".toByteArray()
        val fromStart = PhoneFiles.windowText(bytes.copyOfRange(1, bytes.size), wholeFile = false)!!
        assertEquals("éllo", fromStart.text)

        val midCharacter = PhoneFiles.windowText(bytes.copyOfRange(2, bytes.size), wholeFile = false)!!
        assertEquals("the bytes before the window cannot be invented", "llo", midCharacter.text)
        assertEquals(1, midCharacter.skipped)

        val cutShort = PhoneFiles.windowText(bytes.copyOfRange(0, 2), wholeFile = false)!!
        assertEquals("an incomplete trailing character is trimmed, not replaced", "h", cutShort.text)
        assertEquals(1, cutShort.bytes)

        assertNull(
            "a NUL is binary in a window too",
            PhoneFiles.windowText(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()), false),
        )
    }

    @Test
    fun an_over_cap_whole_file_now_says_to_ask_for_a_window() {
        fake.folders = listOf(folder)
        fake.text = PhoneEngine.PhonePlatform.FileText.TooBig(70_000)
        val r = call(mapOf("action" to "file_read", "name" to "big.txt"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.TOO_BIG, r.reason)
        assertTrue("the refusal names the way in", r.detail!!.contains("offset and length"))
    }

    /** A file's code as written, comments included: used only to slice a region, never to assert it. */
    private fun rawCodeOf(path: String): String = File(path).readText()
}
