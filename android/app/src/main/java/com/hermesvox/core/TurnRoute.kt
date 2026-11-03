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
 * THE route switch. Both vocabularies render the same turn ([RouteParityTest]); the
 * device pass in `docs/RUNS-ROUTE.md` is what confirms the gateway build.
 *
 * [active] ships as [TurnRoute.RUNS] (#404): the route that carries approvals to the
 * phone, whose barge-in stops the run itself (`POST /v1/runs/{id}/stop`) rather than
 * only closing a subscriber. Tests flip it and restore it.
 *
 * A turn rides [active] only when it can keep its conversation — see [routeFor].
 */
internal object StreamRoute {

    @Volatile
    var active: TurnRoute = TurnRoute.RUNS

    /**
     * The route a turn actually takes. A run has no response id, so its only chain is
     * the declared `X-Hermes-Session-Key`: with no key the gateway keys the run's
     * session by its own `run_id` (`api_server_runs.py`, `session_id = … or run_id`),
     * and every turn would start a fresh conversation. Blank is the default scope, so
     * an undeclared install stays on [TurnRoute.RESPONSES], which chains by
     * `previous_response_id` — losing the approval card is a gap; losing the
     * conversation would be a regression.
     */
    fun routeFor(hasSessionKey: Boolean): TurnRoute =
        if (active == TurnRoute.RUNS && !hasSessionKey) TurnRoute.RESPONSES else active

    /**
     * The chain head a turn rides. `/v1/runs` has no response id: a run is chained by
     * its session (`X-Hermes-Session-Key`, required for the route by [routeFor]), never
     * by `previous_response_id`. Sending the Responses chain head on this route would pin
     * every run to the same response-store snapshot, so the runs body omits it — and so
     * does a stored turn (`/compress`) on a session that runs, so it compacts the
     * conversation the runs wrote rather than a stale snapshot.
     */
    fun chainHead(route: TurnRoute, lastId: String): String =
        if (route == TurnRoute.RESPONSES) lastId else ""
}
