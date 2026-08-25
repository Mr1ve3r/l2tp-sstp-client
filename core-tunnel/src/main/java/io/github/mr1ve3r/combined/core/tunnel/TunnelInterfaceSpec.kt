package io.github.mr1ve3r.combined.core.tunnel

import android.net.Network
import android.os.ParcelFileDescriptor
import java.net.InetAddress

/**
 * The subset of `VpnService.Builder` this project uses, behind an interface.
 *
 * `VpnService.Builder` cannot be constructed without a live `VpnService`, and
 * on the JVM unit-test classpath every one of its methods throws "not mocked".
 * That makes the ordering and composition of builder calls — exactly what
 * [TunnelBuilder] is responsible for — untestable in the abstract.
 *
 * So [TunnelBuilder] talks to this interface instead. [VpnServiceTunnelInterface]
 * forwards to the real builder in production; tests use a recording fake and
 * assert on the calls that were made.
 *
 * Methods mirror their platform counterparts, including the fluent style being
 * dropped: the return values were never used and discarding them keeps fakes
 * trivial.
 */
interface TunnelInterfaceSpec {
    /** Sets the session name shown in the system VPN dialog. */
    fun setSession(session: String)

    /** Sets the interface MTU. */
    fun setMtu(mtu: Int)

    /** Assigns the local address of the tunnel interface. */
    fun addAddress(address: InetAddress, prefixLength: Int)

    /** Routes a destination through the tunnel. */
    fun addRoute(address: InetAddress, prefixLength: Int)

    /**
     * Keeps a destination outside the tunnel.
     *
     * @return `false` when the platform is older than API 33 and cannot express
     *   an exclusion, so the caller can log that it fell back to relying on
     *   socket protection alone.
     */
    fun excludeRoute(address: InetAddress, prefixLength: Int): Boolean

    /** Adds a DNS server for the tunnel interface. */
    fun addDnsServer(address: InetAddress)

    /** Adds a DNS search domain. */
    fun addSearchDomain(domain: String)

    /** Restricts the interface to one address family, where supported. */
    fun allowFamily(family: Int)

    /**
     * Restricts the tunnel to this application, excluding all others.
     *
     * @throws PackageNotInstalledException if the package is not installed.
     */
    fun addAllowedApplication(packageName: String)

    /**
     * Excludes this application from the tunnel.
     *
     * @throws PackageNotInstalledException if the package is not installed.
     */
    fun addDisallowedApplication(packageName: String)

    /** Sets whether reads from the tunnel descriptor block. */
    fun setBlocking(blocking: Boolean)

    /** Sets the networks the tunnel runs over; `null` restores the default. */
    fun setUnderlyingNetworks(networks: Array<Network>?)

    /** Creates the interface, or returns `null` if the VPN permission was revoked. */
    fun establish(): ParcelFileDescriptor?
}

/**
 * Thrown when a per-app routing rule names a package that is not installed.
 *
 * Wraps the platform's `PackageManager.NameNotFoundException` so that
 * [TunnelInterfaceSpec] stays free of Android package-manager types and can be
 * implemented by a plain fake in tests.
 */
class PackageNotInstalledException(
    val packageName: String,
    cause: Throwable? = null,
) : Exception("Package not installed: $packageName", cause)
