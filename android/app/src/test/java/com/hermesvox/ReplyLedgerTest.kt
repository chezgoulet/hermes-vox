package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #240: one end per reply id, no audio after it, and a cut id stays dead. */
class ReplyLedgerTest {

    @Test fun exactlyOneEndPerId() {
        val l = ReplyLedger()
        assertTrue(l.begin(1))
        assertTrue(l.end(1, ReplyLedger.End.CANCELLED))
        assertEquals(ReplyLedger.End.CANCELLED, l.ending(1))
        assertFalse("a second end", l.end(1, ReplyLedger.End.CANCELLED))
        assertFalse("a late natural end after a cancel", l.end(1, ReplyLedger.End.DONE))
        assertFalse("a late failure after a cancel", l.end(1, ReplyLedger.End.FAILED))
    }

    @Test fun noEndForAnIdThatNeverBegan() {
        // A hang-up with no turn running must not log a reply ending.
        assertFalse(ReplyLedger().end(7, ReplyLedger.End.CANCELLED))
    }

    @Test fun noAudioAfterTheEndDoneOrCancelled() {
        val l = ReplyLedger()
        l.begin(1); l.begin(2)
        assertTrue(l.accepts(1))
        l.end(1, ReplyLedger.End.CANCELLED)
        l.end(2, ReplyLedger.End.DONE)
        assertFalse(l.accepts(1)); assertFalse(l.accepts(2))
    }

    @Test fun aLateFrameOfACutReplyIsIgnoredWhileTheNextTurnPlays() {
        // The field case: turn 4 is cut, turn 5 begins, and a chunk stamped 4 arrives.
        val l = ReplyLedger()
        l.begin(4); l.end(4, ReplyLedger.End.CANCELLED)
        l.begin(5)
        assertFalse(l.accepts(4)); assertTrue(l.isDead(4))
        assertTrue(l.accepts(5))
    }

    @Test fun aCutIdCannotBeReopened() {
        val l = ReplyLedger()
        l.begin(3); l.end(3, ReplyLedger.End.CANCELLED)
        assertFalse(l.begin(3)); assertFalse(l.accepts(3))
    }

    @Test fun aCompletedIdIsDeadForAudioToo() {
        // Rule 2 is not only about cancel: once a reply has its end event (normal
        // completion), a late frame stamped with that id must not restart a stream.
        val l = ReplyLedger()
        l.begin(6)
        assertTrue(l.end(6, ReplyLedger.End.DONE))
        assertEquals(ReplyLedger.End.DONE, l.ending(6))
        assertFalse(l.accepts(6))
        assertTrue(l.isDead(6))
        assertFalse("a done id cannot be re-opened", l.begin(6))
    }

    @Test fun aFailedReplyStillGetsExactlyOneEnd() {
        // Empty reply / stream error: no playable tail, but the id still ends once so
        // the ledger never leaks an open reply into the rest of the session.
        val l = ReplyLedger()
        l.begin(8)
        assertTrue(l.end(8, ReplyLedger.End.FAILED))
        assertEquals(ReplyLedger.End.FAILED, l.ending(8))
        assertFalse(l.end(8, ReplyLedger.End.FAILED))
        assertFalse(l.accepts(8))
    }

    @Test fun anIdThatWasNeverCancelledStillStreams() {
        val l = ReplyLedger()
        assertTrue(l.begin(9))
        assertTrue(l.accepts(9))
        assertNull(l.ending(9))
        assertFalse(l.isDead(9))
        assertTrue(l.accepts(9))
    }

    @Test fun idsOlderThanTheWindowStayDead() {
        val l = ReplyLedger(capacity = 4)
        for (id in 1L..10L) { l.begin(id); l.end(id, ReplyLedger.End.CANCELLED) }
        // id 1 left the window long ago; it is still dead, and still cannot be reopened.
        assertTrue(l.isDead(1)); assertFalse(l.begin(1)); assertFalse(l.accepts(1))
        assertTrue(l.begin(11)); assertTrue(l.accepts(11))
    }
}
