package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.profile.PerAppMode
import io.github.mr1ve3r.combined.core.profile.VpnProfile
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy

/**
 * A profile as it crosses the method channel (SPEC phase 8).
 *
 * Reading is deliberately forgiving: a missing field takes the value a new
 * profile would get. That is what makes the one-time import of the profiles
 * Flutter used to own a plain [read] of the JSON it already had — those maps
 * have no protocol, no port, and no SSTP fields, and L2TP with the defaults is
 * exactly what they mean (SPEC 8.1.3).
 *
 * Writing is not forgiving about secrets: no password, pre-shared key, or proxy
 * password is ever put in one of these maps. [ProfileChannel] hands those over
 * separately, only when something asked for them.
 */
internal object ProfilePayloads {

    /** The map Flutter receives. Contains no secret. */
    fun write(profile: VpnProfile, trustedCertificateIds: List<String>): Map<String, Any?> = mapOf(
        ProfileContract.FIELD_ID to profile.id,
        ProfileContract.FIELD_NAME to profile.name,
        ProfileContract.FIELD_PROTOCOL to protocolWireValue(profile.protocol),
        ProfileContract.FIELD_SERVER to profile.server,
        ProfileContract.FIELD_USERNAME to profile.username,
        ProfileContract.FIELD_MTU to profile.mtu,
        ProfileContract.FIELD_CREATED_AT to profile.createdAt,
        ProfileContract.FIELD_DNS_AUTOMATIC to profile.dnsAutomatic,
        ProfileContract.FIELD_DNS1_HOST to profile.dns1Host,
        ProfileContract.FIELD_DNS1_PROTOCOL to profile.dns1Protocol,
        ProfileContract.FIELD_DNS2_HOST to profile.dns2Host,
        ProfileContract.FIELD_DNS2_PROTOCOL to profile.dns2Protocol,
        ProfileContract.FIELD_PER_APP_MODE to profile.perAppMode.name,
        ProfileContract.FIELD_APP_LIST to profile.appList,
        ProfileContract.FIELD_KILL_SWITCH to profile.killSwitch,
        ProfileContract.FIELD_AUTO_RECONNECT to profile.autoReconnect,
        ProfileContract.FIELD_IPSEC_ENABLED to profile.ipsecEnabled,
        ProfileContract.FIELD_LOCAL_IDENTIFIER to profile.localIdentifier,
        ProfileContract.FIELD_PHASE1_PROPOSALS to profile.phase1Proposals,
        ProfileContract.FIELD_PHASE2_PROPOSALS to profile.phase2Proposals,
        ProfileContract.FIELD_PORT to profile.port,
        ProfileContract.FIELD_TRUST_POLICY to profile.trustPolicy.name,
        ProfileContract.FIELD_TRUSTED_CERTIFICATE_IDS to trustedCertificateIds,
        ProfileContract.FIELD_PINNED_FINGERPRINTS to profile.pinnedFingerprints,
        ProfileContract.FIELD_EXPECTED_HOSTNAME to profile.expectedHostname,
        ProfileContract.FIELD_MIN_TLS_VERSION to profile.minTlsVersion.name,
        ProfileContract.FIELD_PPP_AUTH_METHODS to profile.pppAuthMethods,
        ProfileContract.FIELD_PROXY_ENABLED to profile.proxyEnabled,
        ProfileContract.FIELD_PROXY_HOST to profile.proxyHost,
        ProfileContract.FIELD_PROXY_PORT to profile.proxyPort,
        ProfileContract.FIELD_PROXY_USERNAME to profile.proxyUsername,
    )

    /**
     * The profile [map] describes.
     *
     * @param id the identifier to store it under.
     * @param createdAt used when the map carries no creation time.
     * @return the profile, or `null` when the map has no server to connect to.
     */
    fun read(map: Map<*, *>, id: String, createdAt: Long): VpnProfile? {
        val server = string(map, ProfileContract.FIELD_SERVER).trim()
        if (server.isEmpty()) return null
        return VpnProfile(
            id = id,
            name = string(map, ProfileContract.FIELD_NAME).trim().ifEmpty { server },
            protocol = TunnelProtocol.fromWireValue(string(map, ProfileContract.FIELD_PROTOCOL)).engineProtocol,
            server = server,
            username = string(map, ProfileContract.FIELD_USERNAME),
            passwordRef = VpnProfile.passwordRefFor(id),
            mtu = int(map, ProfileContract.FIELD_MTU, DEFAULT_MTU),
            createdAt = long(map, ProfileContract.FIELD_CREATED_AT, createdAt),
            dnsAutomatic = bool(map, ProfileContract.FIELD_DNS_AUTOMATIC, true),
            dns1Host = string(map, ProfileContract.FIELD_DNS1_HOST).trim(),
            dns1Protocol = dnsProtocol(map, ProfileContract.FIELD_DNS1_PROTOCOL),
            dns2Host = string(map, ProfileContract.FIELD_DNS2_HOST).trim(),
            dns2Protocol = dnsProtocol(map, ProfileContract.FIELD_DNS2_PROTOCOL),
            perAppMode = enum(map, ProfileContract.FIELD_PER_APP_MODE, PerAppMode.OFF),
            appList = strings(map, ProfileContract.FIELD_APP_LIST),
            killSwitch = bool(map, ProfileContract.FIELD_KILL_SWITCH, false),
            autoReconnect = bool(map, ProfileContract.FIELD_AUTO_RECONNECT, true),
            ipsecEnabled = bool(map, ProfileContract.FIELD_IPSEC_ENABLED, true),
            pskRef = VpnProfile.pskRefFor(id),
            localIdentifier = string(map, ProfileContract.FIELD_LOCAL_IDENTIFIER).trim().ifEmpty { null },
            phase1Proposals = strings(map, ProfileContract.FIELD_PHASE1_PROPOSALS),
            phase2Proposals = strings(map, ProfileContract.FIELD_PHASE2_PROPOSALS),
            port = EngineProfiles.sanitizePort(
                int(map, ProfileContract.FIELD_PORT, DEFAULT_SSTP_PORT),
                DEFAULT_SSTP_PORT,
            ),
            trustPolicy = enum(map, ProfileContract.FIELD_TRUST_POLICY, TrustPolicy.SYSTEM),
            pinnedFingerprints = strings(map, ProfileContract.FIELD_PINNED_FINGERPRINTS).map { it.lowercase() },
            expectedHostname = string(map, ProfileContract.FIELD_EXPECTED_HOSTNAME).trim().ifEmpty { null },
            minTlsVersion = enum(map, ProfileContract.FIELD_MIN_TLS_VERSION, TlsVersion.DEFAULT),
            pppAuthMethods = strings(map, ProfileContract.FIELD_PPP_AUTH_METHODS)
                .ifEmpty { VpnProfile.DEFAULT_AUTH_METHOD_NAMES },
            proxyEnabled = bool(map, ProfileContract.FIELD_PROXY_ENABLED, false),
            proxyHost = string(map, ProfileContract.FIELD_PROXY_HOST).trim(),
            proxyPort = EngineProfiles.sanitizePort(
                int(map, ProfileContract.FIELD_PROXY_PORT, EngineProfiles.DEFAULT_PROXY_PORT),
                EngineProfiles.DEFAULT_PROXY_PORT,
            ),
            proxyUsername = string(map, ProfileContract.FIELD_PROXY_USERNAME),
            proxyPasswordRef = VpnProfile.proxyPasswordRefFor(id),
        )
    }

    /** The certificate ids [map] selects, which are stored in their own table. */
    fun trustedCertificateIds(map: Map<*, *>): List<String> = strings(map, ProfileContract.FIELD_TRUSTED_CERTIFICATE_IDS)

    /** The wire spelling of [protocol]; the same one [TunnelProtocol] reads. */
    fun protocolWireValue(protocol: Protocol): String = TunnelProtocol.entries.first { it.engineProtocol == protocol }.wireValue

    private fun dnsProtocol(map: Map<*, *>, key: String): String =
        string(map, key).trim().ifEmpty { VpnProfile.DEFAULT_DNS_PROTOCOL }

    private fun string(map: Map<*, *>, key: String): String = map[key] as? String ?: ""

    private fun strings(map: Map<*, *>, key: String): List<String> = (map[key] as? List<*>)
        .orEmpty()
        .mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()

    private fun bool(map: Map<*, *>, key: String, fallback: Boolean): Boolean = when (val value = map[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.toBooleanStrictOrNull() ?: fallback
        else -> fallback
    }

    private fun int(map: Map<*, *>, key: String, fallback: Int): Int = when (val value = map[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: fallback
        else -> fallback
    }

    private fun long(map: Map<*, *>, key: String, fallback: Long): Long = when (val value = map[key]) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: fallback
        else -> fallback
    }

    private inline fun <reified T : Enum<T>> enum(map: Map<*, *>, key: String, fallback: T): T {
        val raw = string(map, key).trim()
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback
    }

    /** What the Flutter editor offers for a new profile, and what a map without an MTU means. */
    private const val DEFAULT_MTU = 1450

    private const val DEFAULT_SSTP_PORT = 443
}
