package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.util.concurrent.TimeUnit

/**
 * Checks a certificate at import time and reports what is worth telling the user.
 *
 * Everything here is a warning, never a refusal. A certificate that does not
 * parse is rejected by [CertificateParser]; past that point the user is
 * importing something deliberately, and an expired certificate or a short key
 * may well be exactly what their router presents. Blocking the import would
 * leave them unable to connect to their own server, so the decision stays
 * theirs and this only makes sure it is an informed one (SPEC 5.4).
 */
object CertificateValidator {
    /** How far ahead of expiry to start warning. */
    val EXPIRY_WARNING_WINDOW_MS: Long = TimeUnit.DAYS.toMillis(30)

    /** Shortest RSA key not warned about. */
    const val MINIMUM_RSA_BITS: Int = 2048

    private val WEAK_SIGNATURE_MARKERS = listOf("MD2", "MD5", "SHA1")

    /**
     * @param summary the certificate being imported.
     * @param now current time in milliseconds since the epoch.
     * @param intendedPolicy the trust policy the profile will use, so that a CA
     *   requirement can be checked. `null` when importing outside a profile.
     * @param alreadyImported whether the store already holds this fingerprint.
     * @return warnings, most serious first. Empty means nothing to say.
     */
    fun validate(
        summary: CertificateSummary,
        now: Long,
        intendedPolicy: TrustPolicy? = null,
        alreadyImported: Boolean = false,
    ): List<CertificateWarning> = buildList {
        when {
            summary.notAfter < now -> add(CertificateWarning.Expired(summary.notAfter))
            summary.notAfter - now < EXPIRY_WARNING_WINDOW_MS ->
                add(CertificateWarning.ExpiringSoon(TimeUnit.MILLISECONDS.toDays(summary.notAfter - now)))
        }
        if (summary.notBefore > now) {
            add(CertificateWarning.NotYetValid(summary.notBefore))
        }
        if (intendedPolicy != null && !summary.isCa && requiresCertificateAuthority(intendedPolicy)) {
            add(CertificateWarning.NotACertificateAuthority(intendedPolicy))
        }
        summary.publicKeyBits?.let { bits ->
            if (bits < MINIMUM_RSA_BITS) {
                add(CertificateWarning.WeakKey(bits))
            }
        }
        if (WEAK_SIGNATURE_MARKERS.any { summary.signatureAlgorithm.uppercase().contains(it) }) {
            add(CertificateWarning.WeakSignature(summary.signatureAlgorithm))
        }
        if (alreadyImported) {
            add(CertificateWarning.AlreadyImported)
        }
    }

    /** Whether [policy] looks at the certificates a profile has selected at all. */
    fun consultsSelectedCertificates(policy: TrustPolicy): Boolean =
        policy == TrustPolicy.CUSTOM_ONLY || policy == TrustPolicy.SYSTEM_PLUS_CUSTOM

    /** Whether [policy] builds a chain, and so wants a CA rather than a leaf. */
    fun requiresCertificateAuthority(policy: TrustPolicy): Boolean =
        policy == TrustPolicy.CUSTOM_ONLY || policy == TrustPolicy.SYSTEM_PLUS_CUSTOM
}

/**
 * Something worth telling the user about a certificate they are importing.
 *
 * Each carries the specific number or name the message needs, so the UI can say
 * "expires in 12 days" rather than "expires soon".
 */
sealed interface CertificateWarning {
    /** Localisation key for the message shown to the user. */
    val messageKey: String

    /** The certificate is past its validity window. */
    data class Expired(val notAfter: Long) : CertificateWarning {
        override val messageKey: String get() = "trust.warning.expired"
    }

    /** The certificate expires within [CertificateValidator.EXPIRY_WARNING_WINDOW_MS]. */
    data class ExpiringSoon(val daysRemaining: Long) : CertificateWarning {
        override val messageKey: String get() = "trust.warning.expiring_soon"
    }

    /** The validity window has not started yet, usually a clock problem at either end. */
    data class NotYetValid(val notBefore: Long) : CertificateWarning {
        override val messageKey: String get() = "trust.warning.not_yet_valid"
    }

    /**
     * A chain-building policy was chosen, but this certificate is not a CA.
     *
     * Usually means the user wants [TrustPolicy.PIN_LEAF] instead.
     */
    data class NotACertificateAuthority(val policy: TrustPolicy) : CertificateWarning {
        override val messageKey: String get() = "trust.warning.not_a_ca"
    }

    /** The RSA key is shorter than [CertificateValidator.MINIMUM_RSA_BITS]. */
    data class WeakKey(val bits: Int) : CertificateWarning {
        override val messageKey: String get() = "trust.warning.weak_key"
    }

    /** The certificate is signed with MD2, MD5 or SHA-1. */
    data class WeakSignature(val algorithm: String) : CertificateWarning {
        override val messageKey: String get() = "trust.warning.weak_signature"
    }

    /** The store already holds this fingerprint; importing again only updates the alias. */
    data object AlreadyImported : CertificateWarning {
        override val messageKey: String get() = "trust.warning.already_imported"
    }
}
