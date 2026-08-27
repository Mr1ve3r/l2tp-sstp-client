package io.github.mr1ve3r.combined.engine.l2tp

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class L2tpIpcpTest {
    @Test
    fun readsAnAssignedAddress() {
        assertEquals(
            InetAddress.getByName("10.8.0.42"),
            L2tpIpcp.addressOrNull(intArrayOf(10, 8, 0, 42)),
        )
    }

    // An out-parameter the native layer never wrote reads back as four zeroes.
    @Test
    fun rejectsAnUntouchedOutParameter() {
        assertNull(L2tpIpcp.addressOrNull(IntArray(4)))
        assertNull(L2tpIpcp.addressOrNull(null))
        assertNull(L2tpIpcp.addressOrNull(intArrayOf(10, 8, 0)))
    }

    @Test
    fun rejectsValuesThatAreNotOctets() {
        assertNull(L2tpIpcp.addressOrNull(intArrayOf(10, 8, 0, 256)))
        assertNull(L2tpIpcp.addressOrNull(intArrayOf(-1, 8, 0, 42)))
    }

    @Test
    fun keepsDnsServersInPreferenceOrder() {
        val servers = L2tpIpcp.dnsServers(intArrayOf(10, 8, 0, 1), intArrayOf(10, 8, 0, 2))

        assertEquals(
            listOf(InetAddress.getByName("10.8.0.1"), InetAddress.getByName("10.8.0.2")),
            servers,
        )
    }

    // A server offering the same resolver twice is not offering two of them, and
    // the duplicate would otherwise reach VpnService.Builder.
    @Test
    fun dropsADuplicateSecondaryServer() {
        val servers = L2tpIpcp.dnsServers(intArrayOf(10, 8, 0, 1), intArrayOf(10, 8, 0, 1))

        assertEquals(listOf(InetAddress.getByName("10.8.0.1")), servers)
    }

    @Test
    fun ignoresAnUnsetSecondaryServer() {
        val servers = L2tpIpcp.dnsServers(intArrayOf(10, 8, 0, 1), IntArray(4))

        assertEquals(listOf(InetAddress.getByName("10.8.0.1")), servers)
    }

    @Test
    fun reportsNoServersWhenIpcpProposedNone() {
        assertEquals(emptyList<InetAddress>(), L2tpIpcp.dnsServers(IntArray(4), IntArray(4)))
    }
}
