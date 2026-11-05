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

    /** Every Tier 0 group allowed; Tier 1/2 left at Ask, which is the most they permit. */
    private val open = PhoneGrants.Source { g ->
        if (g.tier == PhoneTiers.INSTANT) PhoneGrants.State.ALLOW else PhoneGrants.State.ASK
    }

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
            listOf("timer", "alarm", "dnd", "volume", "note", "undo", "status"),
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

    @Test fun a_group_at_ask_is_offered_and_every_use_is_a_refusal() {
        val notes = PhoneGrants.Source { g ->
            if (g.id == "notes") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        }
        assertTrue("note is advertised", "note" in enumOf(notes))
        val parsed = PhoneTool.parse(mapOf("action" to "note", "text" to "milk"), notes)
        assertTrue(parsed is PhoneTool.Parsed.Refuse)
        assertEquals(PhoneReason.APPROVAL, (parsed as PhoneTool.Parsed.Refuse).result.reason)
        assertNull("nothing happened", parsed.result.id)
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
        for (consequential in listOf("share", "message", "call", "email", "delete")) {
            assertFalse("$consequential must not be advertised", consequential in offered)
        }
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
        val direction = ((props["direction"] as JObj)["enum"] as JArr).items.map { (it as JStr).value }
        assertEquals(listOf("up", "down"), direction)
        for (key in listOf("at", "on", "text", "id")) assertNotNull("$key must be documented", props[key])
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
        assertEquals(PhoneTiers.APPROVAL, PhoneTiers.of("Message"))
        assertNull(PhoneTiers.of(""))
    }
}
