package com.hermesvox.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A minimal HTTP/1.1 server on a raw ServerSocket, for JVM unit tests (android.jar
 * has no com.sun.net.httpserver at compile time). One request per connection;
 * every response is `Connection: close`, so an SSE body is delimited by the close.
 */
class TestServer(private val handler: (Request, Response) -> Unit) : AutoCloseable {

    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        /** Header lookup ignoring case ("" when absent). */
        fun header(name: String): String =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: ""
    }

    class Response(private val socket: Socket, private val input: InputStream) {
        private val out: OutputStream = socket.getOutputStream()
        private var headSent = false

        fun head(code: Int, reason: String, contentType: String = "application/json", contentLength: Int? = null) {
            val sb = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nConnection: close\r\n")
            if (contentLength != null) sb.append("Content-Length: $contentLength\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray()); out.flush()
            headSent = true
        }

        /** A complete response with a body. */
        fun send(code: Int, reason: String, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            head(code, reason, contentType, bytes.size)
            out.write(bytes); out.flush()
        }

        /** Starts an event stream. */
        fun sse() = head(200, "OK", "text/event-stream")

        /** Writes raw bytes and flushes. */
        fun write(s: String) { out.write(s.toByteArray()); out.flush() }

        /** Blocks until the client closes its end (or [maxMs] passes). True if the client closed. */
        fun holdUntilClientCloses(maxMs: Long): Boolean {
            socket.soTimeout = maxMs.toInt()
            return try { input.read() < 0 } catch (_: IOException) { true }.also { socket.soTimeout = 0 }
        }

        internal val started get() = headSent
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())
    private val acceptor = Thread({ acceptLoop() }, "test-server").apply { isDaemon = true; start() }

    val url: String get() = "http://127.0.0.1:${server.localPort}"

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: IOException) { return }
            Thread({ serve(s) }, "test-server-conn").apply { isDaemon = true; start() }
        }
    }

    private fun serve(s: Socket) {
        s.use {
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: return
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim()] = l.substring(i + 1).trim()
            }
            val len = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toInt() ?: 0
            val body = ByteArray(len)
            var n = 0
            while (n < len) { val r = input.read(body, n, len - n); if (r < 0) break; n += r }
            val req = Request(parts[0], parts.getOrElse(1) { "/" }, headers, String(body, 0, n))
            requests.add(req)
            val resp = Response(s, input)
            try { handler(req, resp) } catch (_: IOException) { /* client went away */ }
            if (!resp.started) resp.send(500, "Internal Server Error", "handler wrote nothing")
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            b.write(c)
        }
    }

    override fun close() { server.close() }

    companion object {
        /** Waits on a latch, failing the test on timeout. */
        fun await(l: CountDownLatch, ms: Long = 5000) {
            check(l.await(ms, TimeUnit.MILLISECONDS)) { "timed out after ${ms}ms" }
        }
    }
}
