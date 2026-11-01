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
            assertTrue(c.name, c.effect.length in 10..52)   // one line at the row's width
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
        assertEquals(CommandCatalog.ALL.size, g.sumOf { it.second.size })
    }

    /**
     * The grouping model, pinned: command → group → the label the header prints. This is the
     * table the screen renders, so a command moved between groups, renamed, or added without
     * a home fails here instead of shipping unlabelled.
     */
    @Test fun everyCommandBelongsToALabelledGroup() {
        val expected = mapOf(
            "/new" to "Session",
            "/models" to "Session",
            "/clear" to "Conversation",
            "/compress" to "Conversation",
            "/reconnect" to "Connection",
            "/health" to "Diagnostics",
            "/help" to "Diagnostics",
        )
        assertEquals(expected.keys, CommandCatalog.ALL.map { it.name }.toSet())
        for ((name, label) in expected) {
            val c = CommandCatalog.find(name)!!
            assertTrue("$name has no group", c.group.label.isNotBlank())
            assertEquals("$name is under the wrong group", label, c.group.label)
        }
        // The four groups are the screen's: named for what a person is doing, not a subsystem.
        assertEquals(listOf("Conversation", "Connection", "Session", "Diagnostics"),
            CommandCatalog.Group.values().map { it.label })
        // And each command appears exactly once in the rendered list.
        val rendered = CommandCatalog.grouped().flatMap { it.second }.map { it.name }
        assertEquals(CommandCatalog.ALL.size, rendered.size)
        assertEquals(CommandCatalog.ALL.map { it.name }.toSet(), rendered.toSet())
    }

    @Test fun aCommandThatChangesSomethingSaysWhatItCosts() {
        // The four that change the world carry a cost or a risk; the two that only ask do not.
        for (name in listOf("/new", "/clear", "/compress", "/reconnect", "/models")) {
            val cost = CommandCatalog.find(name)!!.cost
            assertTrue("$name changes something and must say what it costs", cost != null)
            assertTrue("$name's cost is a sentence", cost!!.text.length in 20..48)
            assertTrue("$name's cost has no line break", !cost.text.contains('\n'))
        }
        for (name in listOf("/health", "/help"))
            assertNull("$name only asks; it has no cost to declare", CommandCatalog.find(name)!!.cost)
    }

    /**
     * Amber is a fact, not a mood: only a command that loses something for good is drawn in
     * the warning colour. If a future command starts shouting in amber without being
     * irreversible, this fails and the row can be argued about instead of assumed.
     */
    @Test fun onlyTheIrreversibleCommandsAreDrawnAsRisks() {
        val risks = CommandCatalog.ALL.filter { it.cost?.risk == true }.map { it.name }
        assertEquals(listOf("/new", "/clear"), risks)
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
        assertEquals(listOf("/new", "/clear", "/compress", "/reconnect"), inline)
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

        // A card command cannot report a state on the row: it says so, always.
        for (name in listOf("/health", "/models", "/help")) {
            val c = CommandCatalog.find(name)!!
            assertTrue("$name runs in a card", !CommandCatalog.reportsOnRow(c))
            for (s in CommandCatalog.RunState.values())
                assertEquals("$name must not fake a state", CommandCatalog.IN_CARD,
                    CommandCatalog.stateText(c, s, 60_000L))
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
