package com.hermesvox.core

/**
 * The app's handle to the entity: the SAME Hermes agent every other frontend talks
 * to. It carries no reasoning, memory or persona of its own — only the transport
 * (/v1/responses, the gateway endpoints) and the one piece of client state the
 * conversation needs, the server-side chain head.
 *
 * Every method blocks; call them off the main thread. Failures throw (a
 * [HermesException] for an answer the gateway gave, an IOException for transport).
 *
 * @param baseURL the gateway API server (e.g. https://gateway.example.ts.net)
 * @param apiKey the Hermes API_SERVER_KEY (user-entered, never committed)
 * @param model the model route (usually "hermes-agent" → the real profile agent)
 */
class HermesSession(baseURL: String, apiKey: String, model: String) {
    private val responses = ResponsesClient(baseURL, apiKey, model)
    private val gateway = GatewayClient(baseURL, apiKey)

    /**
     * The server-side chain head: the latest response id, sent as
     * previous_response_id on the next turn.
     *
     * H2 (0.5.3): ONE chain for every kind of turn. The streamed voice turns and the
     * non-streaming turnStored (/compress) read and write this same id. When they
     * rode separate ids, /compress compacted an empty chain and then overwrote the
     * live head with its fresh id, orphaning the conversation it meant to preserve.
     */
    @Volatile
    internal var lastID: String = ""

    init {
        responses.setConversationId(newConversationId())
    }

    /**
     * H2 (0.9.1 review): the transcript a runs-route turn is filed under — sent as the
     * `/v1/runs` body `session_id` on every streamed and stored turn, and rotated by
     * [resetConversation]. Client-owned and in memory, exactly like [lastID]: a new
     * call, `/new`, a profile switch and a fresh session object all start a new one.
     */
    fun conversationId(): String = responses.conversationId()

    /** The model route for every request (/models chooser). */
    fun setModel(model: String) = responses.setModel(model)

    /** The per-request provider override ("" = gateway default); the app writes it
     *  alongside the model from the catalog. */
    fun setProvider(provider: String) = responses.setProvider(provider)

    /**
     * Declares WHO this install is to the gateway: stamps X-Hermes-Session-Key on every
     * entity request, from which the API server derives a stable long-term-memory
     * scope per channel. "" leaves the gateway's per-transcript default in place.
     */
    fun setSessionKey(scope: String) = responses.setSessionKey(scope)

    /** Verifies the entity connection (GET /v1/models, bearer auth) and returns the body
     *  the host answered with — the host names itself there. Throws on a wrong
     *  URL/key/host. Onboarding uses this — never fake success. */
    fun ping(): String = responses.ping()

    /** The raw /api/model/options catalog JSON — the REAL gateway catalog the /models
     *  chooser reads (the on-device ModelCatalog is separate). */
    fun modelOptions(): String = gateway.modelOptions()

    /** The raw /v1/health JSON (status/version) — the /health card. */
    fun gatewayHealth(): String = gateway.health()

    /** The server-side chain head ("" when there is none).
     *
     *  #351: a caller that deletes the copy AFTER a reset must capture it first —
     *  [resetConversation] drops it, and reading the head later would delete the
     *  NEXT turn's response instead of the one it meant. */
    fun lastResponseId(): String = lastID

    /** Drops a stored server-side response by id (the /new reset): DELETE
     *  /v1/responses/{id}. No id, nothing to do. */
    fun deleteResponse(id: String) {
        if (id.isEmpty()) return
        gateway.deleteResponse(id)
    }

    /** Drops the current chain head. Prefer [deleteResponse] with an id captured
     *  before a reset — see [lastResponseId]. */
    fun deleteLastResponse() = deleteResponse(lastID)

    /**
     * Sends a text turn on the non-streaming /v1/responses path, chained off the
     * session's head (H2), and returns the reply. The reply's id becomes the new head.
     */
    fun turnStored(text: String): String {
        // #404: on a session whose turns ride /v1/runs the conversation is the declared
        // session key, not a response chain — chaining off a stored id would compact a
        // stale snapshot instead of what the runs wrote. The stored turn follows the
        // streamed turns' route so H2's "one chain" still holds.
        val route = route()
        // H2 (0.9.1 review): on the runs route the conversation is the body session_id,
        // which `/v1/responses` cannot address — so the stored turn rides `/v1/runs` too
        // and blocks for the run's answer, landing in the transcript the voice turns use.
        if (route == TurnRoute.RUNS) {
            return Streams.complete(responses, text, ResponsesClient.RESPONSE_TIMEOUT_MS.toLong())
        }
        val res = responses.response(text, StreamRoute.chainHead(route, lastID))
        lastID = res.responseId
        return res.reply
    }

    /** The route this session's turns take now ([StreamRoute.routeFor]). */
    internal fun route(): TurnRoute = StreamRoute.routeFor(responses.hasSessionKey())

    // ---- Streamed turns: the SSE events as they arrive ----

    /**
     * Begins a streamed VOICE turn: [startStream] with the phone-call voice prefix
     * ([ResponsesClient.USER_TURN_PREFIX]) in front of the user text. ER Phase 1, "the
     * agent knows it's on a call": the prefix is a per-turn user-message prefix (as
     * the Hermes CLI's voice mode does), never a persisted instruction, so the prompt
     * cache stays intact and it works on any stock gateway.
     */
    fun voiceTurn(text: String): String = Streams.start(responses, ResponsesClient.USER_TURN_PREFIX + text, lastID)

    /**
     * Begins a streamed turn (the route in [StreamRoute], chained off the head) and
     * returns a stream id. On the responses route that is `/v1/responses stream:true`;
     * on the runs route it is `POST /v1/runs` + `GET /v1/runs/{id}/events`, chained by
     * the session key because a run has no response id. Drain with [pollStreamJSON];
     * end with [cancelStream] (barge-in) or let it complete (the done poll carries the
     * reply + response_id).
     */
    fun startStream(text: String): String = Streams.start(responses, text, lastID)

    /**
     * The events buffered since the last poll, as one JSON payload:
     * {"done","error"(done only),"events","response_id","text","transcript"}. Any
     * response_id it carries becomes the chain head. The done poll retires the
     * stream; polling a retired id throws.
     *
     * A runs-route turn carries no response_id, so it leaves the head alone and the
     * conversation continues on `X-Hermes-Session-Key` — see [StreamRoute.chainHead].
     */
    fun pollStreamJSON(streamID: String): String {
        val r = Streams.poll(streamID)
        if (r.responseId.isNotEmpty()) lastID = r.responseId
        return r.json
    }

    /**
     * Blocks until the stream has new data, the turn completes, or [deadlineMs]
     * passes. True = poll now; false = idle timeout. The push-side wake (#39) that
     * replaced a fixed 240ms poll tick.
     */
    fun waitStream(streamID: String, deadlineMs: Long): Boolean = Streams.wait(streamID, deadlineMs)

    /** Aborts an in-flight streamed turn (barge-in) and retires the stream. On the
     *  responses route closing the connection aborts the generation; on the runs route
     *  the run is stopped with `POST /v1/runs/{id}/stop`. Never throws for the stop. */
    fun cancelStream(streamID: String) = Streams.cancel(streamID)

    /** The gateway run id behind a runs-route stream ("" on the responses route or
     *  once the stream has retired). The turn lifecycle needs it to stop a run it
     *  can no longer follow. */
    fun runIdOf(streamID: String): String = Streams.runIdOf(streamID)

    /** Stops a runs-route run at the gateway: `POST /v1/runs/{id}/stop`. */
    fun stopRun(runId: String) {
        if (runId.isEmpty()) return
        gateway.stopRun(runId)
    }

    // ---- Approvals (#282): the answer to an `approval.request` run event ----

    /**
     * Answers one pending approval: `POST /v1/runs/{run_id}/approval`.
     * [choice] must be one the event offered (the controller renders those and nothing
     * else); [requestId] is omitted from the body when the gateway did not send one.
     * [executionGeneration] is echoed only when the event carried one (session-authority
     * gateways fence the answer on it). Blocks — call it off the main thread.
     */
    fun answerApproval(runId: String, choice: String, requestId: String, executionGeneration: Int? = null) {
        gateway.answerApproval(runId, choice, requestId, executionGeneration)
    }

    // ---- #376: the debug-only approval arm ----

    /**
     * Places a run whose only purpose is to raise an approval ([ApprovalArm]) and returns
     * its id. The approval is then read from the run's status, not from this call — the
     * model has to reach the flagged command first.
     *
     * Blocks. Reachable only from the FLAG_DEBUGGABLE intent harness.
     */
    internal fun startArmRun(input: String): String = gateway.startRun(input)

    /** The raw `GET /v1/runs/{id}` body the arm polls. Blocks. */
    internal fun armRunStatus(runId: String): String = gateway.runStatus(runId)

    /** Starts a NEW conversation: drops the chain head and rotates the runs transcript
     *  id (H2), so the next run is filed under a session with no history. The
     *  gateway-side history remains until its own retention clears it. */
    fun resetConversation() {
        lastID = ""
        responses.setConversationId(newConversationId())
    }

    private companion object {
        /** Visible ASCII and path-safe (the gateway files sessions by id). */
        fun newConversationId(): String = "vox_" + java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
