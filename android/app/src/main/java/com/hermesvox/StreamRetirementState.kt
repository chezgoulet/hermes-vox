package com.hermesvox

/**
 * StreamRetirementState — the pure, emulator-free decision rule for the streaming
 * TTS worker's exit (R1 single-owner retirement). Two distinct "done" parties
 * caused long replies to truncate: the text/settle path closed the stream when the
 * playback head caught streamWritten, mid-synth-gap for the NEXT queued sentence,
 * so the worker (still holding the rest of the reply) obediently aborted. The rule
 * below makes the WORKER the only owner of a graceful close.
 *
 * Three inputs, mutated only under sLock by the caller:
 *  - [sFinal]:   the text side signalled done=true with ok text — the queue no
 *                longer receives. This does NOT close anything.
 *  - [sClosed]:  an abort (barge/hush/stop/error) — cancel semantics, the worker
 *                must stop ASAP and never settle.
 *  - [queueEmpty]: the sentence queue is drained (nothing left to synthesize).
 *
 * Truth table:
 *  sFinal  sClosed  queueEmpty -> decision
 *  false   false    false      WAIT    (stream open, chunks pending — keep playing)
 *  false   false    true       WAIT    (stream open, synth gap — wait for the next chunk)
 *  true    false    false      WAIT    (final, but tail still draining — keep playing)
 *  true    false    true       RETIRE  (NATURAL exit — worker closes the stream)
 *  any     true     any        ABORT   (cancel — abort path, never settle)
 */
enum class Retirement { WAIT, RETIRE, ABORT }

internal object StreamRetirementState {

    /** Decide what a worker that just found the queue empty must do. */
    fun decide(sFinal: Boolean, sClosed: Boolean, queueEmpty: Boolean): Retirement {
        if (sClosed) return Retirement.ABORT
        if (sFinal && queueEmpty) return Retirement.RETIRE
        return Retirement.WAIT
    }
}
