package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SstpEngineConfigTest {
    @Test
    fun `reads the profile without consulting anything else`() {
        val config =
            SstpEngineConfig.of(
                sstpProfile(
                    server = "vpn.example.test",
                    port = 4443,
                    username = "alice",
                    password = "s3cret",
                    proxy = ProxyConfig("proxy.example.test", 8080, "bob", "hunter2"),
                ),
            )

        assertEquals("vpn.example.test", config.server)
        assertEquals(4443, config.port)
        assertEquals("alice", config.username)
        assertEquals("proxy.example.test", config.proxy?.host)
    }

    @Test
    fun `verifies against the expected hostname when the profile names one`() {
        val named = SstpEngineConfig.of(sstpProfile(server = "203.0.113.7", expectedHostname = "vpn.internal.lan"))
        val unnamed = SstpEngineConfig.of(sstpProfile(server = "203.0.113.7"))

        // The whole reason expectedHostname exists: a certificate issued to an
        // internal name, reached over a bare IP, without switching verification off.
        assertEquals("vpn.internal.lan", named.verificationHostname)
        assertEquals("203.0.113.7", unnamed.verificationHostname)
    }

    @Test
    fun `treats a blank expected hostname as none`() {
        val config = SstpEngineConfig.of(sstpProfile(server = "vpn.example.test", expectedHostname = "   "))

        assertEquals("vpn.example.test", config.verificationHostname)
    }

    @Test
    fun `clamps an unusable MTU instead of refusing to connect`() {
        val tiny = SstpEngineConfig.of(sstpProfile(mtu = 1))
        val huge = SstpEngineConfig.of(sstpProfile(mtu = 100_000))

        assertEquals(SstpEngineConfig.MIN_MRU, tiny.mtu)
        assertEquals(SstpEngineConfig.MAX_MRU, huge.mtu)
        assertEquals(tiny.mtu, tiny.mru)
    }

    @Test
    fun `offers only the authentication methods the profile enables`() {
        val config = SstpEngineConfig.of(sstpProfile(pppAuthMethods = setOf(PppAuthMethod.MSCHAPV2)))

        assertTrue(config.isEnabled(PppAuthMethod.MSCHAPV2))
        assertFalse(config.isEnabled(PppAuthMethod.PAP))
        // Carried but off by default: MikroTik and SoftEther negotiate MSCHAPv2 directly.
        assertFalse(config.isEnabled(PppAuthMethod.EAP_MSCHAPV2))
    }

    @Test
    fun `leaves IPv6 negotiation off while TunnelParams carries one address`() {
        val config = SstpEngineConfig.of(sstpProfile())

        assertTrue(config.ipv4Enabled)
        assertFalse(config.ipv6Enabled)
    }
}
