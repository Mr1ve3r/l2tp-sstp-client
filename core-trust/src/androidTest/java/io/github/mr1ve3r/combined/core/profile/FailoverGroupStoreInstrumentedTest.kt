package io.github.mr1ve3r.combined.core.profile

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mr1ve3r.combined.core.trust.store.TrustDatabase
import io.github.mr1ve3r.combined.engine.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Failover groups on a real device (SPEC 10.1.1).
 *
 * The order of the members is what most of this file is about: it is the only
 * thing a group adds to a set of profiles, and it is the thing a table cannot
 * keep on its own.
 */
@RunWith(AndroidJUnit4::class)
class FailoverGroupStoreInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TrustDatabase
    private lateinit var profiles: ProfileStore
    private lateinit var groups: FailoverGroupStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        open()
        runBlocking {
            profiles.save(profile("l2tp-1", "Work L2TP", Protocol.L2TP), "pw", "psk")
            profiles.save(profile("sstp-1", "Work SSTP", Protocol.SSTP), "pw", "")
            profiles.save(profile("spare", "Spare", Protocol.SSTP), "pw", "")
        }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun aGroupKeepsTheOrderItsMembersWereGivenIn() = runBlocking {
        groups.save(group(), listOf("sstp-1", "l2tp-1"))

        open()
        val stored = groups.findWithMembers("group-1")

        // Saved SSTP first, so SSTP is tried first -- not the order the
        // profiles happen to sit in the profile table.
        assertEquals(listOf("sstp-1", "l2tp-1"), stored?.members?.map { it.id })
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, stored?.group?.connectTimeoutSec)
    }

    @Test
    fun savingAgainReplacesTheMembershipRatherThanAddingToIt() = runBlocking {
        groups.save(group(), listOf("l2tp-1", "sstp-1", "spare"))
        groups.save(group(), listOf("sstp-1", "l2tp-1"))

        assertEquals(listOf("sstp-1", "l2tp-1"), groups.findWithMembers("group-1")?.members?.map { it.id })
    }

    @Test
    fun aProfileNamedTwiceIsTriedOnce() = runBlocking {
        // The second attempt would use the same settings and get the same
        // answer; all it adds is the wait.
        groups.save(group(), listOf("l2tp-1", "sstp-1", "l2tp-1"))

        assertEquals(listOf("l2tp-1", "sstp-1"), groups.findWithMembers("group-1")?.members?.map { it.id })
    }

    @Test
    fun deletingAProfileTakesItOutOfEveryGroupThatNamedIt() = runBlocking {
        groups.save(group(), listOf("l2tp-1", "sstp-1"))

        assertEquals(listOf("group-1"), groups.groupIdsUsing("l2tp-1"))
        assertTrue(profiles.delete("l2tp-1"))

        val stored = groups.findWithMembers("group-1")
        // The group survives with what is left: a group short of a member is
        // still a group, and dropping it would delete something the user made.
        assertNotNull(stored)
        assertEquals(listOf("sstp-1"), stored?.members?.map { it.id })
    }

    @Test
    fun deletingAGroupLeavesItsProfilesAlone() = runBlocking {
        groups.save(group(), listOf("l2tp-1", "sstp-1"))

        assertTrue(groups.delete("group-1"))

        assertNull(groups.find("group-1"))
        assertNotNull(profiles.find("l2tp-1"))
        assertNotNull(profiles.find("sstp-1"))
        assertTrue(groups.groupIdsUsing("l2tp-1").isEmpty())
    }

    @Test
    fun aGroupWithNoMembersIsStillAGroup() = runBlocking {
        groups.save(group(), emptyList())

        val stored = groups.findWithMembers("group-1")

        // Not null: the caller has to be able to tell the user that the group
        // has nothing to try, which is different from the group being gone.
        assertNotNull(stored)
        assertTrue(stored!!.members.isEmpty())
    }

    @Test
    fun aBudgetOutsideTheRangeFallsBackToTheDefault() = runBlocking {
        groups.save(group().copy(connectTimeoutSec = 0), listOf("l2tp-1"))
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, groups.find("group-1")?.connectTimeoutSec)

        groups.save(group().copy(connectTimeoutSec = 9_999), listOf("l2tp-1"))
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, groups.find("group-1")?.connectTimeoutSec)

        groups.save(group().copy(connectTimeoutSec = 30), listOf("l2tp-1"))
        assertEquals(30, groups.find("group-1")?.connectTimeoutSec)
    }

    @Test
    fun aGroupIsNotAProfileAndDoesNotHideOne() = runBlocking {
        groups.save(group(), listOf("l2tp-1", "sstp-1"))

        // Membership does not consume a profile: both are still listed and both
        // are still connectable on their own.
        assertEquals(3, profiles.list().size)
        assertFalse(groups.list().isEmpty())
    }

    private fun open() {
        if (::database.isInitialized) database.close()
        database = Room.databaseBuilder(context, TrustDatabase::class.java, DATABASE_NAME).build()
        profiles = ProfileStore(
            dao = database.profiles(),
            secrets = ProfileSecrets(context.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE)),
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
        groups = FailoverGroupStore(database.failoverGroups())
    }

    private fun group() = FailoverGroup(id = "group-1", name = "Work", createdAt = 1L)

    private fun profile(id: String, name: String, protocol: Protocol) = VpnProfile(
        id = id,
        name = name,
        protocol = protocol,
        server = "vpn.example.org",
        username = "alice",
        passwordRef = "",
        mtu = 1400,
        createdAt = 1L,
    )

    private companion object {
        const val DATABASE_NAME = "failover-group-test.db"
        const val SECRETS_NAME = "failover-secrets-test"
        const val PREFS_NAME = "failover-prefs-test"
    }
}
