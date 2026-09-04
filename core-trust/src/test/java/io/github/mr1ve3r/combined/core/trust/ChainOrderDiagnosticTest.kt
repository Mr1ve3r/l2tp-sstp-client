package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.security.cert.CertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What today's `PKIX` trust manager does with the four chains a private CA can
 * produce, and which of them a user would report as "the chain breaks".
 *
 * This file is a diagnosis, not a specification. It exists because four
 * different mistakes produce almost the same symptom -- a handshake that fails
 * with a certificate error when the store visibly holds the server's CA -- and
 * only two of them are fixed by building paths instead of validating the chain
 * as presented:
 *
 * | Cause | Fixed by path building |
 * |---|---|
 * | the chain arrives with the CA first | no -- see [reversedChainAuthenticatesTheCaItself] |
 * | the anchor is also inside the presented chain | yes |
 * | the CA carries no `basicConstraints` | no, and deliberately so |
 * | no certificate was selected on the profile | no -- that is what `STORE_AUTO` is for |
 *
 * The assertions are written against current behaviour on purpose. When
 * `PathBuildingTrustManager` takes over, the ones that should change will fail,
 * and the ones that must not change will keep passing.
 */
class ChainOrderDiagnosticTest {
    /**
     * The control. If this ever fails, the problem is the fixtures or the
     * provider, not the order of anything.
     */
    @Test
    fun `an ordered chain against its CA is trusted`() {
        val manager = TrustManagerFactoryProvider.pkixTrustManager(listOf(TestCertificates.ca))

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
    }

    /**
     * The reported case, and the one worth reading closely.
     *
     * A chain led by the CA does not fail the way one might expect. PKIX
     * short-circuits when `chain[0]` is itself a trust anchor, so the chain is
     * *accepted* -- and what has been accepted is the CA, not the server. The
     * failure surfaces one step later, in hostname verification, because the
     * certificate the server actually authenticated with carries the CA's name
     * and not the server's.
     *
     * That is why path building cannot fix this on its own: TLS binds the
     * server to `chain[0]` and to nothing else, so re-reading the chain to find
     * a "better" leaf would authenticate a key the server never proved it
     * holds. The fix belongs on the server, and the client's job is to say so.
     */
    @Test
    fun reversedChainAuthenticatesTheCaItself() {
        val reversed = TestCertificates.reversedBundle
        assertEquals("fixture must start with the CA", TestCertificates.ca, reversed.first())

        val manager = TrustManagerFactoryProvider.pkixTrustManager(listOf(TestCertificates.ca))
        manager.checkServerTrusted(reversed.toTypedArray(), AUTH_TYPE)

        // Accepted -- but as the CA. This is the user-visible failure.
        val result = HostnameVerification.verify(reversed.first(), "vpn.example.com")
        assertTrue("the CA must not cover the server's name", result is HostnameVerificationResult.Mismatch)
    }

    /**
     * The same reversed chain with nothing to anchor on, which separates "the
     * anchor is also in the chain" from "the order is wrong". Here the CA is
     * unknown, so there is no short circuit and the chain is refused outright.
     */
    @Test
    fun `a reversed chain with no anchor in the store is refused`() {
        val manager = TrustManagerFactoryProvider.pkixTrustManager(listOf(TestCertificates.selfSigned))

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(TestCertificates.reversedBundle.toTypedArray(), AUTH_TYPE)
        }
    }

    /**
     * A router certificate that signed a server certificate without ever being
     * marked as a certificate authority.
     *
     * This one must keep failing. Accepting it would mean ignoring
     * `basicConstraints`, which is the check that stops any leaf certificate
     * from minting certificates for other names. The honest answers are a
     * re-issued CA or [TrustPolicy.PIN_LEAF], and the message should say which.
     */
    @Test
    fun `a CA without basicConstraints cannot anchor a chain`() {
        val manager = TrustManagerFactoryProvider.pkixTrustManager(listOf(TestCertificates.caWithoutBasicConstraints))

        val failure =
            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(
                    arrayOf(
                        TestCertificates.leafSignedByCaWithoutBasicConstraints,
                        TestCertificates.caWithoutBasicConstraints,
                    ),
                    AUTH_TYPE,
                )
            }
        assertTrue("expected a path failure, got: ${failure.message}", failure.message != null)
    }

    /**
     * The fourth cause, which is not a chain problem at all: the certificate is
     * in the store but was never ticked on the profile. The pre-flight check
     * refuses before a socket is opened, which is the behaviour `STORE_AUTO`
     * exists to make unnecessary.
     */
    @Test
    fun `a chain-building policy with nothing selected is blocked before connecting`() {
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.CUSTOM_ONLY,
                selectedCertificateIds = emptyList(),
                availableCertificates = emptyMap(),
                pinnedFingerprints = emptySet(),
                now = NOW,
            )

        assertTrue(report.blocking.any { it is PreflightProblem.NoCertificatesSelected })
    }

    private companion object {
        const val AUTH_TYPE = "RSA"

        /** Well inside every fixture's validity window. */
        const val NOW = 1_800_000_000_000L
    }
}
