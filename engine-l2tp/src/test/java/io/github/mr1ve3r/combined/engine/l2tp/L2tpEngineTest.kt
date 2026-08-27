package io.github.mr1ve3r.combined.engine.l2tp

import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.EngineLogEvent
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.EngineState
import io.github.mr1ve3r.combined.engine.LogLevel
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.Route
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [L2tpEngine] against a scripted native layer.
 *
 * What these check is the contract of SPEC phase 4: negotiation returns
 * [io.github.mr1ve3r.combined.engine.TunnelParams] instead of touching the
 * interface, the native layer's sockets go through the supplied protector, exit
 * codes become errors, and the lifecycle runs Connecting → Connected →
 * Disconnected exactly once.
 */
class L2tpEngineTest {
    @Test
    fun connectReturnsWhatTheServerAgreedTo() = runTest {
        val native = FakeL2tpNative(clientIpv4 = intArrayOf(10, 8, 0, 42))
        val engine = engineOver(native)

        val params = engine.connect(profile(server = "203.0.113.9", mtu = 1400), RecordingSocketProtector())

        assertEquals(InetAddress.getByName("10.8.0.42"), params.localAddress)
        assertEquals(32, params.prefixLength)
        assertEquals(listOf(InetAddress.getByName("10.8.0.1")), params.dnsServers)
        assertEquals(1400, params.mtu)
        // The tunnel carries no routes of its own: empty means the default route.
        assertEquals(emptyList<Route>(), params.routes)
        assertEquals(EngineState.Connecting(L2tpEngine.STAGE_AWAITING_TUN), engine.state.value)
    }

    // Appendix Б: routing the transport back into the tunnel it carries is the
    // loop that wedges a connection. The engine is the only layer that knows
    // which peer that is.
    @Test
    fun connectExcludesTheGatewayFromTheTunnel() = runTest {
        val engine = engineOver(FakeL2tpNative())

        val params = engine.connect(profile(server = "203.0.113.9"), RecordingSocketProtector())

        assertEquals(listOf(Route(InetAddress.getByName("203.0.113.9"), 32)), params.excludedRoutes)
    }

    @Test
    fun connectFallsBackToALocalAddressWhenIpcpAssignsNone() = runTest {
        val engine = engineOver(FakeL2tpNative(clientIpv4 = IntArray(4)))

        val params = engine.connect(profile(), RecordingSocketProtector())

        assertEquals(InetAddress.getByName(L2tpEngine.FALLBACK_LOCAL_IPV4), params.localAddress)
    }

    @Test
    fun connectTurnsOnSocketProtectionAndPassesTheProfileThrough() = runTest {
        val native = FakeL2tpNative()
        val engine = engineOver(native)

        engine.connect(
            profile(server = "vpn.example", user = "alice", password = "s3cret", psk = "shared", mtu = 1380),
            RecordingSocketProtector(),
        )

        assertEquals(listOf(true), native.protectionCalls)
        assertEquals(
            FakeL2tpNative.NegotiateArgs("vpn.example", "alice", "s3cret", "shared", 1380),
            native.negotiateArgs,
        )
    }

    @Test
    fun aFailedNegotiationSurfacesAsAMappedEngineError() = runTest {
        val engine = engineOver(FakeL2tpNative(negotiateResult = L2tpExitCode.IKE_FAILED))

        val thrown =
            try {
                engine.connect(profile(), RecordingSocketProtector())
                fail("connect should have thrown")
                return@runTest
            } catch (e: EngineException) {
                e
            }

        assertTrue(thrown.error is EngineError.IpsecFailed)
        assertEquals(EngineState.Failed(thrown.error), engine.state.value)
    }

    // A stop that lands before the tunnel exists is not a failure, and there is
    // no engine state for it, so it surfaces as cancellation.
    @Test
    fun aStopDuringNegotiationCancelsRatherThanFails() = runTest {
        val engine = engineOver(FakeL2tpNative(negotiateResult = L2tpExitCode.STOPPED))

        try {
            engine.connect(profile(), RecordingSocketProtector())
            fail("connect should have been cancelled")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(EngineState.Disconnected, engine.state.value)
    }

    @Test
    fun theTunnelRunsFromAttachToDisconnect() = runTest {
        val native = FakeL2tpNative(loopResult = L2tpExitCode.STOPPED)
        val engine = engineOver(native)
        val params = engine.connect(profile(), RecordingSocketProtector())

        engine.attachTunDescriptor(tunFd = 77) {}
        assertTrue(native.loopEntered.await(5, TimeUnit.SECONDS))
        assertEquals(77, native.loopFd)

        L2tpNativeCallbacks.tunnelReady("tunnel loop active")
        val connected = engine.awaitState<EngineState.Connected>()
        assertSame(params, connected.params)

        engine.disconnect()

        assertEquals(1, native.stopCalls)
        assertEquals(EngineState.Disconnected, engine.state.value)
    }

    @Test
    fun aLoopThatDiesReportsTheMappedFailure() = runTest {
        val native = FakeL2tpNative(loopResult = L2tpExitCode.POLL_ERROR)
        val engine = engineOver(native)
        engine.connect(profile(), RecordingSocketProtector())

        engine.attachTunDescriptor(tunFd = 5) {}
        assertTrue(native.loopEntered.await(5, TimeUnit.SECONDS))
        native.releaseLoop()

        val failed = engine.awaitState<EngineState.Failed>()
        assertTrue(failed.error is EngineError.NetworkUnreachable)
    }

    // A tunnel that already failed must not be relabelled as a clean shutdown by
    // the disconnect that follows it: the host reports one terminal state.
    @Test
    fun disconnectDoesNotOverwriteAFailure() = runTest {
        val native = FakeL2tpNative(loopResult = L2tpExitCode.PPP_FAILED)
        val engine = engineOver(native)
        engine.connect(profile(), RecordingSocketProtector())
        engine.attachTunDescriptor(tunFd = 5) {}
        assertTrue(native.loopEntered.await(5, TimeUnit.SECONDS))
        native.releaseLoop()
        engine.awaitState<EngineState.Failed>()

        engine.disconnect()

        assertTrue(engine.state.value is EngineState.Failed)
    }

    @Test
    fun disconnectIsSafeBeforeConnectAndMoreThanOnce() = runTest {
        val native = FakeL2tpNative()
        val engine = engineOver(native)

        engine.disconnect()
        engine.disconnect()

        assertEquals(EngineState.Disconnected, engine.state.value)
        assertEquals(1, native.stopCalls)
    }

    // SPEC 4.1.2: the scattered protect() calls become this one path.
    @Test
    fun nativeSocketsGoThroughTheProtectorTheHostSupplied() = runTest {
        val protector = RecordingSocketProtector()
        val engine = engineOver(FakeL2tpNative())
        engine.connect(profile(), protector)

        assertEquals(true, L2tpNativeCallbacks.protect(31))

        assertEquals(listOf(31), protector.protectedFds)
        engine.disconnect()
    }

    @Test
    fun protectionReportsNoInstalledEngineOnceDisconnected() = runTest {
        val engine = engineOver(FakeL2tpNative())
        engine.connect(profile(), RecordingSocketProtector())
        engine.disconnect()

        assertNull(L2tpNativeCallbacks.protect(31))
    }

    @Test
    fun aFailedProtectIsReportedRatherThanSwallowed() = runTest {
        val engine = engineOver(FakeL2tpNative())
        val events = engine.recordEvents()
        engine.connect(profile(), RecordingSocketProtector(result = false))

        assertEquals(false, L2tpNativeCallbacks.protect(31))

        yield()
        assertTrue(events.entries.any { it.level == LogLevel.WARN && it.message.contains("protect() failed") })
        engine.disconnect()
    }

    // SPEC 4.1.5: native log lines join the engine's own stream, tagged L2TP.
    @Test
    fun nativeLogLinesArePublishedAsEngineEvents() = runTest {
        val engine = engineOver(FakeL2tpNative())
        val events = engine.recordEvents()
        engine.connect(profile(), RecordingSocketProtector())

        L2tpNativeCallbacks.nativeLog(ANDROID_LOG_WARN, "ipsec", "QM2 retransmit")

        yield()
        val native = events.entries.single { it.tag == "ipsec" }
        assertEquals(Protocol.L2TP, native.protocol)
        assertEquals(LogLevel.WARN, native.level)
        assertEquals("QM2 retransmit", native.message)
        assertTrue(events.entries.all { it.protocol == Protocol.L2TP })
        engine.disconnect()
    }

    @Test
    fun anSstpProfileIsRejected() = runTest {
        val engine = engineOver(FakeL2tpNative())

        try {
            engine.connect(sstpProfile(), RecordingSocketProtector())
            fail("an SSTP profile should not be accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Sstp"))
        }
    }

    @Test
    fun unsupportedNativeSettingsAreCalledOutRatherThanIgnoredSilently() = runTest {
        val engine = engineOver(FakeL2tpNative())
        val events = engine.recordEvents()

        engine.connect(
            profile().copy(localIdentifier = "gw@example", phase1Proposals = listOf("aes256-sha1-modp2048")),
            RecordingSocketProtector(),
        )

        yield()
        assertTrue(events.entries.any { it.message.contains("localIdentifier is not supported") })
        assertTrue(events.entries.any { it.message.contains("IKE proposals are not supported") })
        assertFalse(events.entries.any { it.message.contains("ipsecEnabled") })
    }

    private fun engineOver(native: FakeL2tpNative): L2tpEngine = L2tpEngine(native = native, dispatcher = Dispatchers.IO)

    /**
     * Waits for the engine to reach a state of type [T].
     *
     * The poll loop runs on its own thread, so a state change can land after the
     * call that triggered it has returned. This waits on real time rather than
     * the test scheduler's virtual clock, because the thread it is waiting for
     * is a real one.
     */
    private suspend inline fun <reified T : EngineState> L2tpEngine.awaitState(): T = withContext(Dispatchers.Default) {
        withTimeout(STATE_TIMEOUT_MS) { state.first { it is T } as T }
    }

    /**
     * Subscribes to [L2tpEngine.events] before the engine emits anything.
     *
     * The stream is hot with no replay, so a collector started after `connect`
     * would see none of the events that connecting produces.
     */
    private fun L2tpEngine.recordEvents(): EventLog {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val entries = mutableListOf<EngineLogEvent>()
        scope.launch { events.collect { entries += it } }
        return EventLog(scope, entries)
    }

    private class EventLog(
        private val scope: CoroutineScope,
        val entries: List<EngineLogEvent>,
    ) {
        fun close() = scope.cancel()
    }

    private fun profile(
        server: String = "203.0.113.9",
        user: String = "user",
        password: String = "password",
        psk: String = "psk",
        mtu: Int = 1400,
    ): EngineProfile.L2tp = EngineProfile.L2tp(
        server = server,
        username = user,
        password = password,
        mtu = mtu,
        customDns = emptyList(),
        ipsecEnabled = true,
        presharedKey = psk,
        localIdentifier = null,
        phase1Proposals = emptyList(),
        phase2Proposals = emptyList(),
    )

    private fun sstpProfile(): EngineProfile.Sstp = EngineProfile.Sstp(
        server = "203.0.113.9",
        username = "user",
        password = "password",
        mtu = EngineProfile.Sstp.DEFAULT_MTU,
        customDns = emptyList(),
        port = EngineProfile.Sstp.DEFAULT_PORT,
        trustPolicy = TrustPolicy.SYSTEM,
        trustedCertificateIds = emptyList(),
        pinnedFingerprints = emptySet(),
        expectedHostname = null,
        minTlsVersion = TlsVersion.TLS_1_2,
        pppAuthMethods = setOf(PppAuthMethod.MSCHAPV2),
        proxy = null,
    )

    private companion object {
        const val STATE_TIMEOUT_MS = 10_000L
        const val ANDROID_LOG_WARN = 5
    }
}
