package com.hermesvox

import com.hermesvox.core.JBool
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import com.hermesvox.core.JsonException

/**
 * PhoneSetupRun — the one parser the setup turn needs, over a poll of the run's own event stream.
 *
 * **What it reads, and why those keys are safe to name.** The setup turn rides the ordinary
 * streamed-turn transport (`HermesSession.startSetupStream` → `POST /v1/runs` then
 * `GET /v1/runs/{run_id}/events`), and the reader in `core/Stream.kt` has already translated the
 * runs wire into the app's own event vocabulary before `pollStreamJSON` shapes it
 * (`StreamEvent.toMap`). So the keys this parser names are the app's own contract — pinned by
 * `testdata/stream/runs-*.golden.json` and `RouteParityTest` — not guesses about the gateway:
 *
 *   - `done` — the turn is over (`Streams.poll`);
 *   - `error` — the gateway's or the transport's own reason, on a done poll only;
 *   - `text` — the answer so far, with the `[tool:…]` markers already removed;
 *   - `events[].type`, `events[].item_type`, `events[].item_id`, `events[].name`.
 *
 * The runs wire behind that translation (documented from the gateway's run-event code in
 * `docs/RUNS-ROUTE.md` and decoded in `core/Stream.kt`'s `Envelope`) calls the tool name `tool`
 * and the tool's preview `preview`. This parser deliberately never touches either: the tool's
 * arguments are where the phone's token could reappear, and a line is built from the name only
 * ([PhoneSetupActivity]).
 *
 * **It counts events, not just tools.** A poll that carried any event moves the silence line's
 * age, even when the event was an answer delta or a progress note. That is what "nothing new for
 * four minutes" means: the last thing the stream said, whatever it was.
 *
 * Pure JVM; [PhoneBootstrapTest] and [PhoneSetupActivityTest] feed it payloads without a network.
 */
internal object PhoneSetupRun {

    /** One event's tool call, when it opened one. The fields are the app's, never the wire's. */
    data class Tool(val name: String, val callId: String)

    /**
     * One drained poll. [events] is how many events the poll carried — the silence line's input;
     * [tools] only the `function_call` openings, in arrival order.
     */
    data class Poll(
        val done: Boolean = false,
        val error: String = "",
        val reply: String = "",
        val tools: List<Tool> = emptyList(),
        val events: Int = 0,
    )

    /**
     * Parse one `pollStreamJSON` payload. A malformed payload is not a crash and not an invented
     * event: it is an empty poll, and the loop's next wait will ask again.
     */
    fun parse(json: String): Poll {
        val o = try {
            Json.parse(json) as? JObj
        } catch (_: JsonException) {
            null
        } ?: return Poll()
        val nodes = try {
            Json.arr(o, "events")
        } catch (_: JsonException) {
            emptyList()
        }
        val tools = ArrayList<Tool>()
        for (node in nodes) {
            val e = try {
                Json.elem(node)
            } catch (_: JsonException) {
                continue
            } ?: continue
            if (str(e, "item_type") != "function_call") continue
            // The responses route emits both `output_item.added` and `output_item.done` for one
            // call; a line is the call's opening, so the closing item is not a second action.
            if (str(e, "type") != "response.output_item.added") continue
            val name = str(e, "name")
            if (name.isBlank()) continue
            tools.add(Tool(name = name, callId = str(e, "item_id")))
        }
        return Poll(
            done = (o["done"] as? JBool)?.value ?: false,
            error = str(o, "error"),
            reply = str(o, "text"),
            tools = tools,
            events = nodes.size,
        )
    }

    /** A string field, or "" when absent or another type (a poll must never fail on a shape). */
    private fun str(o: JObj?, key: String): String = (o?.get(key) as? JStr)?.value ?: ""
}
