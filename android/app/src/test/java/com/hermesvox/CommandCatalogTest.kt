package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The screen's shape holds for a fresh install (nothing optional on) and for a phone
     *  that has every switch on — a gated command must not move a header or empty a group. */
    @Test fun groupsAreInScreenOrderAndNoneIsEmpty() {
        for (gate in listOf(CommandCatalog.Gate.NOTHING, CommandCatalog.Gate.EVERYTHING)) {
            val g = CommandCatalog.grouped(gate = gate)
            assertEquals(CommandCatalog.Group.values().toList(), g.map { it.first })
            assertTrue(g.all { it.second.isNotEmpty() })
            assertEquals(CommandCatalog.commands(gate).count { it.listed }, g.sumOf { it.second.size })
            // A header earns its place by grouping: no group on the sheet holds a single row.
            assertTrue("a header over one row: $g", g.all { it.second.size >= 2 })
        }
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
            // #437: picking up a thread that already exists — the other half of /new.
            "/sessions" to "This conversation",
            "/models" to "The agent",
            // Per-profile pass: the switcher is a command, under The agent — it changes the
            // agent you are talking to, not the thread.
            "/profiles" to "The agent",
            "/reconnect" to "The agent",
            "/health" to "The agent",
            // #418: the report command is under The agent — it is about the agent's build,
            // not about this thread. It is in the catalogue only while its switch is on.
            "/diagnostics" to "The agent",
            "/help" to "The agent",
        )
        assertEquals(expected.keys, CommandCatalog.ALL.map { it.name }.toSet())
        for ((name, label) in expected) {
            val c = CommandCatalog.find(name, CommandCatalog.Gate.EVERYTHING)!!
            assertTrue("$name has no group", c.group.label.isNotBlank())
            assertEquals("$name is under the wrong group", label, c.group.label)
        }
        // The two groups are the screen's: what a person is acting on, not a subsystem.
        assertEquals(listOf("This conversation", "The agent"),
            CommandCatalog.Group.values().map { it.label })
        // And each listed command appears exactly once in the rendered list.
        val rendered = CommandCatalog.grouped(gate = CommandCatalog.Gate.EVERYTHING)
            .flatMap { it.second }.map { it.name }
        val listed = CommandCatalog.commands(CommandCatalog.Gate.EVERYTHING)
            .filter { it.listed }.map { it.name }
        assertEquals(listed.size, rendered.size)
        assertEquals(listed.toSet(), rendered.toSet())
    }

    /**
     * Only useful text: a second line says what is *lost*, and only a command that loses
     * something has one. "/reconnect stays down if the gateway does not answer" restated the
     * command; it is gone. The four that lose something keep their line.
     */
    @Test fun aCommandThatLosesSomethingSaysWhatAndNoOtherDoes() {
        for (name in listOf("/new", "/clear", "/compress", "/models", "/profiles")) {
            val cost = CommandCatalog.find(name, CommandCatalog.Gate.EVERYTHING)!!.cost
            assertTrue("$name loses something and must say what", cost != null)
            assertTrue("$name's cost is a sentence", cost!!.text.length in 20..48)
            assertTrue("$name's cost has no line break", !cost.text.contains('\n'))
        }
        for (name in listOf("/reconnect", "/health", "/help", "/diagnostics", "/sessions"))
            assertNull("$name loses nothing; it has no cost line",
                CommandCatalog.find(name, CommandCatalog.Gate.EVERYTHING)!!.cost)
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
        val inline = CommandCatalog.commands(CommandCatalog.Gate.EVERYTHING)
            .filter { it.kind == CommandCatalog.Kind.INLINE }.map { it.name }
        assertEquals(listOf("/compress", "/clear", "/new", "/reconnect", "/diagnostics"), inline)
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
        val h = CommandCatalog.helpText(CommandCatalog.Gate.EVERYTHING)
        for (c in CommandCatalog.ALL) assertTrue(c.name, h.contains(c.effect))
        assertTrue(h.contains("/clear /reset"))
        // A switch that is off takes the command out of the card as well as off the sheet.
        assertTrue(!CommandCatalog.helpText().contains("/diagnostics"))
    }

    /**
     * #437: the session browser uses the same Gate as /profiles and /diagnostics — one way a
     * row is absent — so with its Alpha set off it is gone from the sheet, the search, /help
     * and the typed word, and nothing else moves.
     */
    @Test fun sessions_disappears_from_the_sheet_search_and_help_when_its_set_is_off() {
        val noSessions = CommandCatalog.Gate { it.name != "/sessions" }
        val rows = CommandCatalog.grouped(gate = noSessions).flatMap { it.second }.map { it.name }
        assertFalse("/sessions", rows.contains("/sessions"))
        assertTrue("the rest of the catalogue is untouched", rows.contains("/new"))
        assertFalse(CommandCatalog.search("session", gate = noSessions).any { it.name == "/sessions" })
        assertTrue(CommandCatalog.search("session", gate = CommandCatalog.Gate.EVERYTHING)
            .any { it.name == "/sessions" })
        assertFalse(CommandCatalog.helpText(noSessions).contains("/sessions"))
        assertTrue(CommandCatalog.helpText(CommandCatalog.Gate.EVERYTHING).contains("/sessions"))
        // It is a card command: its row reports no state, because the card is the report.
        val c = CommandCatalog.find("/sessions", CommandCatalog.Gate.EVERYTHING)!!
        assertEquals(CommandCatalog.Kind.CARD, c.kind)
        assertFalse(CommandCatalog.reportsOnRow(c))
        // And it is in the conversation group, next to /new, where a person looks for threads.
        assertEquals("This conversation", c.group.label)
    }

    @Test fun profiles_disappears_from_the_sheet_search_and_help_when_its_set_is_off() {
        // The same Gate mechanism the diagnostic command uses: one way to hide a row.
        val noProfiles = CommandCatalog.Gate { it.name != "/profiles" }
        val all = CommandCatalog.grouped(gate = noProfiles).flatMap { it.second }.map { it.name }
        assertFalse("/profiles", all.contains("/profiles"))
        assertTrue("the rest of the catalogue is untouched", all.contains("/models"))
        assertFalse(CommandCatalog.search("profiles", gate = noProfiles).any { it.name == "/profiles" })
        assertTrue(
            CommandCatalog.search("profiles", gate = CommandCatalog.Gate.EVERYTHING)
                .any { it.name == "/profiles" })
        assertFalse(CommandCatalog.helpText(noProfiles).contains("/profiles"))
        assertTrue(CommandCatalog.helpText(CommandCatalog.Gate.EVERYTHING).contains("/profiles"))
    }
}
