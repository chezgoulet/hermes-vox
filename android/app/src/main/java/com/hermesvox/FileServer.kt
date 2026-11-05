package com.hermesvox

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FileServer — the one-shot server that serves exactly one file, exactly once (SPEC §B).
 *
 * It binds to **one address** — the phone's tailnet address, chosen by [Tailnet] — on an
 * ephemeral port, and it answers exactly one path: `/<token>`, 128 bits of random. There is no
 * listing, no second route, no method but `GET`, and no way to name a file other than the one
 * that was staged: the path is compared literally, so `..`, `%2e%2e`, a double slash, an
 * absolute-form URL or a longer path can never match.
 *
 * Life:
 *  * the first fully written response is the transfer; it happens **once**;
 *  * after it, every further request is `410 Gone` — the bytes are never served twice, and
 *    the staged copy is already deleted by then ([onTransfer]);
 *  * at [expiresAtMs] (five minutes from the offer) the listener closes itself, and any
 *    request after that point is refused with `410 Gone` for as long as the socket is open.
 *
 * Every refusal is logged with a *reason* and never with the token: the log line names the
 * shape of the request (`path=<token>`, `path=<other>`, `method=POST`, `reason=traversal`),
 * which is what makes a refused request auditable without putting the capability in a log.
 *
 * Binding is injected, so the socket test in `FileServerTest` can bind loopback and run in CI
 * while the app passes the tailnet address. The server itself contains no Android API.
 */
class FileServer private constructor(
    private val server: ServerSocket,
    private val token: String,
    private val file: File,
    private val bytes: Long,
    private val mime: String,
    private val sha256: String,
    private val expiresAtMs: Long,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val onTransfer: () -> Unit,
    private val requestDeadlineMs: Long = FileServer.requestDeadlineFor(bytes),
) : AutoCloseable {

    /** The port the OS gave us. */
    val port: Int get() = server.localPort

    /** `http://<host>:<port>/<token>` — the capability URL, as the turn carries it. */
    val url: String = FileOffer.url(server.inetAddress.hostAddress ?: "", port, token)

    /**
     * True from the instant the one response starts leaving the socket, and until it is known
     * not to have completed. It is latched *before* the first byte goes out — never after —
     * because the requester can read the status line as soon as it arrives, and a caller that
     * asks "was this served?" after a completed fetch must never be told no. A write that
     * aborts (or a body shorter than the announced length) clears it again: that is not a
     * transfer.
     */
    @Volatile var served: Boolean = false
        private set

    /** True once the listener is closed (expiry, transfer end plus expiry, or [close]). */
    @Volatile var closed: Boolean = false
        private set

    private val transferred = CountDownLatch(1)
    private val done = CountDownLatch(1)
    private val accepted = ArrayList<String>()          // test-visible log, no token ever
    private val acceptor: Thread

    init {
        server.soTimeout = 250                            // so expiry is noticed while idle
        acceptor = Thread({ acceptLoop() }, "file-offer-${server.localPort}").apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for the one transfer; true if it happened inside [ms]. */
    fun awaitTransfer(ms: Long): Boolean = transferred.await(ms, TimeUnit.MILLISECONDS)

    /** Waits for the listener to close itself (expiry) or be closed. */
    fun awaitClosed(ms: Long): Boolean = done.await(ms, TimeUnit.MILLISECONDS)

    /** The refusal records this server wrote, in order (tests read these). */
    fun logLines(): List<String> = synchronized(accepted) { accepted.toList() }

    // ---- the listener ----

    private fun acceptLoop() {
        try {
            while (!closed) {
                if (clock() >= expiresAtMs) {
                    // No request to answer: the listener itself goes, and the record says so.
                    note("event=file-offer refused reason=expired status=410 method=<none> path=<token>")
                    break
                }
                val socket = try {
                    server.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                try {
                    handle(socket)
                } catch (_: Throwable) {
                    // A connection that dies mid-request is not a reason to stop serving the
                    // one that has not happened yet; it is logged and forgotten.
                    note("event=file-offer error=connection")
                } finally {
                    try { socket.close() } catch (_: IOException) { }
                }
            }
        } finally {
            close()
        }
    }

    /**
     * One request, under a total deadline from accept (L7, 0.9.1 review). The per-read
     * [READ_TIMEOUT_MS] only bounds each read, so a peer that trickles one byte at a time
     * would otherwise hold the one-shot handler until expiry. The watchdog closes the
     * socket at [requestDeadlineMs]; closing interrupts a blocked read or write, and the
     * connection is logged and forgotten like any other dead peer.
     */
    private fun handle(socket: Socket) {
        val watchdog = deadlines.schedule({
            try { socket.close() } catch (_: IOException) { }
        }, requestDeadlineMs, TimeUnit.MILLISECONDS)
        try {
            handleRequest(socket)
        } finally {
            watchdog.cancel(false)
        }
    }

    private fun handleRequest(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input, MAX_LINE) ?: return refuseConnection(socket, "empty")
        val parts = requestLine.split(' ')
        val method = parts.getOrElse(0) { "" }
        val target = parts.getOrElse(1) { "" }

        // Read (and bound) the headers before answering, so the client sees our status rather
        // than a reset and so a header flood cannot hold the one-shot port open.
        var headerBytes = 0
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input, MAX_LINE)
                ?: return refuseConnection(socket, "headers")
            headerBytes += line.length
            if (headerBytes > MAX_HEADERS) return refuse(socket, "headers")
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.US)] =
                line.substring(i + 1).trim()
        }

        if (method != "GET") return refuse(socket, "method", method = method, status = 405)
        if (target.contains('?') || target.contains('#')) return refuse(socket, "query")
        if (target != "/$token") return refuse(socket, reasonForPath(target))
        if (served) return refuse(socket, "spent", status = 410)
        if (clock() >= expiresAtMs) return refuse(socket, "expired", status = 410)
        if (headers.containsKey("range")) return refuse(socket, "range", status = 416)
        // Content-Length is a promise. If the copy is no longer the file we hashed, refuse it
        // rather than hand the agent a body that cannot match the header — it would hang, and
        // a half-response that looks complete is exactly what this flow must never produce.
        if (!sourceIntact()) return refuse(socket, "source", status = 500)
        serve(socket, input)
    }

    /** True when the staged copy is still exactly [bytes] long and readable. */
    private fun sourceIntact(): Boolean {
        val onDisk = try {
            if (file.isFile) file.length() else -1L
        } catch (_: Throwable) {
            -1L
        }
        if (onDisk == bytes) return true
        note("event=file-offer error=size-changed expected=$bytes actual=$onDisk")
        return false
    }

    /** The only successful response: the staged bytes, once, with their hash in a header. */
    private fun serve(socket: Socket, input: InputStream) {
        val out = socket.getOutputStream()
        val head = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ").append(mime).append("\r\n")
            .append("Content-Length: ").append(bytes).append("\r\n")
            .append("X-Vox-Sha256: ").append(sha256).append("\r\n")
            .append("Accept-Ranges: none\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
        try {
            // Latch the transfer *before* the first byte of it leaves this socket. The client
            // can read the status line the moment it arrives — before the body is written, and
            // long before this method returns — and a reply that arrives after a completed
            // fetch must never be answered "no transfer reached us". So the record of the
            // transfer has to exist no later than the bytes that prove it.
            served = true
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            var sent = 0L
            file.inputStream().use { src ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                }
            }
            out.flush()
            if (sent != bytes) {
                // Announced a length we could not deliver: this was never a transfer, and the
                // offer may still be fetched until it expires.
                served = false
                note("event=file-offer error=short-source sent=$sent bytes=$bytes")
                return
            }
            note("event=file-offer served bytes=$bytes sha256=$sha256")
            try { onTransfer() } catch (_: Throwable) { }
            transferred.countDown()
        } catch (_: IOException) {
            // The client went away mid-body. Not a completed transfer: the staged file stays
            // and the offer may be fetched again until it expires.
            served = false
            note("event=file-offer error=aborted")
        } finally {
            // Drain whatever the client still had in flight, so closing the socket does not
            // turn a delivered response into a reset before the client reads it.
            try {
                socket.soTimeout = DRAIN_MS
                while (input.read() >= 0) { /* discard */ }
            } catch (_: IOException) {
            }
        }
    }

    // ---- refusals: every one is logged, none of them names the token ----

    private fun reasonForPath(target: String): String {
        val t = target.lowercase(Locale.US)
        return when {
            t.contains("..") || t.contains("%2e") || t.contains("%252e") -> "traversal"
            t.startsWith("http://") || t.startsWith("https://") -> "absolute-form"
            t == "/" || t.isEmpty() -> "listing"
            t.startsWith("//") -> "path"
            else -> "path"
        }
    }

    private fun refuse(
        socket: Socket,
        reason: String,
        method: String = "GET",
        status: Int = 404,
    ) {
        val path = if (reason == "spent" || reason == "expired") "<token>" else "<refused>"
        note(
            "event=file-offer refused reason=$reason status=$status " +
                "method=${method.ifBlank { "<none>" }} path=$path",
        )
        val body = when (status) {
            410 -> "gone\n"
            405 -> "method not allowed\n"
            416 -> "range not supported\n"
            500 -> "the staged copy is not the file we hashed\n"
            else -> "not found\n"
        }
        val extra = if (status == 405) "Allow: GET\r\n" else ""
        try {
            val out = socket.getOutputStream()
            val reasonText = when (status) {
                410 -> "Gone"
                405 -> "Method Not Allowed"
                416 -> "Range Not Satisfiable"
                500 -> "Internal Server Error"
                else -> "Not Found"
            }
            out.write(
                ("HTTP/1.1 $status $reasonText\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n" + extra + "\r\n" + body).toByteArray(Charsets.US_ASCII),
            )
            out.flush()
        } catch (_: IOException) {
            // The requester hung up before reading the refusal; it is still logged.
        }
    }

    private fun refuseConnection(socket: Socket, reason: String) =
        refuse(socket, reason, method = "<none>")

    /** Records one line, on the injected logger and on the test-visible list. */
    private fun note(line: String) {
        synchronized(accepted) { accepted.add(line) }
        log(line)
    }

    override fun close() {
        if (closed) return
        closed = true
        try { server.close() } catch (_: IOException) { }
        done.countDown()
    }

    // ---- a bounded request reader ----

    /**
     * One CRLF- or LF-terminated line, at most [max] bytes kept. A longer line is *drained* to
     * its end and truncated: the caller refuses it (it cannot match the token), and the rest of
     * the over-long line is never re-read as if it were a header.
     */
    private fun readLine(input: InputStream, max: Int): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (b.size() < max) b.write(c)
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 10_000

        /**
         * L7: the whole request must complete inside this window, from accept, plus the
         * body allowance in [requestDeadlineFor]. The per-read timeout is unchanged; this
         * is the ceiling a trickling peer cannot stretch.
         */
        const val REQUEST_DEADLINE_MS = 15_000L

        /**
         * L7: the deadline is total, and it is fair to the file. Fifteen seconds covers the
         * request and a small body; a large body additionally gets time at a floor rate of
         * 512 KB/s, so a legitimate 100 MB transfer over a slow link is not cut while a
         * trickler still cannot hold the only handler until the five-minute expiry.
         */
        const val BODY_BYTES_PER_SECOND = 512L * 1024L

        fun requestDeadlineFor(bytes: Long): Long =
            REQUEST_DEADLINE_MS + (bytes.coerceAtLeast(0) * 1000L / BODY_BYTES_PER_SECOND)

        /** One daemon thread for every request's deadline; cancelling a fired task is cheap. */
        private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "file-offer-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private const val DRAIN_MS = 1_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 32 * 1024

        /**
         * Binds [bind] on an ephemeral port and starts serving [file] at `/<token>` until
         * [expiresAtMs]. The caller owns [file] and is told through [onTransfer] the moment a
         * complete response has been written, so it can delete the staged copy at once.
         */
        fun start(
            bind: InetAddress,
            token: String,
            file: File,
            bytes: Long,
            mime: String,
            sha256: String,
            expiresAtMs: Long,
            clock: () -> Long = System::currentTimeMillis,
            log: (String) -> Unit = {},
            onTransfer: () -> Unit = {},
            requestDeadlineMs: Long = FileServer.requestDeadlineFor(bytes),
        ): FileServer {
            require(bind.isAnyLocalAddress.not()) { "refusing to bind 0.0.0.0 / :: — pick one address" }
            val socket = ServerSocket()
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(bind, 0), 4)
            return FileServer(
                socket, token, file, bytes, mime, sha256, expiresAtMs, clock, log, onTransfer,
                requestDeadlineMs,
            )
        }
    }
}
