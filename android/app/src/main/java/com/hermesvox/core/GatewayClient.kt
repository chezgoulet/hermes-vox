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

    companion object {
        const val TIMEOUT_MS = 20_000
    }
}
