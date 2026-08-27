package io.github.evokelektrique.tunnelforge

import androidx.annotation.Keep
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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
import io.github.mr1ve3r.combined.engine.l2tp.L2tpEngine
import io.github.mr1ve3r.combined.engine.l2tp.L2tpNativeCallbacks
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
    private var activeEngine: L2tpEngine? = null
    private var engineWatcher: CoroutineScope? = null
    private var vpnDnsPacketBridge: VpnDnsPacketBridge? = null
    private var localProxyRuntime: ProxyServerRuntime? = null
    private var activeAttemptId: String = ""
    private var activeProxyConfig: ProxyRuntimeConfig? = null
    private var activeServer: String = ""
    private var activeProfileName: String? = null

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
        if (hasActiveSession()) {
            stopTunnelInternal()
        }
        instance = null
        super.onDestroy()
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
                activeAttemptId = attemptId
                activeProxyConfig = proxyConfig
                activeServer = server
                activeProfileName = profileName.ifEmpty { null }
                RuntimeEnvironmentInfo.emit(this, TAG, prefixAttempt(attemptId), mode = VpnContract.MODE_VPN_TUNNEL)
                VpnTunnelEvents.emitEngineLog(
                    Log.DEBUG,
                    TAG,
                    "${prefixAttempt(attemptId)}ACTION_START accepted server=$server userPresent=${user.isNotEmpty()} pskPresent=${psk.isNotEmpty()} dnsMode=${if (dnsAutomatic) "automatic" else "manual"} dns=${dnsServers.joinToString(",") { "${it.host}[${it.protocol.shortLabel}]" }} mtu=$tunMtu splitTunnelEnabled=$splitTunnelEnabled splitTunnelMode=$splitTunnelMode inclusiveApps=${inclusivePackages?.size ?: 0} exclusiveApps=${exclusivePackages?.size ?: 0} http=${proxyConfig.httpPort} socks=${proxyConfig.socksPort} lan=${if (proxyAllowLan) "on" else "off"}",
                )
                // TUN establish() can block; do not hold up onStartCommand after startForeground.
                val startupThread =
                    Thread(
                        {
                            startTunnel(
                                attemptId,
                                server,
                                user,
                                password,
                                psk,
                                dnsAutomatic,
                                dnsServers,
                                tunMtu,
                                splitTunnelEnabled,
                                splitTunnelMode,
                                inclusivePackages,
                                exclusivePackages,
                                proxyConfig,
                            )
                        },
                        "tun-setup",
                    )
                synchronized(sessionLock) {
                    setupThread = startupThread
                }
                startupThread.start()
                return START_STICKY
            }
            else -> {
                if (intent?.action != null) {
                    AppLog.w(TAG, "Unknown action: ${intent.action}")
                }
                if (!running.get()) {
                    stopSelf()
                }
                return START_NOT_STICKY
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

    private fun emitAttemptState(attemptId: String, state: String, detail: String): Boolean {
        if (!shouldHandleAttempt(attemptId, "state:$state")) return false
        VpnTunnelEvents.emit(state, detail, attemptId)
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

    private fun startTunnel(
        attemptId: String,
        server: String,
        user: String,
        password: String,
        psk: String,
        dnsAutomatic: Boolean,
        dnsServers: List<DnsServerConfig>,
        tunMtu: Int,
        splitTunnelEnabled: Boolean,
        splitTunnelMode: String,
        inclusivePackages: ArrayList<String>?,
        exclusivePackages: ArrayList<String>?,
        proxyConfig: ProxyRuntimeConfig,
    ) {
        val currentSetupThread = Thread.currentThread()
        val nativeOwner = nativeOwner(attemptId)
        var nativeOwnerAcquired = false
        var nativeLoopStarted = false
        var startedEngine: L2tpEngine? = null
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

            emitAttemptState(attemptId, VpnContract.TUNNEL_CONNECTING, "Negotiating IKE/L2TP/PPP...")
            VpnTunnelEvents.emitEngineLog(
                Log.INFO,
                TAG,
                "${prefixAttempt(attemptId)}Starting native negotiation (IKE/L2TP/PPP)",
            )
            nativeOwnerAcquired =
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
            val engine = L2tpEngine(VpnBridgeL2tpNative)
            startedEngine = engine
            synchronized(sessionLock) { activeEngine = engine }
            val negotiatedParams =
                try {
                    runBlocking {
                        engine.connect(
                            profile = l2tpProfile(server, user, password, psk, tunMtu),
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
                    emitAttemptState(attemptId, VpnContract.TUNNEL_FAILED, engineFailureDetail(e))
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
            if (!dnsAutomatic) {
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
            } else {
                VpnBridge.nativeSetVpnDnsInterceptIpv4(null)
            }
            // Everything except DNS is what the engine negotiated. DNS is the one
            // parameter the host is allowed to override, and manual DNS replaces
            // the server's resolvers with the virtual one the bridge answers on.
            val tunnelParams =
                negotiatedParams.copy(
                    dnsServers = tunDnsServers(dnsAutomatic, negotiatedParams.dnsServers),
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
                if (nativeOwnerAcquired) {
                    NativeTunnelSessions.shared.stopOwner(nativeOwner, reason = "vpn startup ended before loop")
                    NativeTunnelSessions.shared.release(nativeOwner, reason = "vpn startup ended before loop")
                }
            }
            clearSetupThreadIfCurrent(currentSetupThread)
        }
    }

    /**
     * Turns the engine's state into the tunnel events the app already speaks.
     *
     * This is what the poll-loop thread's own `finally` block used to do. The
     * engine reaches a terminal state exactly once per session, so the watcher
     * stops itself as soon as it sees one.
     */
    private fun watchEngine(attemptId: String, engine: L2tpEngine) {
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
    private fun stopTunnelInternal() {
        val capturedSetupThread = setupThread
        val capturedAttemptId = synchronized(sessionLock) { activeAttemptId }
        val owner = capturedAttemptId.takeIf { it.isNotEmpty() }?.let(::nativeOwner)
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
        synchronized(sessionLock) {
            activeAttemptId = ""
        }
        activeProxyConfig = null
        activeServer = ""
        activeProfileName = null
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
        NativeTunnelSessions.shared.release(nativeOwner(attemptId), reason = "vpn loop finished")
        schedulePendingStopSelf()
    }

    /**
     * Reacts to [EngineState.Connected]: the tunnel carries packets.
     *
     * Reached from the engine watcher rather than straight from JNI, so a
     * report from a session that has already been replaced is dropped by the
     * engine identity check rather than by comparing worker threads.
     */
    private fun handleTunnelReady(attemptId: String, engine: L2tpEngine) {
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
     * The intent's arguments as the engine contract expects them.
     *
     * IPsec is always on for this path: the native engine has no plain-L2TP
     * mode, and the identity and proposal fields have no native equivalent yet.
     * The engine logs a warning if a profile ever sets them.
     */
    private fun l2tpProfile(
        server: String,
        user: String,
        password: String,
        psk: String,
        tunMtu: Int,
    ): EngineProfile.L2tp =
        EngineProfile.L2tp(
            server = server,
            username = user,
            password = password,
            mtu = tunMtu,
            customDns = emptyList(),
            ipsecEnabled = true,
            presharedKey = psk,
            localIdentifier = null,
            phase1Proposals = emptyList(),
            phase2Proposals = emptyList(),
        )

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

    private fun buildNotification(text: String): Notification {
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
        startForegroundWithType(buildNotification(text))
    }

    private fun connectedNotificationText(): String {
        val label = activeProfileName?.takeIf { it.isNotEmpty() } ?: activeServer
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

        fun sanitizeMtu(value: Int): Int = value.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU)

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
            negotiatedDnsServers: List<InetAddress>,
        ): List<InetAddress> =
            if (dnsAutomatic) {
                negotiatedDnsServers
            } else {
                listOf(InetAddress.getByName(MANUAL_DNS_VIRTUAL_IPV4))
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
