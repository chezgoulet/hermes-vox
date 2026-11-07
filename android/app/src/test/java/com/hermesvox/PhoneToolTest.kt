package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421 step 3 and #429 step 1: the tool schema is the contract with the mind, so it is pinned here.
 *
 * The shape is the point: **one** tool whose action `enum` is a function of the owner's grants.
 * A group that is Off is **absent** — a model cannot call what it cannot see — while the dispatcher
 * still recognises the name and refuses it with `reason="not_granted"`. With every group Off (the
 * default) only `undo` and `status` remain, because those two do not act on the phone.
 */
class PhoneToolTest {

    /** Every group at Allow. Ask and Allow both reach the engine; the card tests live below. */
    private val open = PhoneGrants.Source { PhoneGrants.State.ALLOW }

    private fun schemaObj(grants: PhoneGrants.Source = open): JObj =
        Json.parse(Json.encode(PhoneTool.schema(grants))) as JObj

    private fun input(grants: PhoneGrants.Source = open): JObj = schemaObj(grants)["inputSchema"] as JObj

    private fun enumOf(grants: PhoneGrants.Source = open): List<String> =
        (((input(grants)["properties"] as JObj)["action"] as JObj)["enum"] as JArr)
            .items.map { (it as JStr).value }

    @Test fun it_is_one_tool_called_phone() {
        val s = schemaObj()
        assertEquals("phone", (s["name"] as JStr).value)
        val desc = (s["description"] as JStr).value
        assertTrue("the description must tell the mind to check the result", desc.contains("check ok"))
        assertTrue("and to report what happened", desc.contains("what actually happened"))
    }

    @Test fun the_action_enum_is_built_from_the_grants() {
        assertEquals(
            listOf(
                "timer", "alarm", "dnd", "volume", "note", "play", "pause", "skip", "now_playing",
                "state", "clip", "clip_read", "file_list", "file_read", "file_offer", "agenda",
                "event_create", "notifications", "notification_read", "notification_dismiss",
                "message", "dial", "apps", "launch", "open", "uninstall", "contact", "undo",
                "status",
            ),
            enumOf(),
        )
    }

    @Test fun every_group_off_offers_only_undo_and_status() {
        assertEquals(listOf("undo", "status"), enumOf(PhoneGrants.ALL_OFF))
        assertEquals(
            "the enum and the registry are the same function",
            PhoneTool.offeredActions(PhoneGrants.ALL_OFF),
            enumOf(PhoneGrants.ALL_OFF),
        )
    }

    @Test fun a_denied_group_is_absent_from_the_enum_and_from_the_description() {
        // Timers allowed, notes and dnd/volume Off.
        val onlyTimers = PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(listOf("timer", "alarm", "undo", "status"), enumOf(onlyTimers))
        val desc = (schemaObj(onlyTimers)["description"] as JStr).value
        assertTrue("what is on is named", desc.contains("Timers and alarms"))
        assertFalse("a group that is off is not promised", desc.contains("Do not disturb and volume"))
    }

    @Test fun a_group_that_is_off_says_so_in_the_tool_description() {
        val desc = (schemaObj(PhoneGrants.ALL_OFF)["description"] as JStr).value
        assertTrue("no group is on is stated plainly", desc.contains("No capability group is on"))
        assertTrue("the reason vocabulary is named", desc.contains("not_granted"))
    }

    @Test fun a_reported_group_at_ask_is_offered_and_parses_into_the_handshake() {
        val notes = PhoneGrants.Source { g ->
            if (g.id == "notes") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        }
        assertTrue("note is advertised", "note" in enumOf(notes))
        val parsed = PhoneTool.parse(mapOf("action" to "note", "text" to "milk"), notes)
        assertTrue(
            "a group at Ask parses into a call; the engine raises the card",
            parsed is PhoneTool.Parsed.Run,
        )
        assertEquals("note", (parsed as PhoneTool.Parsed.Run).call.action)
    }

    /**
     * The corrected M8 rule: Ask used to withhold a Tier 0 group because there was no card. With
     * one card for every action, Ask offers the actions and the card answers them.
     */
    @Test fun a_tier_zero_group_at_ask_is_advertised_and_parses_into_the_handshake() {
        val timers = PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        }
        assertEquals(
            "Ask on a Tier 0 group offers its actions, because every action has a card",
            listOf("timer", "alarm", "undo", "status"),
            enumOf(timers),
        )

        val parsed = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 1_000), timers)
        assertEquals("timer", (parsed as PhoneTool.Parsed.Run).call.action)

        val contacts = PhoneGrants.Source { g ->
            if (g.id == "contacts") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        }
        assertTrue("contact is advertised at Ask", "contact" in enumOf(contacts))
        val desc = (schemaObj(contacts)["description"] as JStr).value
        assertTrue(
            "the description names the card that will be raised: $desc",
            desc.contains("Contacts (ask): contact — each call raises a card"),
        )
    }

    /** With one handshake for every action, Ask keeps the whole apps group, not one name. */
    @Test fun a_group_at_ask_keeps_every_action_not_only_the_one_with_a_card() {
        val apps = PhoneGrants.Source { g ->
            if (g.id == "apps") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        }
        assertEquals(listOf("apps", "launch", "open", "uninstall", "undo", "status"), enumOf(apps))
        for (action in listOf("apps", "launch", "open", "uninstall")) {
            val parsed = PhoneTool.parse(
                when (action) {
                    "apps" -> mapOf("action" to action)
                    "launch" -> mapOf("action" to action, "app" to "Firefox")
                    "open" -> mapOf("action" to action, "uri" to "https://example.org")
                    else -> mapOf("action" to action, "package" to "org.mozilla.firefox")
                },
                apps,
            )
            assertTrue("$action at Ask must reach the engine's handshake", parsed is PhoneTool.Parsed.Run)
            assertEquals(action, (parsed as PhoneTool.Parsed.Run).call.action)
        }
    }

    @Test fun an_action_in_an_off_group_is_refused_as_not_granted() {
        val parsed = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000), PhoneGrants.ALL_OFF)
        assertTrue(parsed is PhoneTool.Parsed.Refuse)
        val result = (parsed as PhoneTool.Parsed.Refuse).result
        assertEquals(PhoneReason.NOT_GRANTED, result.reason)
        assertFalse(result.ok)
        assertNull(result.id)
        assertTrue("the refusal names the group the owner must grant", result.detail!!.contains("Timers and alarms"))
    }

    @Test fun an_unknown_action_lists_only_what_is_offered() {
        val parsed = PhoneTool.parse(mapOf("action" to "fly"), PhoneGrants.ALL_OFF)
        val detail = (parsed as PhoneTool.Parsed.Refuse).result.detail!!
        assertTrue(detail.contains("undo"))
        assertFalse("an off action is not advertised in the refusal", detail.contains("timer"))
    }

    @Test fun every_offered_action_is_one_the_phone_can_actually_perform() {
        for (name in enumOf()) {
            assertTrue(
                "$name is offered but not executable",
                name == "undo" || name == "status" || PhoneTiers.executable(name),
            )
        }
    }

    @Test fun the_tier_two_capabilities_are_not_offered() {
        val offered = enumOf().toSet()
        for (consequential in listOf("share", "call", "email", "delete", "send")) {
            assertFalse("$consequential must not be advertised", consequential in offered)
        }
        assertTrue("uninstall is offered, because its card exists", "uninstall" in offered)
    }

    @Test fun action_is_required_and_unknown_arguments_are_refused() {
        assertEquals(listOf("action"), (input()["required"] as JArr).items.map { (it as JStr).value })
        assertEquals(false, (input()["additionalProperties"] as JBool).value)
    }

    @Test fun each_argument_names_what_it_is_for_and_its_bound() {
        val props = input()["properties"] as JObj
        assertEquals(PhoneTool.MIN_DURATION_MS, ((props["duration_ms"] as JObj)["minimum"] as com.hermesvox.core.JNum).raw.toLong())
        assertEquals(PhoneTool.MAX_DURATION_MS, ((props["duration_ms"] as JObj)["maximum"] as com.hermesvox.core.JNum).raw.toLong())
        assertEquals("0", ((props["level"] as JObj)["minimum"] as com.hermesvox.core.JNum).raw)
        assertEquals("100", ((props["level"] as JObj)["maximum"] as com.hermesvox.core.JNum).raw)
        val direction = (props["direction"] as JObj)
        assertNull(
            "volume and skip share `direction` with disjoint values, so an enum here would " +
                "advertise a value one of them refuses",
            direction["enum"],
        )
        val directionText = (direction["description"] as JStr).value
        for (values in PhoneTool.DIRECTIONS.values) {
            for (value in values) {
                assertTrue(
                    "`$value` is accepted but the schema does not name it: $directionText",
                    directionText.contains(value),
                )
            }
        }
        for (key in listOf("at", "on", "text", "id", "to", "app", "uri", "package")) {
            assertNotNull("$key must be documented", props[key])
        }
    }

    // ---- the media group ----

    @Test fun media_actions_are_absent_until_the_group_is_granted() {
        val mediaOff = PhoneGrants.Source { g ->
            if (g.id == "media") PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
        }
        val offered = enumOf(mediaOff)
        for (a in listOf("play", "pause", "skip", "now_playing")) {
            assertFalse("$a must be absent while Media is off", a in offered)
        }
        assertTrue("the rest of the surface is untouched", "timer" in offered)

        val mediaOn = PhoneGrants.Source { g ->
            if (g.id == "media") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(listOf("play", "pause", "skip", "now_playing", "undo", "status"), enumOf(mediaOn))
    }

    @Test fun skip_defaults_to_next_and_accepts_only_its_own_directions() {
        val next = PhoneTool.parse(mapOf("action" to "skip"), open)
        assertEquals(false, ((next as PhoneTool.Parsed.Run).call as PhoneTool.Call.Skip).previous)

        val explicit = PhoneTool.parse(mapOf("action" to "skip", "direction" to "next"), open) as PhoneTool.Parsed.Run
        assertEquals(false, (explicit.call as PhoneTool.Call.Skip).previous)

        val back = PhoneTool.parse(mapOf("action" to "skip", "direction" to "previous"), open) as PhoneTool.Parsed.Run
        assertEquals(true, (back.call as PhoneTool.Call.Skip).previous)

        // The two actions share the `direction` property; each rejects the other's words, and the
        // refusal names the action and the value it was given — "invalid-arguments" alone would
        // leave the mind guessing which of two actions and four words was wrong.
        val up = PhoneTool.parse(mapOf("action" to "skip", "direction" to "up"), open) as PhoneTool.Parsed.Refuse
        assertEquals(PhoneReason.INVALID, up.result.reason)
        assertTrue(up.result.detail!!, up.result.detail!!.contains("skip"))
        assertTrue(up.result.detail!!, up.result.detail!!.contains("up"))
        val previousVolume = PhoneTool.parse(
            mapOf("action" to "volume", "direction" to "previous"), open,
        ) as PhoneTool.Parsed.Refuse
        assertEquals(PhoneReason.INVALID, previousVolume.result.reason)
        assertTrue(previousVolume.result.detail!!, previousVolume.result.detail!!.contains("volume"))
        assertTrue(previousVolume.result.detail!!, previousVolume.result.detail!!.contains("previous"))
    }

    @Test fun a_missing_per_action_argument_names_the_action_and_the_argument() {
        // `required` can only name `action`: one tool carries many actions, so the per-action
        // requirements are the parser's job. A refusal that says only "duration_ms is required"
        // leaves a mind holding eight actions and no idea which one asked for it.
        val cases = listOf<Pair<Map<String, Any?>, String>>(
            mapOf("action" to "timer") to "duration_ms",
            mapOf("action" to "alarm") to "at",
            mapOf("action" to "dnd") to "on",
            mapOf("action" to "volume") to "direction",
            mapOf("action" to "note") to "text",
            mapOf("action" to "clip") to "text",
            mapOf("action" to "file_read") to "name",
            mapOf("action" to "file_offer") to "name",
            mapOf("action" to "event_create") to "title",
            mapOf("action" to "event_create", "title" to "Standup") to "start",
            mapOf(
                "action" to "event_create",
                "title" to "Standup",
                "start" to "2026-11-06T09:00",
            ) to "duration_min",
            mapOf("action" to "notification_read") to "id",
            mapOf("action" to "notification_dismiss") to "id",
            mapOf("action" to "contact") to "name",
            mapOf("action" to "message") to "text",
            mapOf("action" to "message", "text" to "hello") to "to",
            mapOf("action" to "dial") to "to",
            mapOf("action" to "launch") to "app",
            mapOf("action" to "open") to "uri",
            mapOf("action" to "uninstall") to "package",
            mapOf("action" to "undo") to "id",
        )
        for ((args, missing) in cases) {
            val action = args["action"] as String
            val parsed = PhoneTool.parse(args, open)
            val refused = parsed as? PhoneTool.Parsed.Refuse
                ?: throw AssertionError("$action with no `$missing` parsed into a call")
            val detail = refused.result.detail.orEmpty()
            assertTrue("$action's refusal does not name the action: $detail", detail.contains(action))
            assertTrue("$action's refusal does not name `$missing`: $detail", detail.contains(missing))
        }
    }

    @Test fun every_direction_value_is_accepted_by_the_action_it_is_advertised_for() {
        val all = PhoneTool.DIRECTIONS.values.flatten().toSet()
        for ((action, values) in PhoneTool.DIRECTIONS) {
            for (value in values) {
                val parsed = PhoneTool.parse(mapOf("action" to action, "direction" to value), open)
                assertTrue(
                    "$action advertises `$value` and must accept it: ${(parsed as? PhoneTool.Parsed.Refuse)?.result?.detail}",
                    parsed is PhoneTool.Parsed.Run,
                )
            }
            // Every accepted value is advertised: a value that is not in the action's own set is
            // refused, and the refusal names both.
            for (other in all - values) {
                val refused = PhoneTool.parse(
                    mapOf("action" to action, "direction" to other),
                    open,
                ) as PhoneTool.Parsed.Refuse
                assertEquals(PhoneReason.INVALID, refused.result.reason)
                assertTrue(refused.result.detail!!, refused.result.detail!!.contains(action))
                assertTrue(refused.result.detail!!, refused.result.detail!!.contains(other))
            }
        }

        // `prev` was accepted but never advertised. Either word can go; both cannot stay.
        val prev = PhoneTool.parse(mapOf("action" to "skip", "direction" to "prev"), open)
            as PhoneTool.Parsed.Refuse
        assertEquals(PhoneReason.INVALID, prev.result.reason)
        assertTrue(prev.result.detail!!, prev.result.detail!!.contains("skip"))
        assertTrue(prev.result.detail!!, prev.result.detail!!.contains("prev"))
    }

    @Test fun the_state_action_is_offered_only_when_device_state_is_granted_and_takes_no_arguments() {
        val off = PhoneGrants.Source { g ->
            if (g.id == "device-state") PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
        }
        assertFalse("state must be absent while the group is off", "state" in enumOf(off))

        val on = PhoneGrants.Source { g ->
            if (g.id == "device-state") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(listOf("state", "undo", "status"), enumOf(on))
        assertTrue(PhoneTool.parse(mapOf("action" to "state"), on) is PhoneTool.Parsed.Run)
    }

    @Test fun the_clip_action_is_offered_only_when_granted_and_names_its_bound() {
        val off = PhoneGrants.Source { g ->
            if (g.id == "clipboard") PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
        }
        assertFalse("clip must be absent while the group is off", "clip" in enumOf(off))

        val on = PhoneGrants.Source { g ->
            if (g.id == "clipboard") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(listOf("clip", "clip_read", "undo", "status"), enumOf(on))
        val text = ((input(on)["properties"] as JObj)["text"] as JObj)["description"] as JStr
        assertTrue("the bound is in the schema", text.value.contains(PhoneTool.MAX_CLIP_CHARS.toString()))
        assertFalse(
            "the description does not name an action: clipboard may be Off while it is read, " +
                "and a description must not promise what the grants forbid",
            Regex("\\bclip\\b").containsMatchIn(text.value),
        )
        assertTrue(PhoneTool.parse(mapOf("action" to "clip", "text" to "hi"), on) is PhoneTool.Parsed.Run)
        assertTrue(
            "the read is offered by the same group and takes no text",
            PhoneTool.parse(mapOf("action" to "clip_read"), on) is PhoneTool.Parsed.Run,
        )
    }

    // ---- the handoff groups ----

    @Test
    fun messages_and_calls_are_separate_instant_groups_with_allow() {
        for ((id, action) in listOf("messages" to "message", "calls" to "dial")) {
            val group = PhoneGrants.groupOf(action)
            assertEquals("$action belongs to $id", id, group?.id)
            assertEquals("$id is instant", PhoneTiers.INSTANT, group?.tier)
            assertTrue(
                "$id offers Allow",
                PhoneGrants.State.ALLOW in PhoneGrants.statesFor(group!!.tier),
            )

            val off = PhoneGrants.Source { g ->
                if (g.id == id) PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
            }
            assertFalse("$action is absent while $id is off", action in enumOf(off))

            val on = PhoneGrants.Source { g ->
                if (g.id == id) PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
            }
            assertEquals(listOf(action, "undo", "status"), enumOf(on))
        }
    }

    @Test
    fun apps_offers_its_actions_and_uninstall_parses_into_the_handshake() {
        val apps = PhoneGrants.Source { g ->
            if (g.id == "apps") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(listOf("apps", "launch", "open", "uninstall", "undo", "status"), enumOf(apps))
        assertEquals(PhoneTiers.APPROVAL, PhoneTiers.of("uninstall"))
        // An Allow on Apps is a real permission: it reaches the engine, where Ask would card it.
        val parsed = PhoneTool.parse(mapOf("action" to "uninstall", "package" to "com.example.app"), apps)
        assertTrue(parsed is PhoneTool.Parsed.Run)
        assertEquals("uninstall", (parsed as PhoneTool.Parsed.Run).call.action)
    }

    @Test
    fun install_left_the_surface_and_is_unknown_not_a_governance_refusal() {
        val apps = PhoneGrants.Source { g ->
            if (g.id == "apps") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertFalse("install must not be advertised", "install" in enumOf(apps))
        assertNull("install belongs to no group", PhoneGrants.groupOf("install"))
        assertNull("install is not a phone action", PhoneTiers.of("install"))

        val parsed = PhoneTool.parse(mapOf("action" to "install", "app" to "com.example.app"), apps)
        assertTrue("a direct call must be an unknown-action refusal", parsed is PhoneTool.Parsed.Refuse)
        val result = (parsed as PhoneTool.Parsed.Refuse).result
        assertEquals(PhoneReason.UNKNOWN, result.reason)
        assertTrue(
            "the refusal says it is not a phone action: ${result.detail}",
            result.detail!!.contains("not a phone action"),
        )
    }

    @Test fun notifications_is_an_instant_group_with_allow_and_two_steps() {
        val group = PhoneGrants.groupOf("notifications")
        assertEquals("notifications", group?.id)
        assertEquals(PhoneTiers.INSTANT, group?.tier)
        assertTrue("Notifications offers Allow", PhoneGrants.State.ALLOW in PhoneGrants.statesFor(group!!.tier))

        val off = PhoneGrants.Source { g ->
            if (g.id == "notifications") PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
        }
        assertFalse("notifications is absent while the group is off", "notifications" in enumOf(off))
        assertFalse("notification_read is absent too", "notification_read" in enumOf(off))

        val on = PhoneGrants.Source { g ->
            if (g.id == "notifications") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(
            listOf("notifications", "notification_read", "notification_dismiss", "undo", "status"),
            enumOf(on),
        )
        assertTrue(PhoneTool.parse(mapOf("action" to "notifications"), on) is PhoneTool.Parsed.Run)
        assertTrue(
            PhoneTool.parse(mapOf("action" to "notification_read", "id" to "n1"), on)
                is PhoneTool.Parsed.Run,
        )
        assertEquals(
            PhoneReason.INVALID,
            (PhoneTool.parse(mapOf("action" to "notification_read"), on) as PhoneTool.Parsed.Refuse)
                .result.reason,
        )
    }

    @Test fun the_tool_description_carries_the_notification_privacy_rule() {
        val expected =
            "Notifications and their contents may include verification codes, private messages " +
                "and financial alerts. Do not read them aloud unless the owner asked you to, do " +
                "not repeat them back, and do not store them in a note."
        assertEquals(expected, PhoneTool.NOTIFICATION_PRIVACY)
        val description = (schemaObj()["description"] as JStr).value
        assertTrue("the rule is in the description, not only in the docs", description.contains(expected))
        // The source-level check: the constant is the description's own sentence, so a later edit
        // cannot quietly drop it while the docs still promise it.
        val source = java.io.File("src/main/java/com/hermesvox/PhoneTool.kt").readText()
        assertTrue(source.contains("NOTIFICATION_PRIVACY"))
        assertTrue(source.contains("Notifications and their contents may include verification codes"))
    }

    @Test fun contacts_is_an_instant_group_with_allow_and_a_bounded_name() {
        val group = PhoneGrants.groupOf("contact")
        assertEquals("contacts", group?.id)
        assertEquals(PhoneTiers.INSTANT, group?.tier)
        assertTrue("Contacts offers Allow", PhoneGrants.State.ALLOW in PhoneGrants.statesFor(group!!.tier))

        val off = PhoneGrants.Source { g ->
            if (g.id == "contacts") PhoneGrants.State.OFF else PhoneGrants.State.ALLOW
        }
        assertFalse("contact is absent while Contacts is off", "contact" in enumOf(off))

        val on = PhoneGrants.Source { g ->
            if (g.id == "contacts") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        assertEquals(listOf("contact", "undo", "status"), enumOf(on))
        val name = ((input(on)["properties"] as JObj)["name"] as JObj)["description"] as JStr
        assertTrue(
            "the name's two shapes are documented without naming an action that may be Off: " +
                name.value,
            name.value.contains("person") && name.value.contains("file"),
        )
        assertTrue("the bound is in the schema", name.value.contains(PhoneTool.MAX_CONTACT_NAME_CHARS.toString()))
        assertTrue(PhoneTool.parse(mapOf("action" to "contact", "name" to "Mo"), on) is PhoneTool.Parsed.Run)
        assertEquals(
            PhoneReason.INVALID,
            (PhoneTool.parse(mapOf("action" to "contact"), on) as PhoneTool.Parsed.Refuse).result.reason,
        )
    }

    @Test fun the_tool_is_marked_write_capable_so_untrusted_mode_can_gate_it() {
        val annotations = schemaObj()["annotations"] as JObj
        assertEquals(false, (annotations["readOnlyHint"] as JBool).value)
    }

    // ---- argument reading ----

    @Test fun integers_are_read_from_every_shape_a_json_reader_produces() {
        val fromLong = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000L), open)
        val fromInt = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000), open)
        val fromDouble = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000.0), open)
        val fromString = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to "60000"), open)
        for (p in listOf(fromLong, fromInt, fromDouble, fromString)) {
            assertTrue(p is PhoneTool.Parsed.Run)
            assertEquals(60_000L, (p as PhoneTool.Parsed.Run).call.let { (it as PhoneTool.Call.Timer).durationMs })
        }
    }

    @Test fun a_fractional_duration_is_not_a_duration() {
        assertTrue(PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 1_000.5), open) is PhoneTool.Parsed.Refuse)
    }

    @Test fun note_bounds_are_checked_once_the_group_is_past_the_gate() {
        assertTrue(PhoneTool.parseArguments("note", mapOf("text" to "  "), open) is PhoneTool.Parsed.Refuse)
        val tooLong = "x".repeat(PhoneTool.MAX_NOTE_CHARS + 1)
        assertTrue(PhoneTool.parseArguments("note", mapOf("text" to tooLong), open) is PhoneTool.Parsed.Refuse)
        assertTrue(PhoneTool.parseArguments("note", mapOf("text" to "milk"), open) is PhoneTool.Parsed.Run)
    }

    @Test fun the_alarm_clock_is_24_hour_and_strict() {
        assertEquals(7 to 30, PhoneTool.clock("07:30"))
        assertEquals(0 to 0, PhoneTool.clock("0:00"))
        assertEquals(23 to 59, PhoneTool.clock("23:59"))
        assertNull(PhoneTool.clock("24:00"))
        assertNull(PhoneTool.clock("7:5"))
        assertNull(PhoneTool.clock("7 am"))
        assertNull(PhoneTool.clock(null))
    }

    @Test fun the_tier_is_readable_before_anything_runs() {
        assertEquals(PhoneTiers.INSTANT, PhoneTiers.of("TIMER"))
        assertEquals(PhoneTiers.REPORTED, PhoneTiers.of(" note "))
        assertEquals(PhoneTiers.INSTANT, PhoneTiers.of("Message"))
        assertEquals(PhoneTiers.APPROVAL, PhoneTiers.of("Uninstall"))
        assertNull(PhoneTiers.of(""))
    }
}
