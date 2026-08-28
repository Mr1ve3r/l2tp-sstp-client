package io.github.mr1ve3r.combined.core.trust.store

import io.github.mr1ve3r.combined.core.trust.CertificateSummary

/**
 * A certificate as the store holds it: the certificate's own fields, plus what
 * the application knows about it.
 *
 * @property alias the name the user gave it.
 * @property importedAt milliseconds since the epoch.
 * @property usageCount how many profiles refer to it.
 */
data class StoredCertificate(
    val summary: CertificateSummary,
    val alias: String,
    val importedAt: Long,
    val usageCount: Int,
) {
    /** SHA-256 fingerprint, which is also the identity of the entry. */
    val id: String get() = summary.id

    companion object {
        internal fun of(row: CertificateWithUsage): StoredCertificate = StoredCertificate(
            summary = row.certificate.toSummary(),
            alias = row.certificate.alias,
            importedAt = row.certificate.importedAt,
            usageCount = row.usageCount,
        )
    }
}
