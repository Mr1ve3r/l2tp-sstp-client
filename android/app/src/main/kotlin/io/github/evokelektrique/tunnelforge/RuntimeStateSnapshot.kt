package io.github.evokelektrique.tunnelforge

/**
 * What a running session negotiated, as the status screen shows it
 * (SPEC 9.1.7).
 *
 * @property rxBytes bytes this application's UID received since the tunnel came
 *   up. That is the transport's traffic — the packets other applications send
 *   through the TUN interface are billed to them, not to us.
 */
internal data class TunnelSessionInfo(
    val protocol: String,
    val address: String,
    val dnsServers: List<String>,
    val mtu: Int,
    val since: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val proxyHost: String?,
)

internal object RuntimeStateSnapshot {
    fun tunnel(
        state: String,
        detail: String,
        attemptId: String,
        connectionMode: String,
        proxyExposure: ProxyExposureInfo? = null,
        session: TunnelSessionInfo? = null,
    ): Map<String, Any?> =
        buildMap {
            put(VpnContract.ARG_TUNNEL_STATE, state)
            put(VpnContract.ARG_TUNNEL_DETAIL, detail)
            put(VpnContract.ARG_ATTEMPT_ID, attemptId)
            put(VpnContract.ARG_CONNECTION_MODE, connectionMode)
            proxyExposure?.let { putProxyExposure(it) }
            session?.let { putSession(it) }
        }

    private fun MutableMap<String, Any?>.putSession(session: TunnelSessionInfo) {
        put(VpnContract.ARG_TUNNEL_PROTOCOL, session.protocol)
        put(VpnContract.ARG_SESSION_ADDRESS, session.address)
        put(VpnContract.ARG_SESSION_DNS, session.dnsServers)
        put(VpnContract.ARG_SESSION_MTU, session.mtu)
        put(VpnContract.ARG_SESSION_SINCE, session.since)
        put(VpnContract.ARG_SESSION_RX_BYTES, session.rxBytes)
        put(VpnContract.ARG_SESSION_TX_BYTES, session.txBytes)
        put(VpnContract.ARG_SESSION_PROXY_HOST, session.proxyHost)
    }

    private fun MutableMap<String, Any?>.putProxyExposure(exposure: ProxyExposureInfo) {
        put(VpnContract.ARG_PROXY_EXPOSURE_ACTIVE, exposure.active)
        put(VpnContract.ARG_PROXY_EXPOSURE_BIND_ADDRESS, exposure.bindAddress)
        put(VpnContract.ARG_PROXY_EXPOSURE_DISPLAY_ADDRESS, exposure.displayAddress)
        put(VpnContract.ARG_PROXY_EXPOSURE_HTTP_PORT, exposure.httpPort)
        put(VpnContract.ARG_PROXY_EXPOSURE_SOCKS_PORT, exposure.socksPort)
        put(VpnContract.ARG_PROXY_EXPOSURE_LAN_REQUESTED, exposure.lanRequested)
        put(VpnContract.ARG_PROXY_EXPOSURE_LAN_ACTIVE, exposure.lanActive)
        put(VpnContract.ARG_PROXY_EXPOSURE_WARNING, exposure.warning ?: "")
    }
}
