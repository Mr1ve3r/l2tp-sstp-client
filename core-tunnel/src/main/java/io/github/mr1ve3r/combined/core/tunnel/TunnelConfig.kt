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
 *   application now passes it, having confirmed on a device that the L2TP
 *   tunnel is unaffected (SPEC В.1).
 *
 *   It is unaffected because `tunnel_loop.c` calls `set_nonblock()` on the
 *   descriptor when the poll loop starts, which overrides the builder either
 *   way. The flag becomes load-bearing for an engine that reads the descriptor
 *   from Kotlin instead — which is what phase 6 brings.
 * @property underlyingNetworks which networks the tunnel runs over.
 * @property ownPackage this application's package name, which travels inside
 *   the tunnel like any other application, or `null` to leave the question
 *   alone.
 *
 *   It used to be excluded (SPEC 3.1), and that turned out to be the reason the
 *   connectivity check never measured the tunnel: `addDisallowedApplication`
 *   works on the whole UID, so every socket the application opened -- the check
 *   among them -- went over the underlying network. A probe on the far side of
 *   the tunnel could not be reached, and a public one answered whether the
 *   tunnel was up or not.
 *
 *   Nothing about the transport depends on the exclusion. The sockets that
 *   carry the tunnel are protected individually through
 *   [SocketProtector][io.github.mr1ve3r.combined.engine.SocketProtector], and
 *   the peer's own address is kept off the tunnel's routes through
 *   [TunnelParams.excludedRoutes][
 *   io.github.mr1ve3r.combined.engine.TunnelParams.excludedRoutes]. Excluding
 *   the whole application was a third layer over those two, and the widest one.
 *
 *   Only meaningful for [PerAppRouting.Include], where an application is
 *   outside the tunnel unless it is named. Under the other two it is already
 *   inside.
 */
data class TunnelConfig(
    val sessionName: String,
    val perAppRouting: PerAppRouting = PerAppRouting.AllApps,
    val ipv4Only: Boolean = false,
    val blocking: Boolean? = null,
    val underlyingNetworks: UnderlyingNetworks = UnderlyingNetworks.Unspecified,
    val ownPackage: String? = null,
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
