package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression path 2: the cold row exists, has the shape the path names, and is written once. */
class ColdFrameTest {

    @After fun forget() = ColdFrame.reset()

    @Test fun `the row is the event=lat cold row the regression path names`() {
        assertEquals(
            "event=lat label=cold metric=first-frame ms=412 createMs=180",
            ColdFrame.line(412, 180),
        )
    }

    @Test fun `the row is written once per process, not once per activity`() {
        ColdFrame.reset()
        assertTrue(ColdFrame.take())
        assertFalse("a recreated activity is not a cold start", ColdFrame.take())
    }

    @Test fun `a clock that went backwards reports zero, never a negative`() {
        assertEquals("event=lat label=cold metric=first-frame ms=0 createMs=0", ColdFrame.line(-5, -1))
    }
}
