package io.github.mr1ve3r.combined.core.trust.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mr1ve3r.combined.core.profile.ProfileConverters
import io.github.mr1ve3r.combined.core.profile.ProfileDao
import io.github.mr1ve3r.combined.core.profile.VpnProfile

/**
 * The on-device store (SPEC 5.1, 8.1).
 *
 * Certificates and profiles share one database because they are one relation:
 * an SSTP profile selects certificates, and `profile_certificate_ref` can only
 * be a foreign key on both ends if both ends are here. The file keeps the name
 * it was created with; renaming it would cost a migration and buy nothing.
 */
@Database(
    entities = [ServerCertificateEntity::class, ProfileCertificateRef::class, VpnProfile::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class, ProfileConverters::class)
abstract class TrustDatabase : RoomDatabase() {
    abstract fun serverCertificates(): ServerCertificateDao

    abstract fun profiles(): ProfileDao

    companion object {
        /** File name under the application's database directory. */
        const val NAME: String = "trust.db"

        @Volatile
        private var instance: TrustDatabase? = null

        /** The process-wide database, opened on first use. */
        fun get(context: Context): TrustDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): TrustDatabase = Room
            .databaseBuilder(context, TrustDatabase::class.java, NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

        /**
         * Adds the profile table and puts the missing half of
         * `profile_certificate_ref`'s foreign key in place (SPEC 8.1).
         *
         * Version 1 shipped that table with a foreign key on the certificate
         * side only, because profiles lived in the Flutter layer's own storage
         * and the database could not enforce the other end. Now it can, and
         * SQLite can only add a constraint by rebuilding the table.
         *
         * The rebuild drops whatever `profile_certificate_ref` held. No
         * shipped build could put a row in it — nothing selected certificates
         * for a profile before there were profiles here — and a row naming a
         * profile this database has never seen is exactly what the new
         * constraint exists to reject.
         *
         * The `CREATE TABLE` statements are the ones Room generates, copied from
         * `schemas/2.json`. A migration that produces anything else fails
         * `MigrationTestHelper`, which is how they stay copied.
         */
        private const val CREATE_PROFILES =
            "CREATE TABLE IF NOT EXISTS `profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`protocol` TEXT NOT NULL, `server` TEXT NOT NULL, `username` TEXT NOT NULL, " +
                "`passwordRef` TEXT NOT NULL, `mtu` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`dnsAutomatic` INTEGER NOT NULL, `dns1Host` TEXT NOT NULL, `dns1Protocol` TEXT NOT NULL, " +
                "`dns2Host` TEXT NOT NULL, `dns2Protocol` TEXT NOT NULL, `perAppMode` TEXT NOT NULL, " +
                "`appList` TEXT NOT NULL, `killSwitch` INTEGER NOT NULL, `autoReconnect` INTEGER NOT NULL, " +
                "`ipsecEnabled` INTEGER NOT NULL, `pskRef` TEXT NOT NULL, `localIdentifier` TEXT, " +
                "`phase1Proposals` TEXT NOT NULL, `phase2Proposals` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                "`trustPolicy` TEXT NOT NULL, `pinnedFingerprints` TEXT NOT NULL, `expectedHostname` TEXT, " +
                "`minTlsVersion` TEXT NOT NULL, `pppAuthMethods` TEXT NOT NULL, `proxyEnabled` INTEGER NOT NULL, " +
                "`proxyHost` TEXT NOT NULL, `proxyPort` INTEGER NOT NULL, `proxyUsername` TEXT NOT NULL, " +
                "`proxyPasswordRef` TEXT NOT NULL, PRIMARY KEY(`id`))"

        private const val CREATE_PROFILE_CERTIFICATE_REF =
            "CREATE TABLE IF NOT EXISTS `profile_certificate_ref` (`profileId` TEXT NOT NULL, " +
                "`certificateId` TEXT NOT NULL, PRIMARY KEY(`profileId`, `certificateId`), " +
                "FOREIGN KEY(`certificateId`) REFERENCES `server_certificates`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        private const val CREATE_PROFILE_CERTIFICATE_REF_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_profile_certificate_ref_certificateId` " +
                "ON `profile_certificate_ref` (`certificateId`)"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(CREATE_PROFILES)
                connection.execSQL("DROP TABLE `profile_certificate_ref`")
                connection.execSQL(CREATE_PROFILE_CERTIFICATE_REF)
                connection.execSQL(CREATE_PROFILE_CERTIFICATE_REF_INDEX)
            }
        }
    }
}
