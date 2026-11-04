package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A2 / F4 (2026-11-04 Presence review): the two per-request extras a voice turn sends and a
 * typed turn does not — the ephemeral `instructions` and `model_options.reasoning`.
 *
 * Read against the gateway's own decode
 * (`gateway/platforms/api_server.py:_runtime_options_from_model_options` and
 * `api_server_request_options.py:_request_reasoning_config`): `{"enabled":false}` turns
 * reasoning off, `{"enabled":true,"effort":<one of none|minimal|low|medium|high|xhigh|max|ultra>}`
 * asks for an effort. Anything else is ignored by the gateway, which is why the client never
 * guesses a token it cannot name.
 */
class RequestOptionsTest {

    @Test fun blank_or_unknown_effort_falls_back_to_the_default() {
        assertEquals("low", ResponsesClient.DEFAULT_EFFORT)
        for (setting in listOf(null, "", "  ", "ultrathink", "banana")) {
            val o = ResponsesClient.reasoningOption(setting)
            assertEquals(true, o["enabled"])
            assertEquals("default for '$setting'", ResponsesClient.DEFAULT_EFFORT, o["effort"])
        }
    }

    @Test fun the_users_choice_passes_through() {
        for (effort in listOf("minimal", "low", "medium", "high", "max")) {
            val o = ResponsesClient.reasoningOption(effort)
            assertEquals(true, o["enabled"])
            assertEquals(effort, o["effort"])
        }
        assertEquals("medium", ResponsesClient.reasoningOption("  Medium ")["effort"])
    }

    @Test fun off_turns_reasoning_off_rather_than_asking_for_none() {
        // `{"enabled":false}` is the gateway's own shape for "do not reason"; the string
        // "none" is accepted too, and both must not leak an `effort` key.
        for (setting in listOf("off", "none", "OFF")) {
            val o = ResponsesClient.reasoningOption(setting)
            assertEquals(false, o["enabled"])
            assertNull("no effort with reasoning disabled", o["effort"])
        }
    }

    @Test fun a_default_request_options_carries_nothing() {
        val o = RequestOptions()
        assertEquals("", o.instructions)
        assertNull(o.reasoning)
    }

    @Test fun the_body_sends_instructions_even_when_empty_but_only_sends_reasoning_when_asked() {
        val client = ResponsesClient("http://127.0.0.1:1", "k", "m")
        val plain = Json.parse(String(client.buildBody("hi", "", true), Charsets.UTF_8)) as JObj
        assertEquals("the field is always present, so a chained turn cannot inherit one",
            "", Json.str(plain, "instructions"))
        assertNull("no override unless the caller asks", Json.obj(plain, "model_options"))

        val voice = Json.parse(String(
            client.buildBody("hi", "", true, "You are on a call.", ResponsesClient.reasoningOption("low")),
            Charsets.UTF_8)) as JObj
        assertEquals("You are on a call.", Json.str(voice, "instructions"))
        assertEquals("hi", Json.str(voice, "input"))
        val reasoning = Json.obj(Json.obj(voice, "model_options"), "reasoning")
        assertEquals(true, (reasoning?.get("enabled") as JBool).value)
        assertEquals("low", Json.str(reasoning, "effort"))
    }

    @Test fun the_runs_body_carries_the_same_two_fields() {
        val client = ResponsesClient("http://127.0.0.1:1", "k", "m")
        client.setConversationId("vox_test")
        val voice = Json.parse(String(
            client.buildRunBody("hi", "You are on a call.", ResponsesClient.reasoningOption("high")),
            Charsets.UTF_8)) as JObj
        assertEquals("You are on a call.", Json.str(voice, "instructions"))
        assertEquals("vox_test", Json.str(voice, "session_id"))
        assertEquals("high", Json.str(Json.obj(Json.obj(voice, "model_options")!!, "reasoning"), "effort"))
    }
}
