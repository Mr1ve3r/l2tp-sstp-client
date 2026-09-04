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
     * @param storeSize how many certificates the store holds, for
     *   [TrustPolicy.STORE_AUTO], whose anchors come from there rather than
     *   from the profile. Defaults to a value that never trips, so callers
     *   using a selection-based policy need not supply it.
     */
    fun check(
        policy: TrustPolicy,
        selectedCertificateIds: List<String>,
        availableCertificates: Map<String, CertificateSummary>,
        pinnedFingerprints: Set<String>,
        now: Long,
        storeSize: Int = Int.MAX_VALUE,
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

        // Anchoring on the whole store is not a way to connect without any
        // certificate at all. An empty store means there is nothing to build a
        // path to, and saying so now beats a handshake failure later.
        if (CertificateValidator.consultsWholeStore(policy) && storeSize == 0) {
            blocking += PreflightProblem.StoreIsEmpty
        }

        if (policy == TrustPolicy.PIN_LEAF && pinnedFingerprints.isEmpty()) {
            blocking += PreflightProblem.NoPinsConfigured
        }

        // A certificate the policy will never look at. The profile keeps its
        // selection when the policy changes, so a profile can carry a
        // certificate and verify against the system store without either the
        // list or the error saying so.
        //
        // Whole-store policies are excluded because the message would be a lie
        // there: a leftover selection is still consulted, just not exclusively.
        if (!CertificateValidator.consultsSelectedCertificates(policy) &&
            !CertificateValidator.consultsWholeStore(policy) &&
            selectedCertificateIds.isNotEmpty()
        ) {
            confirmations += PreflightProblem.CertificatesIgnoredByPolicy(policy, selectedCertificateIds)
        }

        // What the user gave up by not picking a certificate. Shown every time,
        // because the set of certificates that can vouch for this server grows
        // silently every time another one is imported.
        if (CertificateValidator.consultsWholeStore(policy) && storeSize > 0 && storeSize != Int.MAX_VALUE) {
            confirmations += PreflightProblem.WholeStoreIsTrusted(storeSize)
        }

        // A CA pinned under PIN_LEAF matches only if the server serves that
        // certificate itself. Where a CA issued a separate server certificate
        // -- the ordinary case on a router -- the pin can never match, and the
        // handshake reports a fingerprint the user has never seen.
        pinnedFingerprints
            .mapNotNull { availableCertificates[CertificateFingerprint.normalise(it)] }
            .filter { it.isCa }
            .forEach { confirmations += PreflightProblem.PinnedCertificateIsCa(it.id, it.subjectCn) }

        // The mirror image: a leaf handed to a chain-building policy is not an
        // anchor for anything, and PKIX reports only that no trust anchor was
        // found.
        if (CertificateValidator.requiresCertificateAuthority(policy)) {
            selectedCertificateIds
                .mapNotNull(availableCertificates::get)
                .filterNot { it.isCa }
                .forEach { confirmations += PreflightProblem.AnchorIsNotACertificateAuthority(it.id, it.subjectCn) }
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

    /**
     * The profile names certificates that its policy does not consult.
     *
     * Not an error -- the connection may well succeed against the system store
     * -- but it is the difference between "my certificate is not working" and
     * "my certificate is not being used".
     */
    data class CertificatesIgnoredByPolicy(val policy: TrustPolicy, val ids: List<String>) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.certificates_ignored_by_policy"
    }

    /**
     * A pinned certificate is a CA, and a CA is not what a server presents
     * unless it serves its own root directly.
     */
    data class PinnedCertificateIsCa(val id: String, val subjectCn: String?) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.pinned_certificate_is_ca"
    }

    /** A chain-building policy was anchored on a certificate that cannot sign anything. */
    data class AnchorIsNotACertificateAuthority(val id: String, val subjectCn: String?) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.anchor_is_not_a_ca"
    }

    /** [TrustPolicy.PIN_LEAF] was chosen without any fingerprint to compare against. */
    data object NoPinsConfigured : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.no_pins_configured"
    }

    /**
     * [TrustPolicy.STORE_AUTO] was chosen but the store holds nothing.
     *
     * The mirror of [NoCertificatesSelected] for a policy that takes its
     * anchors from the store: there is no selection to be missing, and nothing
     * to build a path to either.
     */
    data object StoreIsEmpty : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.store_is_empty"
    }

    /**
     * What [TrustPolicy.STORE_AUTO] costs, stated before connecting.
     *
     * Not a fault -- it is the policy working as asked -- but the set it names
     * grows every time a certificate is imported for some other server, and
     * nothing else in the interface would say so.
     */
    data class WholeStoreIsTrusted(val certificateCount: Int) : PreflightProblem {
        override val messageKey: String get() = "trust.preflight.whole_store_is_trusted"
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
