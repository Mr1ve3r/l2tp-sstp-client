package io.github.mr1ve3r.combined.core.profile

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The passwords a profile refers to (SPEC 8.2).
 *
 * Each secret is encrypted with an AES-GCM key that lives in the Android
 * keystore and never leaves it, and the ciphertext goes into an ordinary
 * preferences file. The database holds only the reference
 * ([VpnProfile.passwordRefFor] and friends).
 *
 * `androidx.security`'s `EncryptedSharedPreferences` would do the same thing;
 * it is deprecated, and what it adds over the twenty lines below is a second
 * key and a dependency.
 *
 * A secret that cannot be decrypted reads back as `null` rather than throwing.
 * That is what a restore onto another device looks like: the preferences file
 * comes back, the keystore key does not, and the honest answer is that the
 * password is gone and has to be entered again.
 */
class ProfileSecrets(private val prefs: SharedPreferences) {

    /** The secret stored under [ref], or `null` if there is none or it cannot be read. */
    fun read(ref: String): String? {
        val stored = prefs.getString(ref, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH),
            )
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Stores [value] under [ref]. A blank value removes the entry instead. */
    fun write(ref: String, value: String) {
        if (value.isEmpty()) {
            delete(ref)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(ref, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    /** Removes every reference in [refs]. */
    fun delete(vararg refs: String) = delete(refs.toList())

    /** Removes every reference in [refs]. */
    fun delete(refs: List<String>) {
        prefs.edit().apply { refs.forEach(::remove) }.apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** File name of the preferences holding the ciphertexts. */
        const val PREFERENCES_NAME: String = "profile_secrets"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tunnel_forge_profile_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH = 12

        /** The store for this application. */
        fun get(context: Context): ProfileSecrets = ProfileSecrets(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
    }
}
