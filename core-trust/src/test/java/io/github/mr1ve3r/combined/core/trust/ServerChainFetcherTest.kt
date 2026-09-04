package io.github.mr1ve3r.combined.core.trust

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download-from-server import path (SPEC 5.3 C), against a TLS server on
 * the loopback interface.
 *
 * The server is a plain `SSLServerSocket` holding the `tls-server.p12` fixture:
 * a self-signed certificate no trust store anywhere knows, which is exactly the
 * case this path exists for.
 */
class ServerChainFetcherTest {
    private var server: SSLServerSocket? = null
    private var accepting: Thread? = null

    @After
    fun tearDown() {
        runCatching { server?.close() }
        accepting?.join(JOIN_TIMEOUT_MILLIS)
    }

    @Test
    fun `the chain a server presents is captured`() = runBlocking {
        val port = startTlsServer()

        val chain = ServerChainFetcher().fetch(LOOPBACK, port)

        assertEquals(1, chain.size)
        assertTrue("CN=fetcher.test" in chain.single().subjectX500Principal.name)
    }

    @Test
    fun `the captured chain is what the user is asked to compare fingerprints against`() = runBlocking {
        val port = startTlsServer()

        val chain = ServerChainFetcher().fetch(LOOPBACK, port)

        val expected = CertificateFingerprint.sha256(CertificateParser.parse(keystoreCertificate()).single())
        assertEquals(expected, CertificateFingerprint.sha256(chain.single()))
    }

    @Test
    fun `a port that speaks no TLS fails without a chain to show`() {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { plain ->
            accepting = thread { runCatching { plain.accept().close() } }

            assertThrows(IOException::class.java) {
                runBlocking { ServerChainFetcher(timeoutMillis = SHORT_TIMEOUT_MILLIS).fetch(LOOPBACK, plain.localPort) }
            }
        }
    }

    @Test
    fun `an unreachable address fails instead of hanging`() {
        val closed = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).also { it.close() }

        assertThrows(IOException::class.java) {
            runBlocking { ServerChainFetcher(timeoutMillis = SHORT_TIMEOUT_MILLIS).fetch(LOOPBACK, closed.localPort) }
        }
    }

    /** Starts a TLS server that accepts one connection and returns its port. */
    private fun startTlsServer(): Int {
        val context = SSLContext.getInstance("TLS").apply {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keystore(), KEYSTORE_PASSWORD)
            }
            init(keyManagers.keyManagers, null, null)
        }
        val socket = (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) as SSLServerSocket)
        server = socket
        // The handshake has to be driven from this side too: `accept` returns
        // before any TLS record has moved, so a server that accepts and closes
        // never sends the certificate the fetcher is here for.
        accepting = thread {
            runCatching { (socket.accept() as SSLSocket).use(SSLSocket::startHandshake) }
        }
        return socket.localPort
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        TestCertificates.bytesOf("tls-server.p12").inputStream().use { load(it, KEYSTORE_PASSWORD) }
    }

    private fun keystoreCertificate(): ByteArray = keystore().getCertificate(KEYSTORE_ALIAS).encoded

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val KEYSTORE_ALIAS = "tls-server"
        const val SHORT_TIMEOUT_MILLIS = 2_000
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        val KEYSTORE_PASSWORD = "changeit".toCharArray()
    }
}
