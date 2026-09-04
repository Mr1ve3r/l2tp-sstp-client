package io.github.mr1ve3r.combined.engine.l2tp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A scriptable stand-in for the native engine.
 *
 * The real one needs a loaded `.so` and a live IPsec peer, so every test of
 * [L2tpEngine] runs against this instead. It records what it was asked to do,
 * writes whatever IPCP result the test wants into the out-parameters, and lets
 * [startLoop] block until the test — or [stopTunnel] — releases it, which is
 * what the real poll loop does.
 */
internal class FakeL2tpNative(
    private val negotiateResult: Int = L2tpExitCode.OK,
    private val clientIpv4: IntArray = intArrayOf(10, 8, 0, 42),
    private val primaryDns: IntArray = intArrayOf(10, 8, 0, 1),
    private val secondaryDns: IntArray = IntArray(4),
    /** Exit code [startLoop] returns once it is released. */
    private val loopResult: Int = L2tpExitCode.STOPPED,
) : L2tpNative {
    /** Every [setSocketProtectionEnabled] argument, in order. */
    val protectionCalls = mutableListOf<Boolean>()

    /** Arguments of the last [negotiate] call, or `null` if it was never called. */
    @Volatile
    var negotiateArgs: NegotiateArgs? = null
        private set

    /** The descriptor [startLoop] was given, or `null` if it was never called. */
    @Volatile
    var loopFd: Int? = null
        private set

    @Volatile
    var stopCalls: Int = 0
        private set

    /** Released when [startLoop] has actually entered the native call. */
    val loopEntered = CountDownLatch(1)

    private val loopRelease = CountDownLatch(1)

    override fun setSocketProtectionEnabled(enabled: Boolean) {
        protectionCalls += enabled
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
    ): Int {
        negotiateArgs = NegotiateArgs(server, username, password, presharedKey, mtu)
        if (negotiateResult == L2tpExitCode.OK) {
            clientIpv4.copyInto(outClientIpv4)
            primaryDns.copyInto(outPrimaryDnsIpv4)
            secondaryDns.copyInto(outSecondaryDnsIpv4)
        }
        return negotiateResult
    }

    override fun startLoop(tunFd: Int): Int {
        loopFd = tunFd
        loopEntered.countDown()
        loopRelease.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return loopResult
    }

    override fun stopTunnel() {
        stopCalls++
        loopRelease.countDown()
    }

    /** Ends the loop without a [stopTunnel], the way a dropped link would. */
    fun releaseLoop() {
        loopRelease.countDown()
    }

    data class NegotiateArgs(
        val server: String,
        val username: String,
        val password: String,
        val presharedKey: String,
        val mtu: Int,
    )

    private companion object {
        const val LATCH_TIMEOUT_SECONDS = 10L
    }
}
