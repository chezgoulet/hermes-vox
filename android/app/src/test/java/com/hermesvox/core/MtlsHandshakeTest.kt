package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class MtlsHandshakeTest {
    private val keyProvider = TestKeyProvider()

    private fun loadKeyStore(resource: String = "/tls/client-identities.p12", pass: String = "test-only"): KeyStore {
        return KeyStore.getInstance("PKCS12").apply {
            TestKeyProvider::class.java.getResourceAsStream(resource).use { ins ->
                load(requireNotNull(ins), pass.toCharArray())
            }
        }
    }

    private fun createServerContext(needClientAuth: Boolean = true): Pair<SSLContext, X509Certificate> {
        val ks = loadKeyStore()
        val serverCert = ks.getCertificate("server") as X509Certificate
        val clientCert = ks.getCertificate("first") as X509Certificate

        // Server KeyManager
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "test-only".toCharArray())

        // Server TrustManager trusting clientCert
        val ts = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("client-first", clientCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ts)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx to serverCert
    }

    private fun createClientTrustManager(serverCert: X509Certificate): X509TrustManager {
        val ts = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", serverCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ts)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Test
    fun testSuccessfulMtlsHandshakeWhenClientPresentsValidCert() {
        val (serverCtx, serverCert) = createServerContext(needClientAuth = true)
        val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        serverSocket.needClientAuth = true
        val port = serverSocket.localPort

        val peerCertRef = AtomicReference<X509Certificate>()
        val serverLatch = CountDownLatch(1)

        val serverThread = Thread({
            try {
                val socket = serverSocket.accept() as SSLSocket
                socket.use {
                    it.startHandshake()
                    val peers = it.session.peerCertificates
                    if (peers.isNotEmpty()) {
                        peerCertRef.set(peers[0] as X509Certificate)
                    }
                }
            } catch (e: Throwable) {
                // Ignore socket errors on test teardown
            } finally {
                serverLatch.countDown()
            }
        }, "test-mtls-server").apply { isDaemon = true; start() }

        try {
            val origin = MtlsConfig.Origin("localhost", port)
            val km = MtlsKeyManager("localhost", port, "first", keyProvider)
            val clientTrust = createClientTrustManager(serverCert)

            val clientCtx = SSLContext.getInstance("TLS")
            clientCtx.init(arrayOf(km), arrayOf(clientTrust), null)

            val clientSocket = clientCtx.socketFactory.createSocket("localhost", port) as SSLSocket
            clientSocket.use {
                it.startHandshake()
                assertTrue("Client TLS session should be valid", it.session.isValid)
            }

            assertTrue("Server should complete handshake within 5s", serverLatch.await(5, TimeUnit.SECONDS))
            assertNotNull("Server should have received client certificate", peerCertRef.get())
        } finally {
            serverSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testHandshakeFailsWhenClientHasNoCertificate() {
        val (serverCtx, serverCert) = createServerContext(needClientAuth = true)
        val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        serverSocket.needClientAuth = true
        val port = serverSocket.localPort

        val serverThread = Thread({
            try {
                val socket = serverSocket.accept() as SSLSocket
                socket.use {
                    it.startHandshake()
                    it.inputStream.read()
                }
            } catch (_: Throwable) {}
        }, "test-mtls-server-fail").apply { isDaemon = true; start() }

        try {
            // Client with empty alias (no cert)
            val km = MtlsKeyManager("localhost", port, "", keyProvider)
            val clientTrust = createClientTrustManager(serverCert)

            val clientCtx = SSLContext.getInstance("TLS")
            clientCtx.init(arrayOf(km), arrayOf(clientTrust), null)

            val clientSocket = clientCtx.socketFactory.createSocket("localhost", port) as SSLSocket
            try {
                clientSocket.use {
                    it.startHandshake()
                    it.outputStream.write("ping".toByteArray())
                    it.outputStream.flush()
                    it.inputStream.read()
                }
                fail("Handshake or data transfer should fail when server requires client auth but client provides none")
            } catch (e: Exception) {
                // Expected: SSLException or SocketException indicating auth failure
            }
        } finally {
            serverSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testCrossOriginRefusesToSendCertificate() {
        val (serverCtx, serverCert) = createServerContext(needClientAuth = true)
        val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        serverSocket.needClientAuth = true
        val port = serverSocket.localPort

        val serverThread = Thread({
            try {
                val socket = serverSocket.accept() as SSLSocket
                socket.use {
                    it.startHandshake()
                    it.inputStream.read()
                }
            } catch (_: Throwable) {}
        }, "test-mtls-server-cross").apply { isDaemon = true; start() }

        try {
            // Client configured for a DIFFERENT origin (port + 1)
            val km = MtlsKeyManager("localhost", port + 1, "first", keyProvider)
            val clientTrust = createClientTrustManager(serverCert)

            val clientCtx = SSLContext.getInstance("TLS")
            clientCtx.init(arrayOf(km), arrayOf(clientTrust), null)

            val clientSocket = clientCtx.socketFactory.createSocket("localhost", port) as SSLSocket
            try {
                clientSocket.use {
                    it.startHandshake()
                    it.outputStream.write("ping".toByteArray())
                    it.outputStream.flush()
                    it.inputStream.read()
                }
                fail("Handshake or data transfer should fail because key manager refuses to send cert to mismatched origin")
            } catch (e: Exception) {
                // Expected: server rejects handshake due to lack of client cert
            }
        } finally {
            serverSocket.close()
            serverThread.join(1000)
        }
    }
}
