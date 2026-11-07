package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stream parity harness (#126). Every fixture under testdata/stream is replayed
 * through [HermesSession] by the app's own wait → poll loop, and the outcome must
 * equal the committed golden. The goldens were produced by the Go core (the
 * since-removed tools/streamdump) and matched by this Kotlin core before the Go code
 * was deleted, so they are the frozen spec of the streaming behaviour.
 *
 * Fixture format and how to add one: testdata/stream/README.md. To write a golden
 * for a NEW fixture, run with STREAM_GOLDEN_UPDATE=1 — it writes only the missing
 * goldens, never overwrites one; review the output before committing it.
 *
 * The replay machinery lives in [StreamReplay] because [RouteParityTest] replays the
 * same fixtures through the /v1/runs route and compares the two outcomes.
 */
class StreamParityTest {

    @Test
    fun `every fixture replays to its golden`() {
        val fixtures = StreamReplay.fixtureDir().listFiles { f -> f.name.endsWith(".sse") }!!.sortedBy { it.name }
        assertTrue("no fixtures found", fixtures.isNotEmpty())
        val drifted = ArrayList<String>()
        for (f in fixtures) {
            val golden = File(f.parentFile, f.name.removeSuffix(".sse") + ".golden.json")
            val got = StreamReplay.plain(Json.parse(Json.encode(StreamReplay.replay(StreamReplay.parse(f)))))
            if (!golden.isFile && System.getenv("STREAM_GOLDEN_UPDATE") == "1") golden.writeText(StreamReplay.pretty(got, "") + "\n")
            assertTrue("${f.name} has no golden (see testdata/stream/README.md)", golden.isFile)
            val want = StreamReplay.plain(Json.parse(golden.readText()))
            if (got != want) drifted += "${f.name}\n--- kotlin\n${Json.encode(got)}\n--- golden\n${Json.encode(want)}"
        }
        assertEquals("drifted from the goldens:\n" + drifted.joinToString("\n\n"), 0, drifted.size)
    }

    /**
     * #126 review: a turn that used a tool appended its answer to the "[tool-done]"
     * marker line, and plainText() drops any line beginning with that marker — so
     * such a turn spoke nothing. The Go core had the same bug and the original
     * goldens therefore pinned it; the port preserved it faithfully and it was
     * fixed deliberately, with the goldens regenerated.
     */
    @Test
    fun `a turn that used a tool speaks its answer`() {
        val fx = StreamReplay.parse(File(StreamReplay.fixtureDir(), "tools.sse"))
        val out = StreamReplay.replay(fx)
        assertEquals("It is ten.", StreamReplay.lastSnapshot(out)["text"])
    }
}
