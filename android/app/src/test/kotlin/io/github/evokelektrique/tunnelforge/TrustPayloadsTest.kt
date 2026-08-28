package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.trust.CertificateParser
import io.github.mr1ve3r.combined.core.trust.CertificateSummary
import io.github.mr1ve3r.combined.core.trust.CertificateWarning
import io.github.mr1ve3r.combined.core.trust.ImportCandidate
import io.github.mr1ve3r.combined.core.trust.store.StoredCertificate
import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the certificate maps crossing the method channel.
 *
 * These assertions exist because the Dart side reads the same keys by hand: a
 * renamed key here is a field that quietly stops appearing in the UI, and
 * nothing else would catch it.
 */
class TrustPayloadsTest {
    @Test
    fun `a stored certificate carries its fields, alias and usage count`() {
        val stored =
            StoredCertificate(
                summary = summary(),
                alias = "MikroTik",
                importedAt = IMPORTED_AT,
                usageCount = 2,
            )

        val payload = TrustPayloads.stored(stored)

        assertEquals(summary().id, payload[TrustContract.FIELD_ID])
        assertEquals("MikroTik", payload[TrustContract.FIELD_ALIAS])
        assertEquals(IMPORTED_AT, payload[TrustContract.FIELD_IMPORTED_AT])
        assertEquals(2, payload[TrustContract.FIELD_USAGE_COUNT])
    }

    @Test
    fun `a stored certificate is not sent with its PEM`() {
        val payload = TrustPayloads.stored(StoredCertificate(summary(), "alias", IMPORTED_AT, 0))

        // PEM travels only when a certificate is being offered for import or
        // deliberately exported. The list screen has no use for it.
        assertTrue(TrustContract.FIELD_PEM !in payload)
    }

    @Test
    fun `a candidate carries the PEM that would be stored`() {
        val payload = TrustPayloads.candidate(candidate())

        assertEquals(CertificateParser.toPem(certificate()), payload[TrustContract.FIELD_PEM])
    }

    @Test
    fun `every displayed field of a certificate crosses the channel`() {
        val payload = TrustPayloads.summary(summary())

        assertEquals(
            setOf(
                TrustContract.FIELD_ID,
                TrustContract.FIELD_SUBJECT_CN,
                TrustContract.FIELD_SUBJECT_DN,
                TrustContract.FIELD_ISSUER_DN,
                TrustContract.FIELD_SERIAL_NUMBER,
                TrustContract.FIELD_NOT_BEFORE,
                TrustContract.FIELD_NOT_AFTER,
                TrustContract.FIELD_SHA256,
                TrustContract.FIELD_SHA1,
                TrustContract.FIELD_IS_CA,
                TrustContract.FIELD_KEY_USAGE,
                TrustContract.FIELD_SUBJECT_ALT_NAMES,
                TrustContract.FIELD_PUBLIC_KEY_BITS,
                TrustContract.FIELD_SIGNATURE_ALGORITHM,
            ),
            payload.keys,
        )
    }

    @Test
    fun `a warning becomes a key and the number its message needs`() {
        assertEquals(
            mapOf(
                TrustContract.FIELD_WARNING_KEY to "trust.warning.expiring_soon",
                TrustContract.FIELD_WARNING_DETAIL to "12",
            ),
            TrustPayloads.warning(CertificateWarning.ExpiringSoon(daysRemaining = 12)),
        )
        assertEquals(
            "1024",
            TrustPayloads.warning(CertificateWarning.WeakKey(bits = 1024))[TrustContract.FIELD_WARNING_DETAIL],
        )
        assertEquals(
            TrustPolicy.CUSTOM_ONLY.name,
            TrustPayloads
                .warning(CertificateWarning.NotACertificateAuthority(TrustPolicy.CUSTOM_ONLY))[
                TrustContract.FIELD_WARNING_DETAIL,
            ],
        )
    }

    @Test
    fun `a warning with nothing to interpolate has no detail`() {
        assertNull(TrustPayloads.warning(CertificateWarning.AlreadyImported)[TrustContract.FIELD_WARNING_DETAIL])
    }

    @Test
    fun `a chain position survives, so the user can tell the leaf from the root`() {
        val payload = TrustPayloads.candidate(candidate().copy(chainPosition = 1))

        assertEquals(1, payload[TrustContract.FIELD_CHAIN_POSITION])
    }

    private fun candidate(): ImportCandidate = ImportCandidate.of(listOf(certificate()), now = IMPORTED_AT).single()

    private fun summary(): CertificateSummary = CertificateSummary.of(certificate())

    private fun certificate(): X509Certificate = CertificateParser.parsePem(SELF_SIGNED_PEM).single()

    private companion object {
        const val IMPORTED_AT = 1_780_000_000_000L
        val SELF_SIGNED_PEM = TrustTestCertificate.SELF_SIGNED_PEM
    }
}
