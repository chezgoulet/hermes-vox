package com.hermesvox.core

/**
 * Which transport a streamed turn rides.
 *
 * Both readers end in the same [StreamState] and emit the same event shapes, so every
 * consumer above [HermesSession] is route-blind. The routes are not interchangeable at
 * the wire, though:
 *
 *  - [RESPONSES] — `POST /v1/responses` (stream:true). One request, an OpenAI Responses
 *    SSE body, chained with `previous_response_id`. On gateway 0.22.6 this route never
 *    registers an approval notifier, so a flagged command resolves to the unattended
 *    policy with no event reaching the phone (`docs/NOTES-api-events.md`).
 *  - [RUNS] — `POST /v1/runs` (202 `{run_id}`) then `GET /v1/runs/{run_id}/events`
 *    (`docs/NOTES-api-events.md`, `docs/APPROVALS-ACCEPTANCE.md`). The same agent call,
 *    but the writer registers the approval notifier and emits the control events, keeps
 *    a sequence-numbered event backlog for reattach, and answers
 *    `POST /v1/runs/{run_id}/stop` for a barge-in.
 */
internal enum class TurnRoute { RESPONSES, RUNS }

/**
 * THE route switch. Both vocabularies render the same turn ([RouteParityTest]); the
 * device pass in `docs/RUNS-ROUTE.md` is what confirms the gateway build.
 *
 * [active] ships as [TurnRoute.RUNS] (#404): the route that carries approvals to the
 * phone, whose barge-in stops the run itself (`POST /v1/runs/{id}/stop`) rather than
 * only closing a subscriber. Tests flip it and restore it.
 *
 * Every install rides [active]: since 0.9.1's H2 fix a run keeps its conversation by the
 * client-owned body `session_id`, with or without a declared scope — see [routeFor].
 */
internal object StreamRoute {

    @Volatile
    var active: TurnRoute = TurnRoute.RUNS

    /**
     * The profile the fallback belongs to. A gateway without `POST /v1/runs` is a fact
     * about one host's profile, not about the process: switching profiles re-evaluates
     * this, and the negative answer is remembered per name.
     */
    @Volatile
    private var profile: String = ""

    /** Profiles whose gateway answered an admission with 404: their turns ride responses. */
    private val noRunsProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var loadNoRuns: (String) -> Boolean = { false }
    @Volatile
    private var saveNoRuns: (String) -> Unit = { }
    @Volatile
    internal var log: (String) -> Unit = { }

    /**
     * Make [name] the profile the route decision applies to, and load what the last
     * process learned about it. Called on connect and on every profile switch.
     */
    fun useProfile(name: String) {
        val n = name.trim()
        profile = n
        if (loadNoRuns(n)) noRunsProfiles.add(n)
    }

    /** The profile the current route decision belongs to ("" before a profile is named). */
    fun currentProfile(): String = profile

    /** The persistence hooks: read a profile's remembered answer, and write a new one. */
    fun bindStore(load: (String) -> Boolean, save: (String) -> Unit) {
        loadNoRuns = load
        saveNoRuns = save
        if (load(profile)) noRunsProfiles.add(profile)
    }

    /** True only for a profile whose admission already answered 404. */
    fun requiresResponses(name: String = profile): Boolean = noRunsProfiles.contains(name.trim())

    /**
     * Remember that [name]'s gateway has no `/v1/runs`. Idempotent, and persisted once;
     * true only for the first answer, so the probe (and its log line) is paid once.
     */
    fun markNoRuns(name: String = profile): Boolean {
        val n = name.trim()
        if (!noRunsProfiles.add(n)) return false
        try { saveNoRuns(n) } catch (_: Throwable) { }
        return true
    }

    /** Test hook: forget every remembered route answer. */
    internal fun clearForTest() {
        noRunsProfiles.clear()
        profile = ""
    }

    /** Test hook: forget one profile's remembered answer. */
    internal fun forgetForTest(name: String) {
        noRunsProfiles.remove(name.trim())
        if (profile == name.trim()) profile = ""
    }

    /**
     * The route a turn actually takes: [active], unless this profile's gateway already
     * said it has no `/v1/runs` — then [TurnRoute.RESPONSES]. A run has no response id, and it used
     * to need a declared `X-Hermes-Session-Key` to keep its conversation (with neither a
     * key nor a session id the gateway files a run under its own `run_id`), so an
     * unscoped install was held on [TurnRoute.RESPONSES]. The run body now carries the
     * session's own `session_id` ([ResponsesClient.setConversationId]), which the
     * gateway ranks first, so the scope only decides long-term memory, not the route.
     */
    @Suppress("UNUSED_PARAMETER")
    fun routeFor(hasSessionKey: Boolean): TurnRoute =
        if (requiresResponses()) TurnRoute.RESPONSES else active

    /**
     * The chain head a turn rides. `/v1/runs` has no response id: a run is chained by
     * its client `session_id` (and the declared `X-Hermes-Session-Key` scope), never
     * by `previous_response_id`. Sending the Responses chain head on this route would pin
     * every run to the same response-store snapshot, so the runs body omits it — and so
     * does a stored turn (`/compress`) on a session that runs, so it compacts the
     * conversation the runs wrote rather than a stale snapshot.
     */
    fun chainHead(route: TurnRoute, lastId: String): String =
        if (route == TurnRoute.RESPONSES) lastId else ""
}
