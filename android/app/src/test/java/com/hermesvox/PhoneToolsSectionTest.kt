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
 * #453/#511: **Phone Tools** is one screen, one tap from the Settings home.
 *
 * #453 gave the grant groups a section of their own and filed them away from About & Diagnostics.
 * It left two levels in it: the section held a row (*What your agent may do*) whose only job was to
 * open the sheet that held the grants, so reaching the permissions — the thing the section exists
 * for — took two submenus.
 *
 * What is pinned here, because each is a way to get it wrong again:
 *  1. the home row opens the one screen directly: no section container, no row that only opens
 *     another screen, and the screen is the same one the first-run offer shows;
 *  2. the one screen carries everything — the state line, the setup action, the grant groups with
 *     their one line each, and Recent actions;
 *  3. the state line is [PhoneBootstrap.Step] — the same reading the rest of the app makes through
 *     [PhoneBootstrap.settled] — with one line per step;
 *  4. the setup action is on it only while there is something left to set up;
 *  5. the Alpha gate still governs it: the row is dimmed, and the door refuses to open.
 */
class PhoneToolsSectionTest {

    private val layout = File("src/main/res/layout/activity_settings.xml")
    private val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
    private val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
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

    private val now = 1_700_000_000_000L
    private val tag = PhoneMcpToken.fingerprint("0123456789abcdef0123456789abcdef")

    private fun status() = PhoneMcp.Status(
        tailnet = "100.101.102.103",
        listening = false,
        url = null,
        served = 0,
        lastRequest = null,
        lastRequestAt = null,
        token = PhoneMcp.TokenState.STORED,
        dndGranted = true,
        volumeGranted = true,
    )

    // ---- 1. the home row opens the one screen, directly ----

    @Test
    fun theHomeRowOpensTheOneScreenDirectly() {
        // The door: the home row names the section, and the section token resolves to the screen.
        assertTrue(
            "the home row is not wired",
            settings.contains("R.id.row_grp_phone)?.setOnClickListener { showSection(SECTION_PHONE) }"),
        )
        assertTrue(
            "the section token does not open the screen itself",
            settings.contains("if (section == SECTION_PHONE) { openPhoneTools(); return }"),
        )
        assertTrue(
            "and the screen is the one sheet, the same one the first-run offer shows",
            settings.contains("private fun openPhoneTools() {") &&
                settings.contains("PhoneToolsSheet(this).show()"),
        )
        assertEquals(
            "one place opens it",
            1,
            Regex("PhoneToolsSheet\\(this\\)").findAll(settings).count(),
        )

        // No intermediate step survives: no section container, and no row whose only job was to
        // open the screen that holds the grants.
        assertFalse("the intermediate section is back", "sec_phone" in ids)
        assertFalse("the intermediate door row is back", "row_phone_mcp" in ids)
        assertFalse("the setup row moved into the screen", "row_phone_boot" in ids)
        assertFalse("a dead container in the layout", source.contains("@+id/sec_phone"))
        assertFalse("the old two-step label is still on screen", source.contains("What your agent may do"))
        assertFalse(
            "the section token has no container of its own to show",
            settings.contains("SECTION_PHONE to R.id.sec_phone"),
        )
        // The screen's own title, so the row and the screen say the same words.
        assertTrue("the screen has no title of its own", sheet.contains("PHONE TOOLS"))
    }

    @Test
    fun theSectionIsASectionOfTheSettingsHomeAndNotOfAbout() {
        assertTrue("the home row is missing", "row_grp_phone" in ids)
        assertFalse(
            "the phone rows are still in About & Diagnostics",
            inside("row_grp_phone", "sec_about"),
        )
        // It is in the home list, in the alpha block, before About.
        val order = allElements.filter { it.id()?.startsWith("row_grp_") == true }.mapNotNull { it.id() }
        assertTrue("Phone Tools sits in the alpha block", order.indexOf("row_grp_phone") > order.indexOf("row_grp_alpha"))
        assertTrue("Phone Tools comes before About & Diagnostics", order.indexOf("row_grp_phone") < order.indexOf("row_grp_about"))
        assertFalse("the old label is still on screen", source.contains("Phone as an MCP server"))

        // About keeps the log switches and the developer console — and nothing of the phone's.
        val aboutRows = allElements.filter { it.id()?.startsWith("row_") == true }
            .mapNotNull { e -> e.id()?.takeIf { inside(it, "sec_about") } }
            .toSet()
        assertEquals(
            setOf("row_devconsole", "row_logtranscripts", "row_debuglog",
                "row_debug", "row_restore_about"),
            aboutRows,
        )
    }

    // ---- 2. everything phone tools is, on that one screen ----

    @Test
    fun theOneScreenCarriesTheStateTheSetupTheGrantsAndTheAudit() {
        // The state line, first, from the one reading.
        assertTrue("the state line is not drawn", sheet.contains("row(col, \"Setup\", drawn.stateLine)"))
        assertTrue(
            "the state line is not the five steps the app uses",
            sheet.contains("stateLine = PhoneToolsView.stepLine(settled.step, status.allowed)"),
        )

        // The setup action, and the rule that says when it exists.
        assertTrue(
            "the setup action is not gated on there being something left to set up",
            sheet.contains("if (!PhoneToolsView.setupRowVisible(step)) return"),
        )
        assertTrue("the setup block is not on the screen", sheet.contains("private fun setupSection("))

        // The grant groups: Off / Ask / Allow per group, and the one-line summary under them.
        assertTrue(sheet.contains("VoxSheet.subhead(act, \"Capabilities\")"))
        assertTrue("the summary line is missing", sheet.contains("PhoneToolsView.grantSummary(states)"))
        assertTrue(sheet.contains("for ((tier, groups) in PhoneGrants.byTier())"))
        assertTrue("a group does not offer its own states", sheet.contains("for (choice in PhoneGrants.statesFor(group.tier))"))
        assertTrue("Off is a state of the group, not a missing row", sheet.contains("PhoneGrantStore.set(act, group, state)"))

        // Recent actions, the audit.
        assertTrue(sheet.contains("VoxSheet.subhead(act, \"Recent actions\")"))
        assertTrue(sheet.contains("PhoneAuditStore.entries(act)"))

        // One screen: nothing inside it opens another copy of it.
        assertFalse("the screen opens itself again", sheet.contains("PhoneToolsSheet(act).show()"))
    }

    // ---- 3. the state line is the one reading, with one line per step ----

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

    /**
     * Each step, drawn: the line the screen shows is the step's own line, and the step is the one
     * function the home row calls too — so the row and the screen cannot disagree.
     */
    @Test
    fun theScreenDrawsTheSameStepLineTheHomeRowDoes() {
        val cases = mapOf(
            PhoneBootstrap.Step.IDLE to PhoneBootstrap.Progress(),
            PhoneBootstrap.Step.ADDING to PhoneBootstrap.Progress(
                step = PhoneBootstrap.Step.ADDING,
                startedAt = now,
            ),
            PhoneBootstrap.Step.WAITING to PhoneBootstrap.Progress(
                step = PhoneBootstrap.Step.WAITING,
                startedAt = now - 60_000L,
                answeredAt = now,
            ),
            PhoneBootstrap.Step.FAILED to PhoneBootstrap.Progress(
                step = PhoneBootstrap.Step.FAILED,
                failure = PhoneBootstrap.Failure.NO_ANSWER,
                reached = PhoneBootstrap.Step.ADDING,
                startedAt = now - 60_000L,
            ),
        )
        for ((step, progress) in cases) {
            val drawn = PhoneToolsSheet.drawn(status(), PhoneSetup.Record(), tag, now, progress)
            assertEquals("the screen is not drawing $step", step, drawn.step)
            assertEquals(
                "the screen's state line is not the step's line for $step",
                PhoneToolsView.stepLine(step),
                drawn.stateLine,
            )
            // The home row's own call, on the same inputs.
            val rowStep = PhoneBootstrap.settled(progress, PhoneSetup.Record().handshakeAt, now).step
            assertEquals("the row and the screen read the same step", rowStep, drawn.step)
        }

        // Connected is the handshake alone, in this process or a previous one.
        val connected = PhoneToolsSheet.drawn(
            status(),
            PhoneSetup.Record(handshakeAt = now - 1_000L, handshakeTag = tag, tokenTag = tag),
            tag,
            now,
        )
        assertEquals(PhoneBootstrap.Step.CONNECTED, connected.step)
        assertEquals(PhoneToolsView.CONNECTED_HEADING, connected.stateLine)

        // With the gate off the line says why, and it is the one line every phone surface uses.
        assertEquals(
            PhoneToolsView.ALPHA_OFF,
            PhoneToolsView.stepLine(PhoneBootstrap.Step.CONNECTED, allowed = false),
        )
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
        // screen's own words explain a failure; the line is allowed only to deny.
        val withTheWord = PhoneBootstrap.Step.entries
            .filter { PhoneToolsView.stepLine(it).contains("connected", ignoreCase = true) }
        assertEquals(listOf(PhoneBootstrap.Step.CONNECTED, PhoneBootstrap.Step.FAILED), withTheWord)
        assertEquals(
            "the claim is the screen's own heading, so the two cannot drift",
            PhoneToolsView.CONNECTED_HEADING,
            PhoneToolsView.stepLine(PhoneBootstrap.Step.CONNECTED),
        )
    }

    /**
     * The restart case, expressed without a restart: this process has no attempt ([Step.IDLE]),
     * while the record kept the handshake that landed before the app closed. Both the row and the
     * screen must draw *Connected*, because both fold it through [PhoneBootstrap.settled].
     */
    @Test
    fun theRowSaysConnectedAfterARestartFromTheKeptHandshakeAlone() {
        val record = PhoneSetup.Record(handshakeAt = now - 60_000L, handshakeTag = "t", tokenTag = "t")
        val step = PhoneBootstrap.settled(PhoneBootstrap.Progress(), record.handshakeAt, now).step
        assertEquals(
            "a kept handshake with no attempt in this process is Connected, not Not set up",
            "Connected",
            PhoneToolsView.stepLine(step),
        )
        assertEquals("and the record agrees", PhoneSetup.State.CONNECTED, PhoneSetup.state(record, now, "t"))
        assertEquals(
            "the screen draws it from the same step",
            PhoneToolsView.CONNECTED_HEADING,
            PhoneToolsSheet.drawn(status(), record, "t", now).stateLine,
        )
        assertFalse("nothing is left to set up, so the setup action is gone", PhoneToolsView.setupRowVisible(step))
    }

    /**
     * An attempt that just failed outranks a stale handshake: a handshake recorded during the
     * attempt, or one from last week, must not hide the failure the owner just watched happen.
     */
    @Test
    fun aFailedAttemptOutranksAStaleHandshake() {
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
        assertTrue("and the way forward stays visible", PhoneToolsView.setupRowVisible(settled.step))
    }

    // ---- 4. the setup action is on the screen only while there is something to set up ----

    @Test
    fun theSetupActionGoesAwayOnceConnected() {
        for (step in PhoneBootstrap.Step.entries) {
            assertEquals(
                "the setup action belongs on every step but $step",
                step != PhoneBootstrap.Step.CONNECTED,
                PhoneToolsView.setupRowVisible(step),
            )
        }
        // The screen's press is [PhoneToolsSheet.setUpWithAgent], and it is one implementation.
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
        // Every action that begins the setup goes through the one guard; *Ask again* is a re-ask
        // of a working entry, not a setup, so a connected phone keeps it and loses the setup.
        assertTrue("the setup button goes through the guard", sheet.contains("PhoneBootstrap.BUTTON,"))
        assertTrue("and a retry does too", sheet.contains("\"TRY AGAIN\","))
    }

    // ---- 5. the Alpha gate still governs the screen ----

    @Test
    fun withTheGateOffTheRowIsInertAndTheScreenDoesNotOpen() {
        // Dimmed and taking no tap: the row is in the block, and the block drives every row's gate.
        assertTrue(
            "the section row must be in the alpha block",
            AlphaBlock.ROWS.any { it.row == "row_grp_phone" },
        )
        assertTrue(
            "the block's rows are gated from one table",
            settings.contains("for (row in AlphaBlock.ROWS)"),
        )
        assertTrue(
            "turning the set off closes a live listener, window included",
            settings.contains("if (!phoneTools) PhoneMcp.closeNow()"),
        )
        // A dimmed row is not enough: the door asks the same question, before it opens anything —
        // so a banner extra, the debug harness or a stale link cannot get around the gate.
        val gate = settings.indexOf("if (!AlphaBlock.canOpen(prefs, section)) return")
        val door = settings.indexOf("if (section == SECTION_PHONE) { openPhoneTools(); return }")
        assertTrue("the door does not ask the gate", gate in 1 until door)
        assertTrue(
            "the screen's own press refuses while the set is off",
            sheet.contains("AlphaGate.allows(act, AlphaGate.FeatureSet.PHONE_TOOLS)"),
        )
        // The row says why it is off, in the one line every Phone-tools surface uses.
        assertEquals(
            PhoneToolsView.ALPHA_OFF,
            PhoneToolsView.stepLine(PhoneBootstrap.Step.CONNECTED, allowed = false),
        )
        assertEquals(
            "and the state line on the screen says the same",
            PhoneToolsView.ALPHA_OFF,
            PhoneToolsSheet.drawn(
                status().copy(allowed = false),
                PhoneSetup.Record(),
                tag,
                now,
            ).stateLine,
        )
    }
}
