package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.profile.PerAppMode
import io.github.mr1ve3r.combined.core.profile.ProfileWithSecrets
import io.github.mr1ve3r.combined.core.profile.VpnProfile
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profiles crossing the method channel, and the same profiles turned into a connection (SPEC phase 8). */
class ProfilePayloadsTest {

    /**
     * A profile written by the build before this phase: no protocol, no port,
     * no SSTP fields. That is the whole of the upgrade path, so it has to mean
     * L2TP with the defaults rather than nothing (SPEC 8.1.3).
     */
    @Test
    fun aProfileFromTheOldStoreReadsAsL2tp() {
        val legacy = mapOf(
            "id" to "abc",
            "displayName" to "Home",
            "server" to "vpn.example.org",
            "user" to "alice",
            "dnsAutomatic" to false,
            "dns1Host" to "9.9.9.9",
            "dns1Protocol" to "dnsOverTcp",
            "dns2Host" to "",
            "dns2Protocol" to "dnsOverUdp",
            "mtu" to 1400,
        )

        val profile = ProfilePayloads.read(legacy, "abc", createdAt = 17L)!!

        assertEquals(Protocol.L2TP, profile.protocol)
        assertEquals("Home", profile.name)
        assertEquals("vpn.example.org", profile.server)
        assertEquals("alice", profile.username)
        assertEquals(1400, profile.mtu)
        assertEquals(17L, profile.createdAt)
        assertFalse(profile.dnsAutomatic)
        assertEquals("9.9.9.9", profile.dns1Host)
        assertEquals("dnsOverTcp", profile.dns1Protocol)
        assertEquals(PerAppMode.OFF, profile.perAppMode)
        assertTrue(profile.ipsecEnabled)
        assertEquals(EngineProfile.Sstp.DEFAULT_PORT, profile.port)
        assertEquals(TrustPolicy.SYSTEM, profile.trustPolicy)
        assertEquals(VpnProfile.DEFAULT_AUTH_METHOD_NAMES, profile.pppAuthMethods)
    }

    @Test
    fun aProfileWithoutAServerIsNotAProfile() {
        assertNull(ProfilePayloads.read(mapOf("displayName" to "Home"), "abc", createdAt = 0L))
    }

    @Test
    fun anUnnamedProfileIsNamedAfterItsServer() {
        val profile = ProfilePayloads.read(mapOf("server" to " vpn.example.org "), "abc", createdAt = 0L)!!

        assertEquals("vpn.example.org", profile.name)
        assertEquals("vpn.example.org", profile.server)
    }

    @Test
    fun everySstpFieldSurvivesTheRoundTrip() {
        val written = ProfilePayloads.write(sstpProfile(), trustedCertificateIds = listOf("aa11"))

        val read = ProfilePayloads.read(written, "id-1", createdAt = 0L)!!

        assertEquals(sstpProfile(), read)
        assertEquals(listOf("aa11"), ProfilePayloads.trustedCertificateIds(written))
        assertEquals("sstp", written[ProfileContract.FIELD_PROTOCOL])
    }

    /** A listing is drawn from these maps; a secret in one would be a secret in a log. */
    @Test
    fun noSecretIsWrittenIntoAProfileMap() {
        val written = ProfilePayloads.write(sstpProfile(), trustedCertificateIds = emptyList())

        assertFalse(written.keys.any { it.contains("password", ignoreCase = true) })
        assertFalse(written.keys.any { it.contains("psk", ignoreCase = true) })
        assertFalse(written.values.any { it == "hunter2" })
    }

    @Test
    fun anImpossiblePortFallsBackRatherThanFailing() {
        val profile = ProfilePayloads.read(
            mapOf("server" to "vpn.example.org", "port" to 0, "proxyPort" to 99999),
            "abc",
            createdAt = 0L,
        )!!

        assertEquals(EngineProfile.Sstp.DEFAULT_PORT, profile.port)
        assertEquals(EngineProfiles.DEFAULT_PROXY_PORT, profile.proxyPort)
    }

    @Test
    fun aStoredSstpProfileBecomesAnSstpEngineProfile() {
        val row = ProfileWithSecrets(sstpProfile(), password = "hunter2", psk = "", proxyPassword = "proxy-pass")

        val engine = StoredProfileStart.engineProfileOf(row, trustedCertificateIds = listOf("aa11")) as EngineProfile.Sstp

        assertEquals("vpn.example.org", engine.server)
        assertEquals("hunter2", engine.password)
        assertEquals(4443, engine.port)
        assertEquals(TrustPolicy.SYSTEM_PLUS_CUSTOM, engine.trustPolicy)
        assertEquals(listOf("aa11"), engine.trustedCertificateIds)
        assertEquals(TlsVersion.TLS_1_3, engine.minTlsVersion)
        assertEquals(setOf(PppAuthMethod.MSCHAPV2), engine.pppAuthMethods)
        assertEquals("proxy.example.org", engine.proxy?.host)
        assertEquals("proxy-pass", engine.proxy?.password)
    }

    /** A proxy that is configured but switched off must not be dialled. */
    @Test
    fun aDisabledProxyIsNotUsed() {
        val row = ProfileWithSecrets(
            sstpProfile().copy(proxyEnabled = false),
            password = "hunter2",
            psk = "",
            proxyPassword = "proxy-pass",
        )

        val engine = StoredProfileStart.engineProfileOf(row, trustedCertificateIds = emptyList()) as EngineProfile.Sstp

        assertNull(engine.proxy)
    }

    @Test
    fun perAppRoutingComesFromTheProfile() {
        val row = ProfileWithSecrets(
            sstpProfile().copy(perAppMode = PerAppMode.EXCLUDE, appList = listOf("com.example.a")),
            password = "hunter2",
            psk = "",
            proxyPassword = "",
        )

        val request = StoredProfileStart.requestFrom(row, emptyList(), "auto-1", proxyConfig)

        assertTrue(request.splitTunnelEnabled)
        assertEquals(VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE, request.splitTunnelMode)
        assertEquals(listOf("com.example.a"), request.exclusivePackages)
        assertEquals(emptyList<String>(), request.inclusivePackages)
        assertEquals(TunnelProtocol.SSTP, request.protocol)
        assertEquals("Work", request.profileName)
    }

    @Test
    fun onlyTheFilledDnsSlotsAreCarried() {
        val profile = sstpProfile().copy(
            dns1Host = "9.9.9.9",
            dns1Protocol = "dnsOverTls",
            dns2Host = "  ",
        )
        val row = ProfileWithSecrets(profile, password = "", psk = "", proxyPassword = "")

        val request = StoredProfileStart.requestFrom(row, emptyList(), "auto-1", proxyConfig)

        assertEquals(listOf(DnsServerConfig("9.9.9.9", DnsProtocol.dnsOverTls)), request.dnsServers)
    }

    private val proxyConfig =
        ProxyRuntimeConfig(httpEnabled = true, httpPort = 8080, socksEnabled = true, socksPort = 1080)

    private fun sstpProfile() = VpnProfile(
        id = "id-1",
        name = "Work",
        protocol = Protocol.SSTP,
        server = "vpn.example.org",
        username = "alice",
        passwordRef = VpnProfile.passwordRefFor("id-1"),
        mtu = 1400,
        createdAt = 0L,
        pskRef = VpnProfile.pskRefFor("id-1"),
        port = 4443,
        trustPolicy = TrustPolicy.SYSTEM_PLUS_CUSTOM,
        pinnedFingerprints = listOf("bb22"),
        expectedHostname = "vpn.internal.lan",
        minTlsVersion = TlsVersion.TLS_1_3,
        pppAuthMethods = listOf(PppAuthMethod.MSCHAPV2.name),
        proxyEnabled = true,
        proxyHost = "proxy.example.org",
        proxyPort = 3128,
        proxyUsername = "bob",
        proxyPasswordRef = VpnProfile.proxyPasswordRefFor("id-1"),
    )
}
