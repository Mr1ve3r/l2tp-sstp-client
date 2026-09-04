package io.github.mr1ve3r.combined.core.profile

import android.content.Context
import io.github.mr1ve3r.combined.core.trust.store.TrustDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Failover groups as the rest of the application sees them (SPEC 10.1).
 *
 * Groups sit beside profiles rather than inside them: a connection is started
 * by naming a profile or by naming a group, and a profile does not know which
 * groups mention it. That is what keeps a profile connectable on its own after
 * it has been put in a group, and what lets one profile stand in several.
 *
 * The store is deliberately thin. Deciding what to do when a member fails is
 * `EngineError.failoverDecision`'s job, and running the list is the service's;
 * this class only answers what a group is and what is in it.
 */
class FailoverGroupStore(private val dao: FailoverGroupDao) {
    /** Every group, oldest first, updated as the table changes. */
    fun observe(): Flow<List<FailoverGroup>> = dao.observeAll()

    /** Every group, oldest first. */
    suspend fun list(): List<FailoverGroup> = dao.loadAll()

    /** The group with [id], or `null` if there is none. */
    suspend fun find(id: String): FailoverGroup? = dao.find(id)

    /**
     * The group with [id] and the profiles it names, in order.
     *
     * `null` when there is no such group. A group whose members have all been
     * deleted comes back with an empty list rather than `null`: the group still
     * exists, and the caller has to tell the user that it has nothing to try.
     */
    suspend fun findWithMembers(id: String): FailoverGroupWithMembers? {
        val group = dao.find(id) ?: return null
        return FailoverGroupWithMembers(group = group, members = dao.membersOf(id))
    }

    /**
     * Stores [group] and sets its membership to [profileIds], in that order.
     *
     * Repeats are dropped, keeping the first mention: a profile appearing twice
     * in a group would be tried twice with the same settings and the same
     * result, and the second attempt is only a longer wait. The membership is
     * written even when it is empty, which is how a group is emptied.
     *
     * @return the group as it was stored, with the budget clamped.
     */
    suspend fun save(group: FailoverGroup, profileIds: List<String>): FailoverGroup {
        val stored = group.copy(connectTimeoutSec = FailoverGroup.normalizeTimeout(group.connectTimeoutSec))
        dao.upsert(stored)
        dao.setMembers(stored.id, profileIds.distinct())
        return stored
    }

    /**
     * Removes [id]. Its membership goes with it; the profiles stay.
     *
     * @return whether there was a group to remove.
     */
    suspend fun delete(id: String): Boolean = dao.delete(id) > 0

    /** Ids of the groups that name [profileId], so a profile deletion can say what it affects. */
    suspend fun groupIdsUsing(profileId: String): List<String> = dao.groupIdsUsing(profileId).distinct()

    companion object {
        /** The store for this application, over the database the profiles are in. */
        fun get(context: Context): FailoverGroupStore = FailoverGroupStore(
            TrustDatabase.get(context.applicationContext).failoverGroups(),
        )

        /** An identifier for a new group. Same shape as a profile's, from the same generator. */
        fun newGroupId(): String = ProfileStore.newProfileId()
    }
}
