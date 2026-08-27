package io.github.mr1ve3r.combined.core.tunnel

import android.net.Network

/**
 * Host-side tunnel policy: everything about the interface that the server does
 * not get a say in.
 *
 * [TunnelParams][io.github.mr1ve3r.combined.engine.TunnelParams] says what was
 * negotiated; this says what the user asked for. Keeping them apart is what
 * lets both engines share one interface builder.
 *
 * The defaults reproduce upstream TunnelForge exactly. Two of them are
 * deliberately conservative and are discussed on the properties themselves.
 *
 * @property sessionName name shown in the system VPN dialog.
 * @property perAppRouting which applications the tunnel applies to.
 * @property ipv4Only restrict the interface to `AF_INET` where supported.
 * @property blocking whether reads from the tunnel descriptor block.
 *
 *   `null` means *do not call* `setBlocking` at all, which is what upstream
 *   does and therefore the default here: this module takes no position, the
 *   host decides. The SPEC asks for `setBlocking(true)` and the Android
 *   application now passes it; the phase 4 device test checks that the L2TP
 *   tunnel is unaffected (SPEC В.1).
 *
 *   It is unaffected because `tunnel_loop.c` calls `set_nonblock()` on the
 *   descriptor when the poll loop starts, which overrides the builder either
 *   way. The flag becomes load-bearing for an engine that reads the descriptor
 *   from Kotlin instead — which is what phase 6 brings.
 * @property underlyingNetworks which networks the tunnel runs over.
 * @property excludeOwnPackage this application's package name, to be kept out
 *   of the tunnel, or `null` to leave it in.
 *
 *   The default is `null` because this module takes no position on it; the host
 *   decides. The Android application does set it, having verified on a device
 *   that excluding itself breaks neither routing mode (SPEC 3.1).
 *
 *   Only meaningful for [PerAppRouting.Exclude] and [PerAppRouting.AllApps]:
 *   under [PerAppRouting.Include] an application is already outside the tunnel
 *   unless it is named.
 */
data class TunnelConfig(
    val sessionName: String,
    val perAppRouting: PerAppRouting = PerAppRouting.AllApps,
    val ipv4Only: Boolean = false,
    val blocking: Boolean? = null,
    val underlyingNetworks: UnderlyingNetworks = UnderlyingNetworks.Unspecified,
    val excludeOwnPackage: String? = null,
)

/** Which applications a tunnel carries. */
sealed interface PerAppRouting {
    /** Every application on the device. */
    data object AllApps : PerAppRouting

    /**
     * Only the named applications; everything else bypasses the tunnel.
     *
     * @property packages package names to route through the tunnel.
     */
    data class Include(val packages: Set<String>) : PerAppRouting

    /**
     * Every application except the named ones.
     *
     * @property packages package names to keep outside the tunnel.
     */
    data class Exclude(val packages: Set<String>) : PerAppRouting
}

/** What to pass to `VpnService.Builder.setUnderlyingNetworks`. */
sealed interface UnderlyingNetworks {
    /** Do not call it at all, leaving whatever the platform decides. Upstream behaviour. */
    data object Unspecified : UnderlyingNetworks

    /** Call it with `null`, which asks the platform to track the system default. */
    data object SystemDefault : UnderlyingNetworks

    /**
     * Call it with an explicit set.
     *
     * @property networks networks carrying the tunnel.
     */
    data class Specific(val networks: List<Network>) : UnderlyingNetworks
}
