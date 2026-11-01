package com.hermesvox.core

/**
 * The gateway's model/health/session endpoints the on-device catalog CANNOT
 * provide: /api/model/options (the provider/model catalog the /models chooser
 * reads), /v1/health (the status/version card) and DELETE /v1/responses/{id}
 * (the /new reset). Same base URL + bearer as the responses client; these are
 * gateway administration, not entity turns, so they carry no session scope.
 */
internal class GatewayClient(private val baseURL: String, private val apiKey: String) {

    private fun get(path: String): Pair<Int, String> {
        val c = Entity.open("GET", Entity.url(baseURL, path), Entity.headers(apiKey, ""), TIMEOUT_MS, TIMEOUT_MS)
        return Entity.exchange(c, TIMEOUT_MS.toLong(), "hermes $path") {
            val code = c.responseCode
            val body = Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            code to body
        }
    }

    /** The raw /api/model/options JSON
     *  ({providers:[{slug,name,models[],total_models,is_current,authenticated,source,capabilities,warning}]}). */
    fun modelOptions(): String {
        val (code, body) = get("/api/model/options")
        if (code != 200) throw HermesException("hermes model options $code")
        return body
    }

    /** The raw /v1/health JSON (status/version). */
    fun health(): String {
        val (code, body) = get("/v1/health")
        if (code != 200) throw HermesException("hermes health $code")
        return body
    }

    /** Drops a stored server-side response (the /new session reset). */
    fun deleteResponse(id: String) {
        val c = Entity.open("DELETE", Entity.url(baseURL, "/v1/responses/$id"), Entity.headers(apiKey, ""), TIMEOUT_MS, TIMEOUT_MS)
        Entity.exchange(c, TIMEOUT_MS.toLong(), "hermes delete response") {
            val code = c.responseCode
            if (code != 200 && code != 204) {
                throw HermesException("hermes delete response ${Entity.status(c)}: ${Entity.readErrorBody(c)}")
            }
            Entity.body(c)?.use { it.readBytes() }
        }
    }

    /**
     * #282: answers a run's pending approval. [runId] is the event's `run_id` (the
     * gateway registers a streamed completion under that id) and [requestId] is the
     * event's `request_id`; an older gateway may omit the latter.
     *
     * One POST per decision — the exactly-once rule lives in [ApprovalGate], not here.
     * A non-2xx is surfaced: 409 means the gateway already timed the request out or it
     * was answered elsewhere, which is a fact the field log should carry.
     */
    fun answerApproval(runId: String, choice: String, requestId: String, executionGeneration: Int? = null): String {
        val body = Json.encode(linkedMapOf<String, Any?>("choice" to choice).apply {
            if (requestId.isNotEmpty()) put("request_id", requestId)
            // Session-authority runs require the generation fence back; plain runs omit it.
            if (executionGeneration != null) put("execution_generation", executionGeneration)
        }).toByteArray(Charsets.UTF_8)
        val c = Entity.open("POST", Entity.url(baseURL, "/v1/runs/${segment(runId)}/approval"),
            Entity.headers(apiKey, "") + ("Content-Type" to "application/json"),
            TIMEOUT_MS, TIMEOUT_MS)
        return Entity.exchange(c, TIMEOUT_MS.toLong(), "hermes approval") {
            Entity.send(c, body)
            val code = c.responseCode
            if (code != 200) {
                throw HermesException("hermes approval ${Entity.status(c)}: ${Entity.readErrorBody(c)}")
            }
            Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        }
    }

    /** A path segment: run ids are ASCII today, but never concatenate untrusted text raw. */
    private fun segment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val TIMEOUT_MS = 20_000
    }
}
