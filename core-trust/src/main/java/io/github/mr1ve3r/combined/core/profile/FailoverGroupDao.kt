package io.github.mr1ve3r.combined.core.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Reads and writes for failover groups and their membership (SPEC 10.1.1). */
@Dao
interface FailoverGroupDao {
    /** Every group, oldest first, updated as the table changes. */
    @Query("SELECT * FROM failover_groups ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<FailoverGroup>>

    /** One-shot form of [observeAll]. */
    @Query("SELECT * FROM failover_groups ORDER BY createdAt ASC")
    suspend fun loadAll(): List<FailoverGroup>

    @Query("SELECT * FROM failover_groups WHERE id = :id")
    suspend fun find(id: String): FailoverGroup?

    /**
     * Stores [group], replacing an earlier version of it.
     *
     * `@Upsert` rather than `@Insert(onConflict = REPLACE)`. The two sound
     * alike and are not: `INSERT OR REPLACE` is a *delete* followed by an
     * insert, so every `ON DELETE CASCADE` pointing at the row fires, and the
     * children are gone before the new row lands. `@Upsert` updates the row in
     * place, which is what saving an edit was always supposed to mean.
     *
     * Here the children are the group's own membership rows. They survived only
     * because [setMembers] runs immediately afterwards and writes them again;
     * that ordering is no longer load-bearing.
     */
    @Upsert
    suspend fun upsert(group: FailoverGroup)

    @Query("DELETE FROM failover_groups WHERE id = :id")
    suspend fun delete(id: String): Int

    /**
     * The profiles [groupId] names, in the order it names them.
     *
     * A join rather than two queries because the order lives in the membership
     * table and the rows live in the profile table, and re-sorting in Kotlin
     * would be one more place for the order to be lost.
     */
    @Query(
        "SELECT profiles.* FROM profiles " +
            "INNER JOIN failover_group_member ON profiles.id = failover_group_member.profileId " +
            "WHERE failover_group_member.groupId = :groupId " +
            "ORDER BY failover_group_member.position ASC",
    )
    suspend fun membersOf(groupId: String): List<VpnProfile>

    /** Ids of the groups that name [profileId], so a deletion can say what it will change. */
    @Query("SELECT groupId FROM failover_group_member WHERE profileId = :profileId")
    suspend fun groupIdsUsing(profileId: String): List<String>

    @Query("DELETE FROM failover_group_member WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<FailoverGroupMember>)

    /**
     * Replaces the membership of [groupId] with [profileIds], in that order.
     *
     * One transaction because the two halves are one edit: a failure between
     * them would leave a group that is empty, and an empty group is a group
     * that cannot connect anything.
     */
    @Transaction
    suspend fun setMembers(groupId: String, profileIds: List<String>) {
        clearMembers(groupId)
        insertMembers(
            profileIds.mapIndexed { index, profileId ->
                FailoverGroupMember(groupId = groupId, profileId = profileId, position = index)
            },
        )
    }
}
