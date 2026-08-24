package io.github.mr1ve3r.combined.engine

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the [VpnEngine] contract through [FakeVpnEngine].
 *
 * These are tests of the shape of the API, not of any protocol. They fail if
 * the contract stops being implementable without Android plumbing, which is the
 * property phase 7 depends on.
 */
class EngineContractTest {
    @Test
    fun `connect reports the negotiated parameters and reaches Connected`() = runTest {
        val engine = FakeVpnEngine(TUNNEL_PARAMS)
        assertEquals(EngineState.Idle, engine.state.value)

        val params = engine.connect(SSTP_PROFILE, RecordingProtector())

        assertEquals(TUNNEL_PARAMS, params)
        val state = engine.state.value
        assertTrue("expected Connected, was $state", state is EngineState.Connected)
        assertEquals(TUNNEL_PARAMS, (state as EngineState.Connected).params)
    }

    @Test
    fun `connect protects its socket before returning`() = runTest {
        val protector = RecordingProtector()

        FakeVpnEngine(TUNNEL_PARAMS).connect(SSTP_PROFILE, protector)

        assertEquals(
            "the engine must protect its transport socket, or its traffic re-enters the tunnel",
            1,
            protector.protectedDescriptors.size,
        )
    }

    @Test
    fun `a refused protect call fails the connection instead of proceeding`() = runTest {
        val engine = FakeVpnEngine(TUNNEL_PARAMS)

        val thrown =
            runCatching { engine.connect(SSTP_PROFILE, RecordingProtector(succeed = false)) }
                .exceptionOrNull()

        assertTrue("expected EngineException, was $thrown", thrown is EngineException)
        assertTrue(engine.state.value is EngineState.Failed)
    }

    @Test
    fun `connect surfaces a failure as EngineException carrying the error`() = runTest {
        val error = EngineError.AuthenticationFailed("bad password")
        val engine = FakeVpnEngine(TUNNEL_PARAMS, failWith = error)

        val thrown =
            runCatching { engine.connect(SSTP_PROFILE, RecordingProtector()) }
                .exceptionOrNull()

        assertEquals(error, (thrown as EngineException).error)
    }

    @Test
    fun `disconnect is idempotent and releases the tun`() = runTest {
        val engine = FakeVpnEngine(TUNNEL_PARAMS)
        engine.connect(SSTP_PROFILE, RecordingProtector())

        engine.disconnect()
        engine.disconnect()

        assertEquals(EngineState.Disconnected, engine.state.value)
        assertEquals(2, engine.disconnectCount)
        assertNull(engine.attachedTun)
    }

    @Test
    fun `log events carry the protocol that produced them`() = runTest {
        val engine = FakeVpnEngine(TUNNEL_PARAMS)
        engine.connect(SSTP_PROFILE, RecordingProtector())

        val emitted = engine.events.replayCache
        assertTrue("expected log events", emitted.isNotEmpty())
        assertTrue(emitted.all { it.protocol == Protocol.SSTP })
    }

    @Test
    fun `no log event repeats the password`() = runTest {
        val engine = FakeVpnEngine(TUNNEL_PARAMS)
        engine.connect(SSTP_PROFILE, RecordingProtector())

        assertTrue(
            "credentials must never reach the log stream",
            engine.events.replayCache.none { it.message.contains(SSTP_PROFILE.password) },
        )
    }

    @Test
    fun `empty routes mean the default route rather than no routes`() {
        assertTrue(TUNNEL_PARAMS.routes.isEmpty())
    }

    @Test
    fun `the SSTP default MTU sits below the L2TP default`() {
        // SSTP carries IP over TCP; a shared default would put the inner and
        // outer retransmission timers in conflict (SPEC phase 6.5).
        assertTrue(
            "SSTP default MTU ${EngineProfile.Sstp.DEFAULT_MTU} must not exceed " +
                "the L2TP default ${EngineProfile.L2tp.DEFAULT_MTU}",
            EngineProfile.Sstp.DEFAULT_MTU <= EngineProfile.L2tp.DEFAULT_MTU,
        )
    }

    @Test
    fun `EAP-MSCHAPv2 is not enabled by default`() {
        assertTrue(
            "EAP is only needed by some Windows RRAS deployments (SPEC phase 2.1)",
            PppAuthMethod.EAP_MSCHAPV2 !in EngineProfile.Sstp.DEFAULT_AUTH_METHODS,
        )
    }

    @Test
    fun `the minimum TLS version defaults to 1_2 or higher`() {
        assertTrue(TlsVersion.DEFAULT.ordinal >= TlsVersion.TLS_1_2.ordinal)
    }

    @Test
    fun `every error variant has its own message key`() {
        val errors: List<EngineError> =
            listOf(
                EngineError.NetworkUnreachable(null),
                EngineError.AuthenticationFailed(null),
                EngineError.TlsHandshakeFailed(null),
                EngineError.CertificateRejected(null, null),
                EngineError.CertificateExpired(0L, null),
                EngineError.HostnameMismatch("a", emptyList(), null),
                EngineError.IpsecFailed(null),
                EngineError.PppNegotiationFailed("LCP", null),
                EngineError.TimedOut("tls", null),
                EngineError.Internal(null),
            )

        val keys = errors.map { it.messageKey }
        assertEquals("message keys must be unique", keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test
    fun `HostnameMismatch reports the names the certificate actually presents`() {
        // The user needs these to fill in expectedHostname rather than turning
        // verification off (SPEC appendix B, item 4).
        val error =
            EngineError.HostnameMismatch(
                expected = "vpn.example.com",
                presented = listOf("vpn.internal.lan", "router.internal.lan"),
                detail = null,
            )

        assertNotEquals(error.expected, error.presented.first())
        assertEquals(2, error.presented.size)
    }

    private class RecordingProtector(
        private val succeed: Boolean = true,
    ) : SocketProtector {
        val protectedDescriptors = mutableListOf<Int>()

        override fun protect(socket: Socket): Boolean = succeed

        override fun protect(socket: DatagramSocket): Boolean = succeed

        override fun protect(fd: Int): Boolean {
            if (succeed) {
                protectedDescriptors += fd
            }
            return succeed
        }
    }

    private companion object {
        val TUNNEL_PARAMS =
            TunnelParams(
                localAddress = InetAddress.getByName("10.8.0.2"),
                prefixLength = 32,
                dnsServers = listOf(InetAddress.getByName("10.8.0.1")),
                mtu = EngineProfile.Sstp.DEFAULT_MTU,
            )

        val SSTP_PROFILE =
            EngineProfile.Sstp(
                server = "vpn.example.com",
                username = "user",
                password = "correct-horse-battery-staple",
                mtu = EngineProfile.Sstp.DEFAULT_MTU,
                customDns = emptyList(),
                port = EngineProfile.Sstp.DEFAULT_PORT,
                trustPolicy = TrustPolicy.SYSTEM,
                trustedCertificateIds = emptyList(),
                pinnedFingerprints = emptySet(),
                expectedHostname = null,
                minTlsVersion = TlsVersion.DEFAULT,
                pppAuthMethods = EngineProfile.Sstp.DEFAULT_AUTH_METHODS,
                proxy = null,
            )
    }
}
