package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #453: **Phone Tools** is a section of its own.
 *
 * The groups that decide what an outside mind may do to this phone were filed under *About &
 * Diagnostics*, beside the debug log and the dev console — the app's most consequential consent
 * surface, named after the thing we built rather than the thing the owner is deciding. Someone
 * asking *"what may my agent do with my phone"* had no reason to open About.
 *
 * What is pinned here, because each is a way to get it wrong again:
 *  1. the section exists, titled in the owner's words, and its one door opens the sheet;
 *  2. the grant entry is gone from About & Diagnostics;
 *  3. the section row's one line is [PhoneBootstrap.Step] — the same value the sheet draws and the
 *     button advances — and only one of its five lines asserts a connection;
 *  4. the one-button setup is surfaced as a row, in the sheet's own words, and it goes away once
 *     connected;
 *  5. the Alpha gate still governs the section: the row is dimmed, inert, and the section refuses
 *     to open, so the new section is not a way around the gate.
 */
class PhoneToolsSectionTest {

    private val layout = File("src/main/res/layout/activity_settings.xml")
    private val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
    private val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
    private val hints = File("src/main/java/com/hermesvox/SettingsHints.kt").readText()
    private val source = layout.readText()

    private val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)

    private fun Element.id(): String? {
        val raw = when {
            hasAttribute("android:id") -> getAttribute("android:id")
            hasAttribute("id") -> getAttribute("id")
            else -> null
        } ?: return null
        return raw.removePrefix("@+id/").removePrefix("@id/")
    }

    private fun elements(root: Element = document.documentElement): Sequence<Element> = sequence {
        yield(root)
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            if (child is Element) yieldAll(elements(child))
        }
    }

    private val allElements: List<Element> by lazy { elements().toList() }
    private val ids: Map<String, Element> by lazy {
        allElements.mapNotNull { e -> e.id()?.let { it to e } }.toMap()
    }

    /** The nearest ancestor with [id], so "is this row inside that section" is checked structurally. */
    private fun inside(rowId: String, sectionId: String): Boolean {
        var p: Node? = ids.getValue(rowId).parentNode
        while (p is Element) {
            if (p.id() == sectionId) return true
            p = p.parentNode
        }
        return false
    }

    /** Every string a section puts in front of the owner, its headings included. */
    private fun ownerFacingText(sectionId: String): List<String> {
        val section = ids.getValue(sectionId)
        return elements(section).mapNotNull { e ->
            when {
                e.hasAttribute("android:text") -> e.getAttribute("android:text")
                e.hasAttribute("android:contentDescription") ->
                    e.getAttribute("android:contentDescription")
                else -> null
            }
        }.toList()
    }

    // ---- 1. the section exists, and it is where the behaviour sections end ----

    @Test
    fun theSectionIsItsOwnPlaceTitledInTheOwnersWords() {
        assertTrue("the section container is missing", "sec_phone" in ids)
        assertTrue("the home row is missing", "row_grp_phone" in ids)
        assertTrue("its state line is missing", "set_phone_grpval" in ids)

        // Titled for the owner, not for the mechanism: no MCP, no server, no endpoint, no
        // bootstrap anywhere this section speaks.
        val title = ids.getValue("row_grp_phone")
            .getElementsByTagName("TextView").item(0) as Element
        assertEquals("Phone Tools", title.getAttribute("android:text"))

        val sectionCopy = ownerFacingText("sec_phone") +
            ownerFacingText("row_grp_phone") +
            listOf(
                PhoneToolsView.stepLine(PhoneBootstrap.Step.IDLE),
                PhoneToolsView.setupRowTitle(),
                SettingsHints.HINTS.getValue("row_phone_boot"),
                SettingsHints.HINTS.getValue("row_phone_mcp"),
            )
        for (word in listOf("MCP", "server", "endpoint", "bootstrap")) {
            for (line in sectionCopy) {
                assertFalse(
                    "the owner reads no internal vocabulary, and \"$word\" is in \"$line\"",
                    line.contains(word, ignoreCase = true),
                )
            }
        }

        // Inside the alpha block, before About.
        val order = allElements.filter { it.id()?.startsWith("row_grp_") == true }.mapNotNull { it.id() }
        assertTrue("Phone Tools sits in the alpha block", order.indexOf("row_grp_phone") > order.indexOf("row_grp_alpha"))
        assertTrue("Phone Tools comes before About & Diagnostics", order.indexOf("row_grp_phone") < order.indexOf("row_grp_about"))
        val phoneAt = source.indexOf("@+id/sec_phone")
        val aboutAt = source.indexOf("@+id/sec_about")
        assertTrue("the section itself sits before About & Diagnostics", phoneAt in 1 until aboutAt)
    }

    @Test
    fun theSectionsDoorOpensTheSheet() {
        assertTrue("the home row is not wired", settings.contains("R.id.row_grp_phone)?.setOnClickListener { showSection(SECTION_PHONE) }"))
        assertTrue("the section is not registered", settings.contains("SECTION_PHONE to R.id.sec_phone"))
        assertTrue("the row is not registered", settings.contains("SECTION_PHONE to R.id.row_grp_phone"))
        assertTrue(
            "the grant entry must open the one sheet",
            settings.contains("R.id.row_phone_mcp)?.setOnClickListener") &&
                settings.contains("PhoneToolsSheet(this).show()"),
        )
    }

    // ---- 2. the grants leave About, and About keeps no diagnostics switch ----

    @Test
    fun theGrantsLeaveAboutAndDiagnosticsAndKeepTheirSummary() {
        assertFalse(
            "the grant entry is still in About & Diagnostics",
            inside("row_phone_mcp", "sec_about"),
        )
        assertTrue("the grant entry must live in Phone Tools", inside("row_phone_mcp", "sec_phone"))
        assertFalse(
            "the old label is still on screen",
            source.contains("Phone as an MCP server"),
        )
        val grantTitle = ids.getValue("row_phone_mcp")
            .getElementsByTagName("TextView").item(0) as Element
        assertEquals("What your agent may do", grantTitle.getAttribute("android:text"))
        // The one-line summary beneath it is the existing one, word for word.
        assertEquals(
            "Ask your agent to add this phone, and see whether it connected",
            SettingsHints.HINTS.getValue("row_phone_mcp"),
        )
        assertTrue("its value line moved with it", inside("set_phone_mcp_val", "sec_phone"))

        // About keeps the log switches and the developer console — and no diagnostics row.
        val aboutRows = allElements.filter { it.id()?.startsWith("row_") == true }
            .mapNotNull { e -> e.id()?.takeIf { inside(it, "sec_about") } }
            .toSet()
        assertEquals(
            setOf("row_devconsole", "row_logtranscripts", "row_debuglog",
                "row_debug", "row_restore_about"),
            aboutRows,
        )
    }

    // ---- 3. the state line is PhoneBootstrap.Step, in the owner's words ----

    @Test
    fun theStateLineIsTheSetupStepForEveryStep() {
        assertEquals("Not set up", PhoneToolsView.stepLine(PhoneBootstrap.Step.IDLE))
        assertEquals("Asking your agent", PhoneToolsView.stepLine(PhoneBootstrap.Step.ADDING))
        assertEquals("Your agent is working", PhoneToolsView.stepLine(PhoneBootstrap.Step.WAITING))
        assertEquals("Connected", PhoneToolsView.stepLine(PhoneBootstrap.Step.CONNECTED))
        assertEquals("Not connected", PhoneToolsView.stepLine(PhoneBootstrap.Step.FAILED))

        // Every step has its own line: five values, five lines.
        val lines = PhoneBootstrap.Step.entries.map { PhoneToolsView.stepLine(it) }
        assertEquals("each step is its own line", lines.size, lines.toSet().size)
    }

    @Test
    fun onlyTheHandshakeAssertsAConnection() {
        // One line claims the phone is connected, and it is the step only the phone's own
        // `initialize` can reach.
        val asserting = PhoneBootstrap.Step.entries.filter { step ->
            val line = PhoneToolsView.stepLine(step).lowercase()
            Regex("\\bconnected\\b").containsMatchIn(line) && !line.startsWith("not ")
        }
        assertEquals(listOf(PhoneBootstrap.Step.CONNECTED), asserting.toList())

        // The word itself appears in exactly two: the claim, and the plain denial of it. The
        // sheet's own words explain a failure; the row is allowed only to deny.
        val withTheWord = PhoneBootstrap.Step.entries
            .filter { PhoneToolsView.stepLine(it).contains("connected", ignoreCase = true) }
        assertEquals(listOf(PhoneBootstrap.Step.CONNECTED, PhoneBootstrap.Step.FAILED), withTheWord)
        assertEquals(
            "the claim is the sheet's own heading, so the two cannot drift",
            PhoneToolsView.CONNECTED_HEADING,
            PhoneToolsView.stepLine(PhoneBootstrap.Step.CONNECTED),
        )
    }

    @Test
    fun theRowReadsTheStepTheSheetAndTheButtonUse() {
        assertFalse(
            "the row must not read the per-process step on its own: after a restart it would say " +
                "Not set up while the sheet, folding the record's handshake in, says Connected",
            settings.contains("PhoneBootstrapStore.progress().step"),
        )
        assertTrue(
            "the row folds the kept handshake through the same call the sheet makes",
            settings.contains("PhoneBootstrap.settled(") && settings.contains("record.handshakeAt"),
        )
        assertTrue(
            "and renders it through the one line function",
            settings.contains("PhoneToolsView.stepLine(step, allowed)"),
        )
        assertFalse(
            "no parallel list of states in the screen",
            settings.contains("PhoneBootstrap.Step.CONNECTED ->"),
        )
    }

    /**
     * The restart case, expressed without a restart: this process has no attempt ([Step.IDLE]),
     * while the record kept the handshake that landed before the app closed. The row must draw
     * *Connected*, because the sheet does — both fold the record's handshake through the one
     * function, [PhoneBootstrap.settled].
     */
    @Test
    fun theRowSaysConnectedAfterARestartFromTheKeptHandshakeAlone() {
        val now = 1_700_000_000_000L
        val record = PhoneSetup.Record(handshakeAt = now - 60_000L, handshakeTag = "t", tokenTag = "t")
        val step = PhoneBootstrap.settled(PhoneBootstrap.Progress(), record.handshakeAt, now).step
        assertEquals(
            "a kept handshake with no attempt in this process is Connected, not Not set up",
            "Connected",
            PhoneToolsView.stepLine(step),
        )
        assertEquals("and the record agrees", PhoneSetup.State.CONNECTED, PhoneSetup.state(record, now, "t"))
        assertFalse("nothing is left to set up, so the setup row is gone", PhoneToolsView.setupRowVisible(step))
    }

    /**
     * An attempt that just failed outranks a stale handshake: a handshake recorded during the
     * attempt, or one from last week, must not hide the failure the owner just watched happen.
     */
    @Test
    fun aFailedAttemptOutranksAStaleHandshake() {
        val now = 1_700_000_000_000L
        val failed = PhoneBootstrap.Progress(
            step = PhoneBootstrap.Step.FAILED,
            failure = PhoneBootstrap.Failure.NO_HANDSHAKE,
            reached = PhoneBootstrap.Step.WAITING,
            startedAt = now - 200_000L,
            answeredAt = now - 100_000L,
        )
        val settled = PhoneBootstrap.settled(failed, handshakeAt = now - 60_000L, now = now)
        assertEquals("a failed attempt stays failed", PhoneBootstrap.Step.FAILED, settled.step)
        assertEquals("Not connected", PhoneToolsView.stepLine(settled.step))
        assertTrue("and the row keeps the way forward visible", PhoneToolsView.setupRowVisible(settled.step))
    }

    // ---- 4. the one-button setup, surfaced and not re-implemented ----

    @Test
    fun theSetupRowIsTheSheetsOwnActionAndGoesAwayOnceConnected() {
        for (step in PhoneBootstrap.Step.entries) {
            assertEquals(
                "the setup row belongs on every step but $step",
                step != PhoneBootstrap.Step.CONNECTED,
                PhoneToolsView.setupRowVisible(step),
            )
        }
        // The row's label is the button's own words, not a second wording of the same action.
        assertEquals(PhoneBootstrap.BUTTON, PhoneToolsView.setupRowTitle().uppercase())
        assertTrue("the row exists", "row_phone_boot" in ids)
        assertTrue("and it lives in the new section", inside("row_phone_boot", "sec_phone"))
        assertTrue(
            "the title is set from the button's words",
            settings.contains("PhoneToolsView.setupRowTitle()"),
        )

        // Surfaced, not re-implemented: the screen calls the sheet's own press, and the one
        // implementation is the only place the flow is started.
        assertTrue(
            "the row must call the sheet's press",
            settings.contains("PhoneToolsSheet.setUpWithAgent(this)"),
        )
        assertEquals(
            "exactly one implementation of the press",
            1,
            Regex("fun setUpWithAgent\\(").findAll(sheet).count(),
        )
        assertEquals(
            "and it is the one place the flow is started",
            1,
            Regex("PhoneBootstrapStore\\.start\\(").findAll(sheet).count(),
        )
        assertFalse(
            "the screen must not start the flow itself",
            settings.contains("PhoneBootstrapStore.start("),
        )
        // Present only while there is something left to do — the visibility follows the step.
        assertTrue(
            "the row's visibility follows the step",
            settings.contains("PhoneToolsView.setupRowVisible(step)"),
        )
    }

    // ---- 5. the Alpha gate still governs the section ----

    @Test
    fun withTheGateOffTheRowIsInertAndTheSectionDoesNotOpen() {
        // Dimmed and taking no tap: the row is in the block, and the block drives every row's
        // gate ([AlphaBlock]).
        assertTrue(
            "the section row must be in the alpha block",
            AlphaBlock.ROWS.any { it.row == "row_grp_phone" },
        )
        assertTrue(
            "the block's rows are gated from one table",
            settings.contains("for (row in AlphaBlock.ROWS)"),
        )
        assertTrue(
            "the setup row inside it must be gated too",
            settings.contains("gate(R.id.row_phone_boot, phoneTools)"),
        )
        assertTrue(
            "the grant entry keeps its gate",
            settings.contains("gate(R.id.row_phone_mcp, phoneTools)"),
        )
        // A dimmed row is not enough: the section itself refuses to open while the set is off,
        // so a banner extra, the debug harness or a stale link cannot get around the gate.
        assertTrue(
            "the section's own gate is the table's answer",
            settings.contains("if (!AlphaBlock.canOpen(prefs, section)) return"),
        )
        // And it closes under the owner if the set is switched off while he is in it.
        assertTrue(
            "the section closes when the gate goes off",
            settings.contains("if (!allowed && currentSection == row.section) showGroupList()"),
        )
        // The row says why it is off, in the one line every Phone-tools surface uses.
        assertEquals(
            PhoneToolsView.ALPHA_OFF,
            PhoneToolsView.stepLine(PhoneBootstrap.Step.CONNECTED, allowed = false),
        )
        assertTrue("the hint table still describes both rows", hints.contains("\"row_phone_boot\""))
    }
}
