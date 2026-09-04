package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the [X509TrustManager] a profile's [TrustPolicy] calls for.
 *
 * This replaces upstream Open SSTP Client's `SSLTerminal.createTrustManagers()`,
 * which resolved a user-picked directory through `DocumentFile.fromTreeUri` and
 * loaded every file in it into a `KeyStore`. That approach had no pinning, no
 * expiry check and no per-profile selection; one unparseable file in the
 * directory broke every connection; and the `Uri` could be unresolvable when
 * the service starts under always-on VPN before the device is unlocked.
 * Certificates now arrive here as parsed objects that the caller loaded from
 * internal storage.
 */
object TrustManagerFactoryProvider {
    /**
     * @param policy how the server certificate should be verified.
     * @param customCerts the certificates to anchor on. For
     *   [TrustPolicy.CUSTOM_ONLY] and [TrustPolicy.SYSTEM_PLUS_CUSTOM] these
     *   are what the profile selected; for [TrustPolicy.STORE_AUTO] the caller
     *   passes the whole store. Ignored by the others.
     * @param pinnedFingerprints SHA-256 fingerprints accepted under
     *   [TrustPolicy.PIN_LEAF]; ignored by the others.
     * @param allowInsecure whether [TrustPolicy.INSECURE] may be built at all.
     *   Pass `BuildConfig.DEBUG`. Defaults to `false` so that forgetting to
     *   pass it produces a refusal rather than a tunnel with no verification.
     * @throws IllegalArgumentException if the policy cannot be satisfied — no
     *   certificates for a chain-building policy, no pins for pinning, or
     *   [TrustPolicy.INSECURE] without [allowInsecure]. These are caught by
     *   [TrustPreflight] before a socket is opened; reaching here means the
     *   pre-flight was skipped.
     */
    fun create(
        policy: TrustPolicy,
        customCerts: List<X509Certificate> = emptyList(),
        pinnedFingerprints: Set<String> = emptySet(),
        allowInsecure: Boolean = false,
    ): X509TrustManager = when (policy) {
        TrustPolicy.SYSTEM -> systemTrustManager()

        TrustPolicy.CUSTOM_ONLY -> {
            require(customCerts.isNotEmpty()) { "CUSTOM_ONLY needs at least one certificate" }
            PathBuildingTrustManager.anchoredOn(customCerts)
        }

        TrustPolicy.SYSTEM_PLUS_CUSTOM -> {
            require(customCerts.isNotEmpty()) { "SYSTEM_PLUS_CUSTOM needs at least one certificate" }
            CompositeTrustManager(systemTrustManager(), PathBuildingTrustManager.anchoredOn(customCerts))
        }

        TrustPolicy.STORE_AUTO -> {
            require(customCerts.isNotEmpty()) { "STORE_AUTO needs at least one certificate in the store" }
            PathBuildingTrustManager.anchoredOn(
                certs = customCerts,
                pool = customCerts,
                // The anchor set here is the whole store. Naming it in the
                // handshake would hand any server that asks for client
                // authentication the subject of every certificate authority
                // this user has ever imported, and the engine never presents a
                // client certificate anyway.
                exposeAcceptedIssuers = false,
            )
        }

        TrustPolicy.PIN_LEAF -> {
            require(pinnedFingerprints.isNotEmpty()) { "PIN_LEAF needs at least one fingerprint" }
            FingerprintPinningTrustManager(pinnedFingerprints)
        }

        TrustPolicy.INSECURE -> {
            require(allowInsecure) { "INSECURE is not available in a release build" }
            InsecureTrustManager()
        }
    }

    /**
     * The policy actually used for a profile, given the kind of build.
     *
     * A profile carrying [TrustPolicy.INSECURE] loaded by a release build is
     * forced down to [TrustPolicy.SYSTEM_PLUS_CUSTOM] rather than refused, so
     * that a profile written on a debug build still connects — with
     * verification — instead of failing in a way the user cannot diagnose
     * (SPEC 5.5).
     *
     * @param onDowngrade called when the policy was changed, for the log.
     */
    fun effectivePolicy(
        requested: TrustPolicy,
        allowInsecure: Boolean,
        onDowngrade: (from: TrustPolicy, to: TrustPolicy) -> Unit = { _, _ -> },
    ): TrustPolicy = if (requested == TrustPolicy.INSECURE && !allowInsecure) {
        TrustPolicy.SYSTEM_PLUS_CUSTOM.also { onDowngrade(requested, it) }
    } else {
        requested
    }

    /** The platform's own trust store: Android's system CAs, or the JDK's on a JVM. */
    fun systemTrustManager(): X509TrustManager = trustManagerFrom(TrustManagerFactory.getDefaultAlgorithm(), null)

    /**
     * A PKIX trust manager anchored on [certs] and nothing else, validating the
     * chain as the server sent it.
     *
     * No policy uses this any more -- the chain-building ones moved to
     * [PathBuildingTrustManager], which resolves the same chains and several
     * that this cannot. It is kept because it is the reference the path-building
     * tests compare against: the guarantee worth holding onto is that path
     * building accepts everything this accepted and nothing it refused, and that
     * is only checkable while both exist.
     */
    fun pkixTrustManager(certs: List<X509Certificate>): X509TrustManager {
        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { index, certificate -> setCertificateEntry("ca$index", certificate) }
            }
        return trustManagerFrom("PKIX", keyStore)
    }

    private fun trustManagerFrom(algorithm: String, keyStore: KeyStore?): X509TrustManager = TrustManagerFactory.getInstance(algorithm)
        .apply { init(keyStore) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .firstOrNull()
        ?: throw IllegalStateException("No X509TrustManager from algorithm $algorithm")
}

/**
 * Tries the system trust store first, then the profile's own certificates.
 *
 * The order matters for the error, not the outcome: when both reject the chain,
 * the system manager's exception is the one thrown, because it explains what is
 * actually wrong with the certificate, while a PKIX failure against a
 * single-anchor store only ever says the chain could not be built. The custom
 * failure is attached as a suppressed exception so nothing is lost.
 */
class CompositeTrustManager(
    private val system: X509TrustManager,
    /** Readable so the engine can ask it which anchor accepted the chain. */
    val custom: X509TrustManager,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemFailure: CertificateException) {
            try {
                custom.checkServerTrusted(chain, authType)
            } catch (customFailure: CertificateException) {
                systemFailure.addSuppressed(customFailure)
                throw systemFailure
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + custom.acceptedIssuers
}

/**
 * Accepts a server whose leaf certificate has one of the pinned fingerprints.
 *
 * The chain is not built and the validity window is not checked — that is the
 * point of pinning, and it is what makes a self-signed certificate on a router
 * usable without turning verification off everywhere. Expiry is surfaced
 * separately by [TrustPreflight], as a warning before the connection rather
 * than a refusal during it.
 *
 * @property pinnedFingerprints SHA-256 fingerprints, in any of the forms a user
 *   might paste.
 */
class FingerprintPinningTrustManager(
    private val pinnedFingerprints: Set<String>,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf =
            chain.firstOrNull()
                ?: throw CertificatePinMismatchException(null, "The server presented no certificate")
        if (!CertificateFingerprint.matchesAnySha256(pinnedFingerprints, leaf)) {
            val presented = CertificateFingerprint.sha256(leaf)
            throw CertificatePinMismatchException(
                presented,
                "The server's certificate fingerprint ${CertificateFingerprint.formatForDisplay(presented)} " +
                    "does not match any pinned fingerprint",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("This trust manager does not authenticate clients")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts anything.
 *
 * Debug builds only, and [TrustManagerFactoryProvider.create] refuses to build
 * it unless explicitly allowed. It exists so that a developer can packet-trace
 * a connection, not so that a user can get past a certificate problem.
 */
class InsecureTrustManager : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * A pinned fingerprint did not match what the server presented.
 *
 * @property presentedSha256 fingerprint the server actually presented, so the
 *   engine can put it in
 *   [EngineError.CertificateRejected][io.github.mr1ve3r.combined.engine.EngineError.CertificateRejected]
 *   and the user can compare it with what they expected. `null` if the server
 *   sent no certificate at all.
 */
class CertificatePinMismatchException(
    val presentedSha256: String?,
    message: String,
) : CertificateException(message)
