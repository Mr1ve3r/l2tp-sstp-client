package io.github.mr1ve3r.combined.core.trust.store

import android.content.Context
import io.github.mr1ve3r.combined.core.trust.CertificateSummary
import java.io.File
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The certificate store as the rest of the application sees it (SPEC 5.1–5.2).
 *
 * Metadata lives in Room and the certificates themselves in files; keeping the
 * two consistent is this class's job and nobody else's. A row without its file
 * would be a certificate the tunnel cannot use but the user can still select,
 * so every write does the file first: if that fails there is no row, and the
 * import simply did not happen.
 *
 * Every method is `suspend` because both halves touch storage.
 */
class TrustStore(
    private val dao: ServerCertificateDao,
    private val files: CertificateFileStore,
) {
    /** Everything in the store, newest import first, updated as it changes. */
    fun observe(): Flow<List<StoredCertificate>> = dao.observeAll().map { rows -> rows.map(StoredCertificate::of) }

    /** Everything in the store, newest import first. */
    suspend fun list(): List<StoredCertificate> = dao.loadAll().map(StoredCertificate::of)

    /** The entry for [id], or `null` if it was never imported or has been deleted. */
    suspend fun find(id: String): StoredCertificate? = dao.find(id)?.let {
        StoredCertificate(it.toSummary(), it.alias, it.importedAt, dao.profileIdsUsing(id).size)
    }

    /**
     * Stores [certificate], or updates the alias if it is already there.
     *
     * Identity is the fingerprint, so importing the same certificate from a
     * different file is not a duplicate (SPEC 5.4).
     *
     * @param alias the user-visible name; blank falls back to the common name,
     *   and then to the fingerprint, so a list entry is never nameless.
     * @param now milliseconds since the epoch, recorded as the import time.
     */
    suspend fun import(certificate: X509Certificate, alias: String, now: Long): StoredCertificate {
        val summary = CertificateSummary.of(certificate)
        withContext(Dispatchers.IO) { files.write(certificate) }
        val existing = dao.find(summary.id)
        val entity = ServerCertificateEntity.of(
            summary = summary,
            alias = aliasFor(alias, summary),
            importedAt = existing?.importedAt ?: now,
        )
        dao.upsert(entity)
        return StoredCertificate(summary, entity.alias, entity.importedAt, dao.profileIdsUsing(summary.id).size)
    }

    /** Renames [id]. Returns whether there was such an entry. */
    suspend fun rename(id: String, alias: String): Boolean {
        val existing = dao.find(id) ?: return false
        return dao.rename(id, aliasFor(alias, existing.toSummary())) > 0
    }

    /**
     * Removes [id] from the store, file included.
     *
     * References from profiles go with it through the foreign key cascade. A
     * profile that trusted this certificate is not silently repaired: the next
     * pre-flight check tells the user it is missing, which is the moment they
     * can do something about it.
     *
     * @return whether there was an entry to remove.
     */
    suspend fun delete(id: String): Boolean {
        val removedRow = dao.delete(id) > 0
        val removedFile = withContext(Dispatchers.IO) { files.delete(id) }
        return removedRow || removedFile
    }

    /** The stored PEM for [id], for export or for showing the user. */
    suspend fun exportPem(id: String): String? = withContext(Dispatchers.IO) { files.readPem(id) }

    /**
     * The certificates behind [ids], for building a trust manager.
     *
     * Ids with no file are skipped rather than reported: by the time a
     * connection is being made the pre-flight check has already told the user
     * about anything missing, and failing here would replace that message with
     * a worse one.
     */
    suspend fun certificatesFor(ids: List<String>): List<X509Certificate> = withContext(Dispatchers.IO) {
        ids.mapNotNull { runCatching { files.read(it) }.getOrNull() }
    }

    /** Summaries of [ids] that the store still holds, keyed by id, for the pre-flight check. */
    suspend fun summariesFor(ids: List<String>): Map<String, CertificateSummary> = dao
        .findAll(ids)
        .associate { it.id to it.toSummary() }

    /** Ids of the certificates [profileId] trusts. */
    suspend fun certificateIdsFor(profileId: String): List<String> = dao.certificateIdsFor(profileId)

    /** Replaces the set of certificates [profileId] trusts. */
    suspend fun setCertificatesFor(profileId: String, certificateIds: List<String>) {
        dao.setCertificatesFor(profileId, certificateIds.filter { dao.find(it) != null })
    }

    /** Ids of the profiles that trust [certificateId]. */
    suspend fun profileIdsUsing(certificateId: String): List<String> = dao.profileIdsUsing(certificateId)

    private fun aliasFor(alias: String, summary: CertificateSummary): String = alias.trim().ifBlank {
        summary.subjectCn ?: summary.id
    }

    companion object {
        /**
         * The store for this application, sharing one database across callers.
         *
         * @param context any context; the application context is used.
         */
        fun get(context: Context): TrustStore {
            val application = context.applicationContext
            return TrustStore(
                dao = TrustDatabase.get(application).serverCertificates(),
                files = CertificateFileStore(File(application.filesDir, CertificateFileStore.DIRECTORY_NAME)),
            )
        }
    }
}
