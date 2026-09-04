package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.ProxyConfig
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy

/**
 * The protocol a connection request asks for.
 *
 * Carried over the method channel and the start intent as a string, because
 * both are string-keyed maps and neither survives an enum. [L2TP] is the
 * fallback for anything unrecognised: an old client, or a request written
 * before the field existed, still describes an L2TP connection.
 */
internal enum class TunnelProtocol(
    val wireValue: String,
    val displayLabel: String,
    val engineProtocol: Protocol,
) {
    L2TP("l2tp", "L2TP/IPsec", Protocol.L2TP),
    SSTP("sstp", "SSTP", Protocol.SSTP),
    ;

    companion object {
        fun fromWireValue(raw: String?): TunnelProtocol =
            entries.firstOrNull { it.wireValue == raw?.trim()?.lowercase() } ?: L2TP
    }
}

/**
 * Builds [EngineProfile]s out of the values that arrive over the wire.
 *
 * Kept apart from the service so the parsing can be tested without an
 * `Intent`, and so the two protocols' defaults sit next to each other where a
 * difference between them is visible.
 */
internal object EngineProfiles {

    /**
     * An L2TP/IPsec profile.
     *
     * IPsec is always on: the native engine has no plain-L2TP mode, and the
     * identity and proposal fields have no native equivalent yet (SPEC В.5).
     */
    fun l2tp(
        server: String,
        username: String,
        password: String,
        presharedKey: String,
        mtu: Int,
    ): EngineProfile.L2tp =
        EngineProfile.L2tp(
            server = server,
            username = username,
            password = password,
            mtu = mtu,
            customDns = emptyList(),
            ipsecEnabled = true,
            presharedKey = presharedKey,
            localIdentifier = null,
            phase1Proposals = emptyList(),
            phase2Proposals = emptyList(),
        )

    /**
     * An SSTP profile.
     *
     * Every enum arrives as its own `name`, and anything unparseable falls back
     * to the value a new profile would get rather than failing the connection:
     * a malformed field is a settings problem, and the pre-flight reports what
     * the policy actually resolved to before a socket is opened.
     */
    fun sstp(
        server: String,
        username: String,
        password: String,
        mtu: Int,
        port: Int,
        trustPolicy: String?,
        trustedCertificateIds: List<String>?,
        pinnedFingerprints: List<String>?,
        expectedHostname: String?,
        minTlsVersion: String?,
        pppAuthMethods: List<String>?,
        proxy: ProxyConfig?,
    ): EngineProfile.Sstp =
        EngineProfile.Sstp(
            server = server,
            username = username,
            password = password,
            mtu = mtu,
            customDns = emptyList(),
            port = sanitizePort(port, EngineProfile.Sstp.DEFAULT_PORT),
            trustPolicy = parseEnum(trustPolicy, TrustPolicy.SYSTEM),
            trustedCertificateIds = trimmedNonEmpty(trustedCertificateIds),
            pinnedFingerprints = trimmedNonEmpty(pinnedFingerprints).map { it.lowercase() }.toSet(),
            expectedHostname = expectedHostname?.trim()?.takeIf { it.isNotEmpty() },
            minTlsVersion = parseEnum(minTlsVersion, TlsVersion.DEFAULT),
            pppAuthMethods = parseAuthMethods(pppAuthMethods),
            proxy = proxy,
        )

    /** A proxy, or `null` when no host was given. */
    fun proxy(
        host: String?,
        port: Int,
        username: String?,
        password: String?,
    ): ProxyConfig? {
        val trimmedHost = host?.trim().orEmpty()
        if (trimmedHost.isEmpty()) return null
        return ProxyConfig(
            host = trimmedHost,
            port = sanitizePort(port, DEFAULT_PROXY_PORT),
            username = username?.takeIf { it.isNotEmpty() },
            password = password?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The methods to offer, or the default set when the request names none it
     * recognises. An empty set would make the engine refuse to connect.
     */
    private fun parseAuthMethods(raw: List<String>?): Set<PppAuthMethod> {
        val parsed =
            trimmedNonEmpty(raw)
                .mapNotNull { name -> PppAuthMethod.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                .toSet()
        return parsed.ifEmpty { EngineProfile.Sstp.DEFAULT_AUTH_METHODS }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: fallback

    private fun trimmedNonEmpty(values: List<String>?): List<String> =
        values.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    internal fun sanitizePort(value: Int, fallback: Int): Int =
        if (value in 1..65535) value else fallback

    /** Where an HTTP proxy listens when the request names no usable port. */
    internal const val DEFAULT_PROXY_PORT: Int = 8080
}

/**
 * One connection request, complete enough to be repeated.
 *
 * A reconnect after a network change replays exactly this (SPEC В.4), so
 * everything the setup path reads has to be in here rather than in the
 * `Intent` it originally arrived on — the `Intent` is long gone by then.
 */
internal data class TunnelStartRequest(
    val attemptId: String,
    val protocol: TunnelProtocol,
    val profile: EngineProfile,
    val profileName: String?,
    val dnsAutomatic: Boolean,
    val dnsServers: List<DnsServerConfig>,
    val splitTunnelEnabled: Boolean,
    val splitTunnelMode: String,
    val inclusivePackages: ArrayList<String>?,
    val exclusivePackages: ArrayList<String>?,
    val proxyConfig: ProxyRuntimeConfig,
)
