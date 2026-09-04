package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.profile.PerAppMode
import io.github.mr1ve3r.combined.core.profile.ProfileWithSecrets
import io.github.mr1ve3r.combined.core.profile.VpnProfile
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.Protocol

/**
 * Turns a stored profile into a connection request (SPEC 8.1, В.13).
 *
 * This is the path a start with no arguments takes: an always-on tunnel the
 * system brings up before the user has opened the application, a Quick Settings
 * tile, a restart after the service was killed. None of them carry the extras
 * `ACTION_START` normally arrives with, and until phase 8 there was nothing in
 * Kotlin to build them from — the profile was in the Flutter layer's storage.
 *
 * The per-application routing comes from the profile rather than from the
 * global setting the editor writes today, because the global one lives in
 * Flutter's preferences and this path cannot read them. A profile that has not
 * been given a mode yet routes everything through the tunnel, which is the
 * conservative reading of "connect".
 */
internal object StoredProfileStart {

    /** The request that connects [row], as `ACTION_START` would have described it. */
    fun requestFrom(
        row: ProfileWithSecrets,
        trustedCertificateIds: List<String>,
        attemptId: String,
        proxyConfig: ProxyRuntimeConfig,
    ): TunnelStartRequest {
        val profile = row.profile
        return TunnelStartRequest(
            attemptId = attemptId,
            protocol = TunnelProtocol.entries.first { it.engineProtocol == profile.protocol },
            profile = engineProfileOf(row, trustedCertificateIds),
            profileName = profile.name.takeIf { it.isNotEmpty() },
            dnsAutomatic = profile.dnsAutomatic,
            dnsServers = manualDnsServersOf(profile),
            splitTunnelEnabled = profile.perAppMode != PerAppMode.OFF,
            splitTunnelMode =
                if (profile.perAppMode == PerAppMode.EXCLUDE) {
                    VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE
                } else {
                    VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE
                },
            inclusivePackages =
                ArrayList(if (profile.perAppMode == PerAppMode.INCLUDE) profile.appList else emptyList()),
            exclusivePackages =
                ArrayList(if (profile.perAppMode == PerAppMode.EXCLUDE) profile.appList else emptyList()),
            proxyConfig = proxyConfig,
        )
    }

    /**
     * The manual DNS servers [profile] names, in slot order.
     *
     * Empty hosts drop out, which is what an unfilled second slot is. The
     * service decides what to do with them; a DNS-over-TLS entry on SSTP, for
     * instance, is refused there and not here (SPEC В.12).
     */
    fun manualDnsServersOf(profile: VpnProfile): List<DnsServerConfig> = listOf(
        DnsServerConfig(profile.dns1Host.trim(), DnsProtocol.fromWireValue(profile.dns1Protocol)),
        DnsServerConfig(profile.dns2Host.trim(), DnsProtocol.fromWireValue(profile.dns2Protocol)),
    ).filter { it.host.isNotEmpty() }.distinct()

    /** The engine profile [row] describes, with the secrets it refers to filled in. */
    fun engineProfileOf(row: ProfileWithSecrets, trustedCertificateIds: List<String>): EngineProfile {
        val profile = row.profile
        return when (profile.protocol) {
            Protocol.L2TP ->
                EngineProfiles.l2tp(
                    server = profile.server,
                    username = profile.username,
                    password = row.password,
                    presharedKey = row.psk,
                    mtu = profile.mtu,
                )

            Protocol.SSTP ->
                EngineProfiles.sstp(
                    server = profile.server,
                    username = profile.username,
                    password = row.password,
                    mtu = profile.mtu,
                    port = profile.port,
                    trustPolicy = profile.trustPolicy.name,
                    trustedCertificateIds = trustedCertificateIds,
                    pinnedFingerprints = profile.pinnedFingerprints,
                    expectedHostname = profile.expectedHostname,
                    minTlsVersion = profile.minTlsVersion.name,
                    pppAuthMethods = profile.pppAuthMethods,
                    proxy =
                        if (profile.proxyEnabled) {
                            EngineProfiles.proxy(
                                host = profile.proxyHost,
                                port = profile.proxyPort,
                                username = profile.proxyUsername,
                                password = row.proxyPassword,
                            )
                        } else {
                            null
                        },
                )
        }
    }
}
