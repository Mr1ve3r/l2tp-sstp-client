package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostname verification (SPEC 5.8) and the pre-flight check (SPEC 5.7). */
class HostnameAndPreflightTest {
    @Test
    fun `a certificate matches a name in its SAN list`() {
        val result = HostnameVerification.verify(TestCertificates.leafSignedByCa, "vpn.example.com")

        assertEquals(HostnameVerificationResult.Matched, result)
    }

    @Test
    fun `any of the SAN entries will do, not just the first`() {
        val result = HostnameVerification.verify(TestCertificates.leafSignedByCa, "vpn.internal.lan")

        assertEquals(HostnameVerificationResult.Matched, result)
    }

    @Test
    fun `matching ignores case and a trailing dot`() {
        assertEquals(HostnameVerificationResult.Matched, HostnameVerification.verify(TestCertificates.leafSignedByCa, "VPN.Example.COM"))
        assertEquals(HostnameVerificationResult.Matched, HostnameVerification.verify(TestCertificates.leafSignedByCa, "vpn.example.com."))
    }

    @Test
    fun `a mismatch lists the names the certificate actually carries`() {
        // The scenario the expectedHostname field exists for: the certificate is
        // for an internal name, the connection is over something else.
        val result = HostnameVerification.verify(TestCertificates.leafSignedByCa, "vpn.ddns.example.net")

        val mismatch = result as HostnameVerificationResult.Mismatch
        assertEquals("vpn.ddns.example.net", mismatch.expected)
        assertEquals(listOf("DNS:vpn.example.com", "DNS:vpn.internal.lan"), mismatch.presented)
    }

    @Test
    fun `an IP SAN matches the address it names`() {
        val result = HostnameVerification.verify(TestCertificates.selfSigned, "192.168.88.1")

        assertEquals(HostnameVerificationResult.Matched, result)
    }

    @Test
    fun `a different address does not match`() {
        assertTrue(HostnameVerification.verify(TestCertificates.selfSigned, "192.168.88.2") is HostnameVerificationResult.Mismatch)
    }

    @Test
    fun `a subdomain does not match a certificate for the parent`() {
        assertTrue(
            HostnameVerification.verify(TestCertificates.leafSignedByCa, "a.vpn.example.com")
                is HostnameVerificationResult.Mismatch,
        )
    }

    @Test
    fun `a wildcard covers exactly one label`() {
        assertTrue(matchesWildcard("*.example.com", "vpn.example.com"))
        assertFalse("two labels deep is not covered", matchesWildcard("*.example.com", "a.vpn.example.com"))
        assertFalse("the bare domain is not covered", matchesWildcard("*.example.com", "example.com"))
        assertFalse("a different domain is not covered", matchesWildcard("*.example.com", "vpn.example.net"))
    }

    @Test
    fun `a clean profile connects without asking anything`() {
        val summary = CertificateSummary.of(TestCertificates.ca)

        val report =
            TrustPreflight.check(
                policy = TrustPolicy.CUSTOM_ONLY,
                selectedCertificateIds = listOf(summary.id),
                availableCertificates = mapOf(summary.id to summary),
                pinnedFingerprints = emptySet(),
                now = summary.notBefore + 1,
            )

        assertTrue(report.isClean)
        assertTrue(report.canConnect)
    }

    @Test
    fun `a deleted certificate blocks the attempt and names what is missing`() {
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.CUSTOM_ONLY,
                selectedCertificateIds = listOf("deadbeef"),
                availableCertificates = emptyMap(),
                pinnedFingerprints = emptySet(),
                now = NOW,
            )

        assertFalse(report.canConnect)
        assertEquals(listOf("deadbeef"), report.blocking.filterIsInstance<PreflightProblem.CertificatesMissing>().single().ids)
    }

    @Test
    fun `CUSTOM_ONLY with nothing selected is blocked before a socket is opened`() {
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.CUSTOM_ONLY,
                selectedCertificateIds = emptyList(),
                availableCertificates = emptyMap(),
                pinnedFingerprints = emptySet(),
                now = NOW,
            )

        assertFalse(report.canConnect)
        assertEquals(
            TrustPolicy.CUSTOM_ONLY,
            report.blocking.filterIsInstance<PreflightProblem.NoCertificatesSelected>().single().policy,
        )
    }

    @Test
    fun `PIN_LEAF with no pins is blocked`() {
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.PIN_LEAF,
                selectedCertificateIds = emptyList(),
                availableCertificates = emptyMap(),
                pinnedFingerprints = emptySet(),
                now = NOW,
            )

        assertTrue(report.blocking.contains(PreflightProblem.NoPinsConfigured))
    }

    @Test
    fun `PIN_LEAF with a pin needs no certificates selected`() {
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.PIN_LEAF,
                selectedCertificateIds = emptyList(),
                availableCertificates = emptyMap(),
                pinnedFingerprints = setOf("ab".repeat(32)),
                now = NOW,
            )

        assertTrue(report.isClean)
    }

    @Test
    fun `SYSTEM needs neither certificates nor pins`() {
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.SYSTEM,
                selectedCertificateIds = emptyList(),
                availableCertificates = emptyMap(),
                pinnedFingerprints = emptySet(),
                now = NOW,
            )

        assertTrue(report.isClean)
    }

    @Test
    fun `an expired certificate asks rather than blocks`() {
        val summary = CertificateSummary.of(TestCertificates.expired)

        val report =
            TrustPreflight.check(
                policy = TrustPolicy.CUSTOM_ONLY,
                selectedCertificateIds = listOf(summary.id),
                availableCertificates = mapOf(summary.id to summary),
                pinnedFingerprints = emptySet(),
                now = System.currentTimeMillis(),
            )

        assertTrue("expiry must not stop the attempt outright", report.canConnect)
        assertTrue(report.needsConfirmation)
        val expired = report.confirmations.filterIsInstance<PreflightProblem.CertificateExpired>().single()
        assertEquals(summary.id, expired.id)
        assertEquals("expired.example.com", expired.subjectCn)
    }

    @Test
    fun `several problems are all reported, not just the first`() {
        val expired = CertificateSummary.of(TestCertificates.expired)

        val report =
            TrustPreflight.check(
                policy = TrustPolicy.CUSTOM_ONLY,
                selectedCertificateIds = listOf(expired.id, "missing-one"),
                availableCertificates = mapOf(expired.id to expired),
                pinnedFingerprints = emptySet(),
                now = System.currentTimeMillis(),
            )

        assertFalse(report.canConnect)
        assertTrue(report.needsConfirmation)
    }

    @Test
    fun `every problem carries a distinct message key`() {
        val problems =
            listOf(
                PreflightProblem.CertificatesMissing(listOf("a")),
                PreflightProblem.NoCertificatesSelected(TrustPolicy.CUSTOM_ONLY),
                PreflightProblem.NoPinsConfigured,
                PreflightProblem.CertificateExpired("a", "cn", 0L),
            )

        val keys = problems.map { it.messageKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all(String::isNotBlank))
    }

    // No fixture carries a wildcard SAN, so the matcher is exercised directly.
    // It is internal rather than private for exactly this reason.
    private fun matchesWildcard(certificateName: String, expected: String): Boolean =
        HostnameVerification.dnsMatches(certificateName, expected)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
