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
 * | `caWithoutBasicConstraints` | self-signed, with no `basicConstraints` extension at all |
 * | `leafSignedByCaWithoutBasicConstraints` | `CN=vpn.nbc.example.com`, issued by that one |
 * | `chainCa` | a CA with `keyCertSign`, the anchor for `chain-server.p12` |
 * | `chain-server.p12` | `CN=vpn.example.com` and its key, plus `chainCa` and its key |
 * | `expiredLeafSignedByChainCa` | issued by `chainCa`, lapsed in 2024 while its issuer stayed valid |
 *
 * The last four came from `openssl` rather than `keytool`, because `keytool`
 * cannot emit a self-signed certificate with no `basicConstraints` extension --
 * which is exactly the router certificate the path-building tests have to keep
 * on rejecting.
 */
object TestCertificates {
    val ca: X509Certificate get() = load("ca.pem")
    val leafSignedByCa: X509Certificate get() = load("leaf-signed-by-ca.pem")
    val selfSigned: X509Certificate get() = load("self-signed.pem")
    val expired: X509Certificate get() = load("expired.pem")
    val notYetValid: X509Certificate get() = load("not-yet-valid.pem")
    val weakKey: X509Certificate get() = load("weak-key.pem")

    /**
     * A self-signed certificate carrying no `basicConstraints` extension.
     *
     * It signed [leafSignedByCaWithoutBasicConstraints] all the same, which is
     * what a router does when the certificate it generated was never meant to
     * be a certificate authority. PKIX will not build a path through it, and
     * path building does not change that: the tests exist to keep the refusal
     * deliberate rather than accidental.
     */
    val caWithoutBasicConstraints: X509Certificate get() = load("ca-no-basic-constraints.pem")

    /** The leaf [caWithoutBasicConstraints] signed, despite not being a CA. */
    val leafSignedByCaWithoutBasicConstraints: X509Certificate get() = load("leaf-signed-by-ca-nbc.pem")

    /** The anchor for the certificate and key inside `chain-server.p12`. */
    val chainCa: X509Certificate get() = load("chain-ca.pem")

    /**
     * A leaf issued by [chainCa] whose validity window closed in early 2024.
     *
     * Its issuer is still valid, which is what separates "this certificate has
     * expired" from "nothing here can be trusted any more" -- the two report
     * differently and only one of them is the user's to fix.
     */
    val expiredLeafSignedByChainCa: X509Certificate get() = load("expired-leaf-signed-by-chain-ca.pem")

    /** The CA and the leaf it signed, concatenated in one PEM file. */
    val bundle: List<X509Certificate> get() = CertificateParser.parse(bytesOf("bundle.pem"))

    /**
     * The same two certificates as [bundle], but the CA first.
     *
     * The order a misconfigured server sends. JSSE cannot validate it as given:
     * `chain[0]` is the end entity by definition, so a chain led by the CA
     * describes a server authenticating as its own certificate authority.
     */
    val reversedBundle: List<X509Certificate> get() = CertificateParser.parse(bytesOf("reversed-bundle.pem"))

    /** The self-signed certificate in DER rather than PEM. */
    val selfSignedDer: ByteArray get() = bytesOf("self-signed.der")

    /** Raw bytes of a fixture, for tests that exercise parsing itself. */
    fun bytesOf(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/certs/$name")) { "missing fixture: $name" }
        .use { it.readBytes() }

    /** Text of a fixture, for tests that exercise pasted input. */
    fun textOf(name: String): String = String(bytesOf(name), Charsets.US_ASCII)

    private fun load(name: String): X509Certificate = CertificateParser.parse(bytesOf(name)).single()
}
