package io.github.mr1ve3r.combined.core.trust.store

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mr1ve3r.combined.core.trust.CertificateSummary

/**
 * One imported server certificate (SPEC 5.1).
 *
 * The row is metadata only. The certificate itself lives as PEM in
 * [CertificateFileStore], named after the same fingerprint, because a database
 * row is the wrong place for a blob that trust decisions are made from and that
 * the user may want to export unchanged.
 *
 * @property id equal to [sha256Fingerprint]. Two files holding the same
 *   certificate are one entry, so a re-import updates the alias rather than
 *   creating a duplicate.
 * @property alias the name the user gave this certificate.
 * @property fileName name of the PEM file under `filesDir/trust`.
 */
@Entity(tableName = "server_certificates")
data class ServerCertificateEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val subjectCn: String?,
    val subjectDn: String,
    val issuerDn: String,
    val serialNumber: String,
    val notBefore: Long,
    val notAfter: Long,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val isCa: Boolean,
    val keyUsage: String?,
    val subjectAltNames: List<String>,
    val publicKeyBits: Int?,
    val signatureAlgorithm: String,
    val importedAt: Long,
    val fileName: String,
) {
    /** The certificate fields the rest of the application works with. */
    fun toSummary(): CertificateSummary = CertificateSummary(
        id = id,
        subjectCn = subjectCn,
        subjectDn = subjectDn,
        issuerDn = issuerDn,
        serialNumber = serialNumber,
        notBefore = notBefore,
        notAfter = notAfter,
        sha256Fingerprint = sha256Fingerprint,
        sha1Fingerprint = sha1Fingerprint,
        isCa = isCa,
        keyUsage = keyUsage,
        subjectAltNames = subjectAltNames,
        publicKeyBits = publicKeyBits,
        signatureAlgorithm = signatureAlgorithm,
    )

    companion object {
        /**
         * Builds a row from [summary].
         *
         * @param alias the user-visible name.
         * @param importedAt milliseconds since the epoch.
         */
        fun of(summary: CertificateSummary, alias: String, importedAt: Long): ServerCertificateEntity = ServerCertificateEntity(
            id = summary.id,
            alias = alias,
            subjectCn = summary.subjectCn,
            subjectDn = summary.subjectDn,
            issuerDn = summary.issuerDn,
            serialNumber = summary.serialNumber,
            notBefore = summary.notBefore,
            notAfter = summary.notAfter,
            sha256Fingerprint = summary.sha256Fingerprint,
            sha1Fingerprint = summary.sha1Fingerprint,
            isCa = summary.isCa,
            keyUsage = summary.keyUsage,
            subjectAltNames = summary.subjectAltNames,
            publicKeyBits = summary.publicKeyBits,
            signatureAlgorithm = summary.signatureAlgorithm,
            importedAt = importedAt,
            fileName = CertificateFileStore.fileNameFor(summary.id),
        )
    }
}

/**
 * Which certificates a profile trusts (SPEC 5.1, many-to-many).
 *
 * Only the certificate side is a foreign key: profiles live in the Flutter
 * layer's own storage until phase 8 moves them, so the database cannot enforce
 * that end of the relation yet. Deleting a certificate drops its references, so
 * a profile never points at a row that is gone — the pre-flight check
 * ([io.github.mr1ve3r.combined.core.trust.TrustPreflight]) is what reports the
 * profile is now short of a certificate.
 */
@Entity(
    tableName = "profile_certificate_ref",
    primaryKeys = ["profileId", "certificateId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerCertificateEntity::class,
            parentColumns = ["id"],
            childColumns = ["certificateId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("certificateId")],
)
data class ProfileCertificateRef(
    val profileId: String,
    val certificateId: String,
)
