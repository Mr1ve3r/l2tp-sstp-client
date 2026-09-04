package io.github.mr1ve3r.combined.core.tunnel

import android.net.Network
import android.os.ParcelFileDescriptor
import java.net.InetAddress

/**
 * A [TunnelInterfaceSpec] that records what it was asked to do.
 *
 * Every call appends a readable line to [calls], so a test can assert both the
 * composition and the order of builder calls — which is the whole contract of
 * [TunnelBuilder].
 *
 * `establish()` always returns `null`, which is why tests drive
 * [TunnelBuilder.configure] rather than [TunnelBuilder.build]:
 * `ParcelFileDescriptor` cannot be constructed on the unit-test classpath. The
 * one test that does call `build` is the one asserting the null path throws.
 *
 * @property installedPackages packages that "exist"; anything else raises
 *   [PackageNotInstalledException], mirroring the platform.
 * @property supportsExcludeRoute whether the simulated platform is API 33 or
 *   newer.
 */
class RecordingTunnelInterface(
    private val installedPackages: Set<String>? = null,
    private val supportsExcludeRoute: Boolean = true,
) : TunnelInterfaceSpec {
    val calls = mutableListOf<String>()

    override fun setSession(session: String) {
        calls += "setSession($session)"
    }

    override fun setMtu(mtu: Int) {
        calls += "setMtu($mtu)"
    }

    override fun addAddress(address: InetAddress, prefixLength: Int) {
        calls += "addAddress(${address.hostAddress}/$prefixLength)"
    }

    override fun addRoute(address: InetAddress, prefixLength: Int) {
        calls += "addRoute(${address.hostAddress}/$prefixLength)"
    }

    override fun excludeRoute(address: InetAddress, prefixLength: Int): Boolean {
        calls += "excludeRoute(${address.hostAddress}/$prefixLength)"
        return supportsExcludeRoute
    }

    override fun addDnsServer(address: InetAddress) {
        calls += "addDnsServer(${address.hostAddress})"
    }

    override fun addSearchDomain(domain: String) {
        calls += "addSearchDomain($domain)"
    }

    override fun allowFamily(family: Int) {
        calls += "allowFamily($family)"
    }

    override fun addAllowedApplication(packageName: String) {
        requireInstalled(packageName)
        calls += "addAllowedApplication($packageName)"
    }

    override fun addDisallowedApplication(packageName: String) {
        requireInstalled(packageName)
        calls += "addDisallowedApplication($packageName)"
    }

    override fun setBlocking(blocking: Boolean) {
        calls += "setBlocking($blocking)"
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?) {
        calls += "setUnderlyingNetworks(${networks?.size ?: "null"})"
    }

    override fun establish(): ParcelFileDescriptor? {
        calls += "establish()"
        return null
    }

    private fun requireInstalled(packageName: String) {
        val installed = installedPackages ?: return
        if (packageName !in installed) {
            throw PackageNotInstalledException(packageName)
        }
    }
}
