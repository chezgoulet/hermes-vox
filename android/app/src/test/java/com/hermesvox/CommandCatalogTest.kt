package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #226: the commands, grouped by what they do, each saying what it does. */
class CommandCatalogTest {

    @Test fun everyCommandTheOldListOfferedIsStillHere() {
        for (n in listOf("/models", "/health", "/compress", "/new", "/reconnect", "/clear", "/reset", "/status", "/help"))
            assertTrue(n, CommandCatalog.find(n) != null)
        assertEquals("/compress", CommandCatalog.find("/compact")!!.name)
        assertNull(CommandCatalog.find("/nope"))
    }

    @Test fun everyRowStatesItsEffectInPlainWords() {
        for (c in CommandCatalog.ALL) {
            assertTrue(c.name, c.effect.length in 10..72)
            assertTrue(c.name, !c.effect.contains('/'))   // the effect, not another command's name
        }
    }

    @Test fun groupsAreInScreenOrderAndNoneIsEmpty() {
        val g = CommandCatalog.grouped()
        assertEquals(CommandCatalog.Group.values().toList(), g.map { it.first })
        assertTrue(g.all { it.second.isNotEmpty() })
        assertEquals(CommandCatalog.ALL.size, g.sumOf { it.second.size })
    }

    @Test fun inlineCommandsAreTheOnesThatRunInPlace() {
        val inline = CommandCatalog.ALL.filter { it.kind == CommandCatalog.Kind.INLINE }.map { it.name }
        assertEquals(listOf("/new", "/clear", "/compress", "/reconnect"), inline)
    }

    @Test fun helpIsWrittenFromTheCatalogue() {
        val h = CommandCatalog.helpText()
        for (c in CommandCatalog.ALL) assertTrue(c.name, h.contains(c.effect))
        assertTrue(h.contains("/clear /reset"))
    }
}
