package io.github.mr1ve3r.combined.core.trust.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Reads and writes for the certificate store (SPEC 5.1). */
@Dao
interface ServerCertificateDao {
    /** Every certificate with the number of profiles referring to it, newest import first. */
    @Query(
        """
        SELECT c.*, (
            SELECT COUNT(*) FROM profile_certificate_ref r WHERE r.certificateId = c.id
        ) AS usageCount
        FROM server_certificates c
        ORDER BY c.importedAt DESC
        """,
    )
    fun observeAll(): Flow<List<CertificateWithUsage>>

    /** One-shot form of [observeAll], for callers that are not a UI. */
    @Query(
        """
        SELECT c.*, (
            SELECT COUNT(*) FROM profile_certificate_ref r WHERE r.certificateId = c.id
        ) AS usageCount
        FROM server_certificates c
        ORDER BY c.importedAt DESC
        """,
    )
    suspend fun loadAll(): List<CertificateWithUsage>

    @Query("SELECT * FROM server_certificates WHERE id = :id")
    suspend fun find(id: String): ServerCertificateEntity?

    @Query("SELECT * FROM server_certificates WHERE id IN (:ids)")
    suspend fun findAll(ids: List<String>): List<ServerCertificateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(certificate: ServerCertificateEntity)

    @Query("UPDATE server_certificates SET alias = :alias WHERE id = :id")
    suspend fun rename(id: String, alias: String): Int

    @Query("DELETE FROM server_certificates WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT certificateId FROM profile_certificate_ref WHERE profileId = :profileId")
    suspend fun certificateIdsFor(profileId: String): List<String>

    @Query("SELECT profileId FROM profile_certificate_ref WHERE certificateId = :certificateId")
    suspend fun profileIdsUsing(certificateId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRef(ref: ProfileCertificateRef)

    @Query("DELETE FROM profile_certificate_ref WHERE profileId = :profileId")
    suspend fun clearRefsFor(profileId: String)

    /** Replaces the whole set of certificates a profile trusts, in one transaction. */
    @Transaction
    suspend fun setCertificatesFor(profileId: String, certificateIds: List<String>) {
        clearRefsFor(profileId)
        certificateIds.forEach { addRef(ProfileCertificateRef(profileId, it)) }
    }
}
