package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The import-time warning table from SPEC 5.4. */
class CertificateValidatorTest {
    @Test
    fun `a healthy certificate produces no warnings`() {
        val summary = CertificateSummary.of(TestCertificates.leafSignedByCa)

        val warnings = CertificateValidator.validate(summary, now = summary.notBefore + ONE_DAY)

        assertEquals(emptyList<CertificateWarning>(), warnings)
    }

    @Test
    fun `an expired certificate is reported with its expiry date`() {
        val summary = CertificateSummary.of(TestCertificates.expired)

        val warnings = CertificateValidator.validate(summary, now = System.currentTimeMillis())

        val expired = warnings.filterIsInstance<CertificateWarning.Expired>().single()
        assertEquals(summary.notAfter, expired.notAfter)
    }

    @Test
    fun `a certificate close to expiry says how many days are left`() {
        val summary = CertificateSummary.of(TestCertificates.leafSignedByCa)
        val twelveDaysBeforeExpiry = summary.notAfter - TimeUnit.DAYS.toMillis(12)

        val warnings = CertificateValidator.validate(summary, now = twelveDaysBeforeExpiry)

        assertEquals(12L, warnings.filterIsInstance<CertificateWarning.ExpiringSoon>().single().daysRemaining)
    }

    @Test
    fun `the expiry warning window does not fire a month and a half out`() {
        val summary = CertificateSummary.of(TestCertificates.leafSignedByCa)
        val wellBefore = summary.notAfter - TimeUnit.DAYS.toMillis(45)

        val warnings = CertificateValidator.validate(summary, now = wellBefore)

        assertTrue(warnings.none { it is CertificateWarning.ExpiringSoon })
    }

    @Test
    fun `a certificate whose validity has not started is reported`() {
        val summary = CertificateSummary.of(TestCertificates.notYetValid)

        val warnings = CertificateValidator.validate(summary, now = System.currentTimeMillis())

        assertTrue(warnings.any { it is CertificateWarning.NotYetValid })
    }

    @Test
    fun `a short RSA key is reported with its size`() {
        val summary = CertificateSummary.of(TestCertificates.weakKey)

        val warnings = CertificateValidator.validate(summary, now = summary.notBefore + ONE_DAY)

        assertEquals(1024, warnings.filterIsInstance<CertificateWarning.WeakKey>().single().bits)
    }

    @Test
    fun `a leaf chosen for a chain-building policy is flagged, and pinning suggested instead`() {
        val summary = CertificateSummary.of(TestCertificates.selfSigned)

        val warnings =
            CertificateValidator.validate(
                summary,
                now = summary.notBefore + ONE_DAY,
                intendedPolicy = TrustPolicy.CUSTOM_ONLY,
            )

        assertEquals(TrustPolicy.CUSTOM_ONLY, warnings.filterIsInstance<CertificateWarning.NotACertificateAuthority>().single().policy)
    }

    @Test
    fun `a CA chosen for a chain-building policy is not flagged`() {
        val summary = CertificateSummary.of(TestCertificates.ca)

        val warnings =
            CertificateValidator.validate(
                summary,
                now = summary.notBefore + ONE_DAY,
                intendedPolicy = TrustPolicy.CUSTOM_ONLY,
            )

        assertTrue(warnings.none { it is CertificateWarning.NotACertificateAuthority })
    }

    @Test
    fun `pinning a leaf raises no CA complaint, since pinning ignores the chain`() {
        val summary = CertificateSummary.of(TestCertificates.selfSigned)

        val warnings =
            CertificateValidator.validate(
                summary,
                now = summary.notBefore + ONE_DAY,
                intendedPolicy = TrustPolicy.PIN_LEAF,
            )

        assertTrue(warnings.none { it is CertificateWarning.NotACertificateAuthority })
    }

    @Test
    fun `re-importing a known certificate is information, not a problem`() {
        val summary = CertificateSummary.of(TestCertificates.selfSigned)

        val warnings =
            CertificateValidator.validate(summary, now = summary.notBefore + ONE_DAY, alreadyImported = true)

        assertEquals(listOf(CertificateWarning.AlreadyImported), warnings)
    }

    @Test
    fun `every warning carries a distinct message key`() {
        val warnings =
            listOf(
                CertificateWarning.Expired(0L),
                CertificateWarning.ExpiringSoon(1L),
                CertificateWarning.NotYetValid(0L),
                CertificateWarning.NotACertificateAuthority(TrustPolicy.CUSTOM_ONLY),
                CertificateWarning.WeakKey(1024),
                CertificateWarning.WeakSignature("SHA1withRSA"),
                CertificateWarning.AlreadyImported,
            )

        val keys = warnings.map { it.messageKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all(String::isNotBlank))
    }

    @Test
    fun `validation never refuses, it only describes`() {
        // Everything wrong at once still returns warnings rather than throwing:
        // the user may be importing exactly this on purpose (SPEC 5.4).
        val summary = CertificateSummary.of(TestCertificates.expired).copy(publicKeyBits = 512, signatureAlgorithm = "SHA1withRSA")

        val warnings = CertificateValidator.validate(summary, now = System.currentTimeMillis(), alreadyImported = true)

        assertTrue(warnings.size >= 4)
    }

    private companion object {
        val ONE_DAY: Long = TimeUnit.DAYS.toMillis(1)
    }
}
