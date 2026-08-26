package io.github.mr1ve3r.combined.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing, fingerprints and summary extraction, against real certificates. */
class CertificateParsingTest {
    @Test
    fun `a PEM certificate is read`() {
        val certificates = CertificateParser.parse(TestCertificates.bytesOf("self-signed.pem"))

        assertEquals(1, certificates.size)
        assertTrue("CN=mikrotik.local" in certificates.single().subjectX500Principal.name)
    }

    @Test
    fun `the same certificate in DER is read identically`() {
        val fromPem = CertificateParser.parse(TestCertificates.bytesOf("self-signed.pem")).single()
        val fromDer = CertificateParser.parse(TestCertificates.selfSignedDer).single()

        assertEquals(fromPem, fromDer)
        assertEquals(CertificateFingerprint.sha256(fromPem), CertificateFingerprint.sha256(fromDer))
    }

    @Test
    fun `a bundle yields every certificate it holds, in file order`() {
        val bundle = TestCertificates.bundle

        assertEquals(2, bundle.size)
        assertTrue("Test CA" in bundle[0].subjectX500Principal.name)
        assertTrue("vpn.example.com" in bundle[1].subjectX500Principal.name)
    }

    @Test
    fun `pasted text survives windows line endings and stray whitespace`() {
        val mangled = "\n\n  " + TestCertificates.textOf("self-signed.pem").replace("\n", "\r\n") + "  \n"

        val certificates = CertificateParser.parsePem(mangled)

        assertEquals(TestCertificates.selfSigned, certificates.single())
    }

    @Test
    fun `pasted text without a PEM header says so, rather than failing to parse`() {
        val thrown =
            assertThrows(CertificateParseException::class.java) {
                CertificateParser.parsePem("just some text the user copied by mistake")
            }

        assertTrue(thrown.message.orEmpty().contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun `input that is not a certificate is rejected`() {
        assertThrows(CertificateParseException::class.java) {
            CertificateParser.parse(byteArrayOf(1, 2, 3, 4, 5))
        }
    }

    @Test
    fun `a certificate round-trips through PEM unchanged`() {
        val original = TestCertificates.leafSignedByCa

        val reparsed = CertificateParser.parsePem(CertificateParser.toPem(original)).single()

        assertEquals(original, reparsed)
    }

    @Test
    fun `a DER import can be re-exported as PEM, since the store keeps only PEM`() {
        val fromDer = CertificateParser.parse(TestCertificates.selfSignedDer).single()

        val pem = CertificateParser.toPem(fromDer)

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals(fromDer, CertificateParser.parsePem(pem).single())
    }

    @Test
    fun `fingerprints are lowercase hex of the expected length`() {
        val sha256 = CertificateFingerprint.sha256(TestCertificates.selfSigned)
        val sha1 = CertificateFingerprint.sha1(TestCertificates.selfSigned)

        assertEquals(64, sha256.length)
        assertEquals(40, sha1.length)
        assertEquals(sha256.lowercase(), sha256)
        assertTrue(sha256.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `different certificates have different fingerprints`() {
        assertNotEquals(
            CertificateFingerprint.sha256(TestCertificates.ca),
            CertificateFingerprint.sha256(TestCertificates.leafSignedByCa),
        )
    }

    @Test
    fun `the display form is colon-separated uppercase, as routers show it`() {
        val display = CertificateFingerprint.formatForDisplay(CertificateFingerprint.sha256(TestCertificates.selfSigned))

        assertEquals(32, display.split(':').size)
        assertEquals(display.uppercase(), display)
    }

    @Test
    fun `a fingerprint matches its own certificate`() {
        val certificate = TestCertificates.selfSigned

        assertTrue(CertificateFingerprint.matchesSha256(CertificateFingerprint.sha256(certificate), certificate))
    }

    @Test
    fun `a fingerprint entered with colons or spaces still matches`() {
        val certificate = TestCertificates.selfSigned
        val display = CertificateFingerprint.formatForDisplay(CertificateFingerprint.sha256(certificate))

        assertTrue(CertificateFingerprint.matchesSha256(display, certificate))
        assertTrue(CertificateFingerprint.matchesSha256(display.replace(":", " "), certificate))
    }

    @Test
    fun `changing one byte of a fingerprint stops it matching`() {
        val certificate = TestCertificates.selfSigned
        val correct = CertificateFingerprint.sha256(certificate)
        val firstDigit = if (correct[0] == 'a') 'b' else 'a'
        val tampered = firstDigit + correct.substring(1)

        assertFalse(CertificateFingerprint.matchesSha256(tampered, certificate))
    }

    @Test
    fun `a malformed fingerprint fails the check instead of throwing`() {
        val certificate = TestCertificates.selfSigned

        assertFalse(CertificateFingerprint.matchesSha256("not a fingerprint", certificate))
        assertFalse(CertificateFingerprint.matchesSha256("abc", certificate))
        assertFalse(CertificateFingerprint.matchesSha256("", certificate))
    }

    @Test
    fun `a set of pins matches when any one of them is right`() {
        val certificate = TestCertificates.selfSigned
        val pins = setOf("00".repeat(32), CertificateFingerprint.sha256(certificate), "ff".repeat(32))

        assertTrue(CertificateFingerprint.matchesAnySha256(pins, certificate))
        assertFalse(CertificateFingerprint.matchesAnySha256(setOf("00".repeat(32)), certificate))
        assertFalse(CertificateFingerprint.matchesAnySha256(emptySet(), certificate))
    }

    @Test
    fun `a summary reports the fields the certificate screen shows`() {
        val summary = CertificateSummary.of(TestCertificates.leafSignedByCa)

        assertEquals("vpn.example.com", summary.subjectCn)
        assertTrue("Test CA" in summary.issuerDn)
        assertFalse("a leaf must not claim to be a CA", summary.isCa)
        assertFalse("issued by a CA, so not self-signed", summary.isSelfSigned)
        assertEquals(2048, summary.publicKeyBits)
        assertEquals(summary.sha256Fingerprint, summary.id)
    }

    @Test
    fun `a summary lists the names the certificate presents`() {
        val summary = CertificateSummary.of(TestCertificates.leafSignedByCa)

        assertEquals(listOf("DNS:vpn.example.com", "DNS:vpn.internal.lan"), summary.subjectAltNames)
    }

    @Test
    fun `a CA is recognised as one, and as self-signed`() {
        val summary = CertificateSummary.of(TestCertificates.ca)

        assertTrue(summary.isCa)
        assertTrue(summary.isSelfSigned)
    }
}
