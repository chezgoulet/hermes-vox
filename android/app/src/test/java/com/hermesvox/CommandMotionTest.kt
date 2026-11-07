package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #227: six verbs, mapped from a command's category; the outcome is the ending, not the verb. */
class CommandMotionTest {

    @Test fun theGrammarIsClosedAtSixVerbs() {
        assertEquals(listOf("SWEEP", "PULSE", "CONVERGE", "SCATTER", "RING", "SETTLE"),
            CommandMotion.Verb.values().map { it.name })
    }

    @Test fun everyCategoryHasAVerbAndEveryVerbIsUsed() {
        val used = CommandCatalog.Category.values().map { CommandMotion.verbFor(it) }.toSet()
        assertEquals(CommandMotion.Verb.values().toSet(), used)
    }

    @Test fun everyCommandInheritsItsMotionFromItsCategory() {
        for (c in CommandCatalog.ALL) CommandMotion.verbFor(c.category)   // total: no command is left out
        assertEquals(CommandMotion.Verb.CONVERGE, CommandMotion.verbFor(CommandCatalog.find("/compress")!!.category))
        assertEquals(CommandMotion.Verb.RING, CommandMotion.verbFor(CommandCatalog.find("/reconnect")!!.category))
        // /new and /clear do the same kind of thing, so they move the same way.
        assertEquals(CommandMotion.verbFor(CommandCatalog.find("/new")!!.category),
            CommandMotion.verbFor(CommandCatalog.find("/clear")!!.category))
    }

    @Test fun verbsAreDrawnWithShapesTheBeingHasAndAreDistinct() {
        val shapes = CommandMotion.Verb.values().map { it.shape }
        assertTrue(AvatarView.SHAPE_TOKENS.toList().containsAll(shapes + CommandMotion.Ending.values().map { it.shape }))
        assertEquals(shapes.size, shapes.toSet().size)
    }

    @Test fun successAndFailureAreDifferentEndings() {
        assertNotEquals(CommandMotion.ending(true), CommandMotion.ending(false))
        assertEquals("settle", CommandMotion.ending(true).state)
        assertEquals("waiting", CommandMotion.ending(false).state)   // the waiting colour, not the recoil of a barge
        assertTrue(CommandMotion.Verb.values().none { it.shape == CommandMotion.Ending.DONE.shape || it.shape == CommandMotion.Ending.FAILED.shape })
    }
}
