package com.hermesvox.core

/**
 * Which transport a streamed turn rides.
 *
 * Both readers end in the same [StreamState] and emit the same event shapes, so every
 * consumer above [HermesSession] is route-blind. The routes are not interchangeable at
 * the wire, though:
 *
 *  - [RESPONSES] — `POST /v1/responses` (stream:true). One request, an OpenAI Responses
 *    SSE body, chained with `previous_response_id`. On gateway 0.22.6 this route never
 *    registers an approval notifier, so a flagged command resolves to the unattended
 *    policy with no event reaching the phone (`docs/NOTES-api-events.md`).
 *  - [RUNS] — `POST /v1/runs` (202 `{run_id}`) then `GET /v1/runs/{run_id}/events`
 *    (`docs/NOTES-api-events.md`, `docs/APPROVALS-ACCEPTANCE.md`). The same agent call,
 *    but the writer registers the approval notifier and emits the control events, keeps
 *    a sequence-numbered event backlog for reattach, and answers
 *    `POST /v1/runs/{run_id}/stop` for a barge-in.
 */
internal enum class TurnRoute { RESPONSES, RUNS }

/**
 * THE route switch. It exists so the flip to the approval-carrying route is one line
 * after [RouteParityTest] proves both vocabularies render the same turn and a device
 * pass confirms the gateway build — no consumer knows about it.
 *
 * [active] ships as [TurnRoute.RESPONSES]: this PR adds the second reader and the
 * proof, it changes no shipped behaviour. Tests flip it and restore it.
 */
internal object StreamRoute {

    @Volatile
    var active: TurnRoute = TurnRoute.RESPONSES

    /**
     * The chain head a turn rides. `/v1/runs` has no response id: a run is chained by
     * its session (`X-Hermes-Session-Key`, which Vox always stamps), never by
     * `previous_response_id`. Sending the Responses chain head on this route would pin
     * every run to the same response-store snapshot, so the runs body omits it.
     */
    fun chainHead(route: TurnRoute, lastId: String): String =
        if (route == TurnRoute.RESPONSES) lastId else ""
}
