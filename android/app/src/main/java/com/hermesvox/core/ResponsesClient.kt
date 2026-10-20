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

    fun setModel(model: String) = synchronized(lock) { this.model = model }

    /** The per-request provider override ("" = gateway default). */
    fun setProvider(provider: String) = synchronized(lock) { this.provider = provider }

    /** The X-Hermes-Session-Key scope ("" = the gateway's per-transcript default). */
    fun setSessionKey(scope: String) = synchronized(lock) { this.sessionKey = scope }

    fun entityHeaders(): Map<String, String> = Entity.headers(apiKey, synchronized(lock) { sessionKey })

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
     * 200. Onboarding's "Connect & verify" — never fakes success.
     */
    fun ping() {
        val c = Entity.open("GET", Entity.url(baseURL, "/v1/models"), entityHeaders(), PING_TIMEOUT_MS, PING_TIMEOUT_MS)
        Entity.exchange(c, PING_TIMEOUT_MS.toLong(), "hermes ping") {
            val code = c.responseCode
            Entity.body(c)?.use { it.readBytes() }
            if (code != 200) throw HermesException("hermes ${Entity.status(c)}")
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
