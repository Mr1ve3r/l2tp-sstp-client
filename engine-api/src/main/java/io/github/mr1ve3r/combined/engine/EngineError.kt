package io.github.mr1ve3r.combined.engine

/**
 * A failure reported by an engine, in a vocabulary shared by both protocols.
 *
 * The point of this type is to replace an opaque `SSLHandshakeException` ten
 * seconds into a connection attempt with something the UI can act on. Each
 * variant carries the specific facts a user needs to fix the problem:
 * [HostnameMismatch] lists the names the certificate actually presents, so the
 * user can see what to put in `expectedHostname` instead of reaching for a
 * switch that disables verification.
 *
 * Per-protocol mapping tables — native L2TP status codes, and SSTP's
 * `Where`/`Result` pair — live in `docs/ARCHITECTURE.md`.
 */
sealed interface EngineError {
    /** Localisation key for the message shown to the user. */
    val messageKey: String

    /** Technical detail for the log. May be `null`; never contains secrets. */
    val detail: String?

    /** No route to the server: the network is down, or the host does not resolve. */
    data class NetworkUnreachable(override val detail: String?) : EngineError {
        override val messageKey: String get() = "engine.error.network_unreachable"
    }

    /**
     * The server rejected the credentials.
     *
     * A failover group must **stop** on this rather than trying the next
     * member: the credentials are wrong, and working through the list only
     * spreads failed logins across servers (SPEC phase 10.1).
     */
    data class AuthenticationFailed(override val detail: String?) : EngineError {
        override val messageKey: String get() = "engine.error.authentication_failed"
    }

    /** The TLS handshake failed for a reason other than certificate trust. */
    data class TlsHandshakeFailed(override val detail: String?) : EngineError {
        override val messageKey: String get() = "engine.error.tls_handshake_failed"
    }

    /**
     * The server certificate did not satisfy the profile's [TrustPolicy].
     *
     * @property fingerprintSha256 SHA-256 of the presented leaf certificate,
     *   lowercase hex without separators, so the user can compare it with the
     *   fingerprint they expected. `null` if the chain was empty.
     */
    data class CertificateRejected(
        val fingerprintSha256: String?,
        override val detail: String?,
    ) : EngineError {
        override val messageKey: String get() = "engine.error.certificate_rejected"
    }

    /**
     * The server certificate is outside its validity window.
     *
     * @property notAfter expiry time in milliseconds since the epoch.
     */
    data class CertificateExpired(
        val notAfter: Long,
        override val detail: String?,
    ) : EngineError {
        override val messageKey: String get() = "engine.error.certificate_expired"
    }

    /**
     * The certificate is trusted but was issued to a different name.
     *
     * @property expected the name that was verified against — `expectedHostname`
     *   if the profile sets one, otherwise the server address.
     * @property presented every name the certificate carries, from its subject
     *   alternative names and common name. Shown to the user so they can put
     *   the right value in `expectedHostname` instead of disabling verification.
     */
    data class HostnameMismatch(
        val expected: String,
        val presented: List<String>,
        override val detail: String?,
    ) : EngineError {
        override val messageKey: String get() = "engine.error.hostname_mismatch"
    }

    /** IPsec negotiation failed. L2TP only. */
    data class IpsecFailed(override val detail: String?) : EngineError {
        override val messageKey: String get() = "engine.error.ipsec_failed"
    }

    /**
     * PPP negotiation failed.
     *
     * @property phase the sub-protocol that failed, such as `LCP`, `IPCP` or an
     *   authentication method.
     */
    data class PppNegotiationFailed(
        val phase: String,
        override val detail: String?,
    ) : EngineError {
        override val messageKey: String get() = "engine.error.ppp_negotiation_failed"
    }

    /**
     * A stage of the connection exceeded its deadline.
     *
     * @property stage the stage that timed out.
     */
    data class TimedOut(
        val stage: String,
        override val detail: String?,
    ) : EngineError {
        override val messageKey: String get() = "engine.error.timed_out"
    }

    /** An unexpected failure inside the engine. A bug, not a configuration problem. */
    data class Internal(override val detail: String?) : EngineError {
        override val messageKey: String get() = "engine.error.internal"
    }
}
