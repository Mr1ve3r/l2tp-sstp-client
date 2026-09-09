package io.github.mr1ve3r.combined.core.profile

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Reads and writes for the profile table (SPEC 8.1). */
@Dao
interface ProfileDao {
    /** Every profile, oldest first, updated as the table changes. */
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<VpnProfile>>

    /** One-shot form of [observeAll]. */
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    suspend fun loadAll(): List<VpnProfile>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun find(id: String): VpnProfile?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    /**
     * Stores [profile], replacing an earlier version of it.
     *
     * `@Upsert` rather than `@Insert(onConflict = REPLACE)`. The two sound
     * alike and are not: `INSERT OR REPLACE` is a *delete* followed by an
     * insert, so every `ON DELETE CASCADE` pointing at the row fires, and the
     * children are gone before the new row lands. `@Upsert` updates the row in
     * place, which is what saving an edit was always supposed to mean.
     *
     * Here the children are the profile's failover group memberships and its
     * certificate references. Saving a profile used to drop it out of every
     * group it belonged to -- entering the login on a profile that arrived in
     * a shared set was enough -- and the certificate references survived only
     * because `ProfileChannel` happens to rewrite them straight afterwards.
     */
    @Upsert
    suspend fun upsert(profile: VpnProfile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String): Int
}
