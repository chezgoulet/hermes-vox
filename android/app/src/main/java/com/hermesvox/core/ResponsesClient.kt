package com.hermesvox.core

/** The outcome of a non-streaming /v1/responses turn. */
internal data class ResponseResult(val reply: String, val responseId: String)

/**
 * Talks to the Hermes /v1/responses endpoint — the server-side-conversation path.
 * The entity keeps its full history (incl. tool calls) on the server via
 * previous_response_id, so the app doesn't manage context. A provider+model can
 * ride each request (the blessed override): the gateway switches the entity's
 * inference backend WITHOUT touching the entity (memory/skills/context preserved).
 *
 * model/provider/sessionKey are re-declared from Settings while a turn may be in
 * flight on another thread, so they are read and written under one lock.
 */
internal class ResponsesClient(val baseURL: String, val apiKey: String, model: String) {
    private val lock = Any()
    private var model = model
    private var provider = ""
    private var sessionKey = ""
    private var conversationId = ""

    fun setModel(model: String) = synchronized(lock) { this.model = model }

    /** The per-request provider override ("" = gateway default). */
    fun setProvider(provider: String) = synchronized(lock) { this.provider = provider }

    /** The X-Hermes-Session-Key scope ("" = the gateway's per-transcript default). */
    fun setSessionKey(scope: String) = synchronized(lock) { this.sessionKey = scope }

    /**
     * H2 (0.9.1 review): the client-owned transcript id a run is filed under — the body
     * `session_id` on `/v1/runs`, which the gateway ranks above `X-Hermes-Session-Key`
     * (`api_server_runs.py`: body session_id > response chain > declared key > run_id).
     * The key stays the long-term-memory scope; this id is the conversation, and
     * [HermesSession.resetConversation] rotates it. "" sends none.
     */
    fun setConversationId(id: String) = synchronized(lock) { this.conversationId = id }

    fun conversationId(): String = synchronized(lock) { conversationId }

    fun entityHeaders(): Map<String, String> = Entity.headers(apiKey, synchronized(lock) { sessionKey })

    /** Whether this install declares a conversation scope — the only chain a run has
     *  (see [StreamRoute.routeFor]). */
    fun hasSessionKey(): Boolean = synchronized(lock) { sessionKey.isNotBlank() }

    /** The request body; provider and previous_response_id only when set. */
    fun buildBody(input: String, previousResponseId: String, stream: Boolean): ByteArray {
        val body = synchronized(lock) {
            linkedMapOf<String, Any?>("model" to model, "input" to input, "stream" to stream).apply {
                if (provider.isNotEmpty()) put("provider", provider)
                if (previousResponseId.isNotEmpty()) put("previous_response_id", previousResponseId)
            }
        }
        return Json.encode(body).toByteArray(Charsets.UTF_8)
    }

    /**
     * The `POST /v1/runs` admission body. Same identity fields as [buildBody] — model,
     * input and the per-request provider override — but no `stream` (the route is
     * asynchronous: 202 `{run_id}`, then `GET /v1/runs/{run_id}/events`) and no
     * `previous_response_id` (see [StreamRoute.chainHead]: a response-store snapshot
     * would freeze the chain). The conversation is the client-owned `session_id`
     * ([setConversationId], H2): without it the gateway files the run under the latest
     * session the key declared, and "new conversation" would keep the old history.
     */
    fun buildRunBody(input: String): ByteArray {
        val body = synchronized(lock) {
            linkedMapOf<String, Any?>("model" to model, "input" to input).apply {
                if (provider.isNotEmpty()) put("provider", provider)
                if (conversationId.isNotEmpty()) put("session_id", conversationId)
            }
        }
        return Json.encode(body).toByteArray(Charsets.UTF_8)
    }

    /**
     * Sends one turn and returns the reply + the new response id. Pass the prior id
     * as [previousResponseId] to keep the server-side conversation across turns.
     */
    fun response(input: String, previousResponseId: String): ResponseResult {
        val c = Entity.open("POST", Entity.url(baseURL, "/v1/responses"),
            entityHeaders() + ("Content-Type" to "application/json"), RESPONSE_TIMEOUT_MS, RESPONSE_TIMEOUT_MS)
        return Entity.exchange(c, RESPONSE_TIMEOUT_MS.toLong(), "hermes responses") {
            Entity.send(c, buildBody(input, previousResponseId, false))
            if (c.responseCode != 200) {
                throw HermesException("hermes responses ${Entity.status(c)}: ${Entity.readErrorBody(c)}")
            }
            val raw = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseResponse(raw)
        }
    }

    /**
     * Verifies the entity connection: GET /v1/models with the bearer must answer
     * 200, and returns that answer. Onboarding's "Connect & verify" — never fakes
     * success. The body is handed back because the host names itself there
     * (`data[0].id`); a ping that threw it away made the user type the name.
     */
    fun ping(): String {
        val c = Entity.open("GET", Entity.url(baseURL, "/v1/models"), entityHeaders(), PING_TIMEOUT_MS, PING_TIMEOUT_MS)
        return Entity.exchange(c, PING_TIMEOUT_MS.toLong(), "hermes ping") {
            val code = c.responseCode
            val raw = Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code != 200) throw HermesException("hermes ${Entity.status(c)}")
            raw
        }
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 120_000
        const val PING_TIMEOUT_MS = 10_000

        /**
         * The ER Phase 1 voice-mode signal: a live-call-local instruction prepended to
         * each VOICE turn's user text (client-side, in voiceTurn). It is ordinary user
         * text, so the per-conversation prompt cache stays intact and it reaches the
         * model on any stock gateway. Wording is locked by
         * docs/BUILD-ER-enhanced-realtime.md Phase 1 and the 0.6.4 presence-cadence
         * tuning (the app speaks its own "let me think"; the model must not re-state
         * it, and it greets back before diving in). ErVoicePrefixTest pins it.
         */
        const val USER_TURN_PREFIX = "[Voice input — the app adds its own spoken acknowledgments like \"let me think\" while you work, so do NOT re-state that you are thinking or working. Greet the caller naturally first if they greet you. Then respond conversationally, 2-4 sentences. " +
            "Speak plainly, no code or markdown. Answer from what you know; do NOT do " +
            "exhaustive tool work or re-audit your own docs/state unless the caller " +
            "explicitly asks.] "

        /** Every output_text of every message output item, in order, plus the id. */
        fun parseResponse(raw: String): ResponseResult {
            val text = StringBuilder()
            val id: String
            try {
                val root = Json.parse(raw, allowTrailing = true)
                val o = root as? JObj ?: if (root is JNull) null else throw JsonException("want object")
                id = Json.str(o, "id")
                for (item in Json.arr(o, "output")) {
                    val it = Json.elem(item)
                    if (Json.str(it, "type") != "message") continue
                    for (part in Json.arr(it, "content")) {
                        val p = Json.elem(part)
                        val t = Json.str(p, "text")
                        if (Json.str(p, "type") == "output_text" && t.isNotEmpty()) text.append(t)
                    }
                }
            } catch (e: JsonException) {
                throw HermesException("hermes responses: unreadable body: ${e.message}")
            }
            if (text.isEmpty()) throw HermesException("hermes responses: no output_text in response")
            return ResponseResult(text.toString(), id)
        }
    }
}
