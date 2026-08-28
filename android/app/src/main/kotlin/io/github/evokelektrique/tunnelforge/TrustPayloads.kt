package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.trust.CertificateSummary
import io.github.mr1ve3r.combined.core.trust.CertificateWarning
import io.github.mr1ve3r.combined.core.trust.ImportCandidate
import io.github.mr1ve3r.combined.core.trust.store.StoredCertificate

/**
 * Turns certificate store types into the maps that cross the method channel.
 *
 * Kept apart from [TrustChannel] because this is the part worth testing: the
 * shape here and the parser in `lib/features/trust` have to agree, and a
 * renamed key is a field that silently disappears from the UI.
 *
 * Warnings become a key and an optional detail rather than a sentence. The
 * wording is the UI's business, and it is the side that knows the user's
 * language.
 */
object TrustPayloads {
    /** A certificate already in the store, with its alias and usage count. */
    fun stored(certificate: StoredCertificate): Map<String, Any?> =
        summary(certificate.summary) +
            mapOf(
                TrustContract.FIELD_ALIAS to certificate.alias,
                TrustContract.FIELD_IMPORTED_AT to certificate.importedAt,
                TrustContract.FIELD_USAGE_COUNT to certificate.usageCount,
            )

    /** A certificate the user has been offered but not yet accepted. */
    fun candidate(candidate: ImportCandidate): Map<String, Any?> =
        summary(candidate.summary) +
            mapOf(
                TrustContract.FIELD_PEM to candidate.pem,
                TrustContract.FIELD_CHAIN_POSITION to candidate.chainPosition,
                TrustContract.FIELD_WARNINGS to candidate.warnings.map(::warning),
            )

    /** The fields both forms share. */
    fun summary(summary: CertificateSummary): Map<String, Any?> =
        mapOf(
            TrustContract.FIELD_ID to summary.id,
            TrustContract.FIELD_SUBJECT_CN to summary.subjectCn,
            TrustContract.FIELD_SUBJECT_DN to summary.subjectDn,
            TrustContract.FIELD_ISSUER_DN to summary.issuerDn,
            TrustContract.FIELD_SERIAL_NUMBER to summary.serialNumber,
            TrustContract.FIELD_NOT_BEFORE to summary.notBefore,
            TrustContract.FIELD_NOT_AFTER to summary.notAfter,
            TrustContract.FIELD_SHA256 to summary.sha256Fingerprint,
            TrustContract.FIELD_SHA1 to summary.sha1Fingerprint,
            TrustContract.FIELD_IS_CA to summary.isCa,
            TrustContract.FIELD_KEY_USAGE to summary.keyUsage,
            TrustContract.FIELD_SUBJECT_ALT_NAMES to summary.subjectAltNames,
            TrustContract.FIELD_PUBLIC_KEY_BITS to summary.publicKeyBits,
            TrustContract.FIELD_SIGNATURE_ALGORITHM to summary.signatureAlgorithm,
        )

    /**
     * One import warning.
     *
     * The detail is whatever number the message needs — days remaining, key
     * size, a timestamp — as a string, because the channel has no type that
     * covers all three and the UI only interpolates it.
     */
    fun warning(warning: CertificateWarning): Map<String, Any?> =
        mapOf(
            TrustContract.FIELD_WARNING_KEY to warning.messageKey,
            TrustContract.FIELD_WARNING_DETAIL to detailOf(warning),
        )

    private fun detailOf(warning: CertificateWarning): String? =
        when (warning) {
            is CertificateWarning.Expired -> warning.notAfter.toString()
            is CertificateWarning.ExpiringSoon -> warning.daysRemaining.toString()
            is CertificateWarning.NotYetValid -> warning.notBefore.toString()
            is CertificateWarning.NotACertificateAuthority -> warning.policy.name
            is CertificateWarning.WeakKey -> warning.bits.toString()
            is CertificateWarning.WeakSignature -> warning.algorithm
            CertificateWarning.AlreadyImported -> null
        }
}
