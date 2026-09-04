package io.github.mr1ve3r.combined.core.tunnel

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import io.github.mr1ve3r.combined.engine.Route
import io.github.mr1ve3r.combined.engine.TunnelParams
import java.net.InetAddress

/**
 * Turns negotiated [TunnelParams] plus host [TunnelConfig] into a TUN device.
 *
 * This is the only place in the project that decides how a tunnel interface is
 * shaped, and it is shared by both protocols. Engines never see it: they report
 * what the server agreed to and receive the resulting descriptor through
 * `VpnEngine.attachTun`.
 *
 * Nothing here is protocol-aware, and that is load-bearing. If this class ever
 * needs to know whether it is building for L2TP or SSTP, the split between
 * engine and host has gone wrong.
 *
 * @property onEvent optional sink for diagnostics, so the caller can put them
 *   in its own log without this class depending on one.
 */
class TunnelBuilder(
    private val onEvent: (String) -> Unit = {},
) {
    /**
     * Applies [params] and [config] to [spec] and establishes the interface.
     *
     * @return the tunnel descriptor.
     * @throws PackageNotInstalledException if a per-app rule names a package
     *   that is not installed.
     * @throws TunnelEstablishFailedException if the platform declined to create
     *   the interface, which normally means the VPN permission was revoked.
     */
    fun build(spec: TunnelInterfaceSpec, params: TunnelParams, config: TunnelConfig): ParcelFileDescriptor {
        configure(spec, params, config)
        return spec.establish() ?: throw TunnelEstablishFailedException()
    }

    /**
     * Applies [params] and [config] to [spec] without establishing anything.
     *
     * Split out from [build] because this is where all the decisions are, and
     * because `ParcelFileDescriptor` cannot be created on the unit-test
     * classpath — a test that had to go through `establish()` could not check
     * the configuration at all.
     */
    fun configure(spec: TunnelInterfaceSpec, params: TunnelParams, config: TunnelConfig) {
        spec.setSession(config.sessionName)
        spec.setMtu(params.mtu)
        spec.addAddress(params.localAddress, params.prefixLength)

        applyRoutes(spec, params)

        params.dnsServers.forEach(spec::addDnsServer)
        params.searchDomains.forEach(spec::addSearchDomain)

        // Order below matches upstream TunnelForge exactly. The platform builder
        // accumulates configuration and only acts on establish(), so ordering
        // between unrelated calls is not load-bearing -- but keeping it
        // identical means a behaviour difference here can never be the
        // explanation for a regression.
        if (config.ipv4Only) {
            spec.allowFamily(OsConstants.AF_INET)
            onEvent("ipv4-only: allowFamily(AF_INET)")
        }

        applyExcludedRoutes(spec, params)
        applyPerAppRouting(spec, config)

        config.blocking?.let(spec::setBlocking)
        applyUnderlyingNetworks(spec, config)
    }

    private fun applyRoutes(spec: TunnelInterfaceSpec, params: TunnelParams) {
        // An empty route list means the default route, not "no routes". Getting
        // this backwards would silently build a tunnel that carries nothing.
        val routes = params.routes.ifEmpty { listOf(DEFAULT_ROUTE) }
        routes.forEach { spec.addRoute(it.address, it.prefixLength) }
    }

    private fun applyExcludedRoutes(spec: TunnelInterfaceSpec, params: TunnelParams) {
        params.excludedRoutes.forEach { route ->
            val address = "${route.address.hostAddress}/${route.prefixLength}"
            if (spec.excludeRoute(route.address, route.prefixLength)) {
                onEvent("excludeRoute $address so the transport stays outside the tunnel")
            } else {
                onEvent(
                    "excludeRoute unavailable below API 33; $address stays outside " +
                        "the tunnel through socket protection only",
                )
            }
        }
    }

    private fun applyPerAppRouting(spec: TunnelInterfaceSpec, config: TunnelConfig) {
        when (val routing = config.perAppRouting) {
            is PerAppRouting.AllApps -> {
                onEvent("per-app routing off: full-device tunnel")
                config.excludeOwnPackage?.let(spec::addDisallowedApplication)
            }

            is PerAppRouting.Include -> {
                // Self-exclusion is meaningless here: anything not named is
                // already outside the tunnel.
                onEvent("per-app routing inclusive: ${routing.packages.size} package(s)")
                routing.packages.forEach(spec::addAllowedApplication)
            }

            is PerAppRouting.Exclude -> {
                val packages =
                    config.excludeOwnPackage
                        ?.let { routing.packages + it }
                        ?: routing.packages
                onEvent("per-app routing exclusive: ${packages.size} package(s)")
                packages.forEach(spec::addDisallowedApplication)
            }
        }
    }

    private fun applyUnderlyingNetworks(spec: TunnelInterfaceSpec, config: TunnelConfig) {
        when (val networks = config.underlyingNetworks) {
            is UnderlyingNetworks.Unspecified -> Unit
            is UnderlyingNetworks.SystemDefault -> spec.setUnderlyingNetworks(null)
            is UnderlyingNetworks.Specific -> spec.setUnderlyingNetworks(networks.networks.toTypedArray())
        }
    }

    private companion object {
        /** 0.0.0.0/0 — built from raw bytes so it never touches a resolver. */
        val DEFAULT_ROUTE = Route(InetAddress.getByAddress(ByteArray(4)), 0)
    }
}

/** Thrown when `VpnService.Builder.establish()` returns `null`. */
class TunnelEstablishFailedException :
    Exception("Could not establish the tunnel interface; the VPN permission may have been revoked")
