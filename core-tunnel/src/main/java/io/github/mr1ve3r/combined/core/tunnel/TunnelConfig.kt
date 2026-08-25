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
 *   does and therefore the default. The SPEC asks for `setBlocking(true)`, but
 *   flipping it changes the read semantics of the descriptor handed to the
 *   native L2TP loop, and phase 4 is supposed to be behaviour-preserving. Make
 *   it an explicit choice at the call site rather than a silent default.
 * @property underlyingNetworks which networks the tunnel runs over.
 * @property excludeOwnPackage this application's package name, to be kept out
 *   of the tunnel, or `null` to leave it in.
 *
 *   The SPEC asks for self-exclusion unconditionally. Upstream does not do it,
 *   relying on socket protection instead, so the default here is `null` to
 *   preserve behaviour. Note it is only meaningful for
 *   [PerAppRouting.Exclude] and [PerAppRouting.AllApps]: under
 *   [PerAppRouting.Include] an application is already outside the tunnel unless
 *   it is named.
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
