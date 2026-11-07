package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** Every command is on the sheet with no switch in front of it, and no header is empty. */
    @Test fun groupsAreInScreenOrderAndNoneIsEmpty() {
        val g = CommandCatalog.grouped()
        assertEquals(CommandCatalog.Group.values().toList(), g.map { it.first })
        assertTrue(g.all { it.second.isNotEmpty() })
        assertEquals(CommandCatalog.commands().count { it.listed }, g.sumOf { it.second.size })
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
            // #437: picking up a thread that already exists — the other half of /new.
            "/sessions" to "This conversation",
            "/models" to "The agent",
            // Per-profile pass: the switcher is a command, under The agent — it changes the
            // agent you are talking to, not the thread.
            "/profiles" to "The agent",
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
        val rendered = CommandCatalog.grouped()
            .flatMap { it.second }.map { it.name }
        val listed = CommandCatalog.commands()
            .filter { it.listed }.map { it.name }
        assertEquals(listed.size, rendered.size)
        assertEquals(listed.toSet(), rendered.toSet())
    }

    /**
     * Only useful text: a second line says what is *lost*, and only a command whose row
     * states one has it. "/reconnect stays down if the gateway does not answer" restated the
     * command; it is gone. `/clear` and `/new` lost their caution lines on 2026-11-06 (owner):
     * the row keeps its name and its one-line description, and nothing else.
     */
    @Test fun aCommandThatLosesSomethingSaysWhatAndNoOtherDoes() {
        for (name in listOf("/compress", "/models", "/profiles")) {
            val cost = CommandCatalog.find(name)!!.cost
            assertTrue("$name loses something and must say what", cost != null)
            assertTrue("$name's cost is a sentence", cost!!.length in 20..48)
            assertTrue("$name's cost has no line break", !cost.contains('\n'))
        }
        for (name in listOf("/clear", "/new", "/reconnect", "/health", "/help", "/sessions"))
            assertNull("$name states only its name and its effect",
                CommandCatalog.find(name)!!.cost)
        assertNull("the share row sends nothing by itself", CommandCatalog.SHARE.cost)
    }

    /** The row's mark is the command colour for every command: one rule, no warning tint. */
    @Test fun everyCommandRowIsDrawnInTheOneCommandColour() {
        // The token is the theme's command colour, named once in the catalogue.
        assertEquals("hv_cyan", CommandCatalog.ROW_INK)
        // `/clear` and `/new` resolve to exactly what every other row resolves to.
        val clear = CommandCatalog.find("/clear")!!
        val new = CommandCatalog.find("/new")!!
        assertEquals(CommandCatalog.ROW_INK, CommandCatalog.rowInk(clear))
        assertEquals(CommandCatalog.ROW_INK, CommandCatalog.rowInk(new))
        for (c in CommandCatalog.ALL + CommandCatalog.SHARE) {
            assertEquals("${c.name} has a colour of its own",
                CommandCatalog.ROW_INK, CommandCatalog.rowInk(c))
            assertEquals("${c.name} resolves like /clear", CommandCatalog.rowInk(clear), CommandCatalog.rowInk(c))
        }
        // And the sheet resolves that one token for the row's mark, in the theme's command
        // colour — no warning colour, and no branch on which command it is.
        val sheet = File("src/main/java/com/hermesvox/CommandsSheet.kt").readText()
        assertTrue("the row's mark is not the command colour",
            sheet.contains("val tint = color(R.color.hv_cyan)"))
        assertFalse("a command row is drawn in the warning colour again",
            Regex("""R\.color\.hv_warn""").containsMatchIn(sheet))
        assertFalse("the sheet still branches its tint", sheet.contains("CommandCatalog.tint"))
    }

    /**
     * The owner removed the two caution sentences on 2026-11-06: `/clear` and `/new` keep
     * their name and their one-line description, and nothing else. They were the only two
     * commands drawn as risks, so no row is a warning any more — and the two are still two
     * rows, told apart by their marks as well as their words.
     */
    @Test fun theTwoClearRowsKeepTheirMarksAndNoWarningColour() {
        val clear = CommandCatalog.find("/clear")!!
        val new = CommandCatalog.find("/new")!!
        assertNull(clear.cost)
        assertNull(new.cost)
        // What still distinguishes them is wording and drawing, both of which stay.
        assertTrue(clear.effect != new.effect)
        assertNull("clear draws the category's still", clear.glyph)
        assertEquals(Marks.Mark.NEW_THREAD, new.glyph)
        for (c in CommandCatalog.ALL + CommandCatalog.SHARE)
            assertNull("${c.name} still carries a caution sentence",
                c.cost?.takeIf { it.contains("cannot", ignoreCase = true) })
    }

    @Test fun inlineCommandsAreTheOnesThatRunInPlace() {
        val inline = CommandCatalog.commands()
            .filter { it.kind == CommandCatalog.Kind.INLINE }.map { it.name }
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
        // The removed bug-report command is in the card no more than it is on the sheet.
        assertTrue(!CommandCatalog.helpText().contains("/diagnostics"))
    }

    /**
     * #437, amended by D39: `/sessions` left the Alpha gate on 2026-11-06, so it is not a
     * switchable command any more — nothing can take it off the sheet, the search, /help or
     * the typed word, and nothing else moves.
     */
    @Test fun sessions_is_no_longer_gated() {
        val rows = CommandCatalog.grouped().flatMap { it.second }.map { it.name }
        assertTrue("/sessions", rows.contains("/sessions"))
        assertTrue(CommandCatalog.search("session").any { it.name == "/sessions" })
        assertTrue(CommandCatalog.helpText().contains("/sessions"))
        // It is a card command: its row reports no state, because the card is the report.
        val c = CommandCatalog.find("/sessions")!!
        assertEquals(CommandCatalog.Kind.CARD, c.kind)
        assertFalse(CommandCatalog.reportsOnRow(c))
        // And it is in the conversation group, next to /new, where a person looks for threads.
        assertEquals("This conversation", c.group.label)
    }

    /**
     * #436, amended by D39: `/profiles` left the Alpha gate on 2026-11-06 too, so it is no more
     * switchable than `/sessions` — the picker is every install's.
     */
    @Test fun profiles_is_no_longer_gated() {
        val rows = CommandCatalog.grouped().flatMap { it.second }.map { it.name }
        assertTrue("/profiles", rows.contains("/profiles"))
        assertTrue(CommandCatalog.search("profiles").any { it.name == "/profiles" })
        assertTrue(CommandCatalog.helpText().contains("/profiles"))
        // It is a card command under The agent, and switching still costs a fresh conversation.
        val c = CommandCatalog.find("/profiles")!!
        assertEquals(CommandCatalog.Kind.CARD, c.kind)
        assertFalse(CommandCatalog.reportsOnRow(c))
        assertEquals("The agent", c.group.label)
        assertTrue("the cost line is stated", c.cost != null)
    }

    /**
     * The bug-report command is gone (#418's switch with it), so no command is optional any
     * more. Asserted here so it cannot come back by accident: no catalogue entry, no switch,
     * and nothing to answer the word.
     */
    @Test fun thereIsNoDiagnosticsCommand() {
        assertEquals("no catalogue entry", emptyList<String>(),
            CommandCatalog.ALL.map { it.name }.filter { it.contains("diagnostic") })
        assertNull(CommandCatalog.find("/diagnostics"))
        assertNull(CommandCatalog.find("diagnostics"))
        assertFalse(CommandCatalog.helpText().contains("/diagnostics"))
        for (row in CommandCatalog.grouped(all = true).flatMap { it.second })
            assertFalse(row.name, row.name.contains("diagnostic"))
    }

    /**
     * #499: the screen listed `/reconnect` and `/health` twice — under a "Fits now" heading
     * and again under The agent. What fits the moment is a fact about one row, so it is said
     * on that row's state slot and the command keeps its one home in its group.
     */
    @Test fun whatFitsTheMomentIsAMarkOnTheRowNotASecondRow() {
        val down = CommandCatalog.Situation(connected = false, lastTurnFailed = false, sessionTurns = 0)
        val failed = CommandCatalog.Situation(connected = true, lastTurnFailed = true, sessionTurns = 0)
        for (s in listOf(down, failed)) {
            assertEquals(listOf("/reconnect", "/health"),
                CommandCatalog.suggest(s).map { it.name })
            assertTrue(CommandCatalog.fitsNow(CommandCatalog.find("/reconnect")!!, s))
            assertTrue(CommandCatalog.fitsNow(CommandCatalog.find("/health")!!, s))
            assertFalse(CommandCatalog.fitsNow(CommandCatalog.find("/models")!!, s))
        }
        // A long conversation offers the compaction, and nothing else.
        val long = CommandCatalog.Situation(connected = true, lastTurnFailed = false,
            sessionTurns = CommandCatalog.COMPRESS_AFTER_TURNS)
        assertEquals(listOf("/compress"), CommandCatalog.suggest(long).map { it.name })

        // The sheet renders `grouped`: one row per listed command, in two groups, so no
        // command can appear twice whatever fits. (The extra mark cannot add a row.)
        val rendered = CommandCatalog.grouped()
            .flatMap { it.second }.map { it.name }
        assertEquals(rendered.size, rendered.toSet().size)
        assertEquals(CommandCatalog.Group.values().size, 2)
        assertEquals("fits now", CommandCatalog.FITS_NOW)
    }
}
