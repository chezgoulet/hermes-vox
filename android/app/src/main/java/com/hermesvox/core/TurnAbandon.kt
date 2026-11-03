package com.hermesvox.core

/**
 * What it means to walk away from a turn that ran out of budget.
 *
 * The two routes are not equivalent at the wire:
 *
 *  - [TurnRoute.RESPONSES]: the SSE request IS the generation. Closing the socket
 *    aborts the work, which is what this client has always relied on.
 *  - [TurnRoute.RUNS]: the events GET is only a subscriber. Closing it leaves the run
 *    generating (or parked in `waiting_for_approval`) with nobody listening. The run
 *    must be stopped with `POST /v1/runs/{id}/stop`; the subscriber is then closed too.
 *
 * The actual I/O is injected so the decision is provable off-device: the tests record
 * which action each route took, and the controller supplies the real session calls.
 */
internal object TurnAbandon {

    /**
     * Abandons [streamId] on [route]. [runId] is the gateway run id when the route has
     * one (empty on the responses route). Returns true when the route issued a stop.
     */
    fun perform(
        route: TurnRoute,
        streamId: String,
        runId: String,
        closeSocket: (String) -> Unit,
        stopRun: (String) -> Unit,
    ): Boolean {
        when (route) {
            TurnRoute.RESPONSES -> {
                if (streamId.isNotEmpty()) closeSocket(streamId)
                return false
            }
            TurnRoute.RUNS -> {
                val stopped = runId.isNotEmpty()
                if (stopped) stopRun(runId)
                // The subscriber is ours to close whether or not the stop landed; an
                // open socket into a run nobody follows is a leak either way.
                if (streamId.isNotEmpty()) closeSocket(streamId)
                return stopped
            }
        }
    }
}
