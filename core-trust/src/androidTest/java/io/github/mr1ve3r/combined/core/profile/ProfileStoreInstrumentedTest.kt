package io.github.mr1ve3r.combined.core.profile

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mr1ve3r.combined.core.trust.store.TrustDatabase
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The profile store on a real device: Room, the keystore, and the two staying
 * in step across a restart (SPEC 8.2).
 *
 * The database is closed and reopened between the write and the read, and the
 * secrets are read back through a second [ProfileSecrets] over the same
 * preferences file. That is what restarting the application amounts to for
 * storage; anything that only lived in memory is gone by then.
 */
@RunWith(AndroidJUnit4::class)
class ProfileStoreInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TrustDatabase
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        openStore()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun aSavedProfileAndItsSecretsSurviveARestart() = runBlocking {
        store.save(profile(), password = "hunter2", psk = "shared", proxyPassword = "proxy")

        openStore()
        val row = store.findWithSecrets("id-1")

        assertEquals("Work", row?.profile?.name)
        assertEquals(Protocol.SSTP, row?.profile?.protocol)
        assertEquals(TrustPolicy.SYSTEM_PLUS_CUSTOM, row?.profile?.trustPolicy)
        assertEquals(TlsVersion.TLS_1_3, row?.profile?.minTlsVersion)
        assertEquals(listOf("com.example.a"), row?.profile?.appList)
        assertEquals("hunter2", row?.password)
        assertEquals("shared", row?.psk)
        assertEquals("proxy", row?.proxyPassword)
    }

    /** The database is what a backup or a bug report picks up; it must not carry a password. */
    @Test
    fun theDatabaseHoldsReferencesRatherThanSecrets() = runBlocking {
        store.save(profile(), password = "hunter2", psk = "shared", proxyPassword = "proxy")

        val stored = database.profiles().find("id-1")!!
        assertEquals("profile/id-1/password", stored.passwordRef)
        val text = context.getDatabasePath(DATABASE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("shared"))
    }

    /** The ciphertext is in the preferences file, and only the keystore key opens it. */
    @Test
    fun theSecretsFileHoldsNoPlaintext() = runBlocking {
        store.save(profile(), password = "hunter2", psk = "shared", proxyPassword = "")

        val stored = context.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE)
            .getString("profile/id-1/password", null)

        assertNotEquals("hunter2", stored)
        assertTrue(stored!!.isNotEmpty())
    }

    @Test
    fun deletingAProfileTakesItsSecretsWithIt() = runBlocking {
        store.save(profile(), password = "hunter2", psk = "shared", proxyPassword = "proxy")

        assertTrue(store.delete("id-1"))

        openStore()
        assertNull(store.findWithSecrets("id-1"))
        val secrets = context.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE)
        assertNull(secrets.getString("profile/id-1/password", null))
        assertNull(secrets.getString("profile/id-1/psk", null))
        assertNull(secrets.getString("profile/id-1/proxyPassword", null))
    }

    /**
     * A start with no arguments has to pick a profile on its own, and picking
     * the wrong one connects the user to a server they did not choose.
     */
    @Test
    fun theDefaultProfileIsTheLastOneOrTheOnlyOne() = runBlocking {
        assertNull(store.defaultProfile())

        store.save(profile(), password = "", psk = "", proxyPassword = "")
        assertEquals("id-1", store.defaultProfile()?.id)

        store.save(profile().copy(id = "id-2", name = "Home", createdAt = 2L), password = "", psk = "", proxyPassword = "")
        assertNull(store.defaultProfile())

        store.setLastProfileId("id-2")
        assertEquals("id-2", store.defaultProfile()?.id)
    }

    @Test
    fun theLegacyImportFlagIsRememberedAcrossRestarts() {
        assertFalse(store.legacyImportDone())

        store.markLegacyImportDone()
        openStore()

        assertTrue(store.legacyImportDone())
    }

    private fun openStore() {
        if (::database.isInitialized) database.close()
        database = Room.databaseBuilder(context, TrustDatabase::class.java, DATABASE_NAME).build()
        store = ProfileStore(
            dao = database.profiles(),
            secrets = ProfileSecrets(context.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE)),
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }

    private fun profile() = VpnProfile(
        id = "id-1",
        name = "Work",
        protocol = Protocol.SSTP,
        server = "vpn.example.org",
        username = "alice",
        passwordRef = "",
        mtu = 1400,
        createdAt = 1L,
        perAppMode = PerAppMode.INCLUDE,
        appList = listOf("com.example.a"),
        trustPolicy = TrustPolicy.SYSTEM_PLUS_CUSTOM,
        minTlsVersion = TlsVersion.TLS_1_3,
    )

    private companion object {
        const val DATABASE_NAME = "profile-store-test.db"
        const val SECRETS_NAME = "profile-secrets-test"
        const val PREFS_NAME = "profiles-test"
    }
}

/**
 * The upgrade of an installed database (SPEC 8.2, acceptance criterion 2).
 *
 * `MigrationTestHelper` creates the schema as version 1 shipped it, runs the
 * migration, and then compares the result against the version 2 schema Room
 * exported. A migration whose `CREATE TABLE` differs from the generated one by
 * so much as a column order fails here rather than on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
class TrustDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TrustDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun version1UpgradesToVersion2() {
        helper.createDatabase(NAME, 1).use { database ->
            database.execSQL(
                "INSERT INTO server_certificates VALUES ('aa11', 'Work CA', 'ca', 'CN=ca', 'CN=ca', '01', 0, 1, " +
                    "'aa11', 'bb22', 1, NULL, '[]', 2048, 'SHA256withRSA', 5, 'aa11.pem')",
            )
            database.execSQL("INSERT INTO profile_certificate_ref VALUES ('id-1', 'aa11')")
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 2, true, TrustDatabase.MIGRATION_1_2)

        // The certificate survives; the reference does not, because the profile
        // it points at was never in this database and the new foreign key says
        // so. Nothing the user typed is lost: the reference is rebuilt the
        // first time the profile is saved.
        migrated.query("SELECT COUNT(*) FROM server_certificates").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM profiles").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    /**
     * Version 3 adds failover groups (SPEC 10.1.1).
     *
     * The point of the assertion below is that the upgrade is additive: a user
     * with profiles and certificates keeps every one of them, because a group
     * names profiles rather than being named by them and no existing table had
     * to change.
     */
    @Test
    fun version2UpgradesToVersion3() {
        helper.createDatabase(NAME, 2).use { database ->
            database.execSQL(
                "INSERT INTO server_certificates VALUES ('aa11', 'Work CA', 'ca', 'CN=ca', 'CN=ca', '01', 0, 1, " +
                    "'aa11', 'bb22', 1, NULL, '[]', 2048, 'SHA256withRSA', 5, 'aa11.pem')",
            )
            database.execSQL(
                "INSERT INTO profiles VALUES ('id-1', 'Work', 'SSTP', 'vpn.example.org', 'alice', " +
                    "'profile/id-1/password', 1400, 1, 1, '', 'DNS_OVER_UDP', '', 'DNS_OVER_UDP', 'OFF', '[]', " +
                    "0, 1, 1, 'profile/id-1/psk', NULL, '[]', '[]', 443, 'SYSTEM', '[]', NULL, 'TLS_1_2', " +
                    "'[\"MSCHAPV2\"]', 0, '', 8080, '', 'profile/id-1/proxyPassword')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 3, true, TrustDatabase.MIGRATION_2_3)

        migrated.query("SELECT COUNT(*) FROM profiles").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM server_certificates").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM failover_groups").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    /**
     * The upgrade a real install actually performs (SPEC 11.1).
     *
     * Neither single-step test covers this. A phone that last ran the version 1
     * build and then takes the current release runs both migrations back to
     * back, in one open, and it is the *sequence* that can break: `MIGRATION_1_2`
     * rebuilds `profile_certificate_ref`, and `MIGRATION_2_3` then adds tables
     * with a foreign key on `profiles`. Passing them to Room in one call is the
     * only way to see the chain the way the device sees it.
     */
    @Test
    fun version1UpgradesToVersion3InOneRun() {
        helper.createDatabase(NAME, 1).use { database ->
            database.execSQL(
                "INSERT INTO server_certificates VALUES ('aa11', 'Work CA', 'ca', 'CN=ca', 'CN=ca', '01', 0, 1, " +
                    "'aa11', 'bb22', 1, NULL, '[]', 2048, 'SHA256withRSA', 5, 'aa11.pem')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            NAME,
            3,
            true,
            TrustDatabase.MIGRATION_1_2,
            TrustDatabase.MIGRATION_2_3,
        )

        // Validation against `schemas/3.json` is most of the assertion: it is
        // what fails if either step leaves the database a shape Room did not
        // generate. The counts below add the part validation cannot see --
        // that the certificate the user imported under version 1 is still here.
        migrated.query("SELECT COUNT(*) FROM server_certificates").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM failover_groups").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
