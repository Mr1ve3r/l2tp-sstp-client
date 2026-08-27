package io.github.mr1ve3r.combined.engine.l2tp

import android.os.ParcelFileDescriptor
import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.EngineLogEvent
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.EngineState
import io.github.mr1ve3r.combined.engine.LogLevel
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.Route
import io.github.mr1ve3r.combined.engine.SocketProtector
import io.github.mr1ve3r.combined.engine.TunnelParams
import io.github.mr1ve3r.combined.engine.VpnEngine
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [VpnEngine] over the existing native L2TP/IPsec implementation.
 *
 * This is the whole of SPEC phase 4: the Flutter to Kotlin to JNI to C path
 * that used to run inside the `VpnService` now runs behind the engine contract,
 * and the C layer is untouched. What actually moved is the decision-making —
 * reading IPCP, choosing the tunnel address, naming the routes that must bypass
 * the tunnel, turning exit codes into errors — all of which used to be spread
 * through the service and now sits in one protocol-specific place.
 *
 * The engine never sees a `VpnService`. Its sockets are kept outside the tunnel
 * through the [SocketProtector] handed to [connect]; the native layer reaches
 * that protector by way of [L2tpNativeCallbacks].
 *
 * One instance handles one connection. After [disconnect] the engine stays in a
 * terminal state, and the next attempt needs a new one.
 *
 * @property native the native engine. Injected so this class can be tested
 *   without a loaded `.so`.
 * @property dispatcher where blocking native calls run.
 * @property startWorker starts the thread the poll loop blocks on.
 * @property clock wall-clock source for [EngineState.Connected.since] and for
 *   log timestamps.
 */
class L2tpEngine(
    private val native: L2tpNative,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startWorker: (String, Runnable) -> Thread = ::defaultWorker,
    private val clock: () -> Long = System::currentTimeMillis,
) : VpnEngine {
    private val stateFlow = MutableStateFlow<EngineState>(EngineState.Idle)
    private val eventFlow =
        MutableSharedFlow<EngineLogEvent>(
            replay = 0,
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val state: StateFlow<EngineState> = stateFlow.asStateFlow()
    override val events: SharedFlow<EngineLogEvent> = eventFlow.asSharedFlow()

    private val lock = Any()
    private val disconnectRequested = AtomicBoolean(false)

    @Volatile
    private var protector: SocketProtector? = null

    @Volatile
    private var negotiated: TunnelParams? = null

    private var tun: ParcelFileDescriptor? = null
    private var loopThread: Thread? = null

    override suspend fun connect(profile: EngineProfile, protector: SocketProtector): TunnelParams {
        require(profile is EngineProfile.L2tp) {
            "L2tpEngine cannot run a ${profile.javaClass.simpleName} profile"
        }
        this.protector = protector
        L2tpNativeCallbacks.install(this)
        warnAboutUnsupportedFields(profile)

        stateFlow.value = EngineState.Connecting(STAGE_NEGOTIATING)
        log(LogLevel.INFO, "Starting native negotiation (IKE/L2TP/PPP)")
        native.setSocketProtectionEnabled(true)

        val clientIpv4 = IntArray(IPV4_OCTETS)
        val primaryDns = IntArray(IPV4_OCTETS)
        val secondaryDns = IntArray(IPV4_OCTETS)
        val code =
            withContext(dispatcher) {
                native.negotiate(
                    server = profile.server,
                    username = profile.username,
                    password = profile.password,
                    presharedKey = profile.presharedKey.orEmpty(),
                    mtu = profile.mtu,
                    outClientIpv4 = clientIpv4,
                    outPrimaryDnsIpv4 = primaryDns,
                    outSecondaryDnsIpv4 = secondaryDns,
                )
            }
        log(LogLevel.DEBUG, "nativeNegotiate finished with exit code=$code")

        if (code == L2tpExitCode.STOPPED) {
            // Cancelled from outside before the tunnel existed. That is not a
            // failure to show the user, and the contract has no state for it, so
            // it surfaces the way any cancelled suspend function does.
            finishTerminal(EngineState.Disconnected)
            releaseCallbacks()
            throw CancellationException("L2TP negotiation cancelled before the tunnel was established")
        }
        if (code != L2tpExitCode.OK) {
            val error = L2tpExitCode.toEngineError(code) ?: EngineError.Internal("exit code $code")
            fail(error)
            throw EngineException(error)
        }

        val params =
            withContext(dispatcher) {
                buildTunnelParams(profile, clientIpv4, primaryDns, secondaryDns)
            }
        negotiated = params
        stateFlow.value = EngineState.Connecting(STAGE_AWAITING_TUN)
        return params
    }

    override fun attachTun(fd: ParcelFileDescriptor) {
        synchronized(lock) { tun = fd }
        attachTunDescriptor(fd.fd) { closeTun() }
    }

    /**
     * The body of [attachTun], reachable without a `ParcelFileDescriptor`.
     *
     * `ParcelFileDescriptor` is one of the platform classes that cannot be used
     * on the unit-test classpath, so a test forced through the public entry
     * point could not exercise the poll loop at all.
     *
     * @param tunFd descriptor handed to the native poll loop.
     * @param onLoopFinished releases the descriptor once the loop has returned.
     */
    internal fun attachTunDescriptor(tunFd: Int, onLoopFinished: () -> Unit) {
        val worker =
            startWorker(WORKER_NAME) {
                runLoop(tunFd, onLoopFinished)
            }
        synchronized(lock) { loopThread = worker }
    }

    private fun runLoop(tunFd: Int, onLoopFinished: () -> Unit) {
        val code =
            try {
                log(LogLevel.DEBUG, "nativeStartLoop(tunFd=$tunFd) thread running")
                native.startLoop(tunFd)
            } catch (t: Throwable) {
                log(LogLevel.ERROR, "nativeStartLoop crashed: ${t.javaClass.simpleName}")
                fail(EngineError.Internal(t.message ?: "nativeStartLoop crashed"))
                onLoopFinished()
                return
            }
        log(LogLevel.DEBUG, "nativeStartLoop exited with code=$code")
        if (L2tpExitCode.isCleanExit(code)) {
            finishTerminal(EngineState.Disconnected)
        } else {
            fail(L2tpExitCode.toEngineError(code) ?: EngineError.Internal("exit code $code"))
        }
        onLoopFinished()
    }

    override suspend fun disconnect() {
        if (!disconnectRequested.compareAndSet(false, true)) return
        val worker = synchronized(lock) { loopThread }
        try {
            native.stopTunnel()
        } catch (t: Throwable) {
            log(LogLevel.WARN, "stopTunnel failed: ${t.javaClass.simpleName}")
        }
        if (worker != null && worker !== Thread.currentThread()) {
            withContext(dispatcher) {
                try {
                    worker.join(LOOP_JOIN_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        closeTun()
        synchronized(lock) { loopThread = null }
        finishTerminal(EngineState.Disconnected)
        releaseCallbacks()
    }

    /** Called by the native layer through [L2tpNativeCallbacks]. */
    internal fun protectNativeSocket(fd: Int): Boolean {
        val socketProtector = protector ?: return false
        val ok = socketProtector.protect(fd)
        if (!ok) {
            log(LogLevel.WARN, "protect() failed for fd=$fd; its traffic would re-enter the tunnel")
        }
        return ok
    }

    /** Called by the native layer through [L2tpNativeCallbacks] once packets move. */
    internal fun onNativeTunnelReady(detail: String?) {
        val params = negotiated ?: return
        synchronized(lock) {
            val current = stateFlow.value
            if (current is EngineState.Connected || isTerminal(current)) return
            stateFlow.value = EngineState.Connected(params, clock())
        }
        log(LogLevel.INFO, detail?.takeIf { it.isNotBlank() } ?: "TUN interface ready; tunnel loop active")
    }

    /** Called by the native layer through [L2tpNativeCallbacks] for every log line. */
    internal fun onNativeLog(priority: Int, tag: String, message: String) {
        emit(logLevelOf(priority), tag, message)
    }

    private fun buildTunnelParams(
        profile: EngineProfile.L2tp,
        clientIpv4: IntArray,
        primaryDns: IntArray,
        secondaryDns: IntArray,
    ): TunnelParams {
        val assigned = L2tpIpcp.addressOrNull(clientIpv4)
        if (assigned == null) {
            log(LogLevel.WARN, "IPCP proposed no usable address; falling back to $FALLBACK_LOCAL_IPV4")
        }
        val localAddress = assigned ?: InetAddress.getByName(FALLBACK_LOCAL_IPV4)
        val dns = L2tpIpcp.dnsServers(primaryDns, secondaryDns)
        log(
            LogLevel.INFO,
            "Negotiated address=${localAddress.hostAddress}/$HOST_PREFIX_LENGTH " +
                "mtu=${profile.mtu} dnsServers=${dns.size}",
        )
        return TunnelParams(
            localAddress = localAddress,
            prefixLength = HOST_PREFIX_LENGTH,
            dnsServers = dns,
            mtu = profile.mtu,
            excludedRoutes = serverExclusion(profile.server),
        )
    }

    /**
     * The VPN gateway itself, kept off the tunnel's default route.
     *
     * Only the engine knows which peer carries the tunnel, which is why this
     * lives here and not in the host. It is belt and braces next to
     * [SocketProtector]: `protect()` alone has proved unreliable on some vendor
     * builds when the gateway sits on the local network.
     */
    private fun serverExclusion(server: String): List<Route> = try {
        val resolved = InetAddress.getByName(server)
        if (resolved is Inet4Address) {
            listOf(Route(resolved, HOST_PREFIX_LENGTH))
        } else {
            log(LogLevel.WARN, "VPN server is not IPv4; excludeRoute not applied")
            emptyList()
        }
    } catch (e: Exception) {
        log(LogLevel.WARN, "Could not resolve VPN server for excludeRoute: ${e.message}")
        emptyList()
    }

    /**
     * Names the profile fields the native layer cannot honour yet.
     *
     * IKE identity and explicit proposals are part of the phase 2 contract but
     * have no equivalent in the C engine, and phase 4 may not change it.
     * Silently ignoring them would leave a user staring at a setting that does
     * nothing.
     */
    private fun warnAboutUnsupportedFields(profile: EngineProfile.L2tp) {
        if (!profile.ipsecEnabled) {
            log(LogLevel.WARN, "ipsecEnabled=false is not supported by the native engine; IPsec stays on")
        }
        if (!profile.localIdentifier.isNullOrBlank()) {
            log(LogLevel.WARN, "localIdentifier is not supported by the native engine; ignoring it")
        }
        if (profile.phase1Proposals.isNotEmpty() || profile.phase2Proposals.isNotEmpty()) {
            log(LogLevel.WARN, "explicit IKE proposals are not supported by the native engine; ignoring them")
        }
    }

    private fun fail(error: EngineError) {
        finishTerminal(EngineState.Failed(error))
    }

    /** Moves to [terminal] unless the engine already reached a terminal state. */
    private fun finishTerminal(terminal: EngineState) {
        synchronized(lock) {
            if (isTerminal(stateFlow.value)) return
            stateFlow.value = terminal
        }
    }

    private fun closeTun() {
        val fd = synchronized(lock) { tun.also { tun = null } } ?: return
        try {
            fd.close()
        } catch (_: Exception) {
            // The descriptor is being discarded either way.
        }
    }

    private fun releaseCallbacks() {
        protector = null
        L2tpNativeCallbacks.uninstall(this)
    }

    private fun log(level: LogLevel, message: String) {
        emit(level, TAG, message)
    }

    private fun emit(level: LogLevel, tag: String, message: String) {
        eventFlow.tryEmit(
            EngineLogEvent(
                timestamp = clock(),
                level = level,
                protocol = Protocol.L2TP,
                tag = tag,
                message = message,
            ),
        )
    }

    companion object {
        private const val TAG = "L2tpEngine"

        /** Stage reported while IKE, L2TP and PPP are being negotiated. */
        const val STAGE_NEGOTIATING: String = "ipsec_l2tp_ppp_negotiation"

        /** Stage reported once negotiation is done and the host is building the TUN. */
        const val STAGE_AWAITING_TUN: String = "awaiting_tun"

        /**
         * Address used when IPCP assigns none.
         *
         * The tunnel is unusable without a local address, and this path has
         * always fallen back to this one rather than failing the connection.
         */
        const val FALLBACK_LOCAL_IPV4: String = "10.0.0.2"

        /** A /32 covers exactly one host: the tunnel address, or the gateway. */
        private const val HOST_PREFIX_LENGTH = 32

        private const val IPV4_OCTETS = 4
        private const val EVENT_BUFFER = 256
        private const val LOOP_JOIN_TIMEOUT_MS = 8_000L
        private const val WORKER_NAME = "l2tp-engine-loop"

        private fun isTerminal(state: EngineState): Boolean = state is EngineState.Failed || state is EngineState.Disconnected

        private fun defaultWorker(name: String, body: Runnable): Thread = Thread(body, name).apply {
            isDaemon = true
            start()
        }

        /** Maps an `android.util.Log` priority onto the shared [LogLevel]. */
        internal fun logLevelOf(priority: Int): LogLevel = when {
            priority <= ANDROID_LOG_DEBUG -> LogLevel.DEBUG
            priority == ANDROID_LOG_INFO -> LogLevel.INFO
            priority == ANDROID_LOG_WARN -> LogLevel.WARN
            else -> LogLevel.ERROR
        }

        // android.util.Log priorities, spelled out so this mapping stays a pure
        // function that can be tested off-device.
        private const val ANDROID_LOG_DEBUG = 3
        private const val ANDROID_LOG_INFO = 4
        private const val ANDROID_LOG_WARN = 5
    }
}
