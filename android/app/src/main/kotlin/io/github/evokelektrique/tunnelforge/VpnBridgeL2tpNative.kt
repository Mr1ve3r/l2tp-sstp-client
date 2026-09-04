package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.engine.l2tp.L2tpNative

/**
 * The real [L2tpNative]: the JNI peer that ships with this application.
 *
 * `engine-l2tp` cannot reference [VpnBridge] directly. The JNI methods are
 * registered against a class name the C layer hard-codes, so the peer has to
 * stay in this package, and a library module may not depend on the application
 * that contains it. This adapter is that one-way link, and it is deliberately
 * nothing but delegation — everything worth testing lives above it in the
 * engine.
 */
internal object VpnBridgeL2tpNative : L2tpNative {
    override fun setSocketProtectionEnabled(enabled: Boolean) {
        VpnBridge.nativeSetSocketProtectionEnabled(enabled)
    }

    override fun negotiate(
        server: String,
        username: String,
        password: String,
        presharedKey: String,
        mtu: Int,
        outClientIpv4: IntArray,
        outPrimaryDnsIpv4: IntArray,
        outSecondaryDnsIpv4: IntArray,
    ): Int =
        VpnBridge.nativeNegotiate(
            server,
            username,
            password,
            presharedKey,
            mtu,
            outClientIpv4,
            outPrimaryDnsIpv4,
            outSecondaryDnsIpv4,
        )

    override fun startLoop(tunFd: Int): Int = VpnBridge.nativeStartLoop(tunFd)

    override fun stopTunnel() {
        VpnBridge.nativeStopTunnel()
    }
}
