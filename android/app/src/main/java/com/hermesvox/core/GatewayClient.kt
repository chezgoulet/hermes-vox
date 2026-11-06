package com.hermesvox.core

/**
 * The gateway's model/health/job endpoints the on-device catalog CANNOT provide:
 * /api/model/options (the provider/model catalog the /models chooser reads),
 * /v1/health (the status/version card, and the probe after a restart), and
 * POST /api/jobs/{id}/run (triggering the one restart job the agent created).
 * Same base URL + bearer as the responses client; reads carry no session scope.
 *
 * There is deliberately no DELETE here (or anywhere else): the app reads the
 * host; it never mutates it. The one POST that changes host state is the job
 * trigger, and the job itself is the agent's artefact. [NoDestructiveRequestsTest]
 * makes the no-DELETE rule structural.
 */
internal class GatewayClient(private val baseURL: String, private val apiKey: String) {

    private fun get(path: String): Pair<Int, String> {
        val c = Entity.open("GET", Entity.url(baseURL, path), Entity.headers(apiKey, ""), TIMEOUT_MS, TIMEOUT_MS)
        return Entity.exchange(c, TIMEOUT_MS.toLong(), "hermes $path") {
            val code = c.responseCode
            val body = Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            ProfileReach.observe(code, body, baseURL, StreamRoute.currentProfile())
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

    /**
     * True when `/v1/health` answered 2xx within [timeoutMs]. The bootstrap polls this while the
     * gateway restarts, so a failed probe is data (the expected drop), not an exception the caller
     * has to interpret.
     */
    fun answers(timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
        return try {
            val c = Entity.open(
                "GET", Entity.url(baseURL, "/v1/health"), Entity.headers(apiKey, ""),
                timeoutMs, timeoutMs,
            )
            Entity.exchange(c, timeoutMs.toLong(), "hermes health probe") {
                val ok = c.responseCode == 200
                Entity.body(c)?.use { it.readBytes() }
                ok
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Triggers one cron job: `POST /api/jobs/{id}/run`. The app has no restart route of its own and
     * never edits the host; this asks the gateway to run the one job the agent created. A non-2xx is
     * surfaced — a 404/400 means the id was not a job the gateway knows, and that is a refusal, not
     * evidence of a restart.
     */
    fun runJob(jobId: String): String {
        val body = "{}".toByteArray(Charsets.UTF_8)
        val c = Entity.open(
            "POST", Entity.url(baseURL, "/api/jobs/${segment(jobId)}/run"),
            Entity.headers(apiKey, "") + ("Content-Type" to "application/json"),
            TIMEOUT_MS, TIMEOUT_MS,
        )
        return Entity.exchange(c, TIMEOUT_MS.toLong(), "hermes job run") {
            Entity.send(c, body)
            val code = c.responseCode
            if (code != 200) {
                val err = Entity.readErrorBody(c)
                ProfileReach.observe(code, err, baseURL, StreamRoute.currentProfile())
                throw HermesException("hermes job run ${Entity.status(c)}: $err")
            }
            Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
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
                val err = Entity.readErrorBody(c)
                ProfileReach.observe(code, err, baseURL, StreamRoute.currentProfile())
                throw HermesException("hermes approval ${Entity.status(c)}: $err")
            }
            Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        }
    }

    /**
     * #376: places a run on the approval lane and returns its id. The ordinary turn path
     * is `/v1/responses`, which on gateway 0.22.6 does not bridge approval requests at all;
     * `/v1/runs` does, and answers them through the same run id — see [ApprovalArm].
     *
     * Debug-only by construction: the only caller is the FLAG_DEBUGGABLE arm.
     */
    fun startRun(input: String): String {
        val body = Json.encode(linkedMapOf<String, Any?>("input" to input)).toByteArray(Charsets.UTF_8)
        val c = Entity.open("POST", Entity.url(baseURL, "/v1/runs"),
            Entity.headers(apiKey, "") + ("Content-Type" to "application/json"),
            TIMEOUT_MS, TIMEOUT_MS)
        return Entity.exchange(c, TIMEOUT_MS.toLong(), "hermes run") {
            Entity.send(c, body)
            val code = c.responseCode
            if (code != 200 && code != 202) {
                val err = Entity.readErrorBody(c)
                ProfileReach.observe(code, err, baseURL, StreamRoute.currentProfile())
                throw HermesException("hermes run ${Entity.status(c)}: $err")
            }
            val raw = Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val runId = try {
                Json.str(Json.parse(raw, allowTrailing = true) as? JObj, "run_id")
            } catch (e: JsonException) {
                throw HermesException("hermes run: unreadable body: ${e.message}")
            }
            if (runId.isEmpty()) throw HermesException("hermes run: no run_id in response")
            runId
        }
    }

    /** #376: the raw `GET /v1/runs/{id}` body — status, `pending_controls`, `approval`. */
    fun runStatus(runId: String): String {
        val (code, body) = get("/v1/runs/${segment(runId)}")
        if (code != 200) throw HermesException("hermes run status $code: $body")
        return body
    }

    /**
     * Abandons a runs-route turn: `POST /v1/runs/{id}/stop`. Closing the events
     * subscription does not stop the run (it is only a subscriber), so a client that
     * walks away after its own deadline must say so explicitly. 2xx/204 both mean the
     * gateway accepted the stop; a non-2xx is surfaced for the field log.
     */
    fun stopRun(runId: String) {
        val c = Entity.open("POST", Entity.url(baseURL, "/v1/runs/${segment(runId)}/stop"),
            Entity.headers(apiKey, ""), TIMEOUT_MS, STOP_TIMEOUT_MS)
        Entity.exchange(c, STOP_TIMEOUT_MS.toLong(), "hermes run stop") {
            val code = c.responseCode
            if (code != 200 && code != 202 && code != 204) {
                val err = Entity.readErrorBody(c)
                ProfileReach.observe(code, err, baseURL, StreamRoute.currentProfile())
                throw HermesException("hermes run stop ${Entity.status(c)}: $err")
            }
            Entity.body(c)?.use { it.readBytes() }
        }
    }

    /** A path segment: run ids are ASCII today, but never concatenate untrusted text raw. */
    private fun segment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val TIMEOUT_MS = 20_000

        /** A health probe while a gateway is restarting: short, because absence is the answer. */
        const val PROBE_TIMEOUT_MS = 2_500

        /** A stop is a courtesy to the gateway, not a turn: never hold cleanup for long. */
        const val STOP_TIMEOUT_MS = 5_000
    }
}
