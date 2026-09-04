package io.github.mr1ve3r.combined.core.trust

import java.security.cert.X509Certificate

/**
 * Checks that a certificate was issued to the host being connected to.
 *
 * Kept separate from the trust policy on purpose. A certificate can be
 * perfectly trusted and still carry the wrong name, and the two failures need
 * different answers from the user: a trust failure means importing a
 * certificate, a name mismatch means telling the profile which name to expect.
 *
 * The name checked is `expectedHostname ?: server`. That field exists so a
 * certificate issued to `vpn.internal.lan` can be used when connecting over a
 * DDNS name or a bare IP — the case that otherwise tempts people to switch
 * verification off for good (SPEC 5.8, appendix Б item 4).
 */
object HostnameVerification {
    private const val DNS_PREFIX = "DNS:"
    private const val IP_PREFIX = "IP:"

    /**
     * @param certificate the leaf the server presented.
     * @param expectedHostname the name to check against, already resolved from
     *   `expectedHostname ?: server`.
     * @return [HostnameVerificationResult.Matched], or a mismatch listing every
     *   name the certificate does present so the user can see what to put in
     *   the profile.
     */
    fun verify(certificate: X509Certificate, expectedHostname: String): HostnameVerificationResult {
        val expected = normalise(expectedHostname)
        val presented = CertificateSummary.subjectAltNamesOf(certificate)
        val candidates = presented.ifEmpty { commonNameFallback(certificate) }

        val matched =
            candidates.any { candidate ->
                when {
                    candidate.startsWith(DNS_PREFIX) -> dnsMatches(candidate.removePrefix(DNS_PREFIX), expected)
                    candidate.startsWith(IP_PREFIX) -> normalise(candidate.removePrefix(IP_PREFIX)) == expected
                    else -> dnsMatches(candidate, expected)
                }
            }

        return if (matched) {
            HostnameVerificationResult.Matched
        } else {
            HostnameVerificationResult.Mismatch(expected = expectedHostname, presented = candidates)
        }
    }

    /**
     * Whether a name from a certificate covers [expected].
     *
     * Wildcards match a single leftmost label, so `*.example.com` covers
     * `vpn.example.com` but not `a.vpn.example.com` and not `example.com`.
     */
    internal fun dnsMatches(certificateName: String, expected: String): Boolean {
        val name = normalise(certificateName)
        if (name == expected) {
            return true
        }
        if (!name.startsWith("*.")) {
            return false
        }
        val suffix = name.removePrefix("*.")
        if (suffix.isEmpty() || !expected.endsWith(".$suffix")) {
            return false
        }
        val label = expected.dropLast(suffix.length + 1)
        return label.isNotEmpty() && !label.contains('.')
    }

    /**
     * The common name, used only when the certificate carries no SANs at all.
     *
     * RFC 6125 deprecated this, but routers issuing their own certificates
     * still omit SANs, and refusing outright would make them unusable.
     */
    private fun commonNameFallback(certificate: X509Certificate): List<String> =
        CertificateSummary.of(certificate).subjectCn?.let { listOf("DNS:$it") }.orEmpty()

    private fun normalise(host: String): String = host.trim().trimEnd('.').lowercase()
}

/** Outcome of [HostnameVerification.verify]. */
sealed interface HostnameVerificationResult {
    /** The certificate covers the expected name. */
    data object Matched : HostnameVerificationResult

    /**
     * The certificate is for a different host.
     *
     * @property expected the name that was checked.
     * @property presented every name the certificate carries, in the form
     *   `DNS:vpn.internal.lan`. Goes straight into
     *   [EngineError.HostnameMismatch][io.github.mr1ve3r.combined.engine.EngineError.HostnameMismatch]
     *   so the user is told what to write rather than left guessing.
     */
    data class Mismatch(
        val expected: String,
        val presented: List<String>,
    ) : HostnameVerificationResult
}
