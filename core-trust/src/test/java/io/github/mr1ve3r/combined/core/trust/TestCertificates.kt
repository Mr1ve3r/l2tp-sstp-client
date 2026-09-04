package io.github.mr1ve3r.combined.core.trust

import java.security.cert.X509Certificate

/**
 * Certificate fixtures used by the trust tests.
 *
 * Generated once with `keytool` and committed under `src/test/resources/certs`.
 * They are throwaway keys for testing only; the point of committing them is
 * that the tests assert on fixed fingerprints and validity dates, which is not
 * possible against certificates regenerated on every run.
 *
 * | Fixture | What it is |
 * |---|---|
 * | `ca` | a CA, `CN=l2tp-sstp-client Test CA` |
 * | `leafSignedByCa` | `CN=vpn.example.com`, issued by that CA, SANs `vpn.example.com` and `vpn.internal.lan` |
 * | `selfSigned` | `CN=mikrotik.local`, self-signed, the PIN_LEAF case |
 * | `expired` | valid for one day in June 2024 |
 * | `notYetValid` | validity starts well in the future |
 * | `weakKey` | RSA 1024 |
 */
object TestCertificates {
    val ca: X509Certificate get() = load("ca.pem")
    val leafSignedByCa: X509Certificate get() = load("leaf-signed-by-ca.pem")
    val selfSigned: X509Certificate get() = load("self-signed.pem")
    val expired: X509Certificate get() = load("expired.pem")
    val notYetValid: X509Certificate get() = load("not-yet-valid.pem")
    val weakKey: X509Certificate get() = load("weak-key.pem")

    /** The CA and the leaf it signed, concatenated in one PEM file. */
    val bundle: List<X509Certificate> get() = CertificateParser.parse(bytesOf("bundle.pem"))

    /** The self-signed certificate in DER rather than PEM. */
    val selfSignedDer: ByteArray get() = bytesOf("self-signed.der")

    /** Raw bytes of a fixture, for tests that exercise parsing itself. */
    fun bytesOf(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/certs/$name")) { "missing fixture: $name" }
        .use { it.readBytes() }

    /** Text of a fixture, for tests that exercise pasted input. */
    fun textOf(name: String): String = String(bytesOf(name), Charsets.US_ASCII)

    private fun load(name: String): X509Certificate = CertificateParser.parse(bytesOf(name)).single()
}
