package com.hermesvox

import java.io.File

/**
 * FileShare — one file, one offer, one state machine (SPEC-conversational-bridge §B).
 *
 * The parts that can be got wrong are here, away from Android: which route a file takes
 * ([FileOffer.plan]), what the turn says, when the server starts and stops, what the user is
 * told at each moment, and the rule that the staged copy is deleted however the flow ends.
 *
 * The three edges are injected: [sendTurn] (the ordinary text path the app already has),
 * [onState] (the sheet), and [tailnetHost] (the address the server binds to). Tests drive all
 * three with fakes, so the whole flow — offer, collect, mismatch, expiry, cancel — is proven
 * on the JVM. The socket is [FileServer]; it is started only for route OFFER.
 *
 * The app never turns "the agent mentioned it" into success. [State.Collected] is reachable
 * only when the server wrote a complete response *and* the agent did not report a mismatch
 * (and if the agent named a path, that path is what the line shows). A reply that arrives
 * before the transfer, or a transfer the agent never acknowledges, is stated as what it is.
 */
class FileShare(
    private val sendTurn: (text: String, transcript: String) -> Unit,
    private val onState: (State) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tailnetHost: () -> String? = { Tailnet.address()?.hostAddress },
    private val later: (delayMs: Long, action: () -> Unit) -> Unit =
        { ms, action -> scheduleOnDaemon(ms, action) },
) : AutoCloseable {

    /** What the sheet renders. Every state has one line; there is no spinner anywhere. */
    sealed class State {
        data object Idle : State()

        /** The copy and hash are running; nothing has been sent and no server exists yet. */
        data class Reading(val name: String, val line: String) : State()

        data class Offering(
            val file: FileOffer.File,
            val expiresAtSec: Long,
            val line: String,
        ) : State()

        data class Inline(val file: FileOffer.File, val line: String) : State()

        data class Collected(val file: FileOffer.File, val line: String) : State()

        /** The offer died with no completed transfer. */
        data class Expired(val file: FileOffer.File, val line: String) : State()

        /** The agent said the bytes it got were not the bytes we sent. */
        data class Mismatch(val file: FileOffer.File, val line: String) : State()

        /** Above the cap. Nothing was copied, nothing was sent. */
        data class Refused(val file: FileOffer.File?, val line: String) : State()

        /** No tailnet address: no server nobody can reach. Inline is offered when it can be. */
        data class Unreachable(
            val file: FileOffer.File,
            val line: String,
            val inlineTurn: String?,
        ) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** The one file this flow is about; null outside an offer. */
    private var staged: File? = null
    private var offer: FileOffer.File? = null
    private var server: FileServer? = null
    private var pendingInline: String? = null
    private var generation = 0L

    /** The server currently open, for tests and for the "waiting" line's expiry. */
    val openServer: FileServer? get() = server

    /**
     * Start a flow for [file]. [staged] is the private copy (null for inline and refused
     * routes); [inlineText] is the decoded body when the caller read it as text.
     */
    fun begin(file: FileOffer.File, staged: File?, inlineText: String?) {
        closeServer()
        deleteStaged()
        this.offer = file
        this.staged = staged
        this.pendingInline = null
        generation++
        val plan = FileOffer.plan(file, inlineText, tailnetHost())
        log("event=file-offer route=${plan.route.name.lowercase()} bytes=${file.bytes}")
        when (plan.route) {
            FileOffer.Route.REFUSED -> {
                deleteStaged()
                emit(State.Refused(file, plan.line))
            }
            FileOffer.Route.UNREACHABLE -> {
                // A server nobody can reach is never started, and the copy is not kept while
                // we wait for a decision: tapping inline re-stages through the caller.
                deleteStaged()
                pendingInline = plan.inlineTurn
                emit(State.Unreachable(file, plan.line, plan.inlineTurn))
            }
            FileOffer.Route.INLINE -> {
                deleteStaged()                                   // no copy, no server
                sendTurn(plan.inlineTurn!!, FileOffer.transcriptLine(file, inline = true))
                emit(State.Inline(file, plan.line))
            }
            FileOffer.Route.OFFER -> startOffer(file, plan)
        }
    }

    private fun startOffer(file: FileOffer.File, plan: FileOffer.Plan) {
        val copy = staged
        if (copy == null || !copy.isFile) {
            emit(State.Refused(file, "the file could not be read · nothing was sent"))
            return
        }
        val host = tailnetHost()
        if (host.isNullOrBlank()) {
            deleteStaged()
            emit(State.Unreachable(file, Tailnet.DOWN_LINE, null))
            return
        }
        val token = FileOffer.token()
        val expiresAtMs = clock() + FileOffer.EXPIRY_MS
        val expiresAtSec = FileOffer.expiresAt(clock())
        val mine = generation
        val bind = try {
            java.net.InetAddress.getByName(host)
        } catch (_: Throwable) {
            deleteStaged()
            emit(State.Refused(file, "this phone has no usable tailnet address · nothing was sent"))
            return
        }
        val s = try {
            FileServer.start(
                bind = bind,
                token = token,
                file = copy,
                bytes = file.bytes,
                mime = file.mime,
                sha256 = file.sha256,
                expiresAtMs = expiresAtMs,
                clock = clock,
                log = log,
                onTransfer = {
                    // The agent has the bytes. The copy has done its job and goes now —
                    // whatever the agent says next, there is nothing left on disk.
                    deleteStaged()
                    log("event=file-offer transferred bytes=${file.bytes}")
                },
            )
        } catch (e: Throwable) {
            deleteStaged()
            emit(State.Refused(file, "the file server could not start · nothing was sent"))
            log("event=file-offer error=bind")
            return
        }
        server = s
        val turn = FileOffer.offerTurn(file, host, s.port, token, expiresAtSec)
        log("event=file-offer serving port=${s.port} expires=$expiresAtSec")
        sendTurn(turn, FileOffer.transcriptLine(file, inline = false))
        emit(State.Offering(file, expiresAtSec, plan.line))
        later(FileOffer.EXPIRY_MS) { expireIf(mine) }
    }

    /**
     * The user chose the inline path after Tailscale was found down. It needs no server and
     * no staged copy — the turn was built from the bytes when the file was read.
     */
    fun sendInline(): Boolean {
        val current = state as? State.Unreachable ?: return false
        val turn = pendingInline ?: return false
        sendTurn(turn, FileOffer.transcriptLine(current.file, inline = true))
        pendingInline = null
        generation++
        emit(State.Inline(current.file, "sent inline · ${current.file.name} " +
            "(${FileOffer.size(current.file.bytes)}) · no server needed"))
        return true
    }

    /** The copy is running. Stated, never a spinner: one line naming the file. */
    fun reading(name: String) {
        generation++
        closeServer()
        deleteStaged()
        offer = null
        pendingInline = null
        emit(State.Reading(name, "reading $name · nothing has been sent yet"))
    }

    /** A file that could not be staged at all (too big, unreadable): one stated line. */
    fun refuse(line: String) {
        generation++
        closeServer()
        deleteStaged()
        offer = null
        pendingInline = null
        log("event=file-offer refused reason=staging")
        emit(State.Refused(null, line))
    }

    /**
     * The agent's reply to the offer turn. Only the agent can say "the hash did not match",
     * and that sentence wins over everything: a mismatch is never shown as success, even when
     * the transfer itself completed.
     */
    fun agentReplied(text: String?) {
        val file = offer ?: return
        val current = state
        if (current !is State.Offering && current !is State.Inline) return
        when (val reply = FileOffer.readReply(text)) {
            is FileOffer.Reply.Mismatch -> {
                log("event=file-offer verdict=mismatch")
                finish()                                   // nothing was kept by us either
                emit(State.Mismatch(file, FileOffer.mismatch()))
            }
            is FileOffer.Reply.Kept -> {
                val served = server?.served == true || current is State.Inline
                if (!served) {
                    // The agent claims it has the file but no complete transfer reached us.
                    // That is not a collection — say nothing new and let expiry decide.
                    log("event=file-offer verdict=none reason=no-transfer")
                    return
                }
                log("event=file-offer verdict=collected path=${if (reply.path.isEmpty()) "unstated" else "stated"}")
                closeServer()
                deleteStaged()
                emit(State.Collected(file, FileOffer.collected(file, reply.path)))
            }
            FileOffer.Reply.Silent -> Unit
        }
    }

    /** The expiry watcher. A transfer that completed without a reply is still collected. */
    private fun expireIf(mine: Long) {
        if (mine != generation) return
        if (state !is State.Offering) return
        val file = offer ?: return
        val s = server
        closeServer()
        deleteStaged()
        if (s?.served == true) {
            log("event=file-offer expired reason=silent-agent")
            emit(State.Collected(file, FileOffer.collected(file, "")))
        } else {
            log("event=file-offer expired reason=no-fetch")
            emit(State.Expired(file, FileOffer.expired()))
        }
    }

    /** The user closed the sheet, or the screen went away: the copy goes, the server stops. */
    fun cancel() {
        generation++
        closeServer()
        deleteStaged()
        offer = null
        pendingInline = null
        emit(State.Idle)
    }

    /** End the flow without changing what the sheet shows (a finished state stays visible). */
    fun finish() {
        generation++
        closeServer()
        deleteStaged()
    }

    override fun close() = finish()

    // ---- helpers ----

    private fun emit(s: State) {
        state = s
        onState(s)
    }

    private fun closeServer() {
        try { server?.close() } catch (_: Throwable) { }
        server = null
    }

    private fun deleteStaged() {
        val f = staged ?: return
        staged = null
        try {
            f.delete()
            // The parent is a per-offer directory; remove it once it is empty.
            f.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** A daemon thread, so a pending expiry never keeps the process alive. */
        fun scheduleOnDaemon(delayMs: Long, action: () -> Unit) {
            Thread({
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                try { action() } catch (_: Throwable) { }
            }, "file-offer-expiry").apply { isDaemon = true }.start()
        }
    }
}
