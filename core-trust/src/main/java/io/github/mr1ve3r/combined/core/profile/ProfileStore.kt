package io.github.mr1ve3r.combined.core.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.github.mr1ve3r.combined.core.trust.store.TrustDatabase
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow

/** A profile together with the three secrets it refers to. */
data class ProfileWithSecrets(
    val profile: VpnProfile,
    val password: String,
    val psk: String,
    val proxyPassword: String,
)

/**
 * The profile store as the rest of the application sees it (SPEC 8.1–8.2).
 *
 * Keeping the row and its secrets in step is this class's job: nothing else
 * touches [ProfileSecrets], and every write goes through here so that a saved
 * profile always has its three references filled in and a deleted one leaves no
 * password behind.
 *
 * The store is the source of truth for both Flutter and the service. That is
 * the point of it being here rather than in the Flutter layer: the service is
 * started by the system for an always-on tunnel, or by the Quick Settings tile,
 * long before any Dart code is running (SPEC В.13).
 */
class ProfileStore(
    private val dao: ProfileDao,
    private val secrets: ProfileSecrets,
    private val prefs: SharedPreferences,
) {
    /** Every profile, oldest first, updated as the table changes. */
    fun observe(): Flow<List<VpnProfile>> = dao.observeAll()

    /** Every profile, oldest first. */
    suspend fun list(): List<VpnProfile> = dao.loadAll()

    /** The profile with [id], or `null` if there is none. */
    suspend fun find(id: String): VpnProfile? = dao.find(id)

    /** The profile with [id] and its secrets, or `null` if there is no such profile. */
    suspend fun findWithSecrets(id: String): ProfileWithSecrets? {
        val profile = dao.find(id) ?: return null
        return ProfileWithSecrets(
            profile = profile,
            password = secrets.read(profile.passwordRef).orEmpty(),
            psk = secrets.read(profile.pskRef).orEmpty(),
            proxyPassword = secrets.read(profile.proxyPasswordRef).orEmpty(),
        )
    }

    /**
     * Stores [profile], replacing any earlier version of it, and its secrets.
     *
     * The reference columns are filled in here rather than by the caller: they
     * are derived from the id, and a row whose references disagree with where
     * the secrets were actually written is a profile that cannot connect.
     *
     * @return the row as it was stored.
     */
    suspend fun save(profile: VpnProfile, password: String, psk: String, proxyPassword: String = ""): VpnProfile {
        val stored = profile.copy(
            passwordRef = VpnProfile.passwordRefFor(profile.id),
            pskRef = VpnProfile.pskRefFor(profile.id),
            proxyPasswordRef = VpnProfile.proxyPasswordRefFor(profile.id),
        )
        secrets.write(stored.passwordRef, password)
        secrets.write(stored.pskRef, psk)
        secrets.write(stored.proxyPasswordRef, proxyPassword)
        dao.upsert(stored)
        return stored
    }

    /**
     * Removes [id], its secrets included.
     *
     * References to certificates go with the row through the foreign key
     * cascade; the certificates themselves stay, because other profiles may be
     * using them and because importing one was a deliberate act.
     *
     * @return whether there was a profile to remove.
     */
    suspend fun delete(id: String): Boolean {
        secrets.delete(VpnProfile.secretRefsFor(id))
        if (lastProfileId() == id) setLastProfileId(null)
        return dao.delete(id) > 0
    }

    /** The profile the user connected with last, or `null`. */
    fun lastProfileId(): String? = prefs.getString(KEY_LAST_PROFILE_ID, null)?.takeIf { it.isNotEmpty() }

    /** Records [id] as the profile to offer next, including to an always-on start. */
    fun setLastProfileId(id: String?) {
        prefs.edit().apply {
            if (id.isNullOrEmpty()) remove(KEY_LAST_PROFILE_ID) else putString(KEY_LAST_PROFILE_ID, id)
        }.apply()
    }

    /**
     * The profile a start with no arguments should use: the last one connected,
     * or the only one there is, or `null` when the choice is ambiguous.
     *
     * An always-on start arrives with nothing to identify a profile, and
     * guessing between several of them would connect the user to a server they
     * did not ask for.
     */
    suspend fun defaultProfile(): VpnProfile? {
        lastProfileId()?.let { id -> dao.find(id)?.let { return it } }
        return dao.loadAll().singleOrNull()
    }

    /** Whether the one-time import of the Flutter-side profiles has already run (SPEC 8.1.3). */
    fun legacyImportDone(): Boolean = prefs.getBoolean(KEY_LEGACY_IMPORT_DONE, false)

    /** Records that the one-time import has run, so it does not undo later edits. */
    fun markLegacyImportDone() {
        prefs.edit().putBoolean(KEY_LEGACY_IMPORT_DONE, true).apply()
    }

    companion object {
        /** File name of the preferences holding the store's own bookkeeping. */
        const val PREFERENCES_NAME: String = "profiles"

        private const val KEY_LAST_PROFILE_ID = "lastProfileId"
        private const val KEY_LEGACY_IMPORT_DONE = "legacyImportDone"
        private const val ID_BYTES = 16

        /** An identifier for a new profile: random, and URL-safe so it can go in a file name. */
        fun newProfileId(): String {
            val bytes = ByteArray(ID_BYTES).also(SecureRandom()::nextBytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        /** The store for this application, sharing one database with the certificate store. */
        fun get(context: Context): ProfileStore {
            val application = context.applicationContext
            return ProfileStore(
                dao = TrustDatabase.get(application).profiles(),
                secrets = ProfileSecrets.get(application),
                prefs = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}
