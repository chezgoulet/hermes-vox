package com.hermesvox.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * The test the mechanism needs: the CLIENT CERTIFICATE over the production path.
 *
 * The old handshake tests build their own SSLContext and SSLSocket, so they never touch
 * [MtlsConfig.applyIfConfigured] (the alias lookup + socket factory), [Entity.open] (the
 * request path every turn uses), or [MtlsKeyManager]'s socket branch — including the HOST
 * half of the origin check, the one that is supposed to stop a redirect to another host
 * from collecting the certificate.
 *
 * Here a real `SSLServerSocket` with `needClientAuth = true` records what actually arrived,
 * and the client is [Entity.open] with only preferences and a [MtlsKeyProvider] faked:
 *
 *  * the certificate chosen for the origin is presented to that origin;
 *  * the same call, redirected to a different host on the same port, presents nothing.
 *
 * The second case is a real redirect, because that is the only way an HttpsURLConnection can
 * reach a host other than the one its factory was built for (the JDK carries the socket
 * factory across a 30x). `127.0.0.2` is loopback on Linux, so both servers can hold the same
 * port and only the host differs — which is exactly the branch under test.
 *
 * What this cannot cover: Android's KeyChain (a JVM [MtlsKeyProvider] stands in), Android's
 * own HttpURLConnection implementation (this runs the JDK's), and the platform trust store
 * itself (the test JVM's default store is pointed at `tls/jvm-truststore.p12` by
 * build.gradle, standing in for Android's system store). Those need a device.
 */
class MtlsEntityTest {
    private val prefs = FakeSharedPreferences()
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        MtlsConfig.setPrefsForTest(prefs)
        MtlsConfig.setProviderForTest(TestKeyProvider())
        MtlsConfig.setLogSinkForTest { lines += it }
        MtlsConfig.invalidateCache()
    }

    @After
    fun tearDown() {
        MtlsConfig.setProviderForTest(null)
        MtlsConfig.setPrefsForTest(null)
        MtlsConfig.setLogSinkForTest(null)
    }

    // ---- fixtures -------------------------------------------------------------------

    private fun keyStore(resource: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            MtlsEntityTest::class.java.getResourceAsStream(resource).use {
                load(requireNotNull(it) { "$resource missing" }, PASSWORD.toCharArray())
            }
        }

    /** The server side: one certificate (the CA-issued `system` leaf), and a client CA of
     *  one — the `first` client identity, which is what the provider will offer. */
    private fun serverContext(): SSLContext {
        val src = keyStore("/tls/server-trust.p12")
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", src.getKey("system", PASSWORD.toCharArray()), PASSWORD.toCharArray(),
                src.getCertificateChain("system"))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keys, PASSWORD.toCharArray())

        val trusted = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("client-first", keyStore("/tls/client-identities.p12").getCertificate("first"))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trusted)

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }
    }

    /** What one accepted connection produced: the peer certificate (null = none sent) and
     *  whether the handshake got far enough to read it. */
    private class Attempt {
        val done = CountDownLatch(1)
        var peer: X509Certificate? = null
        var handshakeFailed = false
    }

    /** Binds the server socket first: the port is needed to build a redirect target. */
    private fun bind(address: String, port: Int, needClientAuth: Boolean): SSLServerSocket =
        (serverContext().serverSocketFactory
            .createServerSocket(port, 4, InetAddress.getByName(address)) as SSLServerSocket)
            .apply { this.needClientAuth = needClientAuth }

    /**
     * Accepts one connection, records whether a client certificate arrived, then writes the
     * response (built lazily, so it can name a port discovered after binding).
     */
    private fun serve(socket: SSLServerSocket, attempt: Attempt, response: () -> String) {
        Thread({
            try {
                (socket.accept() as SSLSocket).use { s ->
                    s.startHandshake()
                    // Throws SSLPeerUnverifiedException when the client sent no certificate.
                    attempt.peer = s.session.peerCertificates.firstOrNull() as? X509Certificate
                    readRequest(s)
                    val body = response()
                    if (body.isNotEmpty()) {
                        s.outputStream.write(body.toByteArray())
                        s.outputStream.flush()
                    }
                }
            } catch (_: Throwable) {
                // A refused handshake is an outcome here, not an error: the assertion is
                // about what the server received (nothing), not about how it ended.
                attempt.handshakeFailed = true
            } finally {
                attempt.done.countDown()
            }
        }, "mtls-test-server-${socket.localPort}").apply { isDaemon = true; start() }
    }

    /** Reads one HTTP request head: the request line says which hop this is. */
    private fun readRequest(s: SSLSocket): String {
        s.soTimeout = 5000
        val ins: InputStream = s.inputStream
        val seen = StringBuilder()
        try {
            while (!seen.endsWith("\r\n\r\n") && seen.length < 8192) {
                val b = ins.read()
                if (b < 0) break
                seen.append(b.toChar())
            }
        } catch (_: SocketTimeoutException) {
            // Nothing to read: the handshake is what matters, not the request.
        }
        return seen.toString()
    }

    private fun get(url: String): javax.net.ssl.HttpsURLConnection =
        Entity.open("GET", url, emptyMap(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
            as javax.net.ssl.HttpsURLConnection

    // ---- the two cases ---------------------------------------------------------------

    /**
     * The certificate chosen for an origin is presented to that origin — through
     * `Entity.open`, `applyIfConfigured`, the cached socket factory (null TrustManager, so
     * the platform store verifies the server) and the KeyManager the provider backs.
     */
    @Test
    fun theConfiguredCertificateIsSentToItsOwnOrigin() {
        val attempt = Attempt()
        // Bind to the address `localhost` resolves to, so the URL and the socket agree.
        val server = bind("localhost", 0, needClientAuth = true)
        val port = server.localPort
        serve(server, attempt) { HTTP_OK }
        val origin = MtlsConfig.originOf("https://localhost:$port/")!!
        MtlsConfig.setAlias(prefs, origin, "first")

        val conn = get("https://localhost:$port/v1/models")
        try {
            assertTrue("the production path must attach a client-certificate factory",
                conn.sslSocketFactory != javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory())
            assertEquals(200, conn.responseCode)
        } finally {
            conn.disconnect()
            server.close()
        }
        assertTrue("the server never saw the connection", attempt.done.await(10, TimeUnit.SECONDS))
        assertNotNull("the client certificate must arrive at its own origin", attempt.peer)
        assertEquals("CN=first-client", attempt.peer!!.subjectX500Principal.name)
    }

    /**
     * The same call, redirected to a DIFFERENT HOST on the SAME PORT: the factory (and its
     * KeyManager) travels with the redirect, the host branch of the origin check refuses,
     * and the second server receives no certificate. Without the check, Ghost's certificate
     * would authenticate the app to whatever host a 30x names.
     */
    @Test
    fun aRedirectToAnotherHostGetsNoCertificate() {
        // One server, two names on one port: `localhost` (the origin the certificate was
        // chosen for) and `127.0.0.1` (where the 30x points). The certificate is valid for
        // both, so nothing but the origin guard can stop it being sent again.
        val server = bind("0.0.0.0", 0, needClientAuth = true)
        val port = server.localPort
        val attempts = java.util.Collections.synchronizedList(ArrayList<Attempt>())
        val served = CountDownLatch(2)
        Thread({
            for (i in 0 until 2) {
                val attempt = Attempt()
                try {
                    (server.accept() as SSLSocket).use { s ->
                        s.soTimeout = 5000
                        s.startHandshake()
                        attempt.peer = s.session.peerCertificates.firstOrNull() as? X509Certificate
                        val request = readRequest(s)
                        val response = if (request.contains(" /start")) {
                            "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:$port/final\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            HTTP_OK
                        }
                        s.outputStream.write(response.toByteArray())
                        s.outputStream.flush()
                    }
                } catch (_: Throwable) {
                    // The refusal IS the outcome: the client sent no certificate.
                    attempt.handshakeFailed = true
                } finally {
                    attempts += attempt
                    served.countDown()
                }
            }
        }, "mtls-test-redirect").apply { isDaemon = true; start() }

        val origin = MtlsConfig.originOf("https://localhost:$port/")!!
        MtlsConfig.setAlias(prefs, origin, "first")

        val conn = get("https://localhost:$port/start")
        try {
            // The 30x is followed automatically; the final handshake is refused because the
            // origin check will not answer for 127.0.0.1.
            try { conn.responseCode } catch (_: java.io.IOException) { /* expected */ }
        } finally {
            conn.disconnect()
            server.close()
        }

        assertTrue("both connections should have been attempted", served.await(10, TimeUnit.SECONDS))
        assertEquals("expected exactly two attempts: $attempts", 2, attempts.size)
        assertNotNull("the first hop is the configured origin and must get the certificate",
            attempts[0].peer)
        assertNull("a different host must not receive the certificate", attempts[1].peer)
        assertTrue("the redirected host should have refused the empty client auth",
            attempts[1].handshakeFailed)
        assertEquals("the refusal is a named reason, not a silent no-op; lines=$lines",
            MtlsConfig.Reason.ORIGIN_MISMATCH, MtlsConfig.failureFor(origin))
    }

    private companion object {
        const val PASSWORD = "test-only"
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 5000
        const val HTTP_OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
    }
}
