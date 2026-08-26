package io.github.mr1ve3r.combined.core.trust

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Fingerprints of a certificate, and the one safe way to compare them.
 *
 * A fingerprint is the digest of the DER encoding. Two forms are used
 * throughout: lowercase hex with no separators for storage and comparison, and
 * colon-separated uppercase for display, because that is the form a user will
 * be reading off a router's web interface when they check it.
 */
object CertificateFingerprint {
    /** SHA-256 of the DER encoding, lowercase hex, no separators. */
    fun sha256(certificate: X509Certificate): String = hex(digest("SHA-256", certificate))

    /** SHA-1 of the DER encoding, lowercase hex, no separators. Kept for display only. */
    fun sha1(certificate: X509Certificate): String = hex(digest("SHA-1", certificate))

    /**
     * Reformats a hex fingerprint as `AB:CD:EF:...` for display.
     *
     * @throws IllegalArgumentException if [hexFingerprint] is not valid hex.
     */
    fun formatForDisplay(hexFingerprint: String): String = bytes(hexFingerprint).joinToString(":") { "%02X".format(it) }

    /**
     * Whether [certificate] has the SHA-256 fingerprint [expectedHex].
     *
     * The comparison is constant-time. A fingerprint check is the whole of the
     * security in [TrustPolicy.PIN_LEAF][io.github.mr1ve3r.combined.engine.TrustPolicy.PIN_LEAF],
     * and comparing digests with `==` leaks how many leading bytes matched
     * (SPEC appendix А).
     *
     * Malformed input returns `false` rather than throwing: a pin the user
     * mistyped should fail the connection, not crash it.
     */
    fun matchesSha256(expectedHex: String, certificate: X509Certificate): Boolean {
        val expected = runCatching { bytes(expectedHex) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, digest("SHA-256", certificate))
    }

    /**
     * Whether any of [expectedHexFingerprints] matches [certificate].
     *
     * Every candidate is compared even after a match is found, so that the time
     * taken does not reveal which pin matched or how many were tried.
     */
    fun matchesAnySha256(expectedHexFingerprints: Set<String>, certificate: X509Certificate): Boolean {
        var matched = false
        expectedHexFingerprints.forEach { candidate ->
            if (matchesSha256(candidate, certificate)) {
                matched = true
            }
        }
        return matched
    }

    /** Normalises user-entered fingerprint text: strips separators, lowercases. */
    fun normalise(raw: String): String = raw.filter { !it.isWhitespace() && it != ':' && it != '-' }.lowercase()

    private fun digest(algorithm: String, certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance(algorithm).digest(certificate.encoded)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun bytes(hexFingerprint: String): ByteArray {
        val cleaned = normalise(hexFingerprint)
        require(cleaned.length % 2 == 0) { "A hex fingerprint must have an even number of digits" }
        return ByteArray(cleaned.length / 2) { i ->
            val octet = cleaned.substring(i * 2, i * 2 + 2)
            octet.toIntOrNull(radix = 16)?.toByte() ?: throw IllegalArgumentException("Not hex: $octet")
        }
    }
}
