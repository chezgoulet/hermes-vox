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

    /** Starts a NEW conversation: drops the chain head. The gateway-side history
     *  remains until its own retention clears it. */
    fun resetConversation() {
        lastID = ""
    }
}
