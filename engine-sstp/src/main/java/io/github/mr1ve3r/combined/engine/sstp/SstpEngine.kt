/*
 * Derived from Open SSTP Client
 * https://github.com/kittoku/Open-SSTP-Client
 * Copyright (c) 2019 KOBAYASHI Ittoku
 * Licensed under the MIT License.
 * See third_party/open-sstp-client/LICENSE for the full text.
 *
 * Modifications Copyright (C) 2026 Mr1ve3r
 * Licensed under GPL-3.0-or-later as part of this project.
 */
package io.github.mr1ve3r.combined.engine.sstp

import android.os.ParcelFileDescriptor
import io.github.mr1ve3r.combined.core.trust.PreflightReport
import io.github.mr1ve3r.combined.core.trust.TrustManagerFactoryProvider
import io.github.mr1ve3r.combined.core.trust.TrustPreflight
import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.EngineLogEvent
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.EngineState
import io.github.mr1ve3r.combined.engine.LogLevel
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.Route
import io.github.mr1ve3r.combined.engine.SocketProtector
import io.github.mr1ve3r.combined.engine.TrustPolicy
import io.github.mr1ve3r.combined.engine.TunnelParams
import io.github.mr1ve3r.combined.engine.VpnEngine
import io.github.mr1ve3r.combined.engine.sstp.client.PPP_AUTH_TIMEOUT
import io.github.mr1ve3r.combined.engine.sstp.client.SSTP_REQUEST_TIMEOUT
import io.github.mr1ve3r.combined.engine.sstp.client.SstpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.ConfigClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.IpcpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.Ipv6cpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.LcpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.PPP_NEGOTIATION_TIMEOUT
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.PppClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth.ChapMsChapV2Client
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth.EapMsAuthClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth.PapClient
import io.github.mr1ve3r.combined.engine.sstp.io.IncomingManager
import io.github.mr1ve3r.combined.engine.sstp.io.OutgoingManager
import io.github.mr1ve3r.combined.engine.sstp.terminal.IpTerminal
import io.github.mr1ve3r.combined.engine.sstp.terminal.SslTerminal
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [VpnEngine] over SSTP, built from the protocol code of Open SSTP Client.
 *
 * This class is what upstream called `Controller`, and the sequence it drives
 * is upstream's: transport, SSTP call setup, LCP, authentication, the crypto
 * binding, then IPCP. What changed is everything around that sequence.
 *
 * Upstream, the controller reached into a `SharedBridge` that held the
 * preferences and a live `VpnService.Builder`, built the TUN itself in the
 * middle of the negotiation, and reported failures by writing a log line and
 * posting a notification. Here it takes an [EngineProfile.Sstp], returns
 * [TunnelParams] for the host to build the interface from, receives the
 * descriptor back through [attachTun], and reports every failure as an
 * [EngineError] the UI can act on.
 *
 * One instance handles one connection. A reconnect uses a new engine, which is
 * also what guarantees a fresh socket goes through [SocketProtector] again —
 * the failure where the first connection works and the second wedges (SPEC
 * 6.4.4).
 *
 * @property certificates the profile's selected certificates, from `core-trust`.
 * @property allowInsecureTrust whether [TrustPolicy.INSECURE] may be honoured.
 *   Pass `BuildConfig.DEBUG`; the default refuses, so forgetting it produces a
 *   verified connection rather than an unverified one.
 * @property dispatcher where the blocking socket work runs.
 * @property clock wall-clock source for log timestamps and the session timer.
 * @property terminalFactory builds the transport. Injected for tests.
 */
class SstpEngine internal constructor(
    private val certificates: CertificateSource,
    private val allowInsecureTrust: Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val terminalFactory: TerminalFactory,
) : VpnEngine {
    /**
     * The constructor the host uses.
     *
     * The transport seam is deliberately not on it: a caller outside this
     * module has no reason to replace the transport, and exposing the type
     * would put the whole protocol layer into the module's public API.
     */
    constructor(
        certificates: CertificateSource = CertificateSource.EMPTY,
        allowInsecureTrust: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(certificates, allowInsecureTrust, dispatcher, clock, TerminalFactory.DEFAULT)

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
    private var redactor: Redactor = Redactor.NOTHING

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var bridge: SstpBridge? = null

    @Volatile
    private var terminal: SslTerminal? = null

    @Volatile
    private var sstpClient: SstpClient? = null

    @Volatile
    private var incomingManager: IncomingManager? = null

    @Volatile
    private var outgoingManager: OutgoingManager? = null

    @Volatile
    private var pppClient: PppClient? = null

    @Volatile
    private var ipTerminal: IpTerminal? = null

    @Volatile
    private var negotiated: TunnelParams? = null

    private var watchdog: Job? = null

    override suspend fun connect(profile: EngineProfile, protector: SocketProtector): TunnelParams {
        require(profile is EngineProfile.Sstp) {
            "SstpEngine cannot run a ${profile.javaClass.simpleName} profile"
        }

        val config = SstpEngineConfig.of(profile)
        redactor = Redactor.of(config)
        if (config.authMethods.isEmpty()) {
            val error = EngineError.Internal("The profile offers no PPP authentication method")
            fail(error)
            throw EngineException(error)
        }

        val trustManager = prepareTrust(config)

        val session = SstpSessionState(config.mru)
        val mailbox = ControlMailbox()
        val engineScope = CoroutineScope(SupervisorJob() + dispatcher + failureHandler(mailbox))
        val wiring = SstpBridge(config, session, mailbox, engineScope)
        synchronized(lock) {
            scope = engineScope
            bridge = wiring
        }

        try {
            openTransport(wiring, protector, trustManager)
            negotiate(wiring)
            val params = buildTunnelParams(wiring)
            negotiated = params
            watchAfterNegotiation(wiring)
            stateFlow.value = EngineState.Connecting(STAGE_AWAITING_TUN)
            return params
        } catch (e: EngineException) {
            reportAndClose(e.error)
            throw e
        } catch (e: Exception) {
            val error = EngineError.Internal("${e.javaClass.simpleName}: ${e.message}")
            reportAndClose(error)
            throw EngineException(error, e)
        }
    }

    override fun attachTun(fd: ParcelFileDescriptor) {
        val wiring = bridge ?: error("attachTun() before connect()")
        val params = negotiated ?: error("attachTun() before connect() returned")

        val tun = IpTerminal.of(fd, wiring.config.mtu)
        synchronized(lock) { ipTerminal = tun }
        wiring.ipTerminal = tun

        OutgoingManager(wiring, tun).also {
            it.launchJobMain()
            outgoingManager = it
        }

        stateFlow.value = EngineState.Connected(params, clock())
        log(LogLevel.INFO, "TUN attached; packets are flowing")
    }

    override suspend fun disconnect() {
        if (!disconnectRequested.compareAndSet(false, true)) return

        // A courtesy, not a requirement: a server that never hears the
        // disconnect keeps the session until its own timer expires, so this
        // gets a short deadline and no more.
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            runCatching { sstpClient?.sendLastPacket(SSTP_MESSAGE_TYPE_CALL_DISCONNECT) }
        }

        closeEverything()
        finishTerminal(EngineState.Disconnected)
    }

    /**
     * Builds the trust manager this profile calls for, refusing early when the
     * configuration cannot work.
     *
     * The pre-flight is the point: every problem it finds is one the
     * application already knows about, and finding it here replaces a
     * handshake failure ten seconds into a connection attempt with a sentence
     * before the socket is even opened (SPEC 5.7).
     */
    private suspend fun prepareTrust(config: SstpEngineConfig): X509TrustManager {
        val policy =
            TrustManagerFactoryProvider.effectivePolicy(config.trustPolicy, allowInsecureTrust) { from, to ->
                log(LogLevel.WARN, "This build does not offer $from; the profile is connecting with $to instead")
            }

        val report =
            TrustPreflight.check(
                policy = policy,
                selectedCertificateIds = config.trustedCertificateIds,
                availableCertificates = certificates.summariesFor(config.trustedCertificateIds),
                pinnedFingerprints = config.pinnedFingerprints,
                now = clock(),
            )
        reportPreflight(report)

        val selected =
            if (policy == TrustPolicy.SYSTEM || policy == TrustPolicy.PIN_LEAF) {
                emptyList()
            } else {
                certificates.certificatesFor(config.trustedCertificateIds)
            }

        return TrustManagerFactoryProvider.create(
            policy = policy,
            customCerts = selected,
            pinnedFingerprints = config.pinnedFingerprints,
            allowInsecure = allowInsecureTrust,
        )
    }

    private fun reportPreflight(report: PreflightReport) {
        report.confirmations.forEach { log(LogLevel.WARN, "Trust pre-flight: ${it.messageKey}") }

        if (!report.canConnect) {
            val blocking = report.blocking.joinToString { it.messageKey }
            val error = EngineError.CertificateRejected(null, "The profile cannot be trusted as configured: $blocking")
            fail(error)
            throw EngineException(error)
        }
    }

    private suspend fun openTransport(wiring: SstpBridge, protector: SocketProtector, trustManager: X509TrustManager) {
        stateFlow.value = EngineState.Connecting(STAGE_TRANSPORT)
        val ssl =
            terminalFactory.create(
                config = wiring.config,
                protector = protector,
                trustManager = trustManager,
                log = { level, message -> log(level, message) },
            )
        terminal = ssl

        withContext(dispatcher) { ssl.establish(wiring.state.guid) }
        wiring.transport = ssl
    }

    /**
     * The negotiation, in the order upstream's `Controller` ran it.
     *
     * Each step is started, then waited for on the [ControlMailbox]; a step
     * that reports anything other than
     * [Result.PROCEEDED] ends the attempt with the error it maps to.
     */
    private suspend fun negotiate(wiring: SstpBridge) {
        stateFlow.value = EngineState.Connecting(STAGE_SSTP)
        val incoming = IncomingManager(wiring).also { it.launchJobMain() }
        incomingManager = incoming

        val sstp = SstpClient(wiring)
        sstpClient = sstp
        incoming.registerMailbox(sstp)
        sstp.launchJobRequest()
        expectProceeded(wiring, Where.SSTP_REQUEST, SSTP_REQUEST_TIMEOUT)
        sstp.launchJobControl()

        PppClient(wiring).also {
            pppClient = it
            incoming.registerMailbox(it)
            it.launchJobControl()
        }

        stateFlow.value = EngineState.Connecting(STAGE_PPP)
        LcpClient(wiring).also {
            incoming.registerMailbox(it)
            it.launchJobNegotiation()
            expectProceeded(wiring, Where.LCP, PPP_NEGOTIATION_TIMEOUT)
            incoming.unregisterMailbox(it)
        }

        authenticate(wiring, incoming)

        // The crypto binding proves to the server that the TLS session and the
        // PPP authentication belong together, so it can only be sent once both
        // have happened.
        sstp.sendCallConnected()

        stateFlow.value = EngineState.Connecting(STAGE_IPCP)
        if (wiring.config.ipv4Enabled) {
            negotiateConfig(wiring, incoming, IpcpClient(wiring), Where.IPCP)
        }

        if (wiring.config.ipv6Enabled) {
            negotiateConfig(wiring, incoming, Ipv6cpClient(wiring), Where.IPV6CP)
        }
    }

    private suspend fun <T : ConfigClient<*>> negotiateConfig(wiring: SstpBridge, incoming: IncomingManager, client: T, where: Where) {
        incoming.registerMailbox(client)
        client.launchJobNegotiation()
        expectProceeded(wiring, where, PPP_NEGOTIATION_TIMEOUT)
        incoming.unregisterMailbox(client)
    }

    private suspend fun authenticate(wiring: SstpBridge, incoming: IncomingManager) {
        stateFlow.value = EngineState.Connecting(STAGE_AUTH)

        when (wiring.state.currentAuth) {
            PppAuthMethod.PAP -> PapClient(wiring).also {
                incoming.registerMailbox(it)
                it.launchJobAuth()
                expectProceeded(wiring, Where.PAP, PPP_AUTH_TIMEOUT)
                incoming.unregisterMailbox(it)
            }

            PppAuthMethod.MSCHAPV2, PppAuthMethod.CHAP -> ChapMsChapV2Client(wiring).also {
                incoming.registerMailbox(it)
                it.launchJobAuth()
                expectProceeded(wiring, Where.CHAP, PPP_AUTH_TIMEOUT)
            }

            PppAuthMethod.EAP_MSCHAPV2 -> EapMsAuthClient(wiring).also {
                incoming.registerMailbox(it)
                it.launchJobAuth()
                expectProceeded(wiring, Where.EAP, PPP_AUTH_TIMEOUT)
            }

            // LCP finishes only once an authentication method has been agreed,
            // so this cannot happen without a bug in the LCP client.
            null -> throw EngineException(
                EngineError.PppNegotiationFailed("LCP", "LCP completed without agreeing an authentication method"),
            )
        }
    }

    /**
     * Waits for the step at [where] to report success.
     *
     * On failure the server is told what happened before the socket goes away:
     * a disconnect request is acknowledged, anything else is aborted, which is
     * what stops the server from holding the session open (upstream
     * `Controller.expectProceeded`).
     */
    private suspend fun expectProceeded(wiring: SstpBridge, where: Where, timeout: Long?) {
        val received =
            if (timeout != null) {
                withTimeoutOrNull(timeout) { wiring.mailbox.receive() } ?: ControlMessage(where, Result.ERR_TIMEOUT)
            } else {
                wiring.mailbox.receive()
            }

        if (received.result == Result.PROCEEDED && received.from == where) {
            log(LogLevel.DEBUG, "${where.name} proceeded")
            return
        }

        if (received.result == Result.PROCEEDED) {
            // A step reported success out of order, which means the sequence is
            // no longer the one this function is tracking.
            throw EngineException(
                EngineError.Internal("Expected ${where.name} to report, but ${received.from.name} did"),
            )
        }

        sendLastPacket(received)
        throw EngineException(SstpErrorMapping.toEngineError(received))
    }

    private suspend fun sendLastPacket(received: ControlMessage) {
        val type =
            if (received.result == Result.ERR_DISCONNECT_REQUESTED) {
                SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
            } else {
                SSTP_MESSAGE_TYPE_CALL_ABORT
            }

        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            runCatching { sstpClient?.sendLastPacket(type) }
        }
    }

    /**
     * Keeps listening after the negotiation is done.
     *
     * Everything that goes wrong once the tunnel is up — a disconnect from the
     * server, a keepalive that went unanswered, a frame that would not parse —
     * arrives on the same mailbox, and this is what turns it into a terminal
     * state instead of a silently dead tunnel.
     */
    private fun watchAfterNegotiation(wiring: SstpBridge) {
        watchdog =
            wiring.scope.launch {
                val received = wiring.mailbox.receive()
                if (received.result == Result.PROCEEDED) return@launch

                val error = SstpErrorMapping.toEngineError(received)
                log(LogLevel.ERROR, "The tunnel ended: ${error.detail}")
                sendLastPacket(received)
                closeEverything()
                finishTerminal(EngineState.Failed(error))
            }
    }

    /**
     * What the host needs in order to build the interface.
     *
     * The address and DNS server come from IPCP; the routes do not come from
     * here at all. Which addresses go through the tunnel, which applications
     * are covered and whether private ranges are routed are decisions
     * `core-tunnel` makes for both protocols (PROVENANCE 3.3).
     */
    private fun buildTunnelParams(wiring: SstpBridge): TunnelParams {
        val config = wiring.config
        val assigned = wiring.state.currentIPv4

        if (assigned.all { it == ZERO_BYTE }) {
            throw EngineException(
                EngineError.PppNegotiationFailed("IPCP", "The server assigned no address"),
            )
        }

        val proposedDns =
            wiring.state.currentProposedDns
                .takeIf { proposed -> proposed.any { it != ZERO_BYTE } }
                ?.let { listOf(InetAddress.getByAddress(it)) }
                .orEmpty()

        val dnsServers =
            config.customDns
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                .ifEmpty { proposedDns }

        val params =
            TunnelParams(
                localAddress = InetAddress.getByAddress(assigned),
                prefixLength = HOST_PREFIX_LENGTH,
                dnsServers = dnsServers,
                mtu = config.mtu,
                excludedRoutes = transportExclusion(config),
            )

        log(
            LogLevel.INFO,
            "Negotiated address=${params.localAddress.hostAddress}/$HOST_PREFIX_LENGTH " +
                "mtu=${params.mtu} dnsServers=${params.dnsServers.size}",
        )
        return params
    }

    /**
     * The peer the transport actually talks to, kept off the tunnel's routes.
     *
     * With a proxy configured that is the *proxy*, not the server: the socket
     * goes to the proxy, and routing the proxy into the tunnel it carries is
     * the loop appendix B of the SPEC describes. Belt and braces next to
     * [SocketProtector], which has proved unreliable on some vendor builds.
     */
    private fun transportExclusion(config: SstpEngineConfig): List<Route> {
        val peer = config.proxy?.host ?: config.server

        return try {
            val resolved = InetAddress.getByName(peer)
            if (resolved is Inet4Address) {
                listOf(Route(resolved, HOST_PREFIX_LENGTH))
            } else {
                log(LogLevel.WARN, "The transport peer is not IPv4; excludeRoute not applied")
                emptyList()
            }
        } catch (e: Exception) {
            log(LogLevel.WARN, "Could not resolve the transport peer for excludeRoute: ${e.message}")
            emptyList()
        }
    }

    /**
     * Catches what a crashing client would otherwise throw away.
     *
     * A client coroutine that dies takes its step of the negotiation with it,
     * and without this the engine would wait on the mailbox until its timeout
     * with no idea why.
     */
    private fun failureHandler(mailbox: ControlMailbox) = CoroutineExceptionHandler { _, throwable ->
        log(LogLevel.ERROR, "A client failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
        mailbox.tryReport(Where.INCOMING, Result.ERR_PARSING_FAILED, throwable.javaClass.simpleName)
    }

    private fun reportAndClose(error: EngineError) {
        log(LogLevel.ERROR, "The connection attempt failed: ${error.detail}")
        closeEverything()
        finishTerminal(EngineState.Failed(error))
    }

    private fun closeEverything() {
        watchdog?.cancel()
        outgoingManager?.cancel()
        incomingManager?.cancel()
        sstpClient?.cancel()
        pppClient?.cancel()
        bridge?.mailbox?.close()
        terminal?.close()
        ipTerminal?.close()

        synchronized(lock) {
            scope?.cancel()
            scope = null
            ipTerminal = null
        }
    }

    private fun fail(error: EngineError) {
        finishTerminal(EngineState.Failed(error))
    }

    private fun finishTerminal(terminalState: EngineState) {
        synchronized(lock) {
            val current = stateFlow.value
            if (current is EngineState.Failed || current is EngineState.Disconnected) return
            stateFlow.value = terminalState
        }
    }

    private fun log(level: LogLevel, message: String) {
        eventFlow.tryEmit(
            EngineLogEvent(
                timestamp = clock(),
                level = level,
                protocol = Protocol.SSTP,
                tag = TAG,
                message = redactor.scrub(message),
            ),
        )
    }

    /** Builds the transport. A seam for tests, which have no server to hand. */
    internal fun interface TerminalFactory {
        fun create(
            config: SstpEngineConfig,
            protector: SocketProtector,
            trustManager: X509TrustManager,
            log: (LogLevel, String) -> Unit,
        ): SslTerminal

        companion object {
            val DEFAULT: TerminalFactory =
                TerminalFactory { config, protector, trustManager, log ->
                    SslTerminal(config, protector, trustManager, log)
                }
        }
    }

    companion object {
        private const val TAG = "SstpEngine"

        /** Stage reported while the socket, the proxy and TLS are being set up. */
        const val STAGE_TRANSPORT: String = "tls_handshake"

        /** Stage reported during SSTP call setup. */
        const val STAGE_SSTP: String = "sstp_call_setup"

        /** Stage reported during LCP. */
        const val STAGE_PPP: String = "ppp_negotiation"

        /** Stage reported while authenticating. */
        const val STAGE_AUTH: String = "authentication"

        /** Stage reported during IPCP. */
        const val STAGE_IPCP: String = "ipcp"

        /** Stage reported once negotiation is done and the host is building the TUN. */
        const val STAGE_AWAITING_TUN: String = "awaiting_tun"

        private const val EVENT_BUFFER = 256
        private const val DISCONNECT_TIMEOUT_MS = 3_000L
        private const val HOST_PREFIX_LENGTH = 32
        private const val ZERO_BYTE: Byte = 0
    }
}

/**
 * Keeps secrets out of the event stream.
 *
 * Appendix A of the SPEC requires the engine to redact, not the consumer: a log
 * that is only safe once someone downstream remembers to filter it is not safe.
 * The passwords are scrubbed by value, so a message that quotes a server reply
 * containing one is covered too.
 */
internal class Redactor(private val secrets: List<String>) {
    fun scrub(message: String): String = secrets.fold(message) { text, secret -> text.replace(secret, MASK) }

    companion object {
        private const val MASK = "***"

        /** A redactor with nothing to hide, for events emitted before a profile is known. */
        val NOTHING: Redactor = Redactor(emptyList())

        fun of(config: SstpEngineConfig): Redactor = Redactor(
            listOfNotNull(config.password, config.proxy?.password, config.proxy?.username)
                .filter { it.isNotEmpty() },
        )
    }
}
