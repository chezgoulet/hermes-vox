package com.hermesvox.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** An error the entity connector reports: a non-2xx answer, an unusable body, a
 *  failed or abandoned turn. Transport failures surface as their IOException. */
class HermesException(message: String) : Exception(message)

/**
 * The shared HTTP plumbing for every entity request (HttpURLConnection — the
 * platform client, so no new dependency and the app's network security config
 * applies to the gateway the same way it applies to the conn-test probe).
 */
internal object Entity {

    /**
     * The API server's per-channel identity header. The gateway derives a STABLE
     * long-term-memory scope from it, independent of the transcript-scoped session
     * id that rotates on /new.
     *
     * API_SERVER_KEY is ONE bearer credential for the whole deployment — it names
     * the gateway, not the caller. Several people (or devices) can share a gateway,
     * and with no session key they all write into the same memory scope. Empty =
     * the gateway's per-transcript default, so single-user installs are unchanged.
     *
     * The gateway is the authority on what a valid scope is (≤256 chars, no CR/LF/NUL).
     * There is deliberately no copy of that rule here: SessionScope.kt validates it
     * visibly in Settings, and anything else gets the gateway's loud 400.
     */
    const val SESSION_KEY_HEADER = "X-Hermes-Session-Key"

    /** Caps how much of a non-2xx body is read into an error. Error text travels to
     *  the log and UI; a misbehaving proxy's HTML page is wasted memory and noise. */
    const val MAX_ERROR_BODY = 2048

    /**
     * Joins the user-typed base URL with a gateway path. A pasted URL with a trailing
     * slash is the normal case ("http://host:8642/"), and the API server 404s on both
     * "//v1/models" and "/v1/models/" — which onboarding would report as a misleading
     * "check URL + key". The trim lives here, once, for every request.
     */
    fun url(baseURL: String, path: String): String = goTrimSpace(baseURL).trimEnd('/') + path

    /** The bearer key and the declared scope, each omitted when empty. */
    fun headers(apiKey: String, sessionKey: String): Map<String, String> = buildMap {
        if (apiKey.isNotEmpty()) put("Authorization", "Bearer $apiKey")
        if (sessionKey.isNotEmpty()) put(SESSION_KEY_HEADER, sessionKey)
    }

    /** "502 Bad Gateway" — the status line the way Go's resp.Status printed it. */
    fun status(c: HttpURLConnection): String {
        val msg = c.responseMessage
        return if (msg.isNullOrBlank()) "${c.responseCode}" else "${c.responseCode} $msg"
    }

    /** The response body whatever the status (HttpURLConnection splits it in two). */
    fun body(c: HttpURLConnection): InputStream? =
        if (c.responseCode >= 400) c.errorStream else try { c.inputStream } catch (_: IOException) { c.errorStream }

    /** At most [MAX_ERROR_BODY] bytes of an error body, trimmed. */
    fun readErrorBody(c: HttpURLConnection): String {
        val ins = body(c) ?: return ""
        return ins.use {
            val buf = ByteArray(MAX_ERROR_BODY)
            var n = 0
            while (n < buf.size) {
                val r = try { it.read(buf, n, buf.size - n) } catch (_: IOException) { -1 }
                if (r < 0) break
                n += r
            }
            goTrimSpace(String(buf, 0, n, Charsets.UTF_8))
        }
    }

    fun open(method: String, url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.useCaches = false
        for ((k, v) in headers) c.setRequestProperty(k, v)
        return c
    }

    fun send(c: HttpURLConnection, body: ByteArray) {
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
    }

    private val deadlines = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "hermes-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /**
     * Runs one whole request/response exchange under an overall deadline — the
     * equivalent of Go's http.Client.Timeout. HttpURLConnection only bounds each
     * connect and each read, so a server trickling bytes could otherwise hold a
     * blocking turn open forever. On expiry the connection is torn down and the
     * exchange fails with a timeout naming [what].
     */
    fun <T> exchange(c: HttpURLConnection, timeoutMs: Long, what: String, block: (HttpURLConnection) -> T): T {
        val fired = AtomicBoolean(false)
        val timer = deadlines.schedule({ fired.set(true); c.disconnect() }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            return block(c)
        } catch (e: IOException) {
            if (fired.get()) throw HermesException("$what: timed out after ${timeoutMs / 1000}s")
            // Android refuses cleartext to anything but a tailnet name (#126). The
            // platform's message names the rule but not the way out of it.
            cleartextHint(e.message)?.let { throw HermesException(it) }
            throw e
        } finally {
            timer.cancel(false)
        }
    }

    /**
     * The actionable form of Android's cleartext refusal. Returns null for any other
     * error, so a caller can rethrow the original.
     */
    fun cleartextHint(message: String?): String? {
        if (message == null || !message.contains("CLEARTEXT", ignoreCase = true)) return null
        return "This gateway is plain HTTP, which Android blocks for anything but a tailnet " +
            "name. Serve it over HTTPS — on the gateway host run `tailscale serve --bg 8080`, " +
            "then enter https://<machine>.<tailnet>.ts.net here. " +
            "See DEVICE_VERIFY.md → Upgrading to 0.9."
    }

    /** Go's strings.TrimSpace: trims Unicode White_Space (which Kotlin's trim() and
     *  Java's strip() each define slightly differently). */
    fun goTrimSpace(s: String): String = s.trim { isGoSpace(it) }

    private fun isGoSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', ' ', ' ',
        ' ', ' ', ' ', ' ', '　' -> true
        else -> c in ' '..' '
    }
}
