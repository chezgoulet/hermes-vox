package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** #217: the presence report counts repeats by this hash, so its folding is a contract. */
class LineHashTest {
    @Test fun `case and punctuation do not make a new line`() {
        assertEquals(lineHash("Still on it."), lineHash("still on it"))
        assertEquals(lineHash("Still  on — it!"), lineHash("still on it"))
    }

    @Test fun `different words are different lines`() {
        assertNotEquals(lineHash("still on it"), lineHash("still working on it"))
    }

    @Test fun `eight hex digits`() {
        assert(Regex("^[0-9a-f]{8}$").matches(lineHash("anything")))
    }
}
