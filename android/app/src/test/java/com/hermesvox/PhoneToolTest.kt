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
 * #421 step 3: the tool schema is the contract with the mind, so it is pinned here.
 *
 * The shape is the point: **one** tool with an action enum, because the schema rides on every turn
 * the gateway offers it on and eight tools would cost more and be easier to misuse. Tier 2 names are
 * absent from the enum on purpose — a model cannot call what it cannot see — while the dispatcher
 * still knows them, so a direct call gets an honest "needs the owner" instead of "unknown".
 */
class PhoneToolTest {

    private fun schemaObj(): JObj = Json.parse(Json.encode(PhoneTool.schema())) as JObj

    private fun input(): JObj = schemaObj()["inputSchema"] as JObj

    @Test fun it_is_one_tool_called_phone() {
        val s = schemaObj()
        assertEquals("phone", (s["name"] as JStr).value)
        val desc = (s["description"] as JStr).value
        assertTrue("the description must tell the mind to check the result", desc.contains("check ok"))
        assertTrue("and to report what happened", desc.contains("what actually happened"))
    }

    @Test fun the_action_enum_is_exactly_the_offered_actions() {
        val action = (input()["properties"] as JObj)["action"] as JObj
        val enum = (action["enum"] as JArr).items.map { (it as JStr).value }
        assertEquals(PhoneTool.OFFERED, enum)
        assertEquals(listOf("timer", "alarm", "dnd", "volume", "note", "undo", "status"), enum)
    }

    @Test fun every_offered_action_is_one_the_phone_can_actually_perform() {
        val action = (input()["properties"] as JObj)["action"] as JObj
        for (node in (action["enum"] as JArr).items) {
            val name = (node as JStr).value
            assertTrue("$name is offered but not executable", PhoneTiers.executable(name))
        }
    }

    @Test fun the_tier_two_capabilities_are_not_offered() {
        val action = (input()["properties"] as JObj)["action"] as JObj
        val offered = (action["enum"] as JArr).items.map { (it as JStr).value }.toSet()
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
        val fromLong = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000L))
        val fromInt = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000))
        val fromDouble = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 60_000.0))
        val fromString = PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to "60000"))
        for (p in listOf(fromLong, fromInt, fromDouble, fromString)) {
            assertTrue(p is PhoneTool.Parsed.Run)
            assertEquals(60_000L, (p as PhoneTool.Parsed.Run).call.let { (it as PhoneTool.Call.Timer).durationMs })
        }
    }

    @Test fun a_fractional_duration_is_not_a_duration() {
        assertTrue(PhoneTool.parse(mapOf("action" to "timer", "duration_ms" to 1_000.5)) is PhoneTool.Parsed.Refuse)
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
