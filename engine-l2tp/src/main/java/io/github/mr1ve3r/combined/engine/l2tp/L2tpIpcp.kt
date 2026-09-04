package io.github.mr1ve3r.combined.engine.l2tp

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Reading of the IPCP results the native layer writes into its out-parameters.
 *
 * Split out from [L2tpEngine] because this is where the fiddly part is — an
 * octet array the C layer may have left untouched — and because it is the piece
 * worth testing directly.
 */
internal object L2tpIpcp {
    /**
     * Address the server assigned, or `null` when IPCP produced nothing usable.
     *
     * An untouched out-parameter reads back as four zeroes, and a partially
     * written one can hold values outside an octet, so both are rejected.
     */
    fun addressOrNull(octets: IntArray?): Inet4Address? {
        if (octets == null || octets.size < 4) return null
        for (index in 0..3) {
            if (octets[index] !in OCTET_RANGE) return null
        }
        if ((0..3).all { octets[it] == 0 }) return null
        val literal = "${octets[0]}.${octets[1]}.${octets[2]}.${octets[3]}"
        return InetAddress.getByName(literal) as? Inet4Address
    }

    /**
     * The DNS servers IPCP proposed, in preference order and without repeats.
     *
     * A server that offers the same address twice is not offering two servers,
     * and the duplicate would otherwise reach `VpnService.Builder`.
     */
    fun dnsServers(primary: IntArray?, secondary: IntArray?): List<Inet4Address> =
        listOfNotNull(addressOrNull(primary), addressOrNull(secondary)).distinct()

    private val OCTET_RANGE = 0..255
}
