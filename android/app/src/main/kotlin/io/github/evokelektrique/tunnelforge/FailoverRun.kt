package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.profile.FailoverGroup
import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.FailoverDecision
import io.github.mr1ve3r.combined.engine.failoverDecision

/**
 * One walk through a failover group's members (SPEC 10.1.2).
 *
 * Kept out of the service so the sequencing can be tested without an Android
 * device: the service contributes the sockets and the threads, this contributes
 * the decision of what to try next, and only the second one has rules worth
 * asserting.
 *
 * Every member of a run shares one `attemptId`. From the user's side a group is
 * a single connection attempt — they pressed connect once — and the service's
 * own staleness checks key on that id, so giving each member its own would make
 * the second member's progress look like a leftover from a cancelled attempt.
 * Which member is being tried is carried by [position] instead.
 *
 * @property members in the order the group names them, already resolved with
 *   their secrets. Resolving them up front is deliberate: the alternative is a
 *   database read between two attempts, and a failover that has to wait for
 *   storage is a failover that misses the 20-second budget the SPEC sets.
 */
internal class FailoverRun(
    val groupId: String,
    val groupName: String,
    val members: List<TunnelStartRequest>,
    val connectTimeoutSec: Int = FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC,
) {
    private var cursor: Int = 0

    /** The member being tried, 1-based, for the log and for the UI. */
    val position: Int get() = cursor + 1

    val size: Int get() = members.size

    /** The member currently being tried. */
    val current: TunnelStartRequest get() = members[cursor]

    /** How long one member may take before the run gives up on it. */
    val connectTimeoutMs: Long get() = connectTimeoutSec * 1_000L

    /**
     * The next member to try after [error], or `null` when the run is over.
     *
     * `null` means the failure is the group's failure and the user should see
     * it — either because this error stops a group outright (wrong credentials,
     * a rejected certificate) or because there is nothing left to try. The
     * caller reports the error it already has in both cases, which is what
     * makes the last member's failure the one shown.
     *
     * Advancing moves the cursor, so calling this twice for one failure skips a
     * member. The service calls it once, from the one place a member's
     * negotiation can fail.
     */
    fun advanceAfter(error: EngineError): TunnelStartRequest? {
        if (error.failoverDecision == FailoverDecision.STOP) return null
        val next = members.getOrNull(cursor + 1) ?: return null
        cursor += 1
        return next
    }

    /** Why the run ended where it did, for the log. */
    fun stopReason(error: EngineError): String = when {
        error.failoverDecision == FailoverDecision.STOP ->
            "the failure stops a group: every member would answer the same way"
        else -> "no members left to try"
    }
}
