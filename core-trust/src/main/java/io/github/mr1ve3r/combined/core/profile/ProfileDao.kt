package io.github.mr1ve3r.combined.core.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: VpnProfile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String): Int
}
