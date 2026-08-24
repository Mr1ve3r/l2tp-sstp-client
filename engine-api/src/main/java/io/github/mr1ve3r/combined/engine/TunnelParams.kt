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
 */
data class TunnelParams(
    val localAddress: InetAddress,
    val prefixLength: Int,
    val dnsServers: List<InetAddress>,
    val mtu: Int,
    val searchDomains: List<String> = emptyList(),
    val routes: List<Route> = emptyList(),
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
