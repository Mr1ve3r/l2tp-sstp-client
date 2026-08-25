package io.github.mr1ve3r.combined.core.tunnel

import android.system.OsConstants
import io.github.mr1ve3r.combined.engine.Route
import io.github.mr1ve3r.combined.engine.TunnelParams
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the composition and ordering of the builder calls [TunnelBuilder]
 * makes, against a recording fake standing in for `VpnService.Builder`.
 */
class TunnelBuilderTest {
    @Test
    fun `a default profile reproduces the upstream call sequence`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, TunnelConfig(sessionName = "TunnelForge"))

        assertEquals(
            listOf(
                "setSession(TunnelForge)",
                "setMtu(1400)",
                "addAddress(10.8.0.2/32)",
                "addRoute(0.0.0.0/0)",
                "addDnsServer(10.8.0.1)",
            ),
            spec.calls,
        )
    }

    @Test
    fun `an empty route list becomes the default route`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS.copy(routes = emptyList()), CONFIG)

        assertTrue("addRoute(0.0.0.0/0)" in spec.calls)
    }

    @Test
    fun `explicit routes replace the default route rather than adding to it`() {
        val spec = RecordingTunnelInterface()
        val params =
            PARAMS.copy(
                routes =
                listOf(
                    Route(InetAddress.getByName("10.0.0.0"), 8),
                    Route(InetAddress.getByName("192.168.0.0"), 16),
                ),
            )

        TunnelBuilder().configure(spec, params, CONFIG)

        assertTrue("addRoute(10.0.0.0/8)" in spec.calls)
        assertTrue("addRoute(192.168.0.0/16)" in spec.calls)
        assertTrue("the default route must not be added alongside explicit ones", "addRoute(0.0.0.0/0)" !in spec.calls)
    }

    @Test
    fun `excluded routes are applied so the transport stays out of its own tunnel`() {
        val spec = RecordingTunnelInterface()
        val params = PARAMS.copy(excludedRoutes = listOf(Route(InetAddress.getByName("203.0.113.7"), 32)))

        TunnelBuilder().configure(spec, params, CONFIG)

        assertTrue("excludeRoute(203.0.113.7/32)" in spec.calls)
    }

    @Test
    fun `an exclusion the platform cannot honour is reported rather than swallowed`() {
        val events = mutableListOf<String>()
        val spec = RecordingTunnelInterface(supportsExcludeRoute = false)
        val params = PARAMS.copy(excludedRoutes = listOf(Route(InetAddress.getByName("203.0.113.7"), 32)))

        TunnelBuilder(onEvent = events::add).configure(spec, params, CONFIG)

        assertTrue(
            "the caller must learn it is relying on socket protection alone",
            events.any { "203.0.113.7" in it && "socket protection" in it },
        )
    }

    @Test
    fun `inclusive routing allows only the named packages`() {
        val spec = RecordingTunnelInterface(installedPackages = setOf("com.example.a", "com.example.b"))
        val config = CONFIG.copy(perAppRouting = PerAppRouting.Include(setOf("com.example.a", "com.example.b")))

        TunnelBuilder().configure(spec, PARAMS, config)

        assertEquals(
            listOf("addAllowedApplication(com.example.a)", "addAllowedApplication(com.example.b)"),
            spec.calls.filter { it.startsWith("addAllowedApplication") },
        )
        assertTrue(spec.calls.none { it.startsWith("addDisallowedApplication") })
    }

    @Test
    fun `exclusive routing disallows the named packages`() {
        val spec = RecordingTunnelInterface(installedPackages = setOf("com.example.a"))
        val config = CONFIG.copy(perAppRouting = PerAppRouting.Exclude(setOf("com.example.a")))

        TunnelBuilder().configure(spec, PARAMS, config)

        assertEquals(
            listOf("addDisallowedApplication(com.example.a)"),
            spec.calls.filter { it.startsWith("addDisallowedApplication") },
        )
        assertTrue(spec.calls.none { it.startsWith("addAllowedApplication") })
    }

    @Test
    fun `own package is left in the tunnel unless asked for, preserving upstream behaviour`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG)

        assertTrue(spec.calls.none { it.startsWith("addDisallowedApplication") })
    }

    @Test
    fun `own package is excluded when configured, alongside the profile's exclusions`() {
        val spec = RecordingTunnelInterface(installedPackages = setOf("com.example.a", OWN_PACKAGE))
        val config =
            CONFIG.copy(
                perAppRouting = PerAppRouting.Exclude(setOf("com.example.a")),
                excludeOwnPackage = OWN_PACKAGE,
            )

        TunnelBuilder().configure(spec, PARAMS, config)

        assertEquals(
            setOf("addDisallowedApplication(com.example.a)", "addDisallowedApplication($OWN_PACKAGE)"),
            spec.calls.filter { it.startsWith("addDisallowedApplication") }.toSet(),
        )
    }

    @Test
    fun `an uninstalled package in a routing rule is reported as such`() {
        val spec = RecordingTunnelInterface(installedPackages = setOf("com.example.a"))
        val config = CONFIG.copy(perAppRouting = PerAppRouting.Include(setOf("com.example.gone")))

        val thrown =
            assertThrows(PackageNotInstalledException::class.java) {
                TunnelBuilder().configure(spec, PARAMS, config)
            }

        assertEquals("com.example.gone", thrown.packageName)
    }

    @Test
    fun `setBlocking is not called unless the caller opts in`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG)

        assertTrue(
            "changing blocking mode alters the read semantics of the descriptor handed to the native loop",
            spec.calls.none { it.startsWith("setBlocking") },
        )
    }

    @Test
    fun `setBlocking is applied when the caller opts in`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG.copy(blocking = true))

        assertTrue("setBlocking(true)" in spec.calls)
    }

    @Test
    fun `underlying networks are left alone unless configured`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG)

        assertTrue(spec.calls.none { it.startsWith("setUnderlyingNetworks") })
    }

    @Test
    fun `the system default for underlying networks is requested with null`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG.copy(underlyingNetworks = UnderlyingNetworks.SystemDefault))

        assertTrue("setUnderlyingNetworks(null)" in spec.calls)
    }

    @Test
    fun `ipv4-only restricts the address family`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG.copy(ipv4Only = true))

        assertTrue("allowFamily(${OsConstants.AF_INET})" in spec.calls)
    }

    @Test
    fun `search domains and every dns server are applied in order`() {
        val spec = RecordingTunnelInterface()
        val params =
            PARAMS.copy(
                dnsServers = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("9.9.9.9")),
                searchDomains = listOf("corp.example", "lan"),
            )

        TunnelBuilder().configure(spec, params, CONFIG)

        assertEquals(
            listOf("addDnsServer(1.1.1.1)", "addDnsServer(9.9.9.9)", "addSearchDomain(corp.example)", "addSearchDomain(lan)"),
            spec.calls.filter { it.startsWith("addDnsServer") || it.startsWith("addSearchDomain") },
        )
    }

    @Test
    fun `address and mtu precede any route, as the platform expects`() {
        val spec = RecordingTunnelInterface()

        TunnelBuilder().configure(spec, PARAMS, CONFIG)

        val addressAt = spec.calls.indexOfFirst { it.startsWith("addAddress") }
        val mtuAt = spec.calls.indexOfFirst { it.startsWith("setMtu") }
        val firstRouteAt = spec.calls.indexOfFirst { it.startsWith("addRoute") }
        assertTrue(addressAt in 0 until firstRouteAt)
        assertTrue(mtuAt in 0 until firstRouteAt)
    }

    @Test
    fun `a revoked vpn permission surfaces as a typed failure`() {
        val spec = RecordingTunnelInterface()

        assertThrows(TunnelEstablishFailedException::class.java) {
            TunnelBuilder().build(spec, PARAMS, CONFIG)
        }

        assertTrue("establish()" in spec.calls)
    }

    private companion object {
        const val OWN_PACKAGE = "io.github.mr1ve3r.combined"

        val PARAMS =
            TunnelParams(
                localAddress = InetAddress.getByName("10.8.0.2"),
                prefixLength = 32,
                dnsServers = listOf(InetAddress.getByName("10.8.0.1")),
                mtu = 1400,
            )

        val CONFIG = TunnelConfig(sessionName = "TunnelForge")
    }
}
