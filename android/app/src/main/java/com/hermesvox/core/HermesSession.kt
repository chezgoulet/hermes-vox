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

    /** Verifies the entity connection (GET /v1/models, bearer auth). Throws on a wrong
     *  URL/key/host. Onboarding uses this — never fake success. */
    fun ping() = responses.ping()

    /** The raw /api/model/options catalog JSON — the REAL gateway catalog the /models
     *  chooser reads (the on-device ModelCatalog is separate). */
    fun modelOptions(): String = gateway.modelOptions()

    /** The raw /v1/health JSON (status/version) — the /health card. */
    fun gatewayHealth(): String = gateway.health()

    /** Drops the stored server-side response (the /new reset): DELETE
     *  /v1/responses/{id} for the current chain head. No head, nothing to do. */
    fun deleteLastResponse() {
        val id = lastID
        if (id.isEmpty()) return
        gateway.deleteResponse(id)
    }

    /**
     * Sends a text turn on the non-streaming /v1/responses path, chained off the
     * session's head (H2), and returns the reply. The reply's id becomes the new head.
     */
    fun turnStored(text: String): String {
        val res = responses.response(text, lastID)
        lastID = res.responseId
        return res.reply
    }

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
     * Begins a streamed turn (/v1/responses stream:true, chained off the head) and
     * returns a stream id. Drain with [pollStreamJSON]; end with [cancelStream]
     * (barge-in) or let it complete (the done poll carries the reply + response_id).
     */
    fun startStream(text: String): String = Streams.start(responses, text, lastID)

    /**
     * The events buffered since the last poll, as one JSON payload:
     * {"done","error"(done only),"events","response_id","text","transcript"}. Any
     * response_id it carries becomes the chain head. The done poll retires the
     * stream; polling a retired id throws.
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

    /** Aborts an in-flight streamed turn (barge-in): closes the connection, which
     *  aborts the gateway's generation, and retires the stream. */
    fun cancelStream(streamID: String) = Streams.cancel(streamID)

    /** Starts a NEW conversation: drops the chain head. The gateway-side history
     *  remains until its own retention clears it. */
    fun resetConversation() {
        lastID = ""
    }
}
