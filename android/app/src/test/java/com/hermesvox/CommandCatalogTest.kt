package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #226: the commands, grouped by what they do, each saying what it does and what it costs. */
class CommandCatalogTest {

    @Test fun everyCommandTheOldListOfferedIsStillHere() {
        for (n in listOf("/models", "/health", "/compress", "/new", "/reconnect", "/clear", "/reset", "/status", "/help"))
            assertTrue(n, CommandCatalog.find(n) != null)
        assertEquals("/compress", CommandCatalog.find("/compact")!!.name)
        assertNull(CommandCatalog.find("/nope"))
        // Each alias lands on the command that does the work, so the name a person typed
        // yesterday still means the same thing today. (`/file` is deliberately absent: no
        // build of this app has ever had a handler for it, and a row for a command that
        // does nothing would be the exact lie #226 is about.)
        for ((alias, canonical) in mapOf("/status" to "/health", "/reset" to "/clear",
            "/compact" to "/compress"))
            assertEquals(alias, canonical, CommandCatalog.find(alias)?.name)
    }

    @Test fun everyRowStatesItsEffectInPlainWords() {
        for (c in CommandCatalog.ALL) {
            // One line at the row's width: 46 characters fit beside the mark on a 1080-wide
            // screen at the default font scale (the emulator capture of this pass); 50 wrapped.
            assertTrue("${c.name}: ${c.effect.length} chars", c.effect.length in 10..48)
            assertTrue(c.name, !c.effect.contains('/'))   // the effect, not another command's name
            // The screen's register is the person's: no subsystem, no identifier, no jargon.
            for (word in listOf("SSE", "HTTP", "gateway-", "api/", "_id", "LLM"))
                assertTrue("${c.name}: '${c.effect}' says '$word'", !c.effect.contains(word))
        }
    }

    @Test fun groupsAreInScreenOrderAndNoneIsEmpty() {
        val g = CommandCatalog.grouped()
        assertEquals(CommandCatalog.Group.values().toList(), g.map { it.first })
        assertTrue(g.all { it.second.isNotEmpty() })
        assertEquals(CommandCatalog.ALL.count { it.listed }, g.sumOf { it.second.size })
        // A header earns its place by grouping: no group on the sheet holds a single row.
        assertTrue("a header over one row: $g", g.all { it.second.size >= 2 })
    }

    /** `/help` opens a copy of the sheet it would be listed on; the sheet does not list it,
     *  but a typed or spoken "/help" and the search still reach it. */
    @Test fun helpIsNotARowInsideTheHelp() {
        val help = CommandCatalog.find("/help")!!
        assertTrue(!help.listed)
        assertTrue(CommandCatalog.grouped().none { (_, cs) -> help in cs })
        assertTrue(help in CommandCatalog.search("help"))
        assertEquals(listOf("/help"), CommandCatalog.ALL.filter { !it.listed }.map { it.name })
    }

    /**
     * The grouping model, pinned: command → group → the label the header prints. This is the
     * table the screen renders, so a command moved between groups, renamed, or added without
     * a home fails here instead of shipping unlabelled.
     */
    @Test fun everyCommandBelongsToALabelledGroup() {
        val expected = mapOf(
            "/compress" to "This conversation",
            "/clear" to "This conversation",
            "/new" to "This conversation",
            "/models" to "The agent",
            "/reconnect" to "The agent",
            "/health" to "The agent",
            "/help" to "The agent",
        )
        assertEquals(expected.keys, CommandCatalog.ALL.map { it.name }.toSet())
        for ((name, label) in expected) {
            val c = CommandCatalog.find(name)!!
            assertTrue("$name has no group", c.group.label.isNotBlank())
            assertEquals("$name is under the wrong group", label, c.group.label)
        }
        // The two groups are the screen's: what a person is acting on, not a subsystem.
        assertEquals(listOf("This conversation", "The agent"),
            CommandCatalog.Group.values().map { it.label })
        // And each listed command appears exactly once in the rendered list.
        val rendered = CommandCatalog.grouped().flatMap { it.second }.map { it.name }
        val listed = CommandCatalog.ALL.filter { it.listed }.map { it.name }
        assertEquals(listed.size, rendered.size)
        assertEquals(listed.toSet(), rendered.toSet())
    }

    /**
     * Only useful text: a second line says what is *lost*, and only a command that loses
     * something has one. "/reconnect stays down if the gateway does not answer" restated the
     * command; it is gone. The four that lose something keep their line.
     */
    @Test fun aCommandThatLosesSomethingSaysWhatAndNoOtherDoes() {
        for (name in listOf("/new", "/clear", "/compress", "/models")) {
            val cost = CommandCatalog.find(name)!!.cost
            assertTrue("$name loses something and must say what", cost != null)
            assertTrue("$name's cost is a sentence", cost!!.text.length in 20..48)
            assertTrue("$name's cost has no line break", !cost.text.contains('\n'))
        }
        for (name in listOf("/reconnect", "/health", "/help"))
            assertNull("$name loses nothing; it has no cost line", CommandCatalog.find(name)!!.cost)
        assertNull("the share row sends nothing by itself", CommandCatalog.SHARE.cost)
    }

    /** The row's mark is amber exactly when its cost line is: one fact, two signals. */
    @Test fun theMarkTintIsTheRiskFact() {
        for (c in CommandCatalog.ALL + CommandCatalog.SHARE)
            assertEquals(c.name, c.cost?.risk == true, CommandCatalog.tint(c) == CommandCatalog.Tint.RISK)
    }

    /**
     * Amber is a fact, not a mood: only a command that loses something for good is drawn in
     * the warning colour. If a future command starts shouting in amber without being
     * irreversible, this fails and the row can be argued about instead of assumed.
     */
    @Test fun onlyTheIrreversibleCommandsAreDrawnAsRisks() {
        val risks = CommandCatalog.ALL.filter { it.cost?.risk == true }.map { it.name }
        assertEquals(listOf("/clear", "/new"), risks)
        // And a risk's line says what cannot come back, in words — the colour alone is not
        // the message, because colour is the first thing a colour-blind person loses.
        for (c in CommandCatalog.ALL) {
            val cost = c.cost ?: continue
            if (cost.risk) assertTrue("${c.name}: '${cost.text}' must say what is lost",
                cost.text.contains("cannot") || cost.text.contains("Cannot"))
        }
    }

    @Test fun inlineCommandsAreTheOnesThatRunInPlace() {
        val inline = CommandCatalog.ALL.filter { it.kind == CommandCatalog.Kind.INLINE }.map { it.name }
        assertEquals(listOf("/compress", "/clear", "/new", "/reconnect"), inline)
    }

    /**
     * The running state, in the app's words (#226, #281): four states, no spinner, no number
     * that pretends to be progress — and a card command says where its state actually is
     * instead of showing one it cannot see.
     */
    @Test fun theRunningStateIsWordsAndNeverAHiddenSpinner() {
        assertEquals(listOf("", "queued", "running…", "done", "failed"),
            CommandCatalog.RunState.values().map { it.label })

        val clear = CommandCatalog.find("/clear")!!
        assertEquals("queued", CommandCatalog.stateText(clear, CommandCatalog.RunState.QUEUED))
        assertEquals("running…", CommandCatalog.stateText(clear, CommandCatalog.RunState.RUNNING))
        assertEquals("done", CommandCatalog.stateText(clear, CommandCatalog.RunState.DONE))
        assertEquals("failed", CommandCatalog.stateText(clear, CommandCatalog.RunState.FAILED))
        // Past the operations line's threshold the words move, and never claim progress.
        assertEquals("still working…",
            CommandCatalog.stateText(clear, CommandCatalog.RunState.RUNNING, Operations.LONG_MS))

        // A card command cannot report a state on the row, so the row says nothing at all —
        // never a fake state, and never "opens a card" (a line saying what the tap does).
        for (name in listOf("/health", "/models", "/help")) {
            val c = CommandCatalog.find(name)!!
            assertTrue("$name runs in a card", !CommandCatalog.reportsOnRow(c))
            for (s in CommandCatalog.RunState.values())
                assertEquals("$name must not fake a state", "", CommandCatalog.stateText(c, s, 60_000L))
        }
        for (c in CommandCatalog.ALL) {
            for (s in CommandCatalog.RunState.values()) {
                val t = CommandCatalog.stateText(c, s, 60_000L)
                assertTrue("${c.name}/${s}: '$t' claims progress", !t.contains('%'))
                assertTrue("${c.name}/${s}: '$t' has a digit", !t.any { it.isDigit() })
            }
        }
    }

    @Test fun helpIsWrittenFromTheCatalogue() {
        val h = CommandCatalog.helpText()
        for (c in CommandCatalog.ALL) assertTrue(c.name, h.contains(c.effect))
        assertTrue(h.contains("/clear /reset"))
    }
}
