package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app reads the host; it never mutates it.
 *
 * This is the audit, encoded. A profile disappeared on the test host overnight, and the
 * app must be provably not the cause — so the proof is a test that fails the moment
 * anyone adds a destructive request, not a paragraph that ages into a lie.
 *
 * Three facts are held:
 *
 *  1. only GET and POST are ever issued (no PUT, PATCH or DELETE);
 *  2. the profile surface (`HostProfiles.PATH`, `/v1/profiles`) is read with GET;
 *  3. no `"DELETE"` literal is used as an HTTP method — local `File.delete()` and
 *     `deleteOnExit()` are untouched because they are not request-building calls.
 *
 * The scan reads the request-building forms themselves (`Entity.open("…")`,
 * `requestMethod = "…"`, `setRequestMethod("…")`), so the word "delete" in ordinary
 * file cleanup can never trip it. A failure names the file, line and verb that was
 * added, and says why it is forbidden.
 */
class NoDestructiveRequestsTest {

    private data class Request(
        val file: String,
        val line: Int,
        val method: String,
        val context: String,
    )

    private val sourceRoots = listOf(
        File("src/main/java"),
        File("android/app/src/main/java"),
    )

    /** The request-building calls the app actually has: their method literal and call site. */
    private val verbs = listOf(
        Regex("Entity\\.open\\(\\s*\"([A-Za-z]+)\""),
        Regex("requestMethod\\s*=\\s*\"([A-Za-z]+)\""),
        Regex("setRequestMethod\\(\\s*\"([A-Za-z]+)\""),
    )

    /** A path that names the profile surface, not the conversational ask (`ProfilesAsk.PATH`). */
    private val profilePath = Regex("""HostProfiles\.PATH|"/[^"]*profiles""")

    private fun sources(): List<File> {
        val root = sourceRoots.firstOrNull { it.isDirectory } ?: error(
            "main sources not found from ${File(".").absolutePath} (looked for $sourceRoots)"
        )
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun requests(): List<Request> {
        val out = mutableListOf<Request>()
        for (file in sources()) {
            val lines = file.readLines()
            for ((i, line) in lines.withIndex()) {
                for (re in verbs) {
                    val m = re.find(line) ?: continue
                    val from = (i - 4).coerceAtLeast(0)
                    val to = (i + 6).coerceAtMost(lines.lastIndex)
                    out += Request(
                        file = file.path.removePrefix("./"),
                        line = i + 1,
                        method = m.groupValues[1].uppercase(),
                        context = lines.subList(from, to + 1).joinToString("\n"),
                    )
                }
            }
        }
        return out
    }

    private fun why(r: Request): String =
        "${r.file}:${r.line} issues HTTP ${r.method} in:\n" +
            r.context.lineSequence().filter { it.isNotBlank() }
                .joinToString("\n") { "    ${it.trim()}" } +
            "\nThe app reads the host; it never mutates it."

    @Test fun theScanSeesTheRequestsItIsMeantToGuard() {
        val all = requests()
        assertTrue("no request-building calls found — the scan is blind", all.isNotEmpty())
        assertTrue(
            "the profile surface is not among the scanned requests",
            all.any { profilePath.containsMatchIn(it.context) },
        )
    }

    @Test fun onlyGetAndPostAreEverIssued() {
        val allowed = setOf("GET", "POST")
        val offenders = requests().filter { it.method !in allowed }
        assertTrue(
            offenders.joinToString("\n\n") { why(it) } +
                "\nOnly GET and POST are permitted: a phone that reads the host cannot destroy it.",
            offenders.isEmpty(),
        )
    }

    @Test fun everyProfilesRequestIsAGet() {
        val offenders = requests().filter {
            profilePath.containsMatchIn(it.context) && it.method != "GET"
        }
        assertTrue(
            offenders.joinToString("\n\n") { why(it) } +
                "\nThe profile surface is read-only (`GET /v1/profiles`); a profile belongs to the host.",
            offenders.isEmpty(),
        )
    }

    @Test fun noDeleteLiteralIsUsedAsAnHttpMethod() {
        val offenders = requests().filter { it.method == "DELETE" }
        assertTrue(
            offenders.joinToString("\n\n") { why(it) } +
                "\nNo DELETE is permitted anywhere, for any path. Local File.delete() is not an HTTP call " +
                "and is deliberately not matched.",
            offenders.isEmpty(),
        )
    }

    /**
     * The app may still delete its own local files (staged shares, partial downloads, logs).
     * This pins that those calls are local — not request methods — so the rule above cannot
     * be "passed" by deleting the files' cleanup too.
     */
    @Test fun localFileCleanupIsNotMistakenForANetworkRequest() {
        val local = Regex("""\.delete\(\)|\.deleteOnExit\(\)""")
        assertTrue(
            "the app still cleans up its own local files; if this is gone, check the scan is not over-broad",
            sources().any { local.containsMatchIn(it.readText()) },
        )
        assertEquals("a File.delete() is not an HTTP method",
            emptyList<Request>(), requests().filter { it.method == "DELETE" })
    }
}
