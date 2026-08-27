package io.github.mr1ve3r.combined.engine.l2tp

/**
 * The native L2TP/IPsec engine, as [L2tpEngine] uses it.
 *
 * The C sources and their JNI peer live in the application module and stay
 * there: SPEC phase 4 is a refactor of shape, not of the native layer, and its
 * acceptance criteria include an empty diff over the C code. This interface is
 * the seam that lets `engine-l2tp` sit above that layer without depending on
 * the application it currently ships in — and it is what makes the engine
 * testable at all, since the real implementation needs a loaded `.so`.
 *
 * The signatures deliberately mirror the JNI ones, out-parameters and all, so
 * the adapter in the application is pure delegation with nowhere for a bug to
 * hide. Interpreting what comes back is [L2tpEngine]'s job, and is covered by
 * tests.
 *
 * Implementations are called from several threads: [startLoop] blocks on a
 * worker for the lifetime of the tunnel while [stopTunnel] is called from
 * whichever thread asked for the disconnect.
 */
interface L2tpNative {
    /**
     * Enables or disables protection of the engine's own UDP sockets.
     *
     * `true` for a real tunnel, where IKE and L2TP traffic must bypass the TUN
     * device. The native layer then calls back for every socket it opens, and
     * the host routes that call to the [io.github.mr1ve3r.combined.engine.SocketProtector]
     * handed to [L2tpEngine.connect].
     */
    fun setSocketProtectionEnabled(enabled: Boolean)

    /**
     * Runs IKE, the L2TP handshake and PPP on the real network. The TUN device
     * does not exist yet at this point.
     *
     * @param outClientIpv4 receives the IPCP-assigned address as four octets.
     * @param outPrimaryDnsIpv4 receives the primary DNS server as four octets.
     * @param outSecondaryDnsIpv4 receives the secondary DNS server as four octets.
     * @return `0` on success, otherwise one of the codes in [L2tpExitCode].
     */
    fun negotiate(
        server: String,
        username: String,
        password: String,
        presharedKey: String,
        mtu: Int,
        outClientIpv4: IntArray,
        outPrimaryDnsIpv4: IntArray,
        outSecondaryDnsIpv4: IntArray,
    ): Int

    /**
     * Moves packets between [tunFd] and the negotiated tunnel until the tunnel
     * ends. Blocks the calling thread.
     *
     * The descriptor stays owned by the caller; the native layer must not close
     * it.
     *
     * @return `0` on a clean shutdown, otherwise one of the codes in [L2tpExitCode].
     */
    fun startLoop(tunFd: Int): Int

    /** Asks a running [negotiate] or [startLoop] to return. Safe at any time. */
    fun stopTunnel()
}
