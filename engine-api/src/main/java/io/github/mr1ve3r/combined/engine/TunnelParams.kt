package io.github.mr1ve3r.combined.engine

import java.net.InetAddress

/**
 * Tunnel parameters agreed with the server once the transport is up and the
 * protocol has finished negotiating — for both engines, the result of IPCP.
 *
 * An engine returns this from [VpnEngine.connect] instead of configuring the
 * interface itself. The host turns it into a TUN device and hands the file
 * descriptor back through [VpnEngine.attachTun]. Keeping the two steps apart is
 * what lets routing policy live in one place for both protocols.
 *
 * @property localAddress address assigned to this client by the server.
 * @property prefixLength prefix length for [localAddress].
 * @property dnsServers DNS servers proposed by the server, in preference order.
 *   The host may override these from the profile.
 * @property mtu negotiated MTU. SSTP carries IP over TCP and needs a lower
 *   value than L2TP; see [EngineProfile.Sstp.DEFAULT_MTU].
 * @property searchDomains DNS search domains, empty when the server proposed none.
 * @property routes routes to send through the tunnel. **Empty means the default
 *   route** `0.0.0.0/0`, not "no routes".
 * @property excludedRoutes addresses that must stay outside the tunnel — the
 *   peers this engine is actually talking to.
 *
 *   Only the engine knows what belongs here. For L2TP it is the VPN server; for
 *   SSTP through an HTTP proxy it is the *proxy*, since that is the host the
 *   socket connects to. Routing the transport back into the tunnel it carries
 *   is the loop described in appendix Б.
 *
 *   This is belt and braces: the sockets are already protected through
 *   [SocketProtector]. The exclusion additionally keeps the traffic off the
 *   tunnel interface where the platform supports it — `VpnService.Builder`
 *   gained `excludeRoute` in API 33, and below that the host silently relies on
 *   socket protection alone.
 */
data class TunnelParams(
    val localAddress: InetAddress,
    val prefixLength: Int,
    val dnsServers: List<InetAddress>,
    val mtu: Int,
    val searchDomains: List<String> = emptyList(),
    val routes: List<Route> = emptyList(),
    val excludedRoutes: List<Route> = emptyList(),
)

/**
 * A single route to be installed on the tunnel interface.
 *
 * @property address network address.
 * @property prefixLength prefix length for [address].
 */
data class Route(
    val address: InetAddress,
    val prefixLength: Int,
)
