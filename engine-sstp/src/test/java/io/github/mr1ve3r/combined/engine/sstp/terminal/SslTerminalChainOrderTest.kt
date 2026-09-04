package io.github.mr1ve3r.combined.engine.sstp.terminal

import io.github.mr1ve3r.combined.core.trust.TrustManagerFactoryProvider
import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.sstp.RecordingSocketProtector
import io.github.mr1ve3r.combined.engine.sstp.SstpEngineConfig
import io.github.mr1ve3r.combined.engine.sstp.sstpProfile
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the engine reports when a server sends its chain in the wrong order.
 *
 * The two orders need opposite fixes and the store cannot tell them apart on
 * its own, so this pins down which error the user actually sees.
 *
 * The server is driven by a hand-written [X509KeyManager] rather than by
 * handing a `PKCS12` file to `KeyManagerFactory`, because that path sorts the
 * chain into issuer order as it reads it -- which would quietly repair the one
 * thing under test.
 */
class SslTerminalChainOrderTest {
    private var server: SSLServerSocket? = null
    private var accepting: Thread? = null

    @After
    fun tearDown() {
        runCatching { server?.close() }
        accepting?.join(JOIN_MS)
    }

    /**
     * The ordinary case, and the control for the test below: leaf first, CA
     * second, anchored on the CA. Trust and name both check out.
     */
    @Test
    fun `a chain led by the leaf is trusted and covers the hostname`() {
        val port = startTlsServer(chain = arrayOf(leaf, ca), signingKey = leafKey)

        val terminal = terminal(port, anchoredOn = ca)
        try {
            terminal.establish(GUID)
        } finally {
            terminal.close()
        }
    }

    /**
     * A chain physically sent in the wrong order never reaches the trust store
     * at all.
     *
     * TLS requires the server's private key to match `certificate_list[0]`, so
     * a server that serves its leaf's key behind its CA's certificate fails in
     * `CertificateVerify`, inside the handshake, before any certificate this
     * application holds is consulted. The practical consequence is that "the
     * server sent its chain backwards" is not a diagnosis the certificate store
     * can act on -- and not one that path building could repair either.
     */
    @Test
    fun `a physically reordered chain fails inside the handshake`() {
        val port = startTlsServer(chain = arrayOf(ca, leaf), signingKey = leafKey)

        val failure = failureFrom(terminal(port, anchoredOn = ca))

        assertTrue("expected a TLS-level failure, got $failure", failure is EngineError.TlsHandshakeFailed)
    }

    /**
     * The case that does reach us: a server configured with its certificate
     * authority *as* its server certificate, key and all.
     *
     * `chain[0]` is then the CA, PKIX accepts it -- it is itself a trust anchor
     * -- and the refusal lands in hostname verification, because a CA is not
     * issued to the server's name. This is why path building alone cannot fix
     * this: TLS binds the server to `chain[0]`, so treating some other element
     * as the "real" leaf would accept a key the server never proved it holds.
     * The fix belongs on the server, and the client's job is to say which.
     */
    @Test
    fun `a server serving its own CA is refused for its name, not its trust`() {
        val port = startTlsServer(chain = arrayOf(ca), signingKey = caKey)

        val failure = failureFrom(terminal(port, anchoredOn = ca))

        assertTrue("expected a hostname mismatch, got $failure", failure is EngineError.HostnameMismatch)
        val mismatch = failure as EngineError.HostnameMismatch
        assertEquals(LOOPBACK, mismatch.expected)
        assertTrue(
            "the error should name what the certificate does cover: ${mismatch.presented}",
            mismatch.presented.any { "Chain Test CA" in it },
        )
        // Without this sentence the error reads as though the client were at
        // fault, and the user goes looking in the certificate store for a
        // problem that is in the server's configuration.
        assertTrue(
            "the error should say the server served its CA: ${mismatch.detail}",
            mismatch.detail?.contains("configured with its CA") == true,
        )
    }

    /** Runs [terminal] to completion and returns the error it refused with. */
    private fun failureFrom(terminal: SslTerminal): EngineError = try {
        terminal.establish(GUID)
        error("expected the connection to be refused")
    } catch (e: EngineException) {
        e.error
    } finally {
        terminal.close()
    }

    private fun terminal(port: Int, anchoredOn: X509Certificate) = SslTerminal(
        config = configFor(port),
        protector = RecordingSocketProtector(),
        trustManager = TrustManagerFactoryProvider.pkixTrustManager(listOf(anchoredOn)),
        log = { _, _ -> },
        socketFactory = { Socket() },
    )

    private fun configFor(port: Int): SstpEngineConfig {
        val base = SstpEngineConfig.of(sstpProfile())
        return SstpEngineConfig(
            server = LOOPBACK,
            port = port,
            username = base.username,
            password = base.password,
            mtu = base.mtu,
            mru = base.mru,
            customDns = base.customDns,
            expectedHostname = null,
            minTlsVersion = TlsVersion.DEFAULT,
            authMethods = base.authMethods,
            proxy = null,
            trustPolicy = base.trustPolicy,
            trustedCertificateIds = base.trustedCertificateIds,
            pinnedFingerprints = base.pinnedFingerprints,
        )
    }

    /**
     * Starts a TLS server presenting [chain] verbatim and proving possession of
     * [signingKey], and returns its port.
     *
     * The two are separate parameters on purpose: pairing a chain with a key
     * that does not match its first element is exactly the misconfiguration
     * [a physically reordered chain fails inside the handshake] is about.
     */
    private fun startTlsServer(chain: Array<X509Certificate>, signingKey: PrivateKey): Int {
        val context =
            SSLContext.getInstance("TLS").apply {
                init(arrayOf<KeyManager>(FixedChainKeyManager(chain, signingKey)), arrayOf<X509TrustManager>(), null)
            }
        val socket =
            (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) as SSLServerSocket)
                .also { server = it }

        accepting =
            thread {
                runCatching {
                    (socket.accept() as SSLSocket).use { peer ->
                        peer.startHandshake()
                        // Answer SSTP_DUPLEX_POST so the control case can get
                        // all the way through `establish`. The reversed case
                        // never sends it -- the client gives up on the name
                        // first -- and the read simply ends.
                        readHead(peer)
                        peer.outputStream.write(SSTP_OK.toByteArray(Charsets.US_ASCII))
                        peer.outputStream.flush()
                        peer.inputStream.read()
                    }
                }
            }
        return socket.localPort
    }

    /** Reads up to and including the blank line that ends an HTTP head. */
    private fun readHead(peer: SSLSocket) {
        val head = StringBuilder()
        while (!head.endsWith(HTTP_SUFFIX) && head.length < MAX_HEAD) {
            val next = peer.inputStream.read()
            if (next < 0) return
            head.append(next.toChar())
        }
    }

    /**
     * Presents exactly the chain it was given, in exactly that order.
     *
     * Every `KeyManagerFactory` path builds the chain from a `KeyStore` and
     * sorts it on the way. This one does not.
     */
    private class FixedChainKeyManager(
        private val chain: Array<X509Certificate>,
        private val privateKey: PrivateKey,
    ) : X509KeyManager {
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

        override fun getPrivateKey(alias: String?): PrivateKey = privateKey

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private companion object {
        const val ALIAS = "server"
        const val CA_ALIAS = "ca"
        const val LOOPBACK = "127.0.0.1"
        const val JOIN_MS = 2_000L
        const val STORE_PASSWORD = "changeit"
        const val GUID = "00000000-0000-0000-0000-000000000000"
        const val HTTP_SUFFIX = "\r\n\r\n"
        const val MAX_HEAD = 8 * 1024
        const val SSTP_OK = "HTTP/1.1 200 OK\r\nContent-Length: 18446744073709551615\r\n\r\n"

        /**
         * The fixture keystore, read once.
         *
         * Two private-key entries: `server` holds the leaf and its key, `ca`
         * holds the issuing CA and *its* key -- which is what a server
         * configured with its own certificate authority actually has.
         */
        private val keystore: KeyStore by lazy {
            KeyStore.getInstance("PKCS12").apply {
                requireNotNull(SslTerminalChainOrderTest::class.java.getResourceAsStream("/certs/chain-server.p12")) {
                    "missing fixture: chain-server.p12"
                }.use { load(it, STORE_PASSWORD.toCharArray()) }
            }
        }

        private val storedChain: List<X509Certificate> by lazy {
            keystore.getCertificateChain(ALIAS).map { it as X509Certificate }
        }

        private fun keyOf(alias: String): PrivateKey = keystore.getKey(alias, STORE_PASSWORD.toCharArray()) as PrivateKey

        val leaf: X509Certificate get() = storedChain.first { it.basicConstraints < 0 }
        val ca: X509Certificate get() = storedChain.first { it.basicConstraints >= 0 }
        val leafKey: PrivateKey get() = keyOf(ALIAS)
        val caKey: PrivateKey get() = keyOf(CA_ALIAS)
    }
}
