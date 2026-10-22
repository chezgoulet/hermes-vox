package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #284: find a command by what you want; the interface offers what fits; the hint is rare. */
class DiscoveryTest {

    private fun first(q: String) = CommandCatalog.search(q).firstOrNull()?.name

    @Test fun findACommandByWhatYouWantToDo() {
        assertEquals("/compress", first("it's slow"))
        assertEquals("/compress", first("summarize"))
        assertEquals("/reconnect", first("can't reach it"))
        assertEquals("/reconnect", first("offline"))
        assertEquals("/new", first("start over"))
        assertEquals("/models", first("a smarter model"))
        assertEquals("/health", first("is it working"))
    }

    @Test fun findItByItsNameToo() {
        assertEquals("/compress", first("/comp"))
        assertEquals("/clear", first("reset"))       // an alias
        assertEquals("/health", first("status"))
    }

    @Test fun aBlankQueryIsEverythingAndNonsenseIsNothing() {
        assertEquals(CommandCatalog.ALL, CommandCatalog.search("  "))
        assertTrue(CommandCatalog.search("zzqx").isEmpty())
    }

    @Test fun theInterfaceLeadsWithWhatFitsNow() {
        val off = CommandCatalog.suggest(CommandCatalog.Situation(connected = false, lastTurnFailed = false, sessionTurns = 0))
        assertEquals(listOf("/reconnect", "/health"), off.map { it.name })
        val failed = CommandCatalog.suggest(CommandCatalog.Situation(true, lastTurnFailed = true, sessionTurns = 3))
        assertEquals("/reconnect", failed.first().name)
        val long = CommandCatalog.suggest(CommandCatalog.Situation(true, false, CommandCatalog.COMPRESS_AFTER_TURNS))
        assertEquals(listOf("/compress"), long.map { it.name })
        assertTrue(CommandCatalog.suggest(CommandCatalog.Situation(true, false, 2)).isEmpty())
    }

    @Test fun theStuckHintIsRareAndOnlyWhenStuck() {
        assertTrue(Discovery.stuckHint(Discovery.STUCK_AFTER_MS, 0, 1, false))
        assertFalse("too soon", Discovery.stuckHint(Discovery.STUCK_AFTER_MS - 1, 0, 1, false))
        assertFalse("they spoke", Discovery.stuckHint(60_000, 1, 1, false))
        assertFalse("once per call", Discovery.stuckHint(60_000, 0, 1, true))
        assertFalse("they've learned", Discovery.stuckHint(60_000, 0, Discovery.STUCK_HINT_CALLS + 1, false))
    }

    @Test fun aFailureOffersItsFixAnInterruptionOffersNothing() {
        assertEquals("Stream failed · tap to reconnect" to true, Discovery.errorLine("Stream failed"))
        assertEquals("You interrupted" to false, Discovery.errorLine("interrupted"))
    }
}
