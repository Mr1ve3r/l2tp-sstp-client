package io.github.mr1ve3r.combined.core.profile

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The password was wrong, or the container is not one. */
class ProfileContainerException(message: String) : Exception(message)

/**
 * The password-encrypted container an export with secrets goes into (SPEC 8.4).
 *
 * An export without secrets is plain JSON and needs none of this. An export
 * that carries the VPN password, the pre-shared key, and the proxy password is
 * a file the user will move through a chat application or a cloud drive, so it
 * is never written unencrypted.
 *
 * The layout is [MAGIC] | 16-byte salt | 12-byte nonce | AES-GCM ciphertext,
 * base64 as a whole. The key is PBKDF2-HMAC-SHA256 over the password with
 * [ITERATIONS] rounds; the salt is new on every export, so the same profile
 * exported twice with the same password gives two different files.
 */
object ProfileContainer {

    /** Marks the format, and the version of it. */
    const val MAGIC: String = "TFPC1"

    /** Wraps [plaintext] under [password]. */
    fun seal(plaintext: String, password: String, random: SecureRandom = SecureRandom()): String {
        require(password.isNotEmpty()) { "A container password is required" }
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        val body = MAGIC.toByteArray(Charsets.US_ASCII) + salt + nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(body)
    }

    /** Whether [text] looks like a container rather than plain exported JSON. */
    fun isContainer(text: String): Boolean = runCatching { header(text) }.isSuccess

    /**
     * Unwraps a container produced by [seal].
     *
     * @throws ProfileContainerException if [text] is not a container or
     *   [password] does not open it. The two are reported the same way on
     *   purpose: an attacker holding the file learns nothing from which it was.
     */
    fun open(text: String, password: String): String {
        val body = header(text)
        val salt = body.copyOfRange(0, SALT_LENGTH)
        val nonce = body.copyOfRange(SALT_LENGTH, SALT_LENGTH + NONCE_LENGTH)
        val ciphertext = body.copyOfRange(SALT_LENGTH + NONCE_LENGTH, body.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw ProfileContainerException("The container could not be opened with that password")
        }
    }

    /** The bytes after [MAGIC], or a failure when [text] is not a container. */
    private fun header(text: String): ByteArray {
        val decoded =
            try {
                Base64.getDecoder().decode(text.trim())
            } catch (e: IllegalArgumentException) {
                throw ProfileContainerException("The file is not an encrypted profile container")
            }
        val prefix = MAGIC.toByteArray(Charsets.US_ASCII)
        val minimum = prefix.size + SALT_LENGTH + NONCE_LENGTH + 1
        if (decoded.size < minimum || !decoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            throw ProfileContainerException("The file is not an encrypted profile container")
        }
        return decoded.copyOfRange(prefix.size, decoded.size)
    }

    private fun key(password: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE_BITS))
            .encoded,
        "AES",
    )

    /**
     * PBKDF2 rounds. High enough to cost an offline guesser real time, low
     * enough that opening an export on a phone is not a visible wait.
     */
    private const val ITERATIONS = 210_000

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val TAG_LENGTH_BITS = 128
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
}
