package io.github.mr1ve3r.combined.core.trust

import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * The fields of a certificate the application shows or stores.
 *
 * Deliberately a plain data class with no persistence annotations: the store
 * (SPEC 5.1) maps this onto its own entity. Keeping the two apart means the
 * certificate screen and the trust managers do not depend on a database being
 * present, and can be tested without one.
 *
 * @property id stable identity of a certificate, equal to [sha256Fingerprint].
 *   Two files holding the same certificate are the same entry.
 * @property subjectCn common name of the subject, or `null` if it has none.
 * @property subjectDn full distinguished name of the subject.
 * @property issuerDn distinguished name of the issuer. Equal to [subjectDn] for
 *   a self-signed certificate.
 * @property serialNumber serial number in hex.
 * @property notBefore start of the validity window, milliseconds since the epoch.
 * @property notAfter end of the validity window, milliseconds since the epoch.
 * @property sha256Fingerprint lowercase hex, no separators.
 * @property sha1Fingerprint lowercase hex, no separators. Display only — SHA-1
 *   is not used for any trust decision.
 * @property isCa whether basicConstraints marks this as a certificate authority.
 * @property keyUsage human-readable key usage list, or `null` if the extension
 *   is absent.
 * @property subjectAltNames subject alternative names, prefixed by type, such
 *   as `DNS:vpn.example.com` or `IP:192.168.88.1`.
 * @property publicKeyBits size of the public key in bits, or `null` when it
 *   cannot be determined.
 * @property signatureAlgorithm signature algorithm name, as the certificate
 *   states it.
 */
data class CertificateSummary(
    val id: String,
    val subjectCn: String?,
    val subjectDn: String,
    val issuerDn: String,
    val serialNumber: String,
    val notBefore: Long,
    val notAfter: Long,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val isCa: Boolean,
    val keyUsage: String?,
    val subjectAltNames: List<String>,
    val publicKeyBits: Int?,
    val signatureAlgorithm: String,
) {
    /** Whether the subject and issuer are the same, i.e. the certificate signed itself. */
    val isSelfSigned: Boolean get() = subjectDn == issuerDn

    companion object {
        /** Reads every displayed field out of [certificate]. */
        fun of(certificate: X509Certificate): CertificateSummary {
            val sha256 = CertificateFingerprint.sha256(certificate)
            return CertificateSummary(
                id = sha256,
                subjectCn = commonNameOf(certificate.subjectX500Principal.name),
                subjectDn = certificate.subjectX500Principal.name,
                issuerDn = certificate.issuerX500Principal.name,
                serialNumber = certificate.serialNumber.toString(SERIAL_RADIX),
                notBefore = certificate.notBefore.time,
                notAfter = certificate.notAfter.time,
                sha256Fingerprint = sha256,
                sha1Fingerprint = CertificateFingerprint.sha1(certificate),
                isCa = certificate.basicConstraints != NOT_A_CA,
                keyUsage = keyUsageOf(certificate),
                subjectAltNames = subjectAltNamesOf(certificate),
                publicKeyBits = publicKeyBitsOf(certificate),
                signatureAlgorithm = certificate.sigAlgName,
            )
        }

        /** Names a certificate presents, for hostname verification and for display. */
        fun subjectAltNamesOf(certificate: X509Certificate): List<String> = runCatching { certificate.subjectAlternativeNames }
            .getOrNull()
            .orEmpty()
            .mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int ?: return@mapNotNull null
                val value = entry.getOrNull(1)?.toString() ?: return@mapNotNull null
                when (type) {
                    SAN_DNS -> "DNS:$value"
                    SAN_IP -> "IP:$value"
                    else -> null
                }
            }

        private fun commonNameOf(distinguishedName: String): String? = distinguishedName
            .split(',')
            .map(String::trim)
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)

        private fun keyUsageOf(certificate: X509Certificate): String? {
            val usage = certificate.keyUsage ?: return null
            return KEY_USAGE_NAMES
                .filterIndexed { index, _ -> index < usage.size && usage[index] }
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(", ")
        }

        private fun publicKeyBitsOf(certificate: X509Certificate): Int? = when (val key = certificate.publicKey) {
            is RSAPublicKey -> key.modulus.bitLength()
            else -> null
        }

        private const val SERIAL_RADIX = 16
        private const val NOT_A_CA = -1
        private const val SAN_DNS = 2
        private const val SAN_IP = 7

        /** Bit order of the keyUsage extension, RFC 5280 section 4.2.1.3. */
        private val KEY_USAGE_NAMES =
            listOf(
                "digitalSignature",
                "nonRepudiation",
                "keyEncipherment",
                "dataEncipherment",
                "keyAgreement",
                "keyCertSign",
                "cRLSign",
                "encipherOnly",
                "decipherOnly",
            )
    }
}
