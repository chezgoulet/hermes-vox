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

    /**
     * The profile this session is talking to. The `/v1/runs` fallback is remembered per
     * profile (a 404 is a fact about one host), so a switch re-evaluates it rather than
     * inheriting another profile's answer.
     */
    fun setProfile(name: String) = StreamRoute.useProfile(name)

    /** Verifies the entity connection (GET /v1/models, bearer auth) and returns the body
     *  the host answered with — the host names itself there. Throws on a wrong
     *  URL/key/host. Onboarding uses this — never fake success. */
    fun ping(): String = responses.ping()

    /** The raw /api/model/options catalog JSON — the REAL gateway catalog the /models
     *  chooser reads (the on-device ModelCatalog is separate). */
    fun modelOptions(): String = gateway.modelOptions()

    /** The raw /v1/health JSON (status/version) — the /health card. */
    fun gatewayHealth(): String = gateway.health()

    /**
     * True when the gateway answered `/v1/health` within [timeoutMs]. Used by the phone setup's
     * restart wait: a false answer is the expected drop, not an error.
     */
    fun gatewayAnswers(timeoutMs: Int = GatewayClient.PROBE_TIMEOUT_MS): Boolean =
        gateway.answers(timeoutMs)

    /**
     * Triggers one cron job by id (`POST /api/jobs/{id}/run`). The job belongs to the agent; this
     * call is the owner's press, not a host edit. Blocks — call it off the main thread.
     */
    fun runJob(jobId: String): String = gateway.runJob(jobId)

    /** The server-side chain head ("" when there is none). Read-only: the app
     *  never deletes a stored response, and a reset only drops this local pointer. */
    fun lastResponseId(): String = lastID

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
     * Begins a streamed VOICE turn: the caller's words, byte-exact, plus the per-request
     * ephemeral [instructions] and the voice turn's reasoning effort ([reasoning]).
     *
     * A2 (2026-11-04 Presence review, F4): the voice register used to be a prefix glued onto
     * the user's message, with an anti-tool clause in it. That polluted the agent's own
     * transcript and memory with protocol text and told the mind not to look things up, which
     * is why a price question got a guess. Both now ride `instructions`, which the gateway
     * appends to the system prompt for this request only
     * (`ephemeral_system_prompt`) and never persists.
     */
    fun voiceTurn(input: String, instructions: String, reasoning: Map<String, Any?>?): String =
        Streams.start(responses, input, lastID, RequestOptions(instructions, reasoning))

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

    /**
     * Starts a NEW conversation: drops the chain head and rotates the runs transcript
     * id (H2), so the next run is filed under a session with no history. The
     * gateway-side history remains until its own retention clears it. */
    fun resetConversation() {
        lastID = ""
        responses.setConversationId(newConversationId())
    }

    /**
     * #437: talks in a conversation the host already has. [id] is the host's session id
     * (`GET /api/sessions`), and it becomes the body `session_id` of every following turn —
     * streamed and stored — which `api_server_runs.py` ranks above the response chain and
     * above the declared `X-Hermes-Session-Key`. Adopting a session is setting this field and
     * nothing else: the gateway treats it as the same conversation, so nothing is copied and
     * nothing is forked.
     *
     * The chain head is dropped: a `previous_response_id` belongs to the conversation being
     * left, and chaining off it would replay that conversation's snapshot instead of the one
     * just adopted. The declared scope is left alone — it is long-term memory, not the
     * transcript.
     */
    fun adoptConversation(id: String) {
        lastID = ""
        responses.setConversationId(id.trim())
    }

    private companion object {
        /** Visible ASCII and path-safe (the gateway files sessions by id). */
        fun newConversationId(): String = "vox_" + java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
