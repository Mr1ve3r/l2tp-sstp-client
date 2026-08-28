package io.github.mr1ve3r.combined.engine.sstp.terminal

import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.ProxyConfig
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.sstp.RecordingSocketProtector
import io.github.mr1ve3r.combined.engine.sstp.SstpEngineConfig
import io.github.mr1ve3r.combined.engine.sstp.sstpProfile
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The transport half of [SslTerminal]: the socket and the HTTP proxy.
 *
 * TLS is deliberately out of scope here — everything below the handshake is
 * where the failures that only appear on a device live, and none of it needs a
 * certificate to exercise.
 */
class SslTerminalTransportTest {
    private lateinit var server: ServerSocket
    private var serverThread: Thread? = null

    /** What the fake peer received, so a test can check what was actually sent. */
    private val received = StringBuilder()

    @Before
    fun startServer() {
        server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    }

    @After
    fun stopServer() {
        serverThread?.interrupt()
        server.close()
    }

    @Test
    fun `protects the socket before it is connected`() {
        acceptAndReply(null)
        val protector = RecordingSocketProtector()

        terminal(config(), protector).connectTransportSocket().close()

        assertEquals(1, protector.protectedSockets.size)
        // Protection applied to a connected socket is protection applied too
        // late: the traffic is already routed, and the tunnel wedges.
        assertFalse(protector.connectedWhenProtected.single())
    }

    @Test
    fun `protects every socket, so a reconnect does not slip through`() {
        acceptAndReply(null, times = 3)
        val protector = RecordingSocketProtector()
        val settings = config()

        repeat(3) { terminal(settings, protector).connectTransportSocket().close() }

        assertEquals(3, protector.protectedSockets.size)
        assertTrue(protector.connectedWhenProtected.none { it })
        assertEquals(3, protector.protectedSockets.distinct().size)
    }

    @Test
    fun `protects the socket to the proxy too`() {
        acceptAndReply("HTTP/1.1 200 Connection established")
        val protector = RecordingSocketProtector()

        terminal(config(proxy = ProxyConfig(loopback, server.localPort, null, null)), protector)
            .connectTransportSocket()
            .close()

        // The socket goes to the proxy, so the proxy is the peer that has to
        // stay outside the tunnel. Missing this is the classic SSTP-through-a-
        // proxy loop.
        assertEquals(1, protector.protectedSockets.size)
        assertFalse(protector.connectedWhenProtected.single())
    }

    @Test
    fun `refuses to connect a socket protection would not cover`() {
        acceptAndReply(null)
        val protector = RecordingSocketProtector(result = false)

        val error = errorFrom { terminal(config(), protector).connectTransportSocket() }

        assertTrue(error is EngineError.Internal)
    }

    @Test
    fun `asks the proxy for the server, not for itself`() {
        acceptAndReply("HTTP/1.1 200 Connection established")

        terminal(config(proxy = ProxyConfig(loopback, server.localPort, null, null)), RecordingSocketProtector())
            .connectTransportSocket()
            .close()

        val request = awaitRequest()
        assertTrue(request, request.startsWith("CONNECT vpn.example.test:443 HTTP/1.1"))
        assertTrue(request, request.contains("Host: vpn.example.test:443"))
        assertFalse(request, request.contains("Proxy-Authorization"))
    }

    @Test
    fun `sends basic credentials only when the profile has them`() {
        acceptAndReply("HTTP/1.1 200 Connection established")

        terminal(
            config(proxy = ProxyConfig(loopback, server.localPort, "bob", "hunter2")),
            RecordingSocketProtector(),
        ).connectTransportSocket().close()

        // "bob:hunter2" base64-encoded.
        assertTrue(awaitRequest().contains("Proxy-Authorization: Basic Ym9iOmh1bnRlcjI="))
    }

    @Test
    fun `reports a rejected proxy password as an authentication failure`() {
        acceptAndReply("HTTP/1.1 407 Proxy Authentication Required")

        val error =
            errorFrom {
                terminal(
                    config(proxy = ProxyConfig(loopback, server.localPort, "bob", "wrong")),
                    RecordingSocketProtector(),
                ).connectTransportSocket()
            }

        // The point of checking the status line at all: upstream's alternative
        // is the caller waiting out a timeout with nothing to show the user.
        assertTrue(error.toString(), error is EngineError.AuthenticationFailed)
        assertFalse(error.detail.orEmpty().contains("wrong"))
    }

    @Test
    fun `reports any other proxy refusal without calling it an auth failure`() {
        acceptAndReply("HTTP/1.1 502 Bad Gateway")

        val error =
            errorFrom {
                terminal(config(proxy = ProxyConfig(loopback, server.localPort, null, null)), RecordingSocketProtector())
                    .connectTransportSocket()
            }

        assertTrue(error.toString(), error is EngineError.NetworkUnreachable)
    }

    @Test
    fun `reports an unreachable peer rather than hanging`() {
        val closed = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).also { it.close() }

        val error =
            errorFrom {
                terminal(config(port = closed.localPort), RecordingSocketProtector()).connectTransportSocket()
            }

        assertTrue(error.toString(), error is EngineError.NetworkUnreachable)
    }

    private val loopback: String get() = java.net.InetAddress.getLoopbackAddress().hostAddress ?: "127.0.0.1"

    private fun config(port: Int = 443, proxy: ProxyConfig? = null): SstpEngineConfig {
        val profile = sstpProfile(port = port, proxy = proxy)
        val base = SstpEngineConfig.of(profile)

        // Without a proxy the terminal dials the server itself, so point that at
        // the fake peer while leaving the name the proxy should be asked for.
        return if (proxy == null) {
            SstpEngineConfig(
                server = loopback,
                port = if (port == 443) server.localPort else port,
                username = base.username,
                password = base.password,
                mtu = base.mtu,
                mru = base.mru,
                customDns = base.customDns,
                expectedHostname = base.expectedHostname,
                minTlsVersion = TlsVersion.DEFAULT,
                authMethods = base.authMethods,
                proxy = null,
                trustPolicy = base.trustPolicy,
                trustedCertificateIds = base.trustedCertificateIds,
                pinnedFingerprints = base.pinnedFingerprints,
            )
        } else {
            base
        }
    }

    private fun terminal(config: SstpEngineConfig, protector: RecordingSocketProtector) = SslTerminal(
        config = config,
        protector = protector,
        trustManager = AcceptNothingTrustManager,
        log = { _, _ -> },
        socketFactory = { Socket() },
    )

    /**
     * Accepts [times] connections, reads a request head if one arrives and
     * answers with [response].
     */
    private fun acceptAndReply(response: String?, times: Int = 1) {
        serverThread =
            thread(isDaemon = true, name = "fake-sstp-peer") {
                repeat(times) {
                    try {
                        server.accept().use { peer ->
                            if (response == null) return@use

                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) {
                                val next = peer.getInputStream().read()
                                if (next < 0) return@use
                                head.append(next.toChar())
                            }
                            synchronized(received) { received.append(head) }

                            peer.getOutputStream().write("$response\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            peer.getOutputStream().flush()
                            // Held open until the client has read the answer.
                            Thread.sleep(SETTLE_MS)
                        }
                    } catch (_: IOException) {
                        return@thread
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            }
    }

    private fun awaitRequest(): String {
        serverThread?.join(JOIN_MS)
        return synchronized(received) { received.toString() }
    }

    private fun errorFrom(body: () -> Unit): EngineError {
        try {
            body()
        } catch (e: EngineException) {
            return e.error
        }
        fail("Expected an EngineException")
        error("unreachable")
    }

    private object AcceptNothingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        private const val SETTLE_MS = 200L
        private const val JOIN_MS = 2_000L
    }
}
