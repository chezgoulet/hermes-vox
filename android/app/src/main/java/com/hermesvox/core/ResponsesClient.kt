package com.hermesvox.core

/** The outcome of a non-streaming /v1/responses turn. */
internal data class ResponseResult(val reply: String, val responseId: String)

/**
 * A2 (2026-11-04 Presence review, F4): the per-request extras that ride the wire but never the
 * conversation — the ephemeral `instructions` (the voice register, the soul-sync, the phone's
 * state) and the per-request reasoning effort. Both are absent from the agent's stored
 * transcript and memory, which is the point: protocol text belongs in the request, not in the
 * caller's mouth.
 *
 * The default is "no instructions, no override" — a typed turn sends exactly that (as an
 * explicit empty string on the wire, which clears any instructions inherited down the chain).
 */
internal data class RequestOptions(
    val instructions: String = "",
    val reasoning: Map<String, Any?>? = null,
)

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

    /**
     * The request body; provider and previous_response_id only when set.
     *
     * A2 (2026-11-04 Presence review, F4): [instructions] is the request's EPHEMERAL system
     * prompt — the voice register, the soul-sync and (B1) what the phone is doing. It is sent
     * on every request, even empty, because the gateway inherits the previous turn's
     * `instructions` when a chained request omits the field
     * (`api_server_response_admissions.py`: `if instructions is None: instructions =
     * stored.get('instructions')`), so a typed turn after a voice turn must say "" explicitly
     * or it would inherit the voice register. [reasoning] is the per-request
     * `model_options.reasoning` override ([reasoningOption]); null sends nothing.
     *
     * The caller's words stay in `input`, byte-exact: protocol text never rides the user's
     * message, so the agent's own transcript and memory read like a conversation.
     */
    fun buildBody(
        input: String,
        previousResponseId: String,
        stream: Boolean,
        instructions: String = "",
        reasoning: Map<String, Any?>? = null,
    ): ByteArray {
        val body = synchronized(lock) {
            linkedMapOf<String, Any?>("model" to model, "input" to input, "stream" to stream).apply {
                put("instructions", instructions)
                if (provider.isNotEmpty()) put("provider", provider)
                if (previousResponseId.isNotEmpty()) put("previous_response_id", previousResponseId)
                if (reasoning != null) put("model_options", linkedMapOf("reasoning" to reasoning))
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
     *
     * [instructions] and [reasoning] mean exactly what they do on [buildBody]; the runs
     * admission accepts both (`api_server_runs.py` passes `instructions` as the ephemeral
     * system prompt and `model_options` through as the run's runtime options).
     */
    fun buildRunBody(
        input: String,
        instructions: String = "",
        reasoning: Map<String, Any?>? = null,
    ): ByteArray {
        val body = synchronized(lock) {
            linkedMapOf<String, Any?>("model" to model, "input" to input).apply {
                put("instructions", instructions)
                if (provider.isNotEmpty()) put("provider", provider)
                if (conversationId.isNotEmpty()) put("session_id", conversationId)
                if (reasoning != null) put("model_options", linkedMapOf("reasoning" to reasoning))
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
                val err = Entity.readErrorBody(c)
                // A 404 with the host's missing-profile phrase is a fact about this address,
                // not a key or network failure: record it before surfacing the error.
                ProfileReach.observe(c.responseCode, err, baseURL, StreamRoute.currentProfile())
                throw HermesException("hermes responses ${Entity.status(c)}: $err")
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
            // This is the one probe that can both convict and clear the missing-profile
            // state: a successful GET /v1/models for this profile proves it exists again.
            if (code == 200) ProfileReach.clearedByModels(Entity.url(baseURL, "/v1/models"))
            else ProfileReach.observe(code, raw, baseURL, StreamRoute.currentProfile())
            if (code != 200) {
                val detail = if (raw.isBlank()) "" else ": $raw"
                throw HermesException("hermes ${Entity.status(c)}$detail")
            }
            raw
        }
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 120_000
        const val PING_TIMEOUT_MS = 10_000

        /**
         * A2 (2026-11-04 Presence review, F4): the per-request reasoning effort for a voice
         * turn, as `model_options.reasoning` — the gateway's own shape
         * (`api_server.py` `_runtime_options_from_model_options`,
         * `api_server_request_options._request_reasoning_config`):
         * `{"enabled":true,"effort":"low"}` or `{"enabled":false}`.
         *
         * [setting] is the user's choice: `off`, or one of `low` / `medium` / `high`. Blank or
         * unknown means the default, which is [DEFAULT_EFFORT] — a voice turn wants the fast
         * answer, and the user can change it in Settings. Providers that ignore reasoning
         * options simply behave as before.
         */
        const val DEFAULT_EFFORT = "low"
        private val EFFORTS = setOf("minimal", "low", "medium", "high", "xhigh", "max")

        fun reasoningOption(setting: String?): Map<String, Any?> {
            val s = setting?.trim()?.lowercase().orEmpty()
            if (s == "off" || s == "none") return linkedMapOf("enabled" to false)
            val effort = if (s in EFFORTS) s else DEFAULT_EFFORT
            return linkedMapOf("enabled" to true, "effort" to effort)
        }

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
