package io.github.evokelektrique.tunnelforge

import androidx.annotation.Keep
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.mr1ve3r.combined.core.profile.ProfileStore
import io.github.mr1ve3r.combined.core.trust.store.TrustStore
import io.github.mr1ve3r.combined.core.tunnel.NetworkEvent
import io.github.mr1ve3r.combined.core.tunnel.NetworkMonitor
import io.github.mr1ve3r.combined.core.tunnel.PackageNotInstalledException
import io.github.mr1ve3r.combined.core.tunnel.PerAppRouting
import io.github.mr1ve3r.combined.core.tunnel.SocketProtectorImpl
import io.github.mr1ve3r.combined.core.tunnel.TunnelBuilder
import io.github.mr1ve3r.combined.core.tunnel.TunnelConfig
import io.github.mr1ve3r.combined.core.tunnel.TunnelEstablishFailedException
import io.github.mr1ve3r.combined.core.tunnel.VpnServiceTunnelInterface
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.EngineState
import io.github.mr1ve3r.combined.engine.LogLevel
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TrustPolicy
import io.github.mr1ve3r.combined.engine.TunnelParams
import io.github.mr1ve3r.combined.engine.VpnEngine
import io.github.mr1ve3r.combined.engine.l2tp.L2tpEngine
import io.github.mr1ve3r.combined.engine.l2tp.L2tpNativeCallbacks
import io.github.mr1ve3r.combined.engine.sstp.SstpEngine
import io.github.mr1ve3r.combined.engine.sstp.TrustStoreCertificateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Foreground [VpnService]: TUN, notification, native tunnel thread. */
@Keep
class TunnelVpnService : VpnService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()
    private val pendingStopSelfRunnable =
        Runnable {
            try {
                stopSelf()
            } catch (e: Exception) {
                AppLog.e(TAG, "stopSelf after tunnel", e)
            }
        }

    private val running = AtomicBoolean(false)
    private val connectedEmitted = AtomicBoolean(false)
    private var tunInterface: ParcelFileDescriptor? = null
    private var setupThread: Thread? = null

    /**
     * The L2TP engine for the current session, and the scope watching its state.
     *
     * Where a `Thread` running the native poll loop used to be. The loop is
     * still a thread, but it belongs to the engine now; what the service tracks
     * is the engine itself (SPEC phase 4.1.1).
     */
    private var activeEngine: VpnEngine? = null
    private var engineWatcher: CoroutineScope? = null

    /**
     * The request the current session was started from, kept so a reconnect can
     * repeat it without the app being involved (SPEC 7.1, В.4).
     */
    private var activeRequest: TunnelStartRequest? = null
    private var eventWatcher: CoroutineScope? = null
    private var networkWatcher: CoroutineScope? = null
    private var connectedNetwork: Network? = null
    private val reconnectAttempts = AtomicInteger(0)
    private var vpnDnsPacketBridge: VpnDnsPacketBridge? = null
    private var localProxyRuntime: ProxyServerRuntime? = null
    private var activeAttemptId: String = ""
    private var activeProxyConfig: ProxyRuntimeConfig? = null
    private var activeServer: String = ""
    private var activeProfileName: String? = null
    private var activeProtocol: TunnelProtocol = TunnelProtocol.L2TP
    private var connectedSince: Long = 0L

    /** What the engine negotiated for the live session, or `null` between sessions. */
    private var activeTunnelParams: TunnelParams? = null

    /**
     * UID traffic counters when the tunnel came up, subtracted from the current
     * ones so the status screen shows this session rather than this boot.
     *
     * ponytail: counts every socket this application opens, which outside the
     * tunnel is a connectivity check now and then. Per-interface counters would
     * need the TUN device name, which the platform does not hand back.
     */
    private var trafficBaselineRx: Long = 0L
    private var trafficBaselineTx: Long = 0L

    /**
     * Where a start with no arguments reads the profile store (SPEC В.13).
     *
     * The store suspends and touches a database, which `onStartCommand` may
     * not do. Cancelled in [onDestroy], so a profile that arrives after the
     * service is gone starts nothing.
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun cancelPendingStopSelf() {
        mainHandler.removeCallbacks(pendingStopSelfRunnable)
    }

    private fun schedulePendingStopSelf() {
        mainHandler.removeCallbacks(pendingStopSelfRunnable)
        mainHandler.postDelayed(pendingStopSelfRunnable, 300L)
    }

    // This application stays outside the tunnel in every mode (SPEC 3.1).
    // Upstream did the opposite; see effectiveInclusivePackages for the
    // reasoning and docs/MANUAL_TEST_PHASE3.md for what that changed.
    private fun perAppRoutingFor(
        splitTunnelEnabled: Boolean,
        splitTunnelMode: String,
        inclusivePackages: List<String>,
        exclusivePackages: List<String>,
    ): PerAppRouting =
        when {
            !splitTunnelEnabled -> PerAppRouting.AllApps
            splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE -> PerAppRouting.Include(inclusivePackages.toSet())
            splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE -> PerAppRouting.Exclude(exclusivePackages.toSet())
            else -> PerAppRouting.AllApps
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        cancelPendingStopSelf()
        storeScope.cancel()
        if (hasActiveSession()) {
            stopTunnelInternal()
        }
        instance = null
        super.onDestroy()
    }

    /**
     * The user revoked the VPN permission, or another VPN replaced this one.
     *
     * Android has already torn the interface down by the time this runs; what
     * is left is to stop the engine, release everything and tell the UI, which
     * upstream never did — the app went on showing a connected tunnel that no
     * longer existed (SPEC 7.1.6).
     */
    override fun onRevoke() {
        val attemptId = currentAttemptId()
        VpnTunnelEvents.emitEngineLog(
            Log.WARN,
            TAG,
            "${prefixAttempt(attemptId)}VPN permission revoked; stopping the tunnel",
        )
        cancelPendingStopSelf()
        if (hasActiveSession()) {
            stopTunnelInternal()
        }
        VpnTunnelEvents.emit(VpnContract.TUNNEL_STOPPED, "VPN permission was revoked.", attemptId)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            AppLog.w(TAG, "stopSelf after revoke", e)
        }
        super.onRevoke()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelPendingStopSelf()
                val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: ""
                VpnTunnelEvents.emitEngineLog(Log.INFO, TAG, "${prefixAttempt(attemptId)}ACTION_STOP: tearing down tunnel")
                val hadActiveSession = hasActiveSession()
                val stoppedAttemptId = synchronized(sessionLock) { activeAttemptId }
                if (VpnStopAttemptPolicy.shouldIgnoreStopRequest(attemptId, stoppedAttemptId)) {
                    VpnTunnelEvents.emitEngineLog(
                        Log.DEBUG,
                        TAG,
                        "${prefixAttempt(attemptId)}Ignoring stale tunnel stop activeAttempt=$stoppedAttemptId",
                    )
                    return START_NOT_STICKY
                }
                if (hadActiveSession) {
                    stopTunnelInternal()
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_STOPPED, "Stopped by app", stoppedAttemptId)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Required immediately after Context.startForegroundService(); must run before any early return.
                startForegroundWithType(buildNotification(getString(R.string.vpn_notification_connecting)))
                val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: ""
                val server = intent.getStringExtra(EXTRA_SERVER)
                if (server.isNullOrEmpty()) {
                    AppLog.e(TAG, "${prefixAttempt(attemptId)}ACTION_START missing server")
                    VpnTunnelEvents.emitEngineLog(Log.ERROR, TAG, "${prefixAttempt(attemptId)}ACTION_START rejected: missing server")
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "Invalid tunnel arguments from the app.", attemptId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val user = intent.getStringExtra(EXTRA_USER) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val psk = intent.getStringExtra(EXTRA_PSK) ?: ""
                val dnsAutomatic = intent.getBooleanExtra(EXTRA_DNS_AUTOMATIC, true)
                val dnsServers = manualDnsServersFromIntent(intent)
                val tunMtu = sanitizeMtu(intent.getIntExtra(EXTRA_MTU, DEFAULT_TUN_MTU))
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)?.trim().orEmpty()
                val splitTunnelEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false)
                val splitTunnelMode =
                    intent.getStringExtra(EXTRA_SPLIT_TUNNEL_MODE)
                        ?: VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE
                val inclusivePackages =
                    intent.getStringArrayListExtra(EXTRA_SPLIT_TUNNEL_INCLUSIVE_PACKAGES)
                val exclusivePackages =
                    intent.getStringArrayListExtra(EXTRA_SPLIT_TUNNEL_EXCLUSIVE_PACKAGES)
                val proxyHttpPort =
                    ProxyTunnelService.sanitizePort(
                        intent.getIntExtra(EXTRA_PROXY_HTTP_PORT, ProxyTunnelService.DEFAULT_HTTP_PORT),
                        ProxyTunnelService.DEFAULT_HTTP_PORT,
                    )
                val proxySocksPort =
                    ProxyTunnelService.sanitizePort(
                        intent.getIntExtra(EXTRA_PROXY_SOCKS_PORT, ProxyTunnelService.DEFAULT_SOCKS_PORT),
                        ProxyTunnelService.DEFAULT_SOCKS_PORT,
                    )
                val proxyAllowLan = intent.getBooleanExtra(EXTRA_PROXY_ALLOW_LAN, false)
                val proxyConfig =
                    ProxyRuntimeConfig(
                        httpEnabled = true,
                        httpPort = proxyHttpPort,
                        socksEnabled = true,
                        socksPort = proxySocksPort,
                        allowLanConnections = proxyAllowLan,
                    )
                if (proxyConfig.httpPort == proxyConfig.socksPort) {
                    AppLog.e(TAG, "${prefixAttempt(attemptId)}ACTION_START invalid proxy ports http=${proxyConfig.httpPort} socks=${proxyConfig.socksPort}")
                    VpnTunnelEvents.emitEngineLog(Log.ERROR, TAG, "${prefixAttempt(attemptId)}ACTION_START rejected: duplicate proxy ports")
                    VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "HTTP and SOCKS5 ports must differ.", attemptId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
                        running = running.get(),
                        hasSetupThread = setupThread != null,
                        hasEngine = activeEngine != null,
                        hasTunInterface = tunInterface != null,
                        hasDnsServer = vpnDnsPacketBridge != null,
                        hasLocalProxyRuntime = localProxyRuntime != null,
                    )
                ) {
                    VpnTunnelEvents.emitEngineLog(
                        Log.DEBUG,
                        TAG,
                        "${prefixAttempt(activeAttemptId)}Stopping previous tunnel session reason=restart",
                    )
                    stopTunnelInternal()
                }
                val protocol = TunnelProtocol.fromWireValue(intent.getStringExtra(EXTRA_PROTOCOL))
                val request =
                    TunnelStartRequest(
                        attemptId = attemptId,
                        protocol = protocol,
                        profile = engineProfileFrom(intent, protocol, server, user, password, psk, tunMtu),
                        profileName = profileName.ifEmpty { null },
                        dnsAutomatic = dnsAutomatic,
                        dnsServers = dnsServers,
                        splitTunnelEnabled = splitTunnelEnabled,
                        splitTunnelMode = splitTunnelMode,
                        inclusivePackages = inclusivePackages,
                        exclusivePackages = exclusivePackages,
                        proxyConfig = proxyConfig,
                    )
                beginSession(request)
                RuntimeEnvironmentInfo.emit(this, TAG, prefixAttempt(attemptId), mode = VpnContract.MODE_VPN_TUNNEL)
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}ACTION_START accepted protocol=${protocol.wireValue} server=$server userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dnsMode=${if (dnsAutomatic) "automatic" else "manual"} dns=${dnsServers.joinToString(",") { "${it.host}[${it.protocol.shortLabel}]" }} mtu=$tunMtu splitTunnelEnabled=$splitTunnelEnabled splitTunnelMode=$splitTunnelMode inclusiveApps=${inclusivePackages?.size ?: 0} exclusiveApps=${exclusivePackages?.size ?: 0} http=${proxyConfig.httpPort} socks=${proxyConfig.socksPort} lan=${if (proxyAllowLan) "on" else "off"}",
                )
                // TUN establish() can block; do not hold up onStartCommand after startForeground.
                startSetupThread(request)
                return START_STICKY
            }
            else -> {
                if (intent?.action != null) {
                    AppLog.w(TAG, "Unknown action: ${intent.action}")
                    if (!running.get()) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                // No action and no extras is how the system starts an always-on
                // tunnel, and how it restarts a sticky service it killed. The
                // profile has to come from the store; there is nobody to ask
                // (SPEC В.13).
                if (running.get()) {
                    return START_STICKY
                }
                startForegroundWithType(buildNotification(getString(R.string.vpn_notification_connecting)))
                startFromStoredProfile()
                return START_STICKY
            }
        }
    }

    private fun hasActiveSession(): Boolean =
        TunnelVpnServiceStopPolicy.shouldEmitStoppedOnActionStop(
            running = running.get(),
            hasSetupThread = setupThread != null,
            hasEngine = activeEngine != null,
            hasTunInterface = tunInterface != null,
            hasDnsServer = vpnDnsPacketBridge != null,
            hasLocalProxyRuntime = localProxyRuntime != null,
        )

    private fun currentAttemptId(): String =
        synchronized(sessionLock) { activeAttemptId }

    private fun shouldHandleAttempt(attemptId: String, stage: String): Boolean {
        val currentAttemptId = currentAttemptId()
        val matches = attemptId.isEmpty() || attemptId == currentAttemptId
        if (!matches) {
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "${prefixAttempt(attemptId)}Ignoring stale attempt stage=$stage activeAttempt=$currentAttemptId",
            )
        }
        return matches
    }

    private fun emitAttemptState(
        attemptId: String,
        state: String,
        detail: String,
        errorKey: String? = null,
    ): Boolean {
        if (!shouldHandleAttempt(attemptId, "state:$state")) return false
        VpnTunnelEvents.emit(state, detail, attemptId, errorKey)
        return true
    }

    private fun stopServiceForAttempt(attemptId: String) {
        if (!shouldHandleAttempt(attemptId, "stop-service")) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearSetupThreadIfCurrent(thread: Thread) {
        synchronized(sessionLock) {
            if (setupThread === thread) {
                setupThread = null
            }
        }
    }

    private fun startTunnel(request: TunnelStartRequest) {
        val attemptId = request.attemptId
        val protocol = request.protocol
        val dnsAutomatic = request.dnsAutomatic
        val dnsServers = request.dnsServers
        val splitTunnelEnabled = request.splitTunnelEnabled
        val splitTunnelMode = request.splitTunnelMode
        val inclusivePackages = request.inclusivePackages
        val exclusivePackages = request.exclusivePackages
        val proxyConfig = request.proxyConfig
        val currentSetupThread = Thread.currentThread()
        val nativeOwner = nativeOwner(attemptId)
        var nativeOwnerAcquired = false
        var nativeLoopStarted = false
        var startedEngine: VpnEngine? = null
        try {
            cancelPendingStopSelf()
            if (running.getAndSet(true)) {
                AppLog.w(TAG, "${prefixAttempt(attemptId)}Tunnel already running")
                return
            }
            connectedEmitted.set(false)
            activeProxyConfig = proxyConfig

            val requestedInclusivePkgs =
                requestedInclusivePackages(
                    splitTunnelEnabled = splitTunnelEnabled,
                    splitTunnelMode = splitTunnelMode,
                    inclusivePackages = inclusivePackages,
                )
            if (splitTunnelEnabled &&
                splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE &&
                requestedInclusivePkgs.isEmpty()
            ) {
                emitAttemptState(
                    attemptId,
                    VpnContract.TUNNEL_FAILED,
                    "Inclusive split tunneling needs at least one selected app.",
                )
                VpnTunnelEvents.emitEngineLog(
                    Log.ERROR,
                    TAG,
                    "${prefixAttempt(attemptId)}Rejected start: inclusive split-tunnel list is empty",
                )
                running.set(false)
                stopServiceForAttempt(attemptId)
                return
            }
            val effectiveInclusivePkgs =
                effectiveInclusivePackages(
                    splitTunnelEnabled = splitTunnelEnabled,
                    splitTunnelMode = splitTunnelMode,
                    inclusivePackages = inclusivePackages,
                    selfPackageName = packageName,
                )
            val effectiveExclusivePkgs =
                effectiveExclusivePackages(
                    splitTunnelEnabled = splitTunnelEnabled,
                    splitTunnelMode = splitTunnelMode,
                    exclusivePackages = exclusivePackages,
                    selfPackageName = packageName,
                )

            // Named after the protocol actually starting: a session reading
            // "IKE/L2TP/PPP" while SSTP is what was asked for sends whoever is
            // reading the log after the wrong problem.
            val negotiationLabel =
                when (protocol) {
                    TunnelProtocol.L2TP -> "IKE/L2TP/PPP"
                    TunnelProtocol.SSTP -> "TLS/SSTP/PPP"
                }
            emitAttemptState(attemptId, VpnContract.TUNNEL_CONNECTING, "Negotiating $negotiationLabel...")
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}Starting negotiation ($negotiationLabel)",
            )
            // Only the L2TP engine drives the native poll loop, and only one
            // owner of it may exist. An SSTP session never touches it, so it
            // must not queue behind it either.
            nativeOwnerAcquired =
                protocol != TunnelProtocol.L2TP ||
                    NativeTunnelSessions.shared.acquire(
                        nativeOwner,
                        reason = "vpn negotiation start",
                    )
            if (!nativeOwnerAcquired) {
                emitAttemptState(attemptId, VpnContract.TUNNEL_FAILED, "Tunnel engine is still stopping; try again.")
                running.set(false)
                stopServiceForAttempt(attemptId)
                return
            }
            // Phase 1: negotiate IKE+L2TP+PPP on the real network (no VPN tunnel
            // yet). The engine owns that path now; what comes back is what the
            // server agreed to, and the TUN is still this service's to build.
            val engine = createEngine(request.profile)
            startedEngine = engine
            synchronized(sessionLock) { activeEngine = engine }
            watchEngineEvents(engine)
            val negotiatedParams =
                try {
                    runBlocking {
                        engine.connect(
                            profile = request.profile,
                            protector = engineProtector(attemptId),
                        )
                    }
                } catch (e: CancellationException) {
                    // The engine reports a stop that arrived before the tunnel
                    // existed as cancellation. Nothing failed, so nothing is shown.
                    VpnTunnelEvents.emitEngineLog(
                        Log.INFO,
                        TAG,
                        "${prefixAttempt(attemptId)}Negotiation canceled before tunnel establishment",
                    )
                    return
                } catch (e: EngineException) {
                    emitAttemptState(
                        attemptId,
                        VpnContract.TUNNEL_FAILED,
                        engineFailureDetail(e),
                        errorKey = e.error.messageKey,
                    )
                    running.set(false)
                    VpnTunnelEvents.emitEngineLog(
                        Log.ERROR,
                        TAG,
                        "${prefixAttempt(attemptId)}Tunnel failed during negotiation error=${e.error.messageKey}",
                    )
                    stopServiceForAttempt(attemptId)
                    return
                }
            if (!shouldHandleAttempt(attemptId, "post-negotiate")) {
                return
            }

            // Phase 2: establish TUN interface now that negotiation succeeded.
            VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}Starting TUN establish()")
            val manualDnsServers =
                if (dnsAutomatic) {
                    emptyList()
                } else {
                    DnsConfigSupport.resolveUpstreamServers(dnsServers)
                }
            if ((dnsAutomatic && negotiatedParams.dnsServers.isEmpty()) ||
                (!dnsAutomatic && manualDnsServers.isEmpty())
            ) {
                emitAttemptState(
                    attemptId,
                    VpnContract.TUNNEL_FAILED,
                    if (dnsAutomatic) {
                        "PPP negotiation did not provide any DNS servers."
                    } else {
                        "Manual DNS requires at least one DNS server."
                    },
                )
                VpnTunnelEvents.emitEngineLog(
                    Log.ERROR,
                    TAG,
                    "${prefixAttempt(attemptId)}No DNS servers available after negotiation dnsMode=${if (dnsAutomatic) "automatic" else "manual"}",
                )
                running.set(false)
                stopServiceForAttempt(attemptId)
                return
            }
            if (!shouldHandleAttempt(attemptId, "pre-tun-establish")) {
                return
            }
            // The manual-DNS bridge answers packets the *native* loop diverts to
            // a virtual resolver, so it exists only on the L2TP path. An SSTP
            // session has no native loop to divert anything, and puts the user's
            // resolvers on the interface directly (SPEC В.12).
            if (!dnsAutomatic && protocol != TunnelProtocol.L2TP) {
                val unsupported = manualDnsServers.filter { it.protocol != DnsProtocol.dnsOverUdp }
                if (unsupported.isNotEmpty()) {
                    emitAttemptState(
                        attemptId,
                        VpnContract.TUNNEL_FAILED,
                        "This protocol supports plain UDP DNS servers only; " +
                            "${unsupported.joinToString(", ") { it.protocol.displayLabel }} cannot be used yet.",
                    )
                    VpnTunnelEvents.emitEngineLog(
                        Log.ERROR,
                        TAG,
                        "${prefixAttempt(attemptId)}Rejected start: manual DNS protocol not supported on ${protocol.wireValue} " +
                            "protocols=${unsupported.joinToString(",") { it.protocol.shortLabel }}",
                    )
                    running.set(false)
                    stopServiceForAttempt(attemptId)
                    return
                }
            } else if (!dnsAutomatic) {
                val dnsLogger = { level: Int, message: String ->
                    VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(attemptId)}$message")
                }
                vpnDnsPacketBridge =
                    VpnDnsPacketBridge(
                        virtualDnsIpv4 = MANUAL_DNS_VIRTUAL_IPV4,
                        exchangeClient =
                            DirectDnsExchangeClient(
                                servers = manualDnsServers,
                                logger = dnsLogger,
                                socketProtector = serviceDnsSocketProtector(),
                            ),
                        logger = dnsLogger,
                    ).also { it.start() }
                val interceptRc = VpnBridge.nativeSetVpnDnsInterceptIpv4(MANUAL_DNS_VIRTUAL_IPV4)
                if (interceptRc != 0) {
                    try {
                        vpnDnsPacketBridge?.close()
                    } catch (_: Exception) {
                    }
                    vpnDnsPacketBridge = null
                    val message = "Manual DNS intercept setup failed."
                    emitAttemptState(attemptId, VpnContract.TUNNEL_FAILED, message)
                    VpnTunnelEvents.emitEngineLog(
                        Log.ERROR,
                        TAG,
                        "${prefixAttempt(attemptId)}manual DNS intercept setup failed rc=$interceptRc",
                    )
                    running.set(false)
                    stopServiceForAttempt(attemptId)
                    return
                }
                VpnTunnelEvents.emitEngineLog(
                    Log.INFO,
                    TAG,
                    "${prefixAttempt(attemptId)}Manual DNS upstream sockets are protected and routed outside the VPN to avoid DNS routing loops.",
                )
            } else if (protocol == TunnelProtocol.L2TP) {
                VpnBridge.nativeSetVpnDnsInterceptIpv4(null)
            }
            // Everything except DNS is what the engine negotiated. DNS is the one
            // parameter the host is allowed to override, and manual DNS replaces
            // the server's resolvers with the virtual one the bridge answers on.
            val tunnelParams =
                negotiatedParams.copy(
                    dnsServers =
                        tunDnsServers(
                            dnsAutomatic = dnsAutomatic,
                            protocol = protocol,
                            negotiatedDnsServers = negotiatedParams.dnsServers,
                            manualDnsServers = manualDnsServers,
                        ),
                )
            val tunnelConfig =
                TunnelConfig(
                    sessionName = getString(R.string.vpn_session_name),
                    perAppRouting =
                        perAppRoutingFor(
                            splitTunnelEnabled,
                            splitTunnelMode,
                            effectiveInclusivePkgs,
                            effectiveExclusivePkgs,
                        ),
                    ipv4Only = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    excludeOwnPackage = packageName,
                    // SPEC 3.1.1 asks for this; upstream never called it. The
                    // choice was deferred to phase 4 and taken there (SPEC В.1).
                    //
                    // On the L2TP path it changes nothing today: tunnel_loop.c
                    // calls set_nonblock() on this very descriptor when the poll
                    // loop starts, overriding whatever the builder was told. It
                    // starts to matter in phase 6, where a Kotlin engine reads
                    // the descriptor itself, so it is set now rather than left
                    // as a silent deviation to be discovered then.
                    blocking = true,
                )
            val pfd =
                try {
                    TunnelBuilder { message ->
                        VpnTunnelEvents.emitEngineLog(Log.DEBUG, TAG, "${prefixAttempt(attemptId)}TUN $message")
                    }.build(VpnServiceTunnelInterface(Builder()), tunnelParams, tunnelConfig)
                } catch (e: PackageNotInstalledException) {
                    // Preserve the exact failure text the UI used to show.
                    throw IllegalArgumentException("Package not installed: ${e.packageName}", e)
                } catch (e: TunnelEstablishFailedException) {
                    throw IllegalStateException("TUN establish() returned null", e)
                }
            tunInterface = pfd
            if (!shouldHandleAttempt(attemptId, "post-tun-establish")) {
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
                tunInterface = null
                return
            }
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}TUN established address=${tunnelParams.localAddress.hostAddress}/${tunnelParams.prefixLength} " +
                    "mtu=${tunnelParams.mtu} dnsServers=${tunnelParams.dnsServers.size} " +
                    "excludedRoutes=${tunnelParams.excludedRoutes.size} perApp=${tunnelConfig.perAppRouting}",
            )
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}TUN established; waiting for tunnel loop readiness",
            )
            synchronized(sessionLock) { activeTunnelParams = tunnelParams }

            // Phase 3: hand the descriptor to the engine, which runs the ESP/L2TP
            // poll loop on its own thread. Everything the loop used to report
            // inline now arrives as an engine state change.
            synchronized(sessionLock) {
                if (activeAttemptId != attemptId) {
                    try {
                        pfd.close()
                    } catch (_: Exception) {
                    }
                    tunInterface = null
                    return
                }
            }
            watchEngine(attemptId, engine)
            nativeLoopStarted = true
            engine.attachTun(pfd)
        } catch (e: Exception) {
            if (shouldHandleAttempt(attemptId, "startTunnel-exception")) {
                AppLog.e(TAG, "${prefixAttempt(attemptId)}startTunnel", e)
                VpnTunnelEvents.emitEngineLog(Log.ERROR, TAG, "${prefixAttempt(attemptId)}startTunnel exception=${e.javaClass.simpleName}:${e.message}")
                emitAttemptState(attemptId, VpnContract.TUNNEL_FAILED, e.message ?: "startTunnel failed")
                running.set(false)
                try {
                    vpnDnsPacketBridge?.close()
                } catch (_: Exception) {
                }
                vpnDnsPacketBridge = null
                VpnBridge.nativeSetVpnDnsInterceptIpv4(null)
                try {
                    tunInterface?.close()
                } catch (_: Exception) {
                }
                tunInterface = null
                stopServiceForAttempt(attemptId)
            } else {
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}Suppressing stale startTunnel exception ${e.javaClass.simpleName}",
                )
            }
        } finally {
            if (!nativeLoopStarted) {
                // Every early return above lands here. The engine has to be shut
                // down on all of them, or it stays registered for the native
                // upcalls and the next attempt talks to a dead session.
                startedEngine?.let { engine ->
                    runBlocking { engine.disconnect() }
                    synchronized(sessionLock) {
                        if (activeEngine === engine) {
                            activeEngine = null
                        }
                    }
                }
                if (nativeOwnerAcquired && protocol == TunnelProtocol.L2TP) {
                    NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "vpn startup ended before loop")
                    NativeTunnelSessions.shared.release(nativeOwner, reason = "vpn startup ended before loop")
                }
            }
            clearSetupThreadIfCurrent(currentSetupThread)
        }
    }

    /**
     * Connects the profile a start with no arguments implies (SPEC В.13).
     *
     * The choice is deliberately narrow: the profile last connected, or the
     * only one there is. With several profiles and no record of which was last
     * used, an always-on start would otherwise pick one for the user, and the
     * one it picked would be a server they did not choose.
     *
     * The proxy listeners take their default ports. Their configured ones live
     * in the Flutter layer's preferences, which this path cannot read; a
     * listener on the wrong port is a smaller failure than no tunnel.
     */
    private fun startFromStoredProfile() {
        val attemptId = "auto-${System.currentTimeMillis()}"
        storeScope.launch {
            val store = ProfileStore.get(applicationContext)
            val profile = store.defaultProfile()
            val row = profile?.let { store.findWithSecrets(it.id) }
            if (row == null) {
                VpnTunnelEvents.emitEngineLog(
                    Log.ERROR,
                    TAG,
                    "${prefixAttempt(attemptId)}Start without arguments: no profile to connect",
                )
                VpnTunnelEvents.emit(VpnContract.TUNNEL_FAILED, "No profile to connect. Open the app and pick one.", attemptId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            val request =
                StoredProfileStart.requestFrom(
                    row = row,
                    trustedCertificateIds = TrustStore.get(applicationContext).certificateIdsFor(row.profile.id),
                    attemptId = attemptId,
                    proxyConfig =
                        ProxyRuntimeConfig(
                            httpEnabled = true,
                            httpPort = ProxyTunnelService.DEFAULT_HTTP_PORT,
                            socksEnabled = true,
                            socksPort = ProxyTunnelService.DEFAULT_SOCKS_PORT,
                            allowLanConnections = false,
                        ),
                )
            store.setLastProfileId(row.profile.id)
            beginSession(request)
            RuntimeEnvironmentInfo.emit(this@TunnelVpnService, TAG, prefixAttempt(attemptId), mode = VpnContract.MODE_VPN_TUNNEL)
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "${prefixAttempt(attemptId)}Start without arguments accepted protocol=${request.protocol.wireValue} " +
                    "server=${request.profile.server} profile=${request.profileName}",
            )
            startSetupThread(request)
        }
    }

    /**
     * Records a session's parameters before its setup thread starts.
     *
     * Called from `ACTION_START` and again from a reconnect, which is why it is
     * one function: a reconnect that forgot one of these fields would leave the
     * notification or the runtime snapshot describing the previous session.
     */
    private fun beginSession(request: TunnelStartRequest) {
        synchronized(sessionLock) {
            activeAttemptId = request.attemptId
            activeRequest = request
        }
        activeProxyConfig = request.proxyConfig
        activeServer = request.profile.server
        activeProfileName = request.profileName
        activeProtocol = request.protocol
        VpnTunnelEvents.sessionProtocol = request.protocol.engineProtocol
    }

    /** TUN establish() can block; keep it off the thread that called us. */
    private fun startSetupThread(request: TunnelStartRequest) {
        val thread = Thread({ startTunnel(request) }, "tun-setup")
        synchronized(sessionLock) {
            setupThread = thread
        }
        thread.start()
    }

    /**
     * The engine for [profile] — the whole protocol dispatch of this service
     * (SPEC 7.1.1).
     *
     * Everything after this point is written against [VpnEngine], which is what
     * lets one host serve both protocols.
     */
    private fun createEngine(profile: EngineProfile): VpnEngine =
        when (profile) {
            is EngineProfile.L2tp -> L2tpEngine(VpnBridgeL2tpNative)
            is EngineProfile.Sstp -> {
                val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (profile.trustPolicy == TrustPolicy.SYSTEM) {
                    // Which store SYSTEM actually means is not something the
                    // user can see from the outside, and it differs by build:
                    // only a debug build declares a network security config
                    // that trusts CAs installed through Android's settings.
                    VpnTunnelEvents.emitEngineLog(
                        Log.INFO,
                        TAG,
                        if (debuggable) {
                            "Trust policy SYSTEM: Android's system CAs and CAs installed on this device (debug build)"
                        } else {
                            "Trust policy SYSTEM: Android's system CAs only -- a CA installed through Android's " +
                                "settings is not consulted; import it into the app and pick SYSTEM_PLUS_CUSTOM or CUSTOM_ONLY"
                        },
                    )
                }
                SstpEngine(
                    certificates = TrustStoreCertificateSource(TrustStore.get(applicationContext)),
                    // The INSECURE policy must not be honoured by a release
                    // build (SPEC 5.5).
                    allowInsecureTrust = debuggable,
                )
            }
        }

    /**
     * The profile the start intent describes.
     *
     * Unknown protocols read as L2TP, because that is what every request
     * written before this field existed meant.
     */
    private fun engineProfileFrom(
        intent: Intent,
        protocol: TunnelProtocol,
        server: String,
        user: String,
        password: String,
        psk: String,
        tunMtu: Int,
    ): EngineProfile =
        when (protocol) {
            TunnelProtocol.L2TP -> EngineProfiles.l2tp(server, user, password, psk, tunMtu)
            TunnelProtocol.SSTP ->
                EngineProfiles.sstp(
                    server = server,
                    username = user,
                    password = password,
                    mtu = tunMtu,
                    port = intent.getIntExtra(EXTRA_SSTP_PORT, EngineProfile.Sstp.DEFAULT_PORT),
                    trustPolicy = intent.getStringExtra(EXTRA_SSTP_TRUST_POLICY),
                    trustedCertificateIds = intent.getStringArrayListExtra(EXTRA_SSTP_CERTIFICATE_IDS),
                    pinnedFingerprints = intent.getStringArrayListExtra(EXTRA_SSTP_PINNED_FINGERPRINTS),
                    expectedHostname = intent.getStringExtra(EXTRA_SSTP_EXPECTED_HOSTNAME),
                    minTlsVersion = intent.getStringExtra(EXTRA_SSTP_MIN_TLS_VERSION),
                    pppAuthMethods = intent.getStringArrayListExtra(EXTRA_SSTP_AUTH_METHODS),
                    proxy =
                        EngineProfiles.proxy(
                            host = intent.getStringExtra(EXTRA_SSTP_PROXY_HOST),
                            port = intent.getIntExtra(EXTRA_SSTP_PROXY_PORT, EngineProfiles.DEFAULT_PROXY_PORT),
                            username = intent.getStringExtra(EXTRA_SSTP_PROXY_USERNAME),
                            password = intent.getStringExtra(EXTRA_SSTP_PROXY_PASSWORD),
                        ),
                )
        }

    /**
     * Copies the engine's own log stream into the single buffer the UI reads
     * (SPEC 7.1.7).
     *
     * L2TP is deliberately not copied: its lines already reach the buffer from
     * the JNI bridge, which forwards them whether or not an engine is
     * registered, and copying them here would double every native line.
     */
    private fun watchEngineEvents(engine: VpnEngine) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        synchronized(sessionLock) {
            eventWatcher?.cancel()
            eventWatcher = scope
        }
        scope.launch {
            engine.events.collect { event ->
                if (event.protocol == Protocol.L2TP) return@collect
                VpnTunnelEvents.emitEngineLog(
                    priority = androidPriorityOf(event.level),
                    tag = event.tag,
                    message = event.message,
                    protocol = event.protocol,
                )
            }
        }
    }

    /**
     * Reconnects the session when the active network is replaced.
     *
     * This lives in the host rather than in an engine because the answer is the
     * same for both protocols, and because the engine that was talking over the
     * old network is exactly the thing that has to be thrown away (SPEC В.4).
     * Before this existed, a Wi-Fi to LTE switch left the L2TP engine sending
     * ESP through a socket bound to a route that no longer resolved, which is
     * finding В.7.
     */
    private fun watchNetwork(attemptId: String) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        synchronized(sessionLock) {
            networkWatcher?.cancel()
            networkWatcher = scope
            // Seeded from the first event rather than from `activeNetwork`:
            // with a tunnel up, the active network *is* the tunnel, and every
            // underlying network would then look like a change.
            connectedNetwork = null
        }
        val triggered = AtomicBoolean(false)
        scope.launch {
            NetworkMonitor(manager).events().collect { event ->
                if (event !is NetworkEvent.Available) return@collect
                val previous = synchronized(sessionLock) { connectedNetwork }
                if (previous == null) {
                    synchronized(sessionLock) { connectedNetwork = event.network }
                    return@collect
                }
                if (event.network == previous) return@collect
                if (!triggered.compareAndSet(false, true)) return@collect
                mainHandler.post { requestReconnect(attemptId, "network changed to ${event.transport}") }
            }
        }
    }

    /** Tears the session down and starts it again from the stored request. */
    private fun requestReconnect(attemptId: String, reason: String) {
        val request =
            synchronized(sessionLock) {
                if (activeAttemptId == attemptId) activeRequest else null
            } ?: return
        val attempt = reconnectAttempts.incrementAndGet()
        VpnTunnelEvents.emitEngineLog(
            Log.WARN,
            TAG,
            "${prefixAttempt(attemptId)}Reconnecting attempt=$attempt reason=$reason",
        )
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            emitAttemptState(
                attemptId,
                VpnContract.TUNNEL_FAILED,
                "The network kept changing; gave up after $MAX_RECONNECT_ATTEMPTS reconnect attempts.",
            )
            stopTunnelInternal()
            stopServiceForAttempt(attemptId)
            return
        }
        emitAttemptState(
            attemptId,
            VpnContract.TUNNEL_RECONNECTING,
            "Network changed; reconnecting (attempt $attempt)...",
        )
        updateForegroundNotification(getString(R.string.vpn_notification_connecting))
        Thread(
            {
                stopTunnelInternal(preserveSession = true)
                try {
                    Thread.sleep(reconnectDelayMs(attempt))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                val stillCurrent = synchronized(sessionLock) { activeRequest === request }
                if (!stillCurrent) return@Thread
                beginSession(request)
                startSetupThread(request)
            },
            "tun-reconnect",
        ).start()
    }

    /**
     * Turns the engine's state into the tunnel events the app already speaks.
     *
     * This is what the poll-loop thread's own `finally` block used to do. The
     * engine reaches a terminal state exactly once per session, so the watcher
     * stops itself as soon as it sees one.
     */
    private fun watchEngine(attemptId: String, engine: VpnEngine) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        synchronized(sessionLock) {
            engineWatcher?.cancel()
            engineWatcher = scope
        }
        scope.launch {
            engine.state.collect { state ->
                when (state) {
                    is EngineState.Connected ->
                        mainHandler.post { handleTunnelReady(attemptId, engine) }
                    is EngineState.Failed -> {
                        emitAttemptState(
                            attemptId,
                            VpnContract.TUNNEL_FAILED,
                            state.error.detail ?: "Tunnel engine failed",
                            errorKey = state.error.messageKey,
                        )
                        mainHandler.post { finishTunnelUiOnMain(attemptId) }
                        scope.cancel()
                    }
                    is EngineState.Disconnected -> {
                        emitAttemptState(attemptId, VpnContract.TUNNEL_STOPPED, "Tunnel closed normally")
                        mainHandler.post { finishTunnelUiOnMain(attemptId) }
                        scope.cancel()
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Always release TUN + worker state. Safe when the engine thread already cleared [running]
     * (otherwise [onDestroy] would return early and leak the VPN fd).
     */
    private fun stopTunnelInternal(preserveSession: Boolean = false) {
        val capturedSetupThread = setupThread
        val capturedAttemptId = synchronized(sessionLock) { activeAttemptId }
        val owner =
            capturedAttemptId
                .takeIf { it.isNotEmpty() && activeProtocol == TunnelProtocol.L2TP }
                ?.let(::nativeOwner)
        NativeTunnelSessions.shared.stopOwner(owner, reason = "vpn service stop")
        try {
            if (capturedSetupThread != null && capturedSetupThread !== Thread.currentThread()) {
                capturedSetupThread.join(8_000)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val capturedEngine = synchronized(sessionLock) { activeEngine }
        // disconnect() stops the native loop, waits for its thread and releases
        // the TUN descriptor. It is safe in any engine state, including one that
        // never got as far as attaching a TUN.
        capturedEngine?.let { engine -> runBlocking { engine.disconnect() } }
        synchronized(sessionLock) {
            if (setupThread === capturedSetupThread) {
                setupThread = null
            }
            if (activeEngine === capturedEngine) {
                activeEngine = null
            }
            engineWatcher?.cancel()
            engineWatcher = null
            eventWatcher?.cancel()
            eventWatcher = null
            networkWatcher?.cancel()
            networkWatcher = null
            connectedNetwork = null
            activeTunnelParams = null
        }
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        try {
            vpnDnsPacketBridge?.close()
        } catch (_: Exception) {
        }
        vpnDnsPacketBridge = null
        VpnBridge.nativeSetVpnDnsInterceptIpv4(null)
        stopLocalProxyRuntime()
        // A reconnect keeps the session: same attempt id, same request, same
        // notification. Only the engine and the interface are thrown away.
        if (!preserveSession) {
            synchronized(sessionLock) {
                activeAttemptId = ""
                activeRequest = null
            }
            activeProxyConfig = null
            activeServer = ""
            activeProfileName = null
            reconnectAttempts.set(0)
            connectedSince = 0L
            VpnTunnelEvents.sessionProtocol = null
        }
        connectedEmitted.set(false)
        running.set(false)
        owner?.let {
            NativeTunnelSessions.shared.release(it, reason = "vpn service stopped")
        }
    }

    /** Main-thread cleanup after the engine reached a terminal state. */
    private fun finishTunnelUiOnMain(attemptId: String) {
        if (!shouldHandleAttempt(attemptId, "finish-ui")) return
        synchronized(sessionLock) {
            activeEngine = null
            engineWatcher = null
            eventWatcher?.cancel()
            eventWatcher = null
            networkWatcher?.cancel()
            networkWatcher = null
            connectedNetwork = null
        }
        running.set(false)
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        try {
            vpnDnsPacketBridge?.close()
        } catch (_: Exception) {
        }
        vpnDnsPacketBridge = null
        VpnBridge.nativeSetVpnDnsInterceptIpv4(null)
        stopLocalProxyRuntime()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            AppLog.e(TAG, "stopForeground after tunnel", e)
        }
        synchronized(sessionLock) {
            if (activeAttemptId == attemptId) {
                activeAttemptId = ""
            }
        }
        activeProxyConfig = null
        activeServer = ""
        activeProfileName = null
        connectedEmitted.set(false)
        reconnectAttempts.set(0)
        connectedSince = 0L
        VpnTunnelEvents.sessionProtocol = null
        synchronized(sessionLock) { activeRequest = null }
        if (activeProtocol == TunnelProtocol.L2TP) {
            NativeTunnelSessions.shared.release(nativeOwner(attemptId), reason = "vpn loop finished")
        }
        schedulePendingStopSelf()
    }

    /**
     * Reacts to [EngineState.Connected]: the tunnel carries packets.
     *
     * Reached from the engine watcher rather than straight from JNI, so a
     * report from a session that has already been replaced is dropped by the
     * engine identity check rather than by comparing worker threads.
     */
    private fun handleTunnelReady(attemptId: String, engine: VpnEngine) {
        if (synchronized(sessionLock) { activeEngine } !== engine) {
            AppLog.w(TAG, "Ignoring tunnel ready from a superseded engine")
            return
        }
        if (!running.get()) {
            AppLog.w(TAG, "Ignoring native tunnel ready after shutdown")
            return
        }
        if (!shouldHandleAttempt(attemptId, "tunnel-ready")) return
        try {
            startLocalProxyRuntime()
        } catch (e: Exception) {
            AppLog.e(TAG, "${prefixAttempt(attemptId)}local proxy startup failed", e)
            VpnTunnelEvents.emitEngineLog(
                Log.ERROR,
                TAG,
                "${prefixAttempt(attemptId)}Local proxy startup failed error=${e.message ?: e.javaClass.simpleName}",
            )
            emitAttemptState(attemptId, VpnContract.TUNNEL_FAILED, e.message ?: "Local proxy startup failed")
            stopTunnelInternal()
            stopServiceForAttempt(attemptId)
            return
        }
        if (!connectedEmitted.compareAndSet(false, true)) {
            return
        }
        connectedSince = System.currentTimeMillis()
        trafficBaselineRx = TrafficStats.getUidRxBytes(Process.myUid())
        trafficBaselineTx = TrafficStats.getUidTxBytes(Process.myUid())
        reconnectAttempts.set(0)
        watchNetwork(attemptId)
        emitAttemptState(attemptId, VpnContract.TUNNEL_CONNECTED, TUNNEL_READY_DETAIL)
        updateForegroundNotification(connectedNotificationText())
    }

    private fun startLocalProxyRuntime() {
        if (localProxyRuntime != null) return
        val config = activeProxyConfig ?: throw IllegalStateException("Local proxy settings are missing.")
        val exposure =
            LanProxyAddressResolver(this).resolve(
                httpPort = config.httpPort,
                socksPort = config.socksPort,
                lanRequested = config.allowLanConnections,
            )
        val portSelection =
            ProxyPortAllocator.choosePreferredThenRandom(
                requestedHttpPort = config.httpPort,
                requestedSocksPort = config.socksPort,
                bindHosts = exposure.listenerBindAddresses(),
            ) ?: throw IllegalStateException(
                "Couldn't start local proxy listeners: no available HTTP/SOCKS5 ports after ${ProxyPortAllocator.RANDOM_CHECKS} random checks.",
            )
        val effectiveConfig =
            config.copy(
                httpPort = portSelection.httpPort,
                socksPort = portSelection.socksPort,
            )
        val effectiveExposure =
            exposure.copy(
                httpPort = portSelection.httpPort,
                socksPort = portSelection.socksPort,
            )
        if (portSelection.randomFallbackUsed) {
            VpnTunnelEvents.emitEngineLog(
                Log.WARN,
                TAG,
                "${prefixAttempt(activeAttemptId)}Default proxy ports unavailable; selected random fallback ports requestedHttp=${config.httpPort} requestedSocks=${config.socksPort} http=${portSelection.httpPort} socks=${portSelection.socksPort}",
            )
        } else if (portSelection.differsFrom(config.httpPort, config.socksPort)) {
            VpnTunnelEvents.emitEngineLog(
                Log.WARN,
                TAG,
                "${prefixAttempt(activeAttemptId)}Proxy listener ports changed requestedHttp=${config.httpPort} requestedSocks=${config.socksPort} http=${portSelection.httpPort} socks=${portSelection.socksPort}",
            )
        }
        activeProxyConfig = effectiveConfig
        val logger = { level: Int, message: String ->
            VpnTunnelEvents.emitEngineLog(level, TAG, "${prefixAttempt(activeAttemptId)}$message")
        }
        val runtime =
            ProxyServerRuntime(
                config = localProxyRuntimeConfig(effectiveConfig, effectiveExposure),
                transport = DirectSocketProxyTransport(logger = logger),
                levelLogger = logger,
            )
        try {
            runtime.start()
            localProxyRuntime = runtime
            VpnTunnelEvents.emitProxyExposureChanged(effectiveExposure)
            effectiveExposure.warning?.let { warning ->
                VpnTunnelEvents.emitEngineLog(
                    Log.WARN,
                    TAG,
                    "${prefixAttempt(activeAttemptId)}$warning",
                )
            }
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(activeAttemptId)}Local proxy ready ${runtime.endpointSummary()}",
            )
        } catch (e: Exception) {
            try {
                runtime.stop()
            } catch (_: Exception) {
            }
            throw IllegalStateException(
                "Couldn't start local proxy listeners: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    /**
     * The engine's only route to `VpnService.protect()`.
     *
     * Handing this over instead of the service is what stops an engine reaching
     * `VpnService.Builder` and configuring the interface behind the host's back.
     */
    private fun engineProtector(attemptId: String): SocketProtectorImpl =
        SocketProtectorImpl(this) { message ->
            VpnTunnelEvents.emitEngineLog(Log.WARN, TAG, "${prefixAttempt(attemptId)}$message")
        }

    /** The text shown for a failed connection attempt. */
    /**
     * The live session as the status screen reads it, or `null` when the TUN
     * interface is not up yet.
     */
    private fun sessionInfo(): TunnelSessionInfo? {
        val params = synchronized(sessionLock) { activeTunnelParams } ?: return null
        val proxyHost = (synchronized(sessionLock) { activeRequest }?.profile as? EngineProfile.Sstp)?.proxy?.host
        return TunnelSessionInfo(
            protocol = activeProtocol.name.lowercase(),
            address = params.localAddress.hostAddress.orEmpty(),
            dnsServers = params.dnsServers.mapNotNull { it.hostAddress },
            mtu = params.mtu,
            since = connectedSince,
            rxBytes = (TrafficStats.getUidRxBytes(Process.myUid()) - trafficBaselineRx).coerceAtLeast(0L),
            txBytes = (TrafficStats.getUidTxBytes(Process.myUid()) - trafficBaselineTx).coerceAtLeast(0L),
            proxyHost = proxyHost,
        )
    }

    private fun engineFailureDetail(e: EngineException): String =
        e.error.detail ?: e.message ?: "Tunnel engine failed"

    private fun serviceDnsSocketProtector(): DnsSocketProtector =
        object : DnsSocketProtector {
            override fun protect(socket: Socket): Boolean =
                this@TunnelVpnService.protect(socket)

            override fun protect(socket: DatagramSocket): Boolean =
                this@TunnelVpnService.protect(socket)
        }

    private fun stopLocalProxyRuntime() {
        val config = activeProxyConfig
        try {
            localProxyRuntime?.stop()
        } catch (e: Exception) {
            AppLog.w(TAG, "localProxyRuntime.stop", e)
        }
        localProxyRuntime = null
        VpnTunnelEvents.emitProxyExposureChanged(
            ProxyExposureInfo.inactive(
                httpPort = config?.httpPort ?: 0,
                socksPort = config?.socksPort ?: 0,
                lanRequested = config?.allowLanConnections ?: false,
            ),
        )
    }

    private fun buildNotification(text: String): Notification = buildNotification(text, showTimer = false)

    /**
     * The one notification both protocols share (SPEC 7.1.3).
     *
     * @param showTimer whether to run the session timer. The platform's own
     *   chronometer is used rather than a ticking update, so the elapsed time
     *   stays right without the service waking up once a second.
     */
    private fun buildNotification(text: String, showTimer: Boolean): Notification {
        createChannelIfNeeded()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setShowWhen(showTimer)
            .setUsesChronometer(showTimer)
            .apply { if (showTimer && connectedSince > 0L) setWhen(connectedSince) }
            .addAction(
                0,
                getString(R.string.vpn_notification_action_disconnect),
                disconnectPendingIntent(),
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(text: String) {
        startForegroundWithType(buildNotification(text, showTimer = connectedSince > 0L))
    }

    /**
     * What the notification says while connected: protocol, then the profile
     * name or the server it is talking to.
     */
    private fun connectedNotificationText(): String {
        val label = notificationLabel(activeProtocol, activeProfileName, activeServer)
        return if (label.isEmpty()) {
            getString(R.string.vpn_notification_connected)
        } else {
            getString(R.string.vpn_notification_connected_profile, label)
        }
    }

    private fun disconnectPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            REQUEST_CODE_DISCONNECT,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "TunnelVpnService"
        const val ACTION_START = "io.github.evokelektrique.tunnelforge.action.START"
        const val ACTION_STOP = "io.github.evokelektrique.tunnelforge.action.STOP"
        const val EXTRA_ATTEMPT_ID = "attemptId"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_DNS_AUTOMATIC = "dnsAutomatic"
        const val EXTRA_DNS_SERVER_1_HOST = "dnsServer1Host"
        const val EXTRA_DNS_SERVER_1_PROTOCOL = "dnsServer1Protocol"
        const val EXTRA_DNS_SERVER_2_HOST = "dnsServer2Host"
        const val EXTRA_DNS_SERVER_2_PROTOCOL = "dnsServer2Protocol"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_PROFILE_NAME = "profileName"
        const val EXTRA_SPLIT_TUNNEL_ENABLED = "splitTunnelEnabled"
        const val EXTRA_SPLIT_TUNNEL_MODE = "splitTunnelMode"
        const val EXTRA_SPLIT_TUNNEL_INCLUSIVE_PACKAGES = "splitTunnelInclusivePackages"
        const val EXTRA_SPLIT_TUNNEL_EXCLUSIVE_PACKAGES = "splitTunnelExclusivePackages"
        const val EXTRA_PROXY_HTTP_PORT = "proxyHttpPort"
        const val EXTRA_PROXY_SOCKS_PORT = "proxySocksPort"
        const val EXTRA_PROXY_ALLOW_LAN = "proxyAllowLan"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_SSTP_PORT = "sstpPort"
        const val EXTRA_SSTP_TRUST_POLICY = "sstpTrustPolicy"
        const val EXTRA_SSTP_CERTIFICATE_IDS = "sstpCertificateIds"
        const val EXTRA_SSTP_PINNED_FINGERPRINTS = "sstpPinnedFingerprints"
        const val EXTRA_SSTP_EXPECTED_HOSTNAME = "sstpExpectedHostname"
        const val EXTRA_SSTP_MIN_TLS_VERSION = "sstpMinTlsVersion"
        const val EXTRA_SSTP_AUTH_METHODS = "sstpAuthMethods"
        const val EXTRA_SSTP_PROXY_HOST = "sstpProxyHost"
        const val EXTRA_SSTP_PROXY_PORT = "sstpProxyPort"
        const val EXTRA_SSTP_PROXY_USERNAME = "sstpProxyUsername"
        const val EXTRA_SSTP_PROXY_PASSWORD = "sstpProxyPassword"

        private const val CHANNEL_ID = "tunnel_forge_vpn"
        private const val NOTIFICATION_ID = 7101
        private const val REQUEST_CODE_DISCONNECT = 7102

        /** When [EXTRA_MTU] is absent or invalid. */
        const val DEFAULT_TUN_MTU = 1450
        private const val MIN_TUN_MTU = 576
        private const val MAX_TUN_MTU = 1500

        internal const val MANUAL_DNS_VIRTUAL_IPV4 = "198.18.0.1"

        /**
         * Detail shown when the tunnel starts carrying packets.
         *
         * The native layer passes its own wording to [onNativeTunnelReady]; the
         * engine turns that into a log line, and the state change the UI reacts
         * to no longer carries protocol text.
         */
        private const val TUNNEL_READY_DETAIL = "TUN interface ready; tunnel loop active"

        /**
         * How many times a session may rebuild itself after a network change
         * before it gives up.
         *
         * A handful, not forever: a device wandering between two access points
         * would otherwise reconnect in a loop the user cannot see the end of.
         */
        internal const val MAX_RECONNECT_ATTEMPTS = 5

        fun sanitizeMtu(value: Int): Int = value.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU)

        /**
         * How long to wait before reconnect [attempt].
         *
         * Doubling, capped: the first switch is usually usable immediately, and
         * the cases that are not are the ones where the new network needs a
         * moment to finish coming up.
         */
        internal fun reconnectDelayMs(attempt: Int): Long =
            (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8_000L)

        /** An engine's severity as the Android log levels the UI already speaks. */
        internal fun androidPriorityOf(level: LogLevel): Int =
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }

        /**
         * The connected notification's subject: the protocol, and the profile
         * name when there is one, otherwise the server.
         */
        internal fun notificationLabel(
            protocol: TunnelProtocol,
            profileName: String?,
            server: String,
        ): String {
            val subject = profileName?.takeIf { it.isNotEmpty() } ?: server
            if (subject.isEmpty()) return ""
            return "${protocol.displayLabel} · $subject"
        }

        private fun normalizedPackages(packages: List<String>?): List<String> =
            packages
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()

        internal fun requestedInclusivePackages(
            splitTunnelEnabled: Boolean,
            splitTunnelMode: String,
            inclusivePackages: List<String>?,
        ): List<String> =
            if (splitTunnelEnabled &&
                splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE
            ) {
                normalizedPackages(inclusivePackages)
            } else {
                emptyList()
            }

        /**
         * Packages to route through the tunnel in inclusive mode.
         *
         * This application is **not** among them. Upstream TunnelForge added it
         * here, so its own traffic went through the tunnel; SPEC 3.1 asks for
         * the opposite, and the project owner confirmed that choice. The app
         * reaches the network only to carry the tunnel, and those sockets are
         * already excluded through [SocketProtector][
         * io.github.mr1ve3r.combined.engine.SocketProtector].
         *
         * @param selfPackageName this application, filtered out rather than
         *   added. Kept as a parameter so the intent is visible at call sites.
         */
        internal fun effectiveInclusivePackages(
            splitTunnelEnabled: Boolean,
            splitTunnelMode: String,
            inclusivePackages: List<String>?,
            selfPackageName: String,
        ): List<String> =
            if (splitTunnelEnabled &&
                splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE
            ) {
                requestedInclusivePackages(splitTunnelEnabled, splitTunnelMode, inclusivePackages)
                    .filterNot { it == selfPackageName }
                    .distinct()
            } else {
                emptyList()
            }

        internal fun requestedExclusivePackages(
            splitTunnelEnabled: Boolean,
            splitTunnelMode: String,
            exclusivePackages: List<String>?,
            selfPackageName: String,
        ): List<String> =
            if (splitTunnelEnabled &&
                splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE
            ) {
                normalizedPackages(exclusivePackages)
                    .filterNot { it == selfPackageName }
            } else {
                emptyList()
            }

        internal fun effectiveExclusivePackages(
            splitTunnelEnabled: Boolean,
            splitTunnelMode: String,
            exclusivePackages: List<String>?,
            selfPackageName: String,
        ): List<String> =
            if (splitTunnelEnabled &&
                splitTunnelMode == VpnContract.SPLIT_TUNNEL_MODE_EXCLUSIVE
            ) {
                requestedExclusivePackages(
                    splitTunnelEnabled = splitTunnelEnabled,
                    splitTunnelMode = splitTunnelMode,
                    exclusivePackages = exclusivePackages,
                    selfPackageName = selfPackageName,
                )
            } else {
                emptyList()
            }

        /**
         * DNS servers to put on the tunnel interface.
         *
         * In automatic mode these are the ones the engine negotiated over IPCP.
         * In manual mode the interface advertises a single virtual resolver, and
         * [VpnDnsPacketBridge] answers on it from the user's upstream servers —
         * which is what keeps DoT and DoH working inside a tunnel whose
         * interface can only advertise plain IPv4 resolvers.
         */
        internal fun tunDnsServers(
            dnsAutomatic: Boolean,
            protocol: TunnelProtocol,
            negotiatedDnsServers: List<InetAddress>,
            manualDnsServers: List<ResolvedDnsServerConfig> = emptyList(),
        ): List<InetAddress> =
            when {
                dnsAutomatic -> negotiatedDnsServers
                protocol == TunnelProtocol.L2TP -> listOf(InetAddress.getByName(MANUAL_DNS_VIRTUAL_IPV4))
                else -> manualDnsServers.map { InetAddress.getByName(it.resolvedIpv4) }
            }

        internal fun manualDnsServersFromIntent(intent: Intent): List<DnsServerConfig> {
            val servers =
                listOf(
                    DnsServerConfig(
                        host = intent.getStringExtra(EXTRA_DNS_SERVER_1_HOST)?.trim().orEmpty(),
                        protocol = DnsProtocol.fromWireValue(intent.getStringExtra(EXTRA_DNS_SERVER_1_PROTOCOL)),
                    ),
                    DnsServerConfig(
                        host = intent.getStringExtra(EXTRA_DNS_SERVER_2_HOST)?.trim().orEmpty(),
                        protocol = DnsProtocol.fromWireValue(intent.getStringExtra(EXTRA_DNS_SERVER_2_PROTOCOL)),
                    ),
                )
            return DnsConfigSupport.sanitize(servers)
        }

        internal fun localProxyRuntimeConfig(
            config: ProxyRuntimeConfig,
            exposure: ProxyExposureInfo,
        ): ProxyRuntimeConfig =
            config.copy(
                exposure = exposure,
                maxConcurrentClients = null,
            )

        @Volatile
        private var instance: TunnelVpnService? = null

        /** Whether a tunnel session is up or coming up. Read by the tile. */
        @JvmStatic
        fun isSessionActive(): Boolean = instance?.hasActiveSession() == true

        @JvmStatic
        fun stopActiveSessionForModeSwitch(reason: String): Boolean {
            val svc = instance ?: return false
            if (!svc.hasActiveSession()) return false
            VpnTunnelEvents.emitEngineLog(
                Log.DEBUG,
                TAG,
                "${svc.prefixAttempt(svc.currentAttemptId())}Stopping VPN tunnel before mode switch reason=$reason",
            )
            svc.cancelPendingStopSelf()
            svc.stopTunnelInternal()
            try {
                svc.stopForeground(STOP_FOREGROUND_REMOVE)
                svc.stopSelf()
            } catch (e: Exception) {
                AppLog.w(TAG, "stopActiveSessionForModeSwitch", e)
            }
            return true
        }

        /**
         * Called from JNI by name; keeps a native socket outside the tunnel.
         *
         * The C layer resolves this method on this class at load time, so it
         * stays here. What changed in phase 4 is where it goes: a running engine
         * protects the socket through the [SocketProtectorImpl] it was handed,
         * which is the single `protect()` call site the SPEC asks for. The
         * direct fall-back covers proxy-only mode, where no engine is installed.
         */
        @JvmStatic
        fun protectSocketFd(fd: Int): Boolean {
            L2tpNativeCallbacks.protect(fd)?.let { return it }
            val svc = instance ?: return false
            return try {
                svc.protect(fd)
            } catch (e: Exception) {
                AppLog.e(TAG, "protect failed for fd=$fd", e)
                false
            }
        }

        /**
         * Called from JNI by name once the poll loop is moving packets.
         *
         * The engine turns this into [EngineState.Connected]; the service reacts
         * to that state rather than to this call, so a report from a session
         * that has already been torn down cannot reach the UI.
         */
        @JvmStatic
        fun onNativeTunnelReady(detail: String?) {
            L2tpNativeCallbacks.tunnelReady(detail)
        }

        @JvmStatic
        fun runtimeSnapshot(): Map<String, Any?>? {
            val svc = instance ?: return null
            if (!svc.hasActiveSession()) return null
            val attemptId =
                synchronized(svc.sessionLock) {
                    svc.activeAttemptId
                }
            val connected = svc.connectedEmitted.get()
            val proxyConfig = svc.activeProxyConfig
            val proxyExposure =
                svc.localProxyRuntime?.exposureInfo()
                    ?: proxyConfig?.let {
                        ProxyExposureInfo.loopback(
                            httpPort = it.httpPort,
                            socksPort = it.socksPort,
                            lanRequested = it.allowLanConnections,
                            active = connected,
                        )
                    }
            return RuntimeStateSnapshot.tunnel(
                state = if (connected) VpnContract.TUNNEL_CONNECTED else VpnContract.TUNNEL_CONNECTING,
                detail = if (connected) svc.connectedNotificationText() else "Restoring active VPN tunnel session...",
                attemptId = attemptId,
                connectionMode = VpnContract.MODE_VPN_TUNNEL,
                proxyExposure = proxyExposure,
                session = if (connected) svc.sessionInfo() else null,
            )
        }
    }

    private fun nativeOwner(attemptId: String): NativeTunnelOwner =
        NativeTunnelOwner(VpnContract.MODE_VPN_TUNNEL, attemptId)

    private fun prefixAttempt(attemptId: String): String =
        if (attemptId.isEmpty()) "" else "attempt=$attemptId "
}

internal object TunnelVpnServiceStopPolicy {
    fun shouldEmitStoppedOnActionStop(
        running: Boolean,
        hasSetupThread: Boolean,
        hasEngine: Boolean,
        hasTunInterface: Boolean,
        hasDnsServer: Boolean,
        hasLocalProxyRuntime: Boolean,
    ): Boolean = running || hasSetupThread || hasEngine || hasTunInterface || hasDnsServer || hasLocalProxyRuntime
}

internal object VpnStopAttemptPolicy {
    fun shouldIgnoreStopRequest(requestedAttemptId: String, activeAttemptId: String): Boolean =
        requestedAttemptId.isNotEmpty() && activeAttemptId.isNotEmpty() && requestedAttemptId != activeAttemptId
}
