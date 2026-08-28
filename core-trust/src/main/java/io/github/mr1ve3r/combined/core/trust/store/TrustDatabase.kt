package io.github.mr1ve3r.combined.core.trust.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** The certificate store's database (SPEC 5.1). */
@Database(
    entities = [ServerCertificateEntity::class, ProfileCertificateRef::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class)
abstract class TrustDatabase : RoomDatabase() {
    abstract fun serverCertificates(): ServerCertificateDao

    companion object {
        /** File name under the application's database directory. */
        const val NAME: String = "trust.db"

        @Volatile
        private var instance: TrustDatabase? = null

        /** The process-wide database, opened on first use. */
        fun get(context: Context): TrustDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): TrustDatabase = Room.databaseBuilder(context, TrustDatabase::class.java, NAME).build()
    }
}
