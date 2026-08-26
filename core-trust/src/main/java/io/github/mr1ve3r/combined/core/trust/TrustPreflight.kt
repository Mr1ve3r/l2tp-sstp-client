package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy

/**
 * Checks a profile's trust configuration before a socket is opened.
 *
 * The point is to replace an `SSLHandshakeException` ten seconds into a
 * connection attempt with a specific sentence beforehand. Every problem here is
 * one the application already knows about: a certificate the profile refers to
 * has been deleted, a policy has nothing to work with, something has expired.
 * None of it needs the network (SPEC 5.7).
 */
object TrustPreflight {
    /**
     * @param policy the profile's trust policy, already passed through
     *   [TrustManagerFactoryProvider.effectivePolicy].
     * @param selectedCertificateIds SHA-256 ids the profile refers to.
     * @param availableCertificates what the store actually holds, by id.
     * @param pinnedFingerprints pins configured on the profile.
     * @param now current time in milliseconds since the epoch.
     */
    fun check(
        policy: TrustPolicy,
        selectedCertificateIds: List<String>,
        availableCertificates: Map<String, CertificateSummary>,
        pinnedFingerprints: Set<String>,
        now: Long,
    ): PreflightReport {
        val blocking = mutableListOf<PreflightProblem>()
        val confirmations = mutableListOf<PreflightProblem>()

        val missing = selectedCertificateIds.filterNot(availableCertificates::containsKey)
        if (missing.isNotEmpty()) {
            blocking += PreflightProblem.CertificatesMissing(missing)
        }

        if (CertificateValidator.requiresCertificateAuthority(policy) && selectedCertificateIds.isEmpty()) {
            blocking += PreflightProblem.NoCertificatesSelected(policy)
        }

        if (policy == TrustPolicy.PIN_LEAF && pinnedFingerprints.isEmpty()) {
            blocking += PreflightProblem.NoPinsConfigured
        }

        // Expiry is a confirmation rather than a refusal: a router quietly
        // serving an expired certificate is common, and the user may know
        // exactly why. PIN_LEAF does not check expiry during the handshake at
        // all, so without this the fact would never surface.
        selectedCertificateIds
            .mapNotNull(availableCertificates::get)
            .filter { it.notAfter < now }
            .forEach { confirmations += PreflightProblem.CertificateExpired(it.id, it.subjectCn, it.notAfter) }

        return PreflightReport(blocking = blocking, confirmations = confirmations)
    }
}

/**
 * What the pre-flight found.
 *
 * @property blocking problems that make a connection attempt pointless. The
 *   attempt should not start.
 * @property confirmations facts the user should see and accept before
 *   connecting. The dialog defaults to cancelling (SPEC 5.7).
 */
data class PreflightReport(
    val blocking: List<PreflightProblem>,
    val confirmations: List<PreflightProblem>,
) {
    /** Whether a connection may be attempted at all. */
    val canConnect: Boolean get() = blocking.isEmpty()

    /** Whether the user has something to accept first. */
    val needsConfirmation: Boolean get() = confirmations.isNotEmpty()

    /** Nothing to report; connect without asking. */
    val isClean: Boolean get() = canConnect && !needsConfirmation
}

/** A specific thing wrong with a profile's trust configuration. */
sealed interface PreflightProblem {
    /** Localisation key for the message shown to the user. */
    val messageKey: String

    /**
     * The profile refers to certificates the store no longer holds, usually
     * because they were deleted from the certificate screen.
     */
    data class CertificatesMissing(val ids: List<String>) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.certificates_missing"
    }

    /** A policy that builds a chain was chosen without giving it anything to anchor on. */
    data class NoCertificatesSelected(val policy: TrustPolicy) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.no_certificates_selected"
    }

    /** [TrustPolicy.PIN_LEAF] was chosen without any fingerprint to compare against. */
    data object NoPinsConfigured : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.no_pins_configured"
    }

    /**
     * A selected certificate is past its validity window.
     *
     * @property subjectCn common name, so the dialog can name which one.
     */
    data class CertificateExpired(
        val id: String,
        val subjectCn: String?,
        val notAfter: Long,
    ) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.certificate_expired"
    }
}
