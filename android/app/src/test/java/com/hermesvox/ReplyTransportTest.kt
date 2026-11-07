package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #240 — the four-rule cancel contract at the transport boundary, off-device.
 *
 * The fake transport stands in for the wire + the synth worker's sentence queue: a
 * frame carries the reply id that PRODUCED it (the field bug was a frame from a cut
 * reply arriving after the next turn had begun). The player only receives a frame if
 * [ReplyLedger.accepts] still says the id is open, exactly as the streaming worker
 * checks before it hands a queued sentence to the engine. So the assertions here are
 * about the real ledger rules, not a reimplementation of them.
 *
 * The regression this pins: a late frame stamped with a cancelled (or completed) id
 * must be dropped and logged, and must never start a stream — even while a newer
 * reply is playing normally.
 */
class ReplyTransportTest {

    /** The wire + the worker admission gate, with no Android and no TTS. */
    private class FakeTransport(private val capacity: Int = 32) {
        private val ledger = ReplyLedger(capacity)
        private val inFlight = ArrayDeque<Pair<Long, String>>()
        val logs = ArrayList<String>()
        val played = ArrayList<Pair<Long, String>>()
        /** One entry per id that got its end event (rule 1). */
        val ends = ArrayList<Pair<Long, ReplyLedger.End>>()

        fun open(id: Long): Boolean = ledger.begin(id)

        /** A frame arrives from the wire, stamped with the id of the turn that made it. */
        fun send(id: Long, text: String): Boolean {
            if (!ledger.accepts(id)) {
                logs += "event=tts-drop reason=reply-ended gen=$id source=delta"
                return false
            }
            inFlight.addLast(id to text)
            return true
        }

        /** The cancel is issued: playback stops now; the id gets its one end event. */
        fun cancel(id: Long): Boolean = end(id, ReplyLedger.End.CANCELLED, "barge-in")

        fun complete(id: Long): Boolean = end(id, ReplyLedger.End.DONE, "")

        private fun end(id: Long, how: ReplyLedger.End, cause: String): Boolean {
            if (!ledger.end(id, how)) return false
            ends += id to how
            logs += if (how == ReplyLedger.End.CANCELLED)
                "event=reply-end gen=$id reason=cancelled cause=$cause"
            else "event=reply-end gen=$id reason=${how.name.lowercase()}"
            return true
        }

        /** The synth worker's dequeue/play loop. */
        fun drain() {
            while (inFlight.isNotEmpty()) {
                val (id, text) = inFlight.removeFirst()
                if (!ledger.accepts(id)) {
                    logs += "event=tts-drop reason=reply-ended gen=$id source=queue"
                    continue
                }
                played += id to text
            }
        }

        fun accepts(id: Long) = ledger.accepts(id)
        fun isDead(id: Long) = ledger.isDead(id)
        fun ending(id: Long) = ledger.ending(id)
    }

    @Test fun aCancelledReplysInFlightFramesNeverReachThePlayer() {
        val t = FakeTransport()
        assertTrue(t.open(4))
        assertTrue(t.send(4, "The first half is already"))
        assertTrue(t.send(4, "on the wire."))

        assertTrue(t.cancel(4))          // barge-in: stop playback, end the id
        t.drain()                        // the worker wakes to frames it had queued

        assertEquals("zero frames of the cut reply play", 0, t.played.size)
        assertTrue(t.logs.any { it.contains("source=queue") && it.contains("gen=4") })
    }

    @Test fun aLateFrameForACancelledIdIsIgnoredAndLogged() {
        val t = FakeTransport()
        assertTrue(t.open(4))
        assertTrue(t.cancel(4))
        assertTrue(t.open(5))            // the next turn is live
        assertTrue(t.send(5, "The new answer."))

        assertFalse("a frame carrying the dead id is refused", t.send(4, "and the old one."))
        t.drain()

        assertEquals(listOf(5L to "The new answer."), t.played)
        assertTrue("the drop is logged", t.logs.any {
            it.contains("reason=reply-ended") && it.contains("gen=4") && it.contains("source=delta")
        })
        assertEquals("the cancelled id ended exactly once",
            listOf(4L to ReplyLedger.End.CANCELLED), t.ends)
        assertTrue(t.isDead(4))
    }

    @Test fun aFrameAfterTheEndEventIsIgnored() {
        val t = FakeTransport()
        assertTrue(t.open(6))
        assertTrue(t.send(6, "Only this sentence."))
        t.drain()                        // the reply's audio plays...
        assertTrue(t.complete(6))        // ...then the normal end event lands
        assertFalse("the stream cannot resume after its end", t.send(6, "Late tail."))
        t.drain()

        assertEquals(listOf(6L to "Only this sentence."), t.played)
        assertEquals(ReplyLedger.End.DONE, t.ending(6))
        assertTrue("a completed id is dead for audio too", t.isDead(6))
        assertTrue(t.logs.any { it.contains("reason=reply-ended") && it.contains("gen=6") })
    }

    @Test fun aDuplicateEndEventIsRefused() {
        val t = FakeTransport()
        assertTrue(t.open(7))
        assertTrue(t.cancel(7))
        assertFalse("a second cancel", t.cancel(7))
        assertFalse("a late natural end after the cancel", t.complete(7))
        assertEquals("exactly one end event per id",
            listOf(7L to ReplyLedger.End.CANCELLED), t.ends)
        assertEquals(1, t.logs.count { it.contains("event=reply-end") })
    }

    @Test fun anIdThatWasNeverCancelledStillStreamsNormally() {
        val t = FakeTransport()
        assertTrue(t.open(8))
        assertTrue(t.send(8, "A normal "))
        assertTrue(t.send(8, "reply."))
        t.drain()

        assertEquals(listOf(8L to "A normal ", 8L to "reply."), t.played)
        assertTrue(t.accepts(8))
        assertFalse(t.isDead(8))
        assertTrue(t.complete(8))
        assertEquals(listOf(8L to ReplyLedger.End.DONE), t.ends)
    }

    @Test fun theRingEvictsOldIdsWithoutResurrectingThem() {
        // Rule 3: the window is bounded, but an evicted id is still dead — a frame from
        // long ago cannot reopen it.
        val t = FakeTransport(capacity = 4)
        for (id in 1L..10L) { t.open(id); t.complete(id) }
        assertTrue(t.isDead(1))
        assertFalse(t.open(1))
        assertFalse(t.send(1, "from the ring"))
    }
}
