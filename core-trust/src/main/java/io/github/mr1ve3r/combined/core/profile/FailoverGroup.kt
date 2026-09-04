package io.github.mr1ve3r.combined.core.profile

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An ordered set of profiles tried one after another (SPEC 10.1.1).
 *
 * The group holds no connection settings of its own beyond the budget: it is a
 * list of profiles and the order to try them in, and every profile in it is a
 * profile that can also be connected on its own. The case it exists for is one
 * server reachable two ways — L2TP/IPsec first because UDP is quicker, SSTP on
 * 443 behind it for networks where UDP/500 and ESP are blocked.
 *
 * @property connectTimeoutSec how long one member may take before the group
 *   gives up on it and tries the next. SPEC 10.1.2 sets the default at 15,
 *   which is also what makes the acceptance criterion reachable: a fall-through
 *   to the second member has to happen inside 20 seconds.
 * @property createdAt milliseconds since the epoch, so the list the user sees
 *   keeps the order the groups were made in, the way profiles do.
 */
@Entity(tableName = "failover_groups")
data class FailoverGroup(
    @PrimaryKey val id: String,
    val name: String,
    val connectTimeoutSec: Int = DEFAULT_CONNECT_TIMEOUT_SEC,
    val createdAt: Long,
) {
    companion object {
        /** SPEC 10.1.2's default budget for one member. */
        const val DEFAULT_CONNECT_TIMEOUT_SEC: Int = 15

        /**
         * The range a budget may take.
         *
         * The floor is not a style choice: below a couple of seconds a member
         * is abandoned before a slow network has answered at all, which turns a
         * group into a way of failing over past servers that work. The ceiling
         * keeps a group of three inside a wait a user will sit through.
         */
        const val MIN_CONNECT_TIMEOUT_SEC: Int = 5
        const val MAX_CONNECT_TIMEOUT_SEC: Int = 120

        /** [seconds] if it is a budget this application will run, the default otherwise. */
        fun normalizeTimeout(seconds: Int): Int =
            if (seconds in MIN_CONNECT_TIMEOUT_SEC..MAX_CONNECT_TIMEOUT_SEC) seconds else DEFAULT_CONNECT_TIMEOUT_SEC
    }
}

/**
 * One profile's place in one group.
 *
 * The order is a column rather than the insertion order of the rows: a table
 * has no order of its own, and the whole point of a group is that its members
 * are tried in the order the user arranged them.
 *
 * Both foreign keys cascade. Deleting a profile takes it out of every group
 * that named it, which is what a user deleting a profile means; deleting a
 * group takes its membership with it and leaves the profiles alone.
 *
 * @property position 0-based. Gaps are harmless — every read orders by this
 *   column and nothing reads the number itself — but [FailoverGroupStore]
 *   rewrites the whole membership on every change, so they do not arise.
 */
@Entity(
    tableName = "failover_group_member",
    primaryKeys = ["groupId", "profileId"],
    foreignKeys = [
        ForeignKey(
            entity = FailoverGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VpnProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class FailoverGroupMember(
    val groupId: String,
    val profileId: String,
    val position: Int,
)

/** A group together with the profiles it names, in the order it names them. */
data class FailoverGroupWithMembers(
    val group: FailoverGroup,
    val members: List<VpnProfile>,
)
