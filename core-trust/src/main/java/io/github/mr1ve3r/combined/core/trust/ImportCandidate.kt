package io.github.mr1ve3r.combined.core.trust

import java.security.cert.X509Certificate

/**
 * A certificate the user has been offered but has not yet accepted.
 *
 * Every import path — a picked file, pasted text, a chain pulled off a server —
 * ends at a list of these, and the user chooses which ones to keep (SPEC 5.3).
 * The warnings are attached here rather than computed later so that the choice
 * is made with them on screen.
 *
 * @property summary the certificate's fields.
 * @property pem the certificate as PEM, which is how it will be stored.
 * @property warnings what is worth saying about it (SPEC 5.4). Never a reason
 *   to refuse the import.
 * @property chainPosition where it sat in a chain fetched from a server: 0 is
 *   the leaf, the last is the root the server offered. `null` for the other two
 *   import paths.
 */
data class ImportCandidate(
    val summary: CertificateSummary,
    val pem: String,
    val warnings: List<CertificateWarning>,
    val chainPosition: Int? = null,
) {
    companion object {
        /**
         * Describes [certificates] for the user to choose from.
         *
         * @param now current time in milliseconds since the epoch.
         * @param alreadyImportedIds fingerprints the store already holds, so a
         *   re-import can say so.
         * @param withChainPositions whether these certificates form a chain, in
         *   which case their index is meaningful.
         */
        fun of(
            certificates: List<X509Certificate>,
            now: Long,
            alreadyImportedIds: Set<String> = emptySet(),
            withChainPositions: Boolean = false,
        ): List<ImportCandidate> = certificates.mapIndexed { index, certificate ->
            val summary = CertificateSummary.of(certificate)
            ImportCandidate(
                summary = summary,
                pem = CertificateParser.toPem(certificate),
                warnings = CertificateValidator.validate(
                    summary = summary,
                    now = now,
                    alreadyImported = summary.id in alreadyImportedIds,
                ),
                chainPosition = index.takeIf { withChainPositions },
            )
        }
    }
}
