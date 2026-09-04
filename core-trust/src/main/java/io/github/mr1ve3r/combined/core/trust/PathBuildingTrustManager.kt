package io.github.mr1ve3r.combined.core.trust

import java.security.InvalidAlgorithmParameterException
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager

/**
 * Finds *a* valid path from the server's certificate to one of its anchors,
 * instead of checking the one chain the server happened to send.
 *
 * `TrustManagerFactory.getInstance("PKIX")` validates the presented chain much
 * as it arrived. That is enough for a public CA, which sends a tidy chain, and
 * not enough for the routers this application exists for: they omit the
 * intermediate they were supposed to send, or send an extra certificate nobody
 * needs, or send the anchor along with everything else. Windows copes because
 * `CryptoAPI` treats every certificate it can see -- presented or local -- as a
 * candidate and searches for a path. This does the same thing with
 * [CertPathBuilder]: the presented chain and the store go into one pool, the
 * anchors stay separate, and the builder searches.
 *
 * **What this deliberately does not do.** Nothing here is a relaxation of PKIX.
 * There is no short circuit for a certificate whose fingerprint happens to be
 * in the store, no bypass of `basicConstraints` for an anchor that was never
 * marked as a certificate authority, and no bypass of the validity window.
 * [CertPathBuilder.build] validates every candidate path as it builds it, so a
 * path that comes back has passed the same checks it always did -- signatures,
 * validity, `basicConstraints`, `pathLenConstraint` and `keyUsage`. Revocation
 * is off, and only because a handshake must not make network calls of its own.
 *
 * The end entity is [chain]`[0]` and is never re-chosen. TLS binds the server
 * to that certificate and to nothing else, so picking some other element of the
 * pool as the "real" leaf -- because its name matches, say -- would accept a
 * server that proved possession of a different key entirely.
 *
 * @property anchors what a path is allowed to end at.
 * @property extraIntermediates certificates offered to the builder as
 *   candidates but never as anchors. The store's contents go here.
 * @property exposeAcceptedIssuers whether [getAcceptedIssuers] names the
 *   anchors. False where the anchor set is the whole store, so that a server
 *   asking for client authentication is not handed a list of every certificate
 *   authority the user has ever imported.
 * @property clock current time in milliseconds since the epoch, so a test can
 *   place itself inside or outside a validity window.
 */
class PathBuildingTrustManager(
    private val anchors: Set<TrustAnchor>,
    private val extraIntermediates: List<X509Certificate> = emptyList(),
    private val exposeAcceptedIssuers: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) : X509TrustManager {
    init {
        require(anchors.isNotEmpty()) { "Path building needs at least one trust anchor" }
    }

    /**
     * The path behind the last accepted server, end entity first.
     *
     * Worth logging: when the anchors are a whole store rather than one picked
     * certificate, this is the only way to answer "which of my certificates
     * vouched for this server?".
     */
    @Volatile
    var lastValidatedPath: List<X509Certificate>? = null
        private set

    /** The anchor the last accepted path ended at. */
    @Volatile
    var lastAnchor: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val endEntity =
            chain.firstOrNull()
                ?: throw CertificateException("The server presented no certificate")

        // Checked here rather than left to the builder. When the end entity is
        // the certificate that has lapsed, the builder reports only that no
        // path could be found -- true, and useless, because the reason is the
        // one thing the user could act on. Doing it first turns that into the
        // specific exception the engine already maps to a specific message.
        endEntity.checkValidity(Date(clock()))

        anchorMatching(endEntity)?.let { anchor ->
            // The server is serving one of the anchors itself. The builder
            // handles this inconsistently across providers -- an empty path
            // from one, an exception from another -- so it is settled here.
            lastValidatedPath = listOf(endEntity)
            lastAnchor = anchor
            return
        }

        val parameters =
            PKIXBuilderParameters(anchors, X509CertSelector().apply { certificate = endEntity }).apply {
                isRevocationEnabled = false
                date = Date(clock())
                addCertStore(
                    CertStore.getInstance(
                        "Collection",
                        CollectionCertStoreParameters(chain.toList() + extraIntermediates),
                    ),
                )
            }

        val result =
            try {
                CertPathBuilder.getInstance("PKIX").build(parameters) as PKIXCertPathBuilderResult
            } catch (e: CertPathBuilderException) {
                throw translate(e)
            } catch (e: InvalidAlgorithmParameterException) {
                // Only reachable if the anchor set were empty, which the
                // constructor forbids. Reported as a certificate failure all
                // the same, so it cannot escape as an opaque handshake error.
                throw CertificateException("The trust anchors are unusable: ${e.message}", e)
            }

        val path = result.certPath.certificates.filterIsInstance<X509Certificate>()
        val anchor = result.trustAnchor.trustedCert
        requireAnchorMayIssue(anchor, path)

        lastValidatedPath = path
        lastAnchor = anchor
    }

    /**
     * Refuses an anchor that vouched for another certificate without ever being
     * marked as a certificate authority.
     *
     * RFC 5280 treats a trust anchor as an input to validation rather than a
     * member of the path, so [CertPathBuilder] never applies `basicConstraints`
     * to it -- while `TrustManagerFactory`, which this replaces, does. Without
     * this check, switching to path building would start accepting a router
     * certificate that signed itself a second certificate, which is precisely
     * what the extension exists to prevent.
     *
     * A path of length zero is exempt: there the server presented the anchor
     * itself, nothing was issued, and there is nothing for the extension to
     * govern.
     */
    private fun requireAnchorMayIssue(anchor: X509Certificate?, path: List<X509Certificate>) {
        if (anchor == null || path.isEmpty()) return
        if (anchor.basicConstraints >= 0) return

        throw CertificateException(
            "The chain ends at ${anchor.subjectX500Principal.name}, which is not marked as a certificate " +
                "authority and so cannot vouch for another certificate",
        )
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
        throw CertificateException("This trust manager does not authenticate clients")

    override fun getAcceptedIssuers(): Array<X509Certificate> = if (exposeAcceptedIssuers) {
        anchors.mapNotNull(TrustAnchor::getTrustedCert).toTypedArray()
    } else {
        emptyArray()
    }

    /** The anchor holding [certificate] itself, if there is one. */
    private fun anchorMatching(certificate: X509Certificate): X509Certificate? = anchors
        .mapNotNull(TrustAnchor::getTrustedCert)
        .firstOrNull { it == certificate }

    /**
     * Turns a build failure into the exception the engine already knows how to
     * report.
     *
     * Two things are wrong with what [CertPathBuilder] throws. It is not a
     * [CertificateException], so it would bypass every certificate-specific
     * branch of the engine's error mapping and arrive as a bare handshake
     * failure. And its own message is always the same sentence about not
     * finding a valid path, while the reason the user needs -- an expiry, a
     * missing anchor, a certificate that is not a certificate authority -- is
     * on the [CertPathValidatorException] underneath it.
     */
    private fun translate(failure: CertPathBuilderException): CertificateException {
        val validatorFailure = rootValidatorFailure(failure)
        val reason = validatorFailure?.reason

        if (reason == CertPathValidatorException.BasicReason.EXPIRED) {
            // Reported as an expiry rather than a path failure so it keeps
            // mapping to the specific error the UI can explain.
            return CertificateExpiredException(detailOf(failure, validatorFailure))
                .also { it.initCause(failure) }
        }

        return CertificateException(detailOf(failure, validatorFailure), failure)
    }

    private fun detailOf(failure: CertPathBuilderException, validatorFailure: CertPathValidatorException?): String =
        validatorFailure?.message?.takeIf(String::isNotBlank)
            ?: failure.message
            ?: "no certification path could be built"

    private fun rootValidatorFailure(failure: Throwable): CertPathValidatorException? {
        var current: Throwable? = failure
        while (current != null) {
            if (current is CertPathValidatorException) return current
            current = current.cause.takeIf { it !== current }
        }
        return null
    }

    companion object {
        /**
         * A manager anchored on [certs], with [pool] offered as intermediates.
         *
         * Every certificate in [certs] becomes an anchor, including ones that
         * are not certificate authorities. Filtering them out would look
         * tidier and would quietly break the case where a server presents
         * exactly the certificate the user stored: that path has no issuing
         * step, so `basicConstraints` has nothing to say about it, and PKIX has
         * always accepted it. Where the extension *does* matter -- a
         * certificate asked to vouch for a different one -- PKIX still refuses,
         * which is where that decision belongs.
         *
         * @param certs the anchors: what a path is allowed to end at.
         * @param pool extra path candidates. Being here is not trust; a
         *   certificate in the pool can only ever be a link on the way to an
         *   anchor.
         */
        fun anchoredOn(
            certs: List<X509Certificate>,
            pool: List<X509Certificate> = emptyList(),
            exposeAcceptedIssuers: Boolean = true,
            clock: () -> Long = System::currentTimeMillis,
        ): PathBuildingTrustManager = PathBuildingTrustManager(
            anchors = certs.mapTo(mutableSetOf()) { TrustAnchor(it, null) },
            extraIntermediates = pool,
            exposeAcceptedIssuers = exposeAcceptedIssuers,
            clock = clock,
        )
    }
}
