package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfilesTest {

    // A request written before the protocol field existed describes L2TP, and
    // so does one naming a protocol this build does not know.
    @Test
    fun unknownProtocolReadsAsL2tp() {
        assertEquals(TunnelProtocol.L2TP, TunnelProtocol.fromWireValue(null))
        assertEquals(TunnelProtocol.L2TP, TunnelProtocol.fromWireValue(""))
        assertEquals(TunnelProtocol.L2TP, TunnelProtocol.fromWireValue("wireguard"))
        assertEquals(TunnelProtocol.SSTP, TunnelProtocol.fromWireValue(" SSTP "))
    }

    @Test
    fun l2tpProfileAlwaysEnablesIpsec() {
        val profile = EngineProfiles.l2tp("vpn.example.org", "user", "secret", "psk", 1400)

        assertTrue(profile.ipsecEnabled)
        assertEquals("psk", profile.presharedKey)
        assertEquals(1400, profile.mtu)
    }

    @Test
    fun sstpProfileParsesTheEnumsItRecognises() {
        val profile = sstp(
            trustPolicy = "pin_leaf",
            minTlsVersion = "TLS_1_3",
            pppAuthMethods = listOf("mschapv2", "eap_mschapv2"),
        )

        assertEquals(TrustPolicy.PIN_LEAF, profile.trustPolicy)
        assertEquals(TlsVersion.TLS_1_3, profile.minTlsVersion)
        assertEquals(
            setOf(PppAuthMethod.MSCHAPV2, PppAuthMethod.EAP_MSCHAPV2),
            profile.pppAuthMethods,
        )
    }

    // A malformed field is a settings problem, not a reason to refuse to
    // connect: everything falls back to what a new profile would carry.
    @Test
    fun sstpProfileFallsBackRatherThanFailing() {
        val profile = sstp(
            port = 0,
            trustPolicy = "nonsense",
            minTlsVersion = "SSLv3",
            pppAuthMethods = listOf("kerberos"),
        )

        assertEquals(EngineProfile.Sstp.DEFAULT_PORT, profile.port)
        assertEquals(TrustPolicy.SYSTEM, profile.trustPolicy)
        assertEquals(TlsVersion.DEFAULT, profile.minTlsVersion)
        assertEquals(EngineProfile.Sstp.DEFAULT_AUTH_METHODS, profile.pppAuthMethods)
    }

    // The store and the pinning trust manager both compare lowercase hex.
    @Test
    fun sstpProfileNormalisesFingerprintsAndCertificateIds() {
        val profile = sstp(
            trustedCertificateIds = listOf(" abc ", "abc", ""),
            pinnedFingerprints = listOf("AABBCC", " aabbcc "),
        )

        assertEquals(listOf("abc"), profile.trustedCertificateIds)
        assertEquals(setOf("aabbcc"), profile.pinnedFingerprints)
    }

    @Test
    fun sstpProfileTreatsABlankExpectedHostnameAsAbsent() {
        assertNull(sstp(expectedHostname = "   ").expectedHostname)
        assertEquals("vpn.internal.lan", sstp(expectedHostname = " vpn.internal.lan ").expectedHostname)
    }

    @Test
    fun noProxyHostMeansNoProxy() {
        assertNull(EngineProfiles.proxy(host = null, port = 3128, username = null, password = null))
        assertNull(EngineProfiles.proxy(host = "  ", port = 3128, username = "u", password = "p"))
    }

    @Test
    fun proxyKeepsItsOwnPortFallback() {
        val proxy = EngineProfiles.proxy(host = " proxy.lan ", port = -1, username = "", password = "p")

        assertEquals("proxy.lan", proxy?.host)
        assertEquals(EngineProfiles.DEFAULT_PROXY_PORT, proxy?.port)
        assertNull(proxy?.username)
        assertEquals("p", proxy?.password)
    }

    private fun sstp(
        port: Int = 443,
        trustPolicy: String? = null,
        trustedCertificateIds: List<String>? = null,
        pinnedFingerprints: List<String>? = null,
        expectedHostname: String? = null,
        minTlsVersion: String? = null,
        pppAuthMethods: List<String>? = null,
    ): EngineProfile.Sstp =
        EngineProfiles.sstp(
            server = "vpn.example.org",
            username = "user",
            password = "secret",
            mtu = 1400,
            port = port,
            trustPolicy = trustPolicy,
            trustedCertificateIds = trustedCertificateIds,
            pinnedFingerprints = pinnedFingerprints,
            expectedHostname = expectedHostname,
            minTlsVersion = minTlsVersion,
            pppAuthMethods = pppAuthMethods,
            proxy = null,
        )
}
