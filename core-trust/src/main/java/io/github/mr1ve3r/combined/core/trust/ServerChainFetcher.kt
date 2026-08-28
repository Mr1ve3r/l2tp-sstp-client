package io.github.mr1ve3r.combined.core.trust

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the certificate chain a server presents, so the user can decide what
 * to trust (SPEC 5.3 C).
 *
 * This is deliberately the weakest of the three import paths and the UI says
 * so. The handshake accepts anything, which means an attacker between the
 * device and the server can hand over their own chain and it will be shown as
 * if it were the server's. What makes the path usable anyway is that the user
 * reads the SHA-256 fingerprint off the router in front of them and compares:
 * the download saves typing, the comparison is what provides the security.
 *
 * Nothing is sent. The chain arrives during the handshake, and the socket is
 * closed as soon as it has been captured — no request, no credentials, not even
 * a completed handshake if the server would have wanted more.
 */
class ServerChainFetcher(private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS) {
    /**
     * Connects to [host]:[port] and returns the chain, leaf first.
     *
     * @throws IOException if the server could not be reached, or answered
     *   without presenting a certificate at all — a plain HTTP port, most
     *   likely, which is worth saying plainly rather than as a TLS error.
     */
    suspend fun fetch(host: String, port: Int): List<X509Certificate> = withContext(Dispatchers.IO) {
        val captured = CapturingTrustManager()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(captured), null) }
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), timeoutMillis)
            plain.soTimeout = timeoutMillis
            val socket = context.socketFactory.createSocket(plain, host, port, false) as SSLSocket
            // Servers that host several names behind one address pick the
            // certificate from SNI, so without this the chain shown could be
            // the wrong one, and pinning it would then fail at connect time.
            socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            try {
                socket.startHandshake()
            } catch (e: IOException) {
                // A chain already captured is the answer, whatever went wrong
                // afterwards: the user wants to see what the server offered,
                // and this connection was never going to carry traffic.
                if (captured.chain.isEmpty()) throw e
            } finally {
                runCatching { socket.close() }
            }
            captured.chain.ifEmpty { throw IOException("$host:$port completed the handshake without presenting a certificate") }
        }
    }

    private class CapturingTrustManager : X509TrustManager {
        var chain: List<X509Certificate> = emptyList()
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            this.chain = chain.toList()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** Connect and read timeout. Long enough for a slow link, short enough to give up on a wrong port. */
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000
    }
}
