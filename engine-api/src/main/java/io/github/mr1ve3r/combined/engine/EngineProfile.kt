package io.github.mr1ve3r.combined.engine

/**
 * Everything an engine needs to establish one connection.
 *
 * This is the only configuration channel into an engine. Nothing is read from
 * `SharedPreferences`, and nothing is read from a global — the upstream Open
 * SSTP Client habit of reaching for preferences from inside protocol code is
 * what makes it impossible to run two profiles or to test a client in isolation.
 *
 * The protocol-specific variants are [L2tp] and [Sstp]. Matching on this sealed
 * interface is how the host picks an engine (SPEC phase 7.1).
 */
sealed interface EngineProfile {
    /** Server hostname or IP address. */
    val server: String

    /** Username for PPP authentication. */
    val username: String

    /** Password for PPP authentication. Never logged. */
    val password: String

    /** MTU for the tunnel interface. */
    val mtu: Int

    /** DNS servers that override those proposed by the server. Empty to use the server's. */
    val customDns: List<String>

    /**
     * An L2TP/IPsec profile.
     *
     * @property ipsecEnabled whether to run IPsec beneath L2TP. Plain L2TP is
     *   only sensible on a network that is already trusted.
     * @property presharedKey IKEv1 pre-shared key. Never logged. `null` when
     *   [ipsecEnabled] is `false`.
     * @property localIdentifier IKE identity to present, when the server expects
     *   a specific one.
     * @property phase1Proposals IKE phase 1 proposals, in preference order.
     * @property phase2Proposals IKE phase 2 proposals, in preference order.
     */
    data class L2tp(
        override val server: String,
        override val username: String,
        override val password: String,
        override val mtu: Int,
        override val customDns: List<String>,
        val ipsecEnabled: Boolean,
        val presharedKey: String?,
        val localIdentifier: String?,
        val phase1Proposals: List<String>,
        val phase2Proposals: List<String>,
    ) : EngineProfile {
        companion object {
            /** Default MTU for new L2TP profiles. */
            const val DEFAULT_MTU: Int = 1400
        }
    }

    /**
     * An SSTP profile.
     *
     * @property port TCP port, normally 443.
     * @property trustPolicy how the server certificate is verified.
     * @property trustedCertificateIds SHA-256 fingerprints identifying the
     *   certificates selected from the `core-trust` store, used by
     *   [TrustPolicy.CUSTOM_ONLY] and [TrustPolicy.SYSTEM_PLUS_CUSTOM].
     * @property pinnedFingerprints SHA-256 fingerprints of leaf certificates
     *   accepted under [TrustPolicy.PIN_LEAF]. Lowercase hex, no separators.
     * @property expectedHostname name to verify the certificate against, and the
     *   SNI to send. `null` verifies against [server].
     *
     *   This exists so that a certificate issued to `vpn.internal.lan` can be
     *   used when connecting over a DDNS name or a bare IP. That case is solved
     *   by naming the expected host, never by switching verification off
     *   (SPEC appendix Б, item 4).
     * @property minTlsVersion lowest TLS version to negotiate.
     * @property pppAuthMethods PPP authentication methods to offer, in
     *   preference order of the server. [PppAuthMethod.EAP_MSCHAPV2] is carried
     *   but off by default: MikroTik and SoftEther negotiate MSCHAPv2 directly,
     *   and EAP is only needed for some Windows RRAS deployments.
     * @property proxy HTTP CONNECT proxy to tunnel through, or `null` for a
     *   direct connection.
     */
    data class Sstp(
        override val server: String,
        override val username: String,
        override val password: String,
        override val mtu: Int,
        override val customDns: List<String>,
        val port: Int,
        val trustPolicy: TrustPolicy,
        val trustedCertificateIds: List<String>,
        val pinnedFingerprints: Set<String>,
        val expectedHostname: String?,
        val minTlsVersion: TlsVersion,
        val pppAuthMethods: Set<PppAuthMethod>,
        val proxy: ProxyConfig?,
    ) : EngineProfile {
        companion object {
            /**
             * Default MTU for new SSTP profiles.
             *
             * Deliberately below the L2TP default. SSTP carries IP over TCP, and
             * the tunnelled traffic is usually TCP too; when the link loses
             * packets the inner and outer retransmission timers fight each other.
             * A smaller MTU reduces how often that happens (SPEC phase 6.5).
             */
            const val DEFAULT_MTU: Int = 1400

            /** Default TCP port. */
            const val DEFAULT_PORT: Int = 443

            /** Authentication methods enabled for a new profile. */
            val DEFAULT_AUTH_METHODS: Set<PppAuthMethod> =
                setOf(PppAuthMethod.MSCHAPV2, PppAuthMethod.CHAP, PppAuthMethod.PAP)
        }
    }
}

/**
 * An HTTP CONNECT proxy for the SSTP transport.
 *
 * The proxy carries the connection; it does not terminate TLS. The handshake
 * and all certificate verification happen with the target server on the far
 * side of the `CONNECT`. The proxy's own certificate takes no part in it
 * (SPEC appendix Б, item 9).
 *
 * @property host proxy hostname or IP address.
 * @property port proxy port.
 * @property username basic-auth username, or `null` when the proxy is open.
 * @property password basic-auth password, or `null`. Never logged.
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
)

/** A PPP authentication method an SSTP profile may offer. */
enum class PppAuthMethod {
    /** Sends the password in the clear. Only defensible inside TLS. */
    PAP,

    /** CHAP with MD5. */
    CHAP,

    /** MSCHAPv2. What MikroTik and SoftEther negotiate. */
    MSCHAPV2,

    /** EAP-MSCHAPv2. Off by default; needed by some Windows RRAS deployments. */
    EAP_MSCHAPV2,
}
