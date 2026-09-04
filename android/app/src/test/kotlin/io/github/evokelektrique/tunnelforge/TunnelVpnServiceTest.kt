package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TunnelVpnServiceTest {

    // Upstream TunnelForge added this application to the inclusive list, and
    // these two tests asserted it. SPEC 3.1 asks for the opposite and the
    // project owner confirmed that choice, so they now assert the exclusion.
    @Test
    fun effectiveInclusivePackagesLeavesOutTunnelForgePackage() {
        val effective =
            TunnelVpnService.effectiveInclusivePackages(
                splitTunnelEnabled = true,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
                inclusivePackages = listOf(" com.example.alpha ", "com.example.beta"),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(listOf("com.example.alpha", "com.example.beta"), effective)
    }

    @Test
    fun effectiveInclusivePackagesDropsTunnelForgePackageEvenWhenSelected() {
        val effective =
            TunnelVpnService.effectiveInclusivePackages(
                splitTunnelEnabled = true,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
                inclusivePackages =
                    listOf(
                        "io.github.evokelektrique.tunnelforge",
                        "com.example.alpha",
                        "io.github.evokelektrique.tunnelforge",
                    ),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(listOf("com.example.alpha"), effective)
    }

    @Test
    fun effectiveInclusivePackagesIsEmptyWhenDisabled() {
        val effective =
            TunnelVpnService.effectiveInclusivePackages(
                splitTunnelEnabled = false,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
                inclusivePackages = listOf("com.example.alpha"),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(emptyList<String>(), effective)
    }

    @Test
    fun requestedExclusivePackagesTrimsDedupesAndSkipsSelf() {
        val requested =
            TunnelVpnService.requestedExclusivePackages(
                splitTunnelEnabled = true,
                splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE,
                exclusivePackages =
                    listOf(
                        " com.example.alpha ",
                        "io.github.evokelektrique.tunnelforge",
                        "com.example.alpha",
                        "com.example.beta",
                    ),
                selfPackageName = "io.github.evokelektrique.tunnelforge",
            )

        assertEquals(listOf("com.example.alpha", "com.example.beta"), requested)
    }

    @Test
    fun localProxyRuntimeConfigKeepsClientCapacityUnlimited() {
        val config =
            ProxyRuntimeConfig(
                httpEnabled = true,
                httpPort = 8080,
                socksEnabled = true,
                socksPort = 1080,
                maxConcurrentClients = 1,
            )
        val exposure =
            ProxyExposureInfo.loopback(
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                lanRequested = false,
            )

        val runtimeConfig = TunnelVpnService.localProxyRuntimeConfig(config, exposure)

        assertNull(runtimeConfig.maxConcurrentClients)
        assertEquals(exposure, runtimeConfig.exposure)
    }

    // The negotiated servers now arrive as addresses from the engine's
    // TunnelParams rather than as resolved DNS configs read out of the intent.
    @Test
    fun manualVpnDnsAdvertisesVirtualResolver() {
        val dnsServers =
            TunnelVpnService.tunDnsServers(
                dnsAutomatic = false,
                protocol = TunnelProtocol.L2TP,
                negotiatedDnsServers = listOf(InetAddress.getByName("172.20.21.22")),
            )

        assertEquals(listOf(InetAddress.getByName(TunnelVpnService.MANUAL_DNS_VIRTUAL_IPV4)), dnsServers)
    }

    @Test
    fun automaticVpnDnsAdvertisesNegotiatedResolvers() {
        val negotiated = listOf(InetAddress.getByName("172.20.21.22"))

        val dnsServers =
            TunnelVpnService.tunDnsServers(
                dnsAutomatic = true,
                protocol = TunnelProtocol.L2TP,
                negotiatedDnsServers = negotiated,
            )

        assertEquals(negotiated, dnsServers)
    }

    // SSTP has no native poll loop to divert packets to a virtual resolver, so
    // manual DNS goes on the interface as the user's own servers (SPEC В.12).
    @Test
    fun manualSstpDnsAdvertisesTheUpstreamServersThemselves() {
        val dnsServers =
            TunnelVpnService.tunDnsServers(
                dnsAutomatic = false,
                protocol = TunnelProtocol.SSTP,
                negotiatedDnsServers = listOf(InetAddress.getByName("172.20.21.22")),
                manualDnsServers =
                    listOf(
                        ResolvedDnsServerConfig(
                            host = "9.9.9.9",
                            protocol = DnsProtocol.dnsOverUdp,
                            resolvedIpv4 = "9.9.9.9",
                        ),
                    ),
            )

        assertEquals(listOf(InetAddress.getByName("9.9.9.9")), dnsServers)
    }

    @Test
    fun reconnectBackoffDoublesAndThenStops() {
        val delays = (1..6).map { TunnelVpnService.reconnectDelayMs(it) }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun connectedNotificationNamesTheProtocolAndTheProfile() {
        assertEquals(
            "SSTP · Office",
            TunnelVpnService.notificationLabel(TunnelProtocol.SSTP, "Office", "vpn.example.org"),
        )
    }

    @Test
    fun connectedNotificationFallsBackToTheServer() {
        assertEquals(
            "L2TP/IPsec · vpn.example.org",
            TunnelVpnService.notificationLabel(TunnelProtocol.L2TP, null, "vpn.example.org"),
        )
    }

    @Test
    fun actionStopDoesNotEmitStoppedWhenTunnelServiceIsIdle() {
        assertFalse(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = false,
                hasEngine = false,
                hasTunInterface = false,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
    }

    @Test
    fun actionStopEmitsStoppedWhenTunnelServiceHasActiveState() {
        assertTrue(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = false,
                hasEngine = true,
                hasTunInterface = false,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
        assertTrue(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = false,
                hasEngine = false,
                hasTunInterface = true,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
        assertTrue(
            TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                running = false,
                hasSetupThread = true,
                hasEngine = false,
                hasTunInterface = false,
                hasDnsServer = false,
                hasLocalProxyRuntime = false,
            ),
        )
    }

    @Test
    fun staleStopRequestIsIgnoredWhenAttemptDoesNotMatchActiveAttempt() {
        assertTrue(
            VpnStopAttemptPolicy.shouldIgnoreStopRequest(
                requestedAttemptId = "attempt-old",
                activeAttemptId = "attempt-new",
            ),
        )
        assertFalse(
            VpnStopAttemptPolicy.shouldIgnoreStopRequest(
                requestedAttemptId = "attempt-current",
                activeAttemptId = "attempt-current",
            ),
        )
        assertFalse(
            VpnStopAttemptPolicy.shouldIgnoreStopRequest(
                requestedAttemptId = "",
                activeAttemptId = "attempt-current",
            ),
        )
    }
}
