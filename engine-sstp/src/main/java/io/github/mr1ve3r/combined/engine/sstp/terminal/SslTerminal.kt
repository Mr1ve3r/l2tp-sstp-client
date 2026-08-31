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
package io.github.mr1ve3r.combined.engine.sstp.terminal

import io.github.mr1ve3r.combined.core.trust.CertificateFingerprint
import io.github.mr1ve3r.combined.core.trust.CertificatePinMismatchException
import io.github.mr1ve3r.combined.core.trust.HostnameVerification
import io.github.mr1ve3r.combined.core.trust.HostnameVerificationResult
import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineException
import io.github.mr1ve3r.combined.engine.LogLevel
import io.github.mr1ve3r.combined.engine.SocketProtector
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.sstp.SstpEngineConfig
import io.github.mr1ve3r.combined.engine.sstp.SstpTransport
import io.github.mr1ve3r.combined.engine.sstp.extension.capacityAfterLimit
import io.github.mr1ve3r.combined.engine.sstp.extension.slide
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The transport under SSTP: a TCP socket, optionally through an HTTP CONNECT
 * proxy, with TLS on top and the SSTP HTTP layer negotiated over it.
 *
 * Three things changed from upstream Open SSTP Client's `SSLTerminal`, and they
 * are the three places to look first when something here misbehaves.
 *
 * **Certificates.** `createTrustManagers()` is gone in its entirety. It read a
 * user-picked directory through `DocumentFile.fromTreeUri` and loaded every
 * file in it into a `KeyStore`, which meant no pinning, no expiry check, no
 * per-profile selection, and one unparseable file breaking every connection.
 * The [X509TrustManager] now arrives ready-made from `core-trust`, built for
 * this profile's trust policy (PROVENANCE 3.2).
 *
 * **Socket protection.** Upstream called `bridge.service.protect(socket)` once,
 * and did it *after* the HTTP exchange. Here [protector] is applied to every
 * socket this class opens, before `connect()` — the proxy socket as much as the
 * direct one, and on every reconnect, since a reconnect that forgets it routes
 * the transport into the tunnel it is carrying and wedges (SPEC 6.4.4).
 *
 * **TLS.** Upstream drove an `SSLEngine` and wrapped and unwrapped records by
 * hand, which it needed in order to offer per-suite selection in its settings
 * screen. This fork does not offer that, so the socket is an ordinary
 * [SSLSocket] layered over the raw one. That is also what keeps the proxy case
 * honest: the handshake happens with the target server on the far side of the
 * `CONNECT`, and the proxy's own certificate takes no part in it.
 *
 * @property socketFactory makes the unconnected socket. Injected so a test can
 *   watch what gets protected.
 * @property log where this terminal reports progress. Never given a secret.
 */
internal class SslTerminal(
    private val config: SstpEngineConfig,
    private val protector: SocketProtector,
    trustManager: X509TrustManager,
    private val log: (LogLevel, String) -> Unit,
    private val socketFactory: () -> Socket = { Socket() },
) : SstpTransport {
    private val sendMutex = Mutex()
    private val recordingTrustManager = RecordingTrustManager(trustManager)

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var leafCertificate: X509Certificate? = null

    override val applicationBufferSize: Int get() = APPLICATION_BUFFER_SIZE

    override val serverCertificate: ByteArray
        get() = (leafCertificate ?: error("The TLS handshake has not finished")).encoded

    /**
     * Brings the transport up: socket, proxy, TLS, hostname check, HTTP layer.
     *
     * Blocking throughout, so the caller runs it on an IO dispatcher. Every
     * failure comes back as an [EngineException] carrying the specific
     * [EngineError] the UI can act on, rather than the `SSLHandshakeException`
     * ten seconds in that this whole design exists to replace.
     */
    fun establish(guid: String) {
        val raw = connectTransportSocket()
        val secure = startTls(raw)
        verifyHostname()
        establishHttpLayer(guid)
        secure.soTimeout = READ_TIMEOUT_MS
    }

    /**
     * Opens the TCP socket the tunnel rides on and, when a proxy is configured,
     * gets it CONNECTed through.
     *
     * Internal rather than private so a test can assert what was protected:
     * this is the one method where a missing `protect()` produces a bug that
     * only shows up on a real device, on the second connection.
     */
    internal fun connectTransportSocket(): Socket {
        val proxy = config.proxy
        val host = proxy?.host ?: config.server
        val port = proxy?.port ?: config.port

        val opened = socketFactory()
        // Bound before it is protected, and only then connected. `VpnService.
        // protect(Socket)` reads the socket's file descriptor, and a socket
        // that has never been bound has none yet: the call comes back false on
        // a device even though the VPN holds consent, and the connection dies
        // before a packet is sent. Binding to port 0 creates the descriptor
        // without choosing anything -- `connect()` keeps the ephemeral port the
        // kernel picked, and the socket is still unconnected here, which is the
        // ordering `protect()` needs (SPEC 6.4.4).
        try {
            if (!opened.isBound) opened.bind(InetSocketAddress(0))
        } catch (e: IOException) {
            opened.closeQuietly()
            throw EngineException(EngineError.Internal("could not bind the transport socket: ${e.message}"), e)
        }
        if (!protector.protect(opened)) {
            opened.closeQuietly()
            // Refused rather than logged and carried on: an unprotected socket
            // does not fail, it hangs, and a hang is the one outcome a user
            // cannot diagnose.
            throw EngineException(EngineError.Internal("protect() refused the transport socket"))
        }

        try {
            opened.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            opened.soTimeout = HANDSHAKE_TIMEOUT_MS
        } catch (e: SocketTimeoutException) {
            opened.closeQuietly()
            throw EngineException(EngineError.TimedOut(STAGE_CONNECT, "No answer from $host:$port"), e)
        } catch (e: IOException) {
            opened.closeQuietly()
            throw EngineException(EngineError.NetworkUnreachable("$host:$port: ${e.message}"), e)
        }

        socket = opened
        // Unbuffered on purpose while the proxy is being negotiated: a buffered
        // stream could read past the end of the CONNECT response, and those
        // bytes would be lost when the TLS socket takes the connection over.
        input = opened.getInputStream()
        output = opened.getOutputStream()
        log(LogLevel.INFO, "Connected to $host:$port" + if (proxy != null) " (HTTP proxy)" else "")

        if (proxy != null) {
            establishProxy()
        }

        return opened
    }

    /**
     * Asks the proxy to open a tunnel to the server.
     *
     * The reply is checked properly rather than waited out: a wrong proxy
     * password has to come back as an authentication failure, not as a timeout
     * with no explanation (SPEC 6.6).
     */
    private fun establishProxy() {
        val proxy = config.proxy ?: return
        val target = "${config.server}:${config.port}"

        val request =
            buildList {
                add("CONNECT $target HTTP/1.1")
                add("Host: $target")
                add("SSTPVERSION: 1.0")
                if (!proxy.username.isNullOrEmpty() || !proxy.password.isNullOrEmpty()) {
                    val credentials = "${proxy.username.orEmpty()}:${proxy.password.orEmpty()}"
                    val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.US_ASCII))
                    add("Proxy-Authorization: Basic $encoded")
                }
            }.joinToString(separator = HTTP_DELIMITER, postfix = HTTP_SUFFIX)

        // The request itself is never logged: its last header is the proxy password.
        log(LogLevel.DEBUG, "Sending CONNECT for $target to the proxy")
        writeAscii(request)

        val status = statusLineOf(readHttpHead())
        when {
            status.contains(" 200") -> log(LogLevel.INFO, "The proxy opened a tunnel to $target")

            REJECTED_BY_PROXY.any { status.contains(it) } ->
                throw EngineException(EngineError.AuthenticationFailed("The proxy rejected the credentials: $status"))

            else -> throw EngineException(EngineError.NetworkUnreachable("The proxy refused CONNECT: $status"))
        }
    }

    private fun startTls(raw: Socket): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(recordingTrustManager), null)

        val sni = config.verificationHostname
        val secure =
            (context.socketFactory.createSocket(raw, sni, config.port, true) as SSLSocket).apply {
                useClientMode = true
                enabledProtocols = enabledProtocolsFor(config.minTlsVersion, supportedProtocols)
                sslParameters =
                    sslParameters.also { parameters ->
                        // SNI is the expected hostname when the profile names
                        // one, so a certificate issued to an internal name can
                        // still be reached over a DDNS name or a bare IP.
                        parameters.serverNames = sniOrNull(sni)?.let { listOf(it) } ?: emptyList()
                    }
                soTimeout = HANDSHAKE_TIMEOUT_MS
            }

        try {
            secure.startHandshake()
        } catch (e: Exception) {
            secure.closeQuietly()
            throw handshakeFailure(e)
        }

        socket = secure
        input = secure.inputStream
        output = secure.outputStream
        leafCertificate = secure.session.peerCertificates.firstOrNull() as? X509Certificate
        log(LogLevel.INFO, "TLS established: ${secure.session.protocol} ${secure.session.cipherSuite}")
        logPresentedChain(LogLevel.DEBUG)

        return secure
    }

    /**
     * Checks the certificate names against the host the profile expects.
     *
     * Deliberately separate from the trust policy: a certificate can be
     * perfectly trusted and still carry the wrong name, and the answer to that
     * is to tell the profile which name to expect, not to switch verification
     * off. The names the certificate does carry go into the error so the user
     * can see what to write (SPEC 5.8).
     */
    private fun verifyHostname() {
        val leaf =
            leafCertificate
                ?: throw EngineException(EngineError.CertificateRejected(null, "The server presented no certificate"))

        when (val result = HostnameVerification.verify(leaf, config.verificationHostname)) {
            is HostnameVerificationResult.Matched ->
                log(LogLevel.DEBUG, "The certificate covers ${config.verificationHostname}")

            is HostnameVerificationResult.Mismatch ->
                throw EngineException(
                    EngineError.HostnameMismatch(
                        expected = result.expected,
                        presented = result.presented,
                        detail = "The certificate is issued to ${result.presented.joinToString()}",
                    ),
                )
        }
    }

    /** The HTTP request that turns the TLS stream into an SSTP session. */
    private fun establishHttpLayer(guid: String) {
        val request =
            listOf(
                "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
                "Content-Length: 18446744073709551615",
                "Host: ${config.server}",
                "SSTPCORRELATIONID: {$guid}",
            ).joinToString(separator = HTTP_DELIMITER, postfix = HTTP_SUFFIX)

        writeAscii(request)

        val status = statusLineOf(readHttpHead())
        if (!status.contains(" 200")) {
            throw EngineException(EngineError.Internal("The server refused SSTP_DUPLEX_POST: $status"))
        }
        log(LogLevel.INFO, "The server accepted the SSTP session")
    }

    override suspend fun send(buffer: ByteBuffer) {
        sendMutex.withLock {
            val stream = output ?: throw EngineException(EngineError.Internal("The transport is closed"))
            val length = buffer.remaining()
            stream.write(buffer.array(), buffer.position(), length)
            stream.flush()
            buffer.position(buffer.position() + length)
        }
    }

    /**
     * Appends more bytes to [buffer], keeping whatever has not been consumed.
     *
     * The unconsumed remainder slides to the front and the read lands after it,
     * so a packet split across two records is reassembled without being copied
     * anywhere else. A read timeout is not an error: it is how the incoming
     * loop gets its chance to check the keepalive timers.
     */
    override fun receive(buffer: ByteBuffer) {
        val stream = input ?: throw EngineException(EngineError.Internal("The transport is closed"))
        buffer.slide()

        val readSize =
            try {
                stream.read(buffer.array(), buffer.limit(), buffer.capacityAfterLimit)
            } catch (_: SocketTimeoutException) {
                return
            }

        if (readSize < 0) {
            throw EOFException("The server closed the SSTP connection")
        }

        buffer.limit(buffer.limit() + readSize)
    }

    fun close() {
        socket?.closeQuietly()
        socket = null
        input = null
        output = null
    }

    private fun writeAscii(text: String) {
        val stream = output ?: throw EngineException(EngineError.Internal("The transport is closed"))
        stream.write(text.toByteArray(Charsets.US_ASCII))
        stream.flush()
    }

    /** Reads up to and including the blank line that ends an HTTP head. */
    private fun readHttpHead(): String {
        val stream = input ?: throw EngineException(EngineError.Internal("The transport is closed"))
        val head = StringBuilder()

        while (!head.endsWith(HTTP_SUFFIX)) {
            if (head.length > MAX_HTTP_HEAD) {
                throw EngineException(EngineError.Internal("The HTTP response head never ended"))
            }

            val next =
                try {
                    stream.read()
                } catch (e: SocketTimeoutException) {
                    throw EngineException(EngineError.TimedOut(STAGE_HTTP, "No HTTP response"), e)
                }

            if (next < 0) {
                throw EngineException(EngineError.NetworkUnreachable("The peer closed the connection mid-response"))
            }

            head.append(next.toChar())
        }

        return head.toString()
    }

    /**
     * Writes out the chain the server actually presented.
     *
     * The single fingerprint in a rejection names the leaf and nothing else,
     * which cannot distinguish the two mistakes that produce almost the same
     * message: a CA pinned where the server serves a leaf, and a leaf stored as
     * an anchor where the chain needs the CA. Subject, issuer and fingerprint
     * of every element answer both at a glance, and none of it is secret --
     * this is what the server sends to anyone who connects.
     */
    private fun logPresentedChain(level: LogLevel) {
        val chain = recordingTrustManager.lastChain
        if (chain.isNullOrEmpty()) {
            log(level, "The server presented no certificate chain")
            return
        }
        log(level, "The server presented ${chain.size} certificate(s):")
        chain.forEachIndexed { index, certificate ->
            val role = if (index == 0) "leaf" else "issuer $index"
            val ca = if (certificate.basicConstraints >= 0) "CA" else "not a CA"
            log(
                level,
                "  [$index] $role, $ca: subject=${certificate.subjectX500Principal.name} " +
                    "issuer=${certificate.issuerX500Principal.name} " +
                    "sha256=${CertificateFingerprint.formatForDisplay(CertificateFingerprint.sha256(certificate))}",
            )
        }
    }

    private fun handshakeFailure(cause: Exception): EngineException {
        val presented = recordingTrustManager.lastChain?.firstOrNull()
        val fingerprint = presented?.let(CertificateFingerprint::sha256)
        // Before the mapping, so the chain is in the log whichever branch the
        // failure takes -- and whether or not the user can read the error.
        logPresentedChain(LogLevel.WARN)

        causeOfType<CertificatePinMismatchException>(cause)?.also {
            return EngineException(EngineError.CertificateRejected(it.presentedSha256 ?: fingerprint, it.message), cause)
        }

        causeOfType<CertificateExpiredException>(cause)?.also {
            return EngineException(EngineError.CertificateExpired(presented?.notAfter?.time ?: 0L, it.message), cause)
        }

        causeOfType<CertPathValidatorException>(cause)?.also {
            return EngineException(
                EngineError.CertificateRejected(fingerprint, "The chain could not be validated: ${it.message}"),
                cause,
            )
        }

        causeOfType<CertificateException>(cause)?.also {
            return EngineException(EngineError.CertificateRejected(fingerprint, it.message), cause)
        }

        if (cause is SocketTimeoutException) {
            return EngineException(EngineError.TimedOut(STAGE_TLS, cause.message), cause)
        }

        return EngineException(EngineError.TlsHandshakeFailed("${cause.javaClass.simpleName}: ${cause.message}"), cause)
    }

    companion object {
        private const val HTTP_DELIMITER = "\r\n"
        private const val HTTP_SUFFIX = "\r\n\r\n"
        private const val MAX_HTTP_HEAD = 8 * 1024

        /** Status codes that mean the proxy did not like the credentials. */
        private val REJECTED_BY_PROXY = listOf(" 407", " 401", " 403")

        /** Stage names reported in `EngineError.TimedOut`. */
        internal const val STAGE_CONNECT = "tcp_connect"
        internal const val STAGE_TLS = "tls_handshake"
        internal const val STAGE_HTTP = "http_layer"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val HANDSHAKE_TIMEOUT_MS = 20_000

        /**
         * How long a read blocks once the tunnel is up.
         *
         * Short on purpose: the incoming loop treats a returning read as its
         * chance to check the SSTP and PPP keepalive timers, so this is the
         * resolution at which a dead connection gets noticed.
         */
        private const val READ_TIMEOUT_MS = 1_000

        /** Room for one TLS record's worth of plaintext, which is the most one read can yield. */
        private const val APPLICATION_BUFFER_SIZE = 16_384

        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /**
         * The protocols to enable, given the profile's floor.
         *
         * Anything below TLS 1.2 stays off whatever else is supported: the SPEC
         * does not offer it, and `setEnabledProtocols` is where that is enforced.
         */
        internal fun enabledProtocolsFor(minimum: TlsVersion, supported: Array<String>): Array<String> {
            val allowed =
                when (minimum) {
                    TlsVersion.TLS_1_2 -> setOf(TlsVersion.TLS_1_2.protocolName, TlsVersion.TLS_1_3.protocolName)
                    TlsVersion.TLS_1_3 -> setOf(TlsVersion.TLS_1_3.protocolName)
                }

            return supported.filter { it in allowed }.toTypedArray().ifEmpty { arrayOf(minimum.protocolName) }
        }

        /** The first line of an HTTP response, or the whole thing if it has no line break. */
        internal fun statusLineOf(head: String): String = head.substringBefore(HTTP_DELIMITER).trim()

        /**
         * An [SNIHostName] for [host], unless it is a literal address.
         *
         * RFC 6066 does not allow an IP address in SNI, and some servers drop
         * the handshake rather than ignore it.
         */
        internal fun sniOrNull(host: String): SNIHostName? = try {
            if (host.isBlank() || host.contains(':') || host.matches(IPV4_LITERAL)) null else SNIHostName(host)
        } catch (_: IllegalArgumentException) {
            null
        }

        private inline fun <reified T : Throwable> causeOfType(throwable: Throwable): T? {
            var current: Throwable? = throwable
            while (current != null) {
                if (current is T) return current
                current = current.cause
            }
            return null
        }

        private fun Socket.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // The socket is being discarded either way.
            }
        }
    }
}

/**
 * Remembers the chain the server presented, then delegates the decision.
 *
 * Without this the engine could report *that* a certificate was rejected but
 * not *which* one, and `EngineError.CertificateRejected` exists precisely so a
 * user can compare the fingerprint with the one they expected.
 */
internal class RecordingTrustManager(private val delegate: X509TrustManager) : X509TrustManager {
    @Volatile
    var lastChain: List<X509Certificate>? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        lastChain = chain.toList()
        delegate.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
