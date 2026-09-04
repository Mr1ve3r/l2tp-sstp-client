package io.github.mr1ve3r.combined.core.tunnel

import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.InetAddress

/**
 * [TunnelInterfaceSpec] backed by a real `VpnService.Builder`.
 *
 * Nothing here makes policy decisions — that is [TunnelBuilder]'s job. This
 * class only forwards, and absorbs the two places where the platform is
 * awkward: `excludeRoute` needs API 33, and package errors arrive as a
 * `PackageManager` exception that callers outside Android should not have to
 * know about.
 */
class VpnServiceTunnelInterface(
    private val builder: VpnService.Builder,
) : TunnelInterfaceSpec {
    override fun setSession(session: String) {
        builder.setSession(session)
    }

    override fun setMtu(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addAddress(address: InetAddress, prefixLength: Int) {
        builder.addAddress(address, prefixLength)
    }

    override fun addRoute(address: InetAddress, prefixLength: Int) {
        builder.addRoute(address, prefixLength)
    }

    override fun excludeRoute(address: InetAddress, prefixLength: Int): Boolean {
        // excludeRoute arrived in API 33. Below that the caller has to rely on
        // SocketProtector alone, so report the miss rather than swallowing it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        builder.excludeRoute(IpPrefix(address, prefixLength))
        return true
    }

    override fun addDnsServer(address: InetAddress) {
        builder.addDnsServer(address)
    }

    override fun addSearchDomain(domain: String) {
        builder.addSearchDomain(domain)
    }

    override fun allowFamily(family: Int) {
        builder.allowFamily(family)
    }

    override fun addAllowedApplication(packageName: String) {
        try {
            builder.addAllowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            throw PackageNotInstalledException(packageName, e)
        }
    }

    override fun addDisallowedApplication(packageName: String) {
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            throw PackageNotInstalledException(packageName, e)
        }
    }

    override fun setBlocking(blocking: Boolean) {
        builder.setBlocking(blocking)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?) {
        builder.setUnderlyingNetworks(networks)
    }

    override fun establish(): ParcelFileDescriptor? = builder.establish()
}
