package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool surface, audited action by action (#503).
 *
 * The schema is a promise and the dispatcher is the phone: every name the schema offers must parse
 * into a call of that same name and reach the engine, and every name it does not offer must be
 * refused without touching the phone. This file walks **every advertised action** rather than a
 * chosen few, because the class of bug it exists to catch — a tool that lists and cannot run, or
 * lists under one name and runs under another — is invisible in a per-action test that was written
 * beside the code it tests.
 *
 * Four properties per action, in the three grant states:
 *
 *  * **Off** — `not_granted`, `ok:false`, and the platform was not called at all (not even read);
 *  * **Ask** — the card is raised, `approval-required` with an id, and no effect was performed;
 *  * **Allow** — the action reaches its effect, or answers with the one reason the *environment*
 *    gives (`permission`, `unavailable`, … — never `unknown-action`/`invalid-arguments`);
 *  * and the audit row says what happened (`done` + the undo id, or the refusal's reason).
 *
 * `notifications`, `notification_read` and `notification_dismiss` can only answer `unavailable`
 * here: their listener is attached to a live call, which a JVM test cannot open. That is the one
 * environmental refusal this file accepts, and it is why they are listed in [LISTENER_ACTIONS].
 */
class PhoneSurfaceAuditTest {

    private val platform = FakePhonePlatform()
    private val entries = ArrayList<PhoneAudit.Entry>()
    private val log = ArrayList<String>()
    private val t0 = 1_000_000L
    private val e0 = 1_800_000_000_000L

    private val open = PhoneGrants.Source { PhoneGrants.State.ALLOW }

    /** Every card the engine raises. A group at Ask must raise one, not refuse. */
    private class Surface : PhoneApproval.Surface {
        val raised = ArrayList<PhoneApproval.Card>()
        override fun raise(card: PhoneApproval.Card): Boolean {
            raised.add(card)
            return true
        }
    }

    private val surface = Surface()

    private fun engine(grants: PhoneGrants.Source): PhoneEngine = PhoneEngine(
        platform,
        log = { log.add(it) },
        grants = grants,
        audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
        approvalSurface = surface,
    )

    private fun only(id: String): PhoneGrants.Source = PhoneGrants.Source { g ->
        if (g.id == id) PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
    }

    private fun askOnly(id: String): PhoneGrants.Source = PhoneGrants.Source { g ->
        if (g.id == id) PhoneGrants.State.ASK else PhoneGrants.State.OFF
    }

    /**
     * One minimal, *valid* call per advertised action. Valid matters: the point is to reach the
     * dispatcher, so an argument the action would refuse would prove nothing but the parser.
     */
    private class Case(val args: Map<String, Any?>, val effect: String? = null)

    private val cases: Map<String, Case> = linkedMapOf(
        "timer" to Case(mapOf("action" to "timer", "duration_ms" to 60_000), "startTimer"),
        "alarm" to Case(mapOf("action" to "alarm", "at" to "07:30"), "startAlarm"),
        "dnd" to Case(mapOf("action" to "dnd", "on" to true), "setDnd"),
        "volume" to Case(mapOf("action" to "volume", "level" to 50), "setVolume"),
        "note" to Case(mapOf("action" to "note", "text" to "a note"), "saveNote"),
        "play" to Case(mapOf("action" to "play"), "mediaKey"),
        "pause" to Case(mapOf("action" to "pause"), "mediaKey"),
        "skip" to Case(mapOf("action" to "skip", "direction" to "next"), "mediaKey"),
        "now_playing" to Case(mapOf("action" to "now_playing")),
        "state" to Case(mapOf("action" to "state")),
        "clip" to Case(mapOf("action" to "clip", "text" to "to paste"), "clip"),
        "clip_read" to Case(mapOf("action" to "clip_read")),
        "file_list" to Case(mapOf("action" to "file_list")),
        "file_read" to Case(mapOf("action" to "file_read", "name" to "notes.txt")),
        "file_offer" to Case(mapOf("action" to "file_offer", "name" to "notes.txt"), "fileOffer"),
        "agenda" to Case(mapOf("action" to "agenda")),
        "event_create" to Case(
            mapOf(
                "action" to "event_create",
                "title" to "Standup",
                "start" to "2026-11-06T09:00",
                "duration_min" to 15,
            ),
            "createEvent",
        ),
        "notifications" to Case(mapOf("action" to "notifications")),
        "notification_read" to Case(mapOf("action" to "notification_read", "id" to "n-1")),
        "notification_dismiss" to Case(mapOf("action" to "notification_dismiss", "id" to "n-1")),
        "contact" to Case(mapOf("action" to "contact", "name" to "Ada")),
        "message" to Case(mapOf("action" to "message", "to" to "+15550100", "text" to "hello"), "sendMessage"),
        "dial" to Case(mapOf("action" to "dial", "to" to "+15550100"), "dial"),
        "apps" to Case(mapOf("action" to "apps")),
        "launch" to Case(mapOf("action" to "launch", "app" to "org.example"), "startApp"),
        "open" to Case(mapOf("action" to "open", "uri" to "https://example.com"), "startUri"),
        "uninstall" to Case(
            mapOf("action" to "uninstall", "package" to "org.example.app"),
            "uninstall",
        ),
        // `undo` is dispatched (and refused `expired` when the id is not ours); the success path is
        // pinned separately, because it needs something to reverse.
        "undo" to Case(mapOf("action" to "undo", "id" to "timer-1")),
        "status" to Case(mapOf("action" to "status")),
    )

    /** These three need the listener a live call attaches; off-device they answer `unavailable`. */
    private val listenerActions = setOf("notifications", "notification_read", "notification_dismiss")

    // ---- the table is the surface ----

    @Test fun the_table_covers_every_advertised_action_and_nothing_else() {
        assertEquals(
            "every offered action must have a case here, and no case may name an action the " +
                "schema does not offer",
            PhoneTool.offeredActions(open).toSet(),
            cases.keys,
        )
    }

    @Test fun every_schema_property_is_read_by_some_action() {
        val properties = (PhoneTool.schema(open)["inputSchema"] as Map<*, *>)["properties"] as Map<*, *>
        val consumed = PhoneTool.ARGUMENTS.values.flatten().toSet() + setOf("action", "approval")
        for (key in properties.keys) {
            assertTrue(
                "the schema advertises `$key`, but no action reads it — a call carrying it is refused",
                key in consumed,
            )
        }
    }

    @Test fun every_action_argument_is_advertised() {
        val properties = (PhoneTool.schema(open)["inputSchema"] as Map<*, *>)["properties"] as Map<*, *>
        for ((action, keys) in PhoneTool.ARGUMENTS) {
            for (key in keys) {
                assertTrue(
                    "$action reads `$key`, which the schema does not offer; the model cannot supply it",
                    key in properties,
                )
            }
        }
    }

    @Test fun an_argument_description_names_no_action_whose_group_is_off() {
        // Descriptions are read on every turn, exactly like the enum. A `text` description that
        // says "note, clip or message" while the notes group is Off promises what the grants
        // forbid: the model is told about an action the schema has already refused to offer.
        fun descriptions(grants: PhoneGrants.Source): Map<String, String> {
            val properties = (PhoneTool.schema(grants)["inputSchema"] as Map<*, *>)["properties"] as Map<*, *>
            val out = LinkedHashMap<String, String>()
            for ((key, raw) in properties) {
                val text = (raw as Map<*, *>)["description"] as? String ?: continue
                out[key.toString()] = text
            }
            return out
        }

        fun names(text: String, action: String): Boolean =
            Regex("\\b" + Regex.escape(action) + "\\b").containsMatchIn(text)

        for (group in PhoneGrants.GROUPS) {
            val grants = PhoneGrants.Source { g ->
                if (g.id == group.id) PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
            }
            for ((key, text) in descriptions(grants)) {
                for (action in group.actions) {
                    assertTrue(
                        "`$action` belongs to ${group.id}, which is Off, but the `$key` " +
                            "description names it: $text",
                        !names(text, action),
                    )
                }
            }
        }

        // With every group Off, no argument description may name any action at all.
        val everyAction = PhoneGrants.GROUPS.flatMap { it.actions }
        for ((key, text) in descriptions(PhoneGrants.ALL_OFF)) {
            for (action in everyAction) {
                assertTrue(
                    "nothing is granted, but the `$key` description names `$action`: $text",
                    !names(text, action),
                )
            }
        }
    }

    @Test fun an_unknown_argument_is_refused_rather_than_dropped() {
        // The schema says `additionalProperties: false`. Before #503 only `uninstall` enforced it:
        // this note would have been written and the recipient silently ignored.
        val r = PhoneTool.parse(
            mapOf("action" to "note", "text" to "a note", "to" to "Mom"),
            open,
        )
        val refused = r as PhoneTool.Parsed.Refuse
        assertEquals(PhoneReason.INVALID, refused.result.reason)
        assertTrue(refused.result.detail!!, refused.result.detail!!.contains("unexpected: to"))
    }

    @Test fun every_action_refuses_an_argument_it_does_not_take() {
        for ((action, keys) in PhoneTool.ARGUMENTS) {
            for (property in listOf("text", "duration_ms", "to", "id", "uri", "name")) {
                if (property in keys) continue
                // `approval` is cross-cutting and must not be refused; every other schema property
                // is refused by every action that does not take it.
                val parsed = PhoneTool.parse(
                    mapOf("action" to action, property to "x"),
                    open,
                )
                if (parsed is PhoneTool.Parsed.Refuse) {
                    assertEquals(
                        "$action accepted `$property`, which it does not take",
                        PhoneReason.INVALID,
                        parsed.result.reason,
                    )
                }
            }
        }
    }

    // ---- parse: a name in the enum is a call the phone can make ----

    @Test fun every_advertised_action_parses_into_a_call_of_its_own_name() {
        for ((action, case) in cases) {
            when (val parsed = PhoneTool.parse(case.args, open)) {
                is PhoneTool.Parsed.Refuse -> throw AssertionError(
                    "$action is advertised but parse refused it: ${parsed.result.reason} — " +
                        parsed.result.detail
                )
                is PhoneTool.Parsed.Run -> assertEquals(
                    "the schema's name must be the dispatched name",
                    action,
                    parsed.call.action,
                )
            }
        }
    }

    // ---- Allow: the action runs and says what it did ----

    @Test fun every_advertised_action_reaches_the_engine_and_answers_in_one_shape() {
        for ((action, case) in cases) {
            platform.called.clear()
            platform.performed.clear()
            val result = engine(open).dispatch(case.args, t0, e0)

            assertEquals("the result names the action that was called", action, result.action)
            assertNotEquals(
                "$action is advertised but the dispatcher does not know it",
                PhoneReason.UNKNOWN,
                result.reason,
            )
            assertNotEquals(
                "$action is advertised with arguments the dispatcher refuses",
                PhoneReason.INVALID,
                result.reason,
            )
            if (result.ok) {
                assertNull("a success carries no reason", result.reason)
                assertTrue(
                    "$action succeeded without saying what changed",
                    result.facts.isNotEmpty(),
                )
                case.effect?.let {
                    assertTrue(
                        "$action succeeded but never reached $it (calls: ${platform.called})",
                        it in platform.performed,
                    )
                }
            } else {
                assertNotNull("$action refused without a reason", result.reason)
                assertTrue(
                    "$action refused without a sentence",
                    !result.detail.isNullOrBlank(),
                )
                assertTrue(
                    "$action refused with a reason that means the surface is broken: ${result.reason}",
                    result.reason !in setOf(PhoneReason.UNKNOWN, PhoneReason.INVALID, PhoneReason.NOT_GRANTED),
                )
                if (action in listenerActions) {
                    assertEquals(
                        "the listener is only open during a call, so this is the one honest refusal",
                        PhoneReason.UNAVAILABLE,
                        result.reason,
                    )
                }
            }
        }
    }

    @Test fun every_advertised_action_performs_its_effect_at_allow() {
        for ((action, case) in cases) {
            val effect = case.effect ?: continue
            platform.performed.clear()
            val result = engine(open).dispatch(case.args, t0, e0)
            assertTrue("$action failed at Allow: ${result.reason} ${result.detail}", result.ok)
            assertTrue("$action did not perform $effect", effect in platform.performed)
        }
    }

    @Test fun every_advertised_action_is_reversible_where_it_says_it_is() {
        for ((action, case) in cases) {
            platform.performed.clear()
            val result = engine(open).dispatch(case.args, t0, e0)
            if (!result.ok) continue
            val undoable = result.facts["undoable"] == true
            assertEquals(
                "$action's result and its undo handle disagree",
                undoable,
                result.toMap()["undo"] != null,
            )
        }
    }

    @Test fun every_success_says_whether_it_is_undoable() {
        // Absence used to mean "not reversible" for most actions and "unknown" for a few: a mind
        // could not tell a read apart from an action the phone had silently failed to describe.
        // Every success now carries the field explicitly, false unless an undo was registered.
        for ((action, case) in cases) {
            platform.performed.clear()
            val result = engine(open).dispatch(case.args, t0, e0)
            if (!result.ok) continue
            assertTrue(
                "$action succeeded without saying whether it is undoable: ${result.json()}",
                result.facts.containsKey("undoable"),
            )
            assertTrue(
                "$action's undoable is not a boolean: ${result.json()}",
                result.facts["undoable"] is Boolean,
            )
            assertEquals(
                "$action's undoable and its undo object disagree",
                result.facts["undoable"] == true,
                result.toMap()["undo"] != null,
            )
        }

        // The one non-action success shape: `status` reports, so it is explicitly not undoable.
        val status = engine(open).dispatch(mapOf("action" to "status"), t0, e0)
        assertTrue(status.json(), status.ok)
        assertEquals(false, status.facts["undoable"])
    }

    // ---- the result shape is the same across actions ----

    @Test fun a_refusal_and_a_success_report_the_same_tier_for_the_same_action() {
        // `uninstall` lives in the instant apps group and `notification_dismiss` in the instant
        // notifications group, but both are consequential by their own tier. Before #503 the Off
        // refusal used the group's tier, so the same action reported tier 0 when refused and 2
        // when it ran.
        for (group in PhoneGrants.GROUPS) {
            for (action in group.actions) {
                val ran = engine(open).dispatch(cases.getValue(action).args, t0, e0)
                val refused = engine(PhoneGrants.ALL_OFF).dispatch(cases.getValue(action).args, t0, e0)
                assertFalse("$action must be refused with its group off", refused.ok)
                assertEquals(
                    "$action reports a different tier refused than run",
                    ran.tier,
                    refused.tier,
                )
            }
        }
    }

    // ---- Off: refused before anything is read or performed ----

    @Test fun an_off_group_is_refused_and_the_phone_is_not_touched() {
        for (group in PhoneGrants.GROUPS) {
            for (action in group.actions) {
                val case = cases[action]
                    ?: throw AssertionError("$action is in ${group.id} but has no case")
                platform.called.clear()
                platform.performed.clear()
                entries.clear()
                val result = engine(PhoneGrants.ALL_OFF).dispatch(case.args, t0, e0)

                assertFalse("$action ran with its group off", result.ok)
                assertEquals(
                    "$action must say the group is off, not something vaguer",
                    PhoneReason.NOT_GRANTED,
                    result.reason,
                )
                assertTrue(
                    "$action touched the phone on the way to a refusal: ${platform.called}",
                    platform.called.isEmpty(),
                )
                assertEquals(
                    "the audit row must be the refusal, not an attempt",
                    PhoneReason.NOT_GRANTED,
                    entries.single().outcome,
                )
                assertFalse(entries.single().ok)
            }
        }
    }

    @Test fun an_off_group_is_absent_from_the_enum_while_an_ask_is_present() {
        val off = PhoneTool.offeredActions(PhoneGrants.ALL_OFF).toSet()
        assertEquals(setOf("undo", "status"), off)
        for (group in PhoneGrants.GROUPS) {
            val asked = PhoneTool.offeredActions(askOnly(group.id)).toSet()
            for (action in group.actions) {
                assertTrue("${group.id} at Ask must offer $action", action in asked)
            }
            val allowed = PhoneTool.offeredActions(only(group.id)).toSet()
            assertEquals("Ask and Allow offer the same names", asked, allowed)
        }
    }

    // ---- Ask: a card, and nothing performed ----

    @Test fun an_ask_group_raises_a_card_and_performs_nothing() {
        for (group in PhoneGrants.GROUPS) {
            for (action in group.actions) {
                val case = cases[action]
                    ?: throw AssertionError("$action is in ${group.id} but has no case")
                platform.called.clear()
                platform.performed.clear()
                surface.raised.clear()
                val result = engine(askOnly(group.id)).dispatch(case.args, t0, e0)

                assertFalse("$action ran at Ask without a card", result.ok)
                assertEquals(
                    "$action at Ask must ask for approval",
                    PhoneReason.APPROVAL,
                    result.reason,
                )
                assertNotNull("$action at Ask returned no approval id", result.facts["approval_id"])
                assertNotNull("$action at Ask did not raise a card", surface.raised.lastOrNull())
                assertTrue(
                    "$action performed something on the way to its card: ${platform.performed}",
                    platform.performed.isEmpty(),
                )
            }
        }
    }

    @Test fun a_card_is_answered_and_only_then_does_the_action_run() {
        val e = engine(askOnly("timers-alarms"))
        val asked = e.dispatch(cases.getValue("timer").args, t0, e0)
        assertEquals(PhoneReason.APPROVAL, asked.reason)
        val id = asked.facts["approval_id"] as String
        assertTrue("no timer was armed before the answer", platform.performed.isEmpty())

        val card = surface.raised.last()
        assertTrue(e.answerApproval(card.id, "yes", "tap", e0))

        val ran = e.dispatch(cases.getValue("timer").args + ("approval" to id), t0, e0 + 1_000)
        assertTrue("the approved timer must run: ${ran.reason} ${ran.detail}", ran.ok)
        assertEquals(listOf("startTimer"), platform.performed)
    }

    @Test fun the_audit_does_not_call_an_uncertain_dial_a_refusal() {
        // The live-call dial replies before the dialer opens: the result carries
        // `outcome_uncertain`, and the row must not flatten that to "refused, nothing happened".
        entries.clear()
        val live = object : PhoneEngine.PhonePlatform by platform {
            override fun callLive(): Boolean = true
            override fun announce(line: String, after: () -> Unit) {
                // The line is still playing; the dialer opens after this reply.
            }
        }
        val e = PhoneEngine(
            live,
            grants = only("calls"),
            audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
        )
        val r = e.dispatch(cases.getValue("dial").args, t0, e0)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertEquals(true, r.facts["outcome_uncertain"])
        assertEquals(PhoneAudit.UNCERTAIN, entries.single().outcome)
        assertFalse(entries.single().ok)
    }

    // ---- undo and status: the two that belong to no group ----

    @Test fun undo_reverses_the_thing_that_was_done() {
        val e = engine(only("timers-alarms"))
        val set = e.dispatch(cases.getValue("timer").args, t0, e0)
        assertTrue(set.json(), set.ok)
        platform.performed.clear()
        val undone = e.dispatch(mapOf("action" to "undo", "id" to set.id!!), t0, e0)
        assertTrue(undone.json(), undone.ok)
        assertEquals(listOf("cancelTimer"), platform.performed)
        assertEquals("cancelled", undone.facts["state"])
    }

    @Test fun undo_cannot_reverse_an_action_whose_grant_was_turned_off() {
        // One engine, one registry, and a grant that changes between the two calls: that is the
        // live case (the owner turns the group off while a timer is still running).
        var timers = PhoneGrants.State.ALLOW
        val grants = PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") timers else PhoneGrants.State.OFF
        }
        val engine = engine(grants)
        val set = engine.dispatch(cases.getValue("timer").args, t0, e0)
        platform.performed.clear()
        timers = PhoneGrants.State.OFF
        val off = engine.dispatch(mapOf("action" to "undo", "id" to set.id!!), t0, e0)
        assertFalse(off.ok)
        assertEquals(PhoneReason.NOT_GRANTED, off.reason)
        assertTrue(platform.performed.isEmpty())
    }

    @Test fun status_reports_what_the_grant_state_offers() {
        val r = engine(PhoneGrants.ALL_OFF).dispatch(mapOf("action" to "status"), t0, e0)
        assertTrue(r.ok)
        assertEquals(listOf("undo", "status"), r.facts["offered"])
        assertTrue(
            "an all-off phone must say so, group by group",
            (r.facts["grants"] as String).contains("timers-alarms=off"),
        )
    }

    @Test fun status_names_only_the_actions_tools_list_advertises() {
        // Before this test, `tiers.2` was the whole Tier-2 table — including `delete`, `pay`,
        // `purchase` and `transfer`, which are never advertised and which a direct call refuses.
        // A mind reading `status` was told this phone could delete and pay. It cannot.
        val sources = listOf(PhoneGrants.ALL_OFF, open) + PhoneGrants.GROUPS.map { only(it.id) }
        for (grants in sources) {
            val advertised = PhoneTool.offeredActions(grants).toSet()
            val r = engine(grants).dispatch(mapOf("action" to "status"), t0, e0)
            assertTrue(r.json(), r.ok)

            @Suppress("UNCHECKED_CAST")
            val tiers = r.facts["tiers"] as Map<String, List<String>>
            val named = tiers.values.flatten().toSet()
            for (action in named) {
                assertTrue(
                    "status named `$action`, which tools/list does not advertise and a call " +
                        "would refuse: ${r.json()}",
                    action in advertised,
                )
            }
            assertEquals(
                "every advertised name is reported under its own tier, and nothing else",
                advertised,
                named,
            )
            assertEquals(
                "an advertised action sits under the tier the dispatcher reports",
                named.all { PhoneTiers.of(it)?.let { tier -> tiers[tier.toString()]?.contains(it) } == true },
                true,
            )
            @Suppress("UNCHECKED_CAST")
            val groupsOn = (r.facts["groups_on"] as List<String>).toSet()
            for (group in PhoneGrants.GROUPS) {
                assertEquals(
                    "groups_on must say whether ${group.id} is on",
                    PhoneGrants.stateOf(grants, group) != PhoneGrants.State.OFF,
                    group.id in groupsOn,
                )
            }
        }

        // The exact names the audit found: no unbuilt capability may be reported as a capability.
        val all = engine(open).dispatch(mapOf("action" to "status"), t0, e0)
        @Suppress("UNCHECKED_CAST")
        val tierTwo = (all.facts["tiers"] as Map<String, List<String>>)["2"].orEmpty()
        for (unbuilt in listOf("delete", "pay", "purchase", "transfer")) {
            assertFalse("$unbuilt is not built and must not be reported", unbuilt in tierTwo)
        }
        assertEquals(setOf("uninstall", "file_offer"), tierTwo.toSet())
    }

    // ---- the audit ----

    @Test fun every_action_writes_one_audit_row_that_names_it() {
        for ((action, case) in cases) {
            entries.clear()
            engine(open).dispatch(case.args, t0, e0)
            assertEquals("$action wrote ${entries.size} audit rows", 1, entries.size)
            assertEquals(action, entries.single().action)
        }
    }

    @Test fun the_audit_records_what_happened_and_which_undo_carries_it() {
        entries.clear()
        val r = engine(open).dispatch(cases.getValue("timer").args, t0, e0)
        val row = entries.single()
        assertTrue(row.ok)
        assertEquals("timer", row.action)
        assertEquals("done", row.outcome)
        assertEquals("the undo id must be the one the result named", r.id, row.undoId)
        assertEquals("the duration is recorded as given", "60000", row.arguments["duration_ms"])
    }

    @Test fun the_audit_records_a_failure_as_a_failure_with_no_undo() {
        entries.clear()
        val failing = object : PhoneEngine.PhonePlatform by platform {
            override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean = false
        }
        val e = PhoneEngine(
            failing,
            grants = open,
            audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
        )
        val r = e.dispatch(cases.getValue("timer").args, t0, e0)
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        val row = entries.single()
        assertFalse(row.ok)
        assertEquals(PhoneReason.UNAVAILABLE, row.outcome)
        assertNull("a failed action must not hand out an undo", row.undoId)
    }

    @Test fun the_audit_never_carries_a_note_or_a_message_body() {
        entries.clear()
        val e = engine(open)
        e.dispatch(cases.getValue("note").args, t0, e0)
        e.dispatch(cases.getValue("message").args, t0, e0)
        val note = entries[0]
        assertEquals("${"a note".length} chars", note.arguments["text"])
        assertFalse(note.arguments.toString().contains("a note"))
        val message = entries[1]
        assertEquals("the recipient is what the owner needs to see", "+15550100", message.arguments["to"])
        assertEquals("5 chars", message.arguments["text"])
    }
}
