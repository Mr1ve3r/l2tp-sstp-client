package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.security.cert.CertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TrustPolicy.STORE_AUTO]: the policy that anchors on the store rather than on
 * a selection.
 *
 * Two properties matter and pull against each other. It must not demand a
 * selection -- that is the whole point of it -- and it must not pretend the
 * widening is free, so an empty store blocks and a populated one is announced.
 */
class StoreAutoPolicyTest {
    @Test
    fun `the policy builds a chain without requiring a selection`() {
        assertTrue(CertificateValidator.buildsAChain(TrustPolicy.STORE_AUTO))
        assertFalse(CertificateValidator.requiresCertificateAuthority(TrustPolicy.STORE_AUTO))
        assertTrue(CertificateValidator.consultsWholeStore(TrustPolicy.STORE_AUTO))
    }

    /**
     * Every policy against every predicate.
     *
     * A table rather than four assertions so that adding a policy later forces
     * a decision about it instead of inheriting whatever `false` happens to
     * mean.
     */
    @Test
    fun `each policy answers the four predicates as intended`() {
        val expected =
            mapOf(
                // policy to (consultsSelected, requiresCa, consultsWholeStore, buildsAChain)
                TrustPolicy.SYSTEM to listOf(false, false, false, false),
                TrustPolicy.SYSTEM_PLUS_CUSTOM to listOf(true, true, false, true),
                TrustPolicy.CUSTOM_ONLY to listOf(true, true, false, true),
                TrustPolicy.STORE_AUTO to listOf(false, false, true, true),
                TrustPolicy.PIN_LEAF to listOf(false, false, false, false),
                TrustPolicy.INSECURE to listOf(false, false, false, false),
            )

        assertEquals("every policy needs a row", TrustPolicy.entries.toSet(), expected.keys)
        expected.forEach { (policy, answers) ->
            assertEquals(
                "$policy",
                answers,
                listOf(
                    CertificateValidator.consultsSelectedCertificates(policy),
                    CertificateValidator.requiresCertificateAuthority(policy),
                    CertificateValidator.consultsWholeStore(policy),
                    CertificateValidator.buildsAChain(policy),
                ),
            )
        }
    }

    @Test
    fun `the trust manager anchors on everything it is given`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.STORE_AUTO,
                customCerts = listOf(TestCertificates.selfSigned, TestCertificates.ca),
            )

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)
        assertEquals(TestCertificates.ca, (manager as PathBuildingTrustManager).lastAnchor)
    }

    /** A server nothing in the store issued is still refused. */
    @Test
    fun `a store that vouches for nobody refuses the server`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.STORE_AUTO,
                customCerts = listOf(TestCertificates.selfSigned),
            )

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
        }
    }

    /**
     * The store's subjects are not advertised during the handshake.
     *
     * Under this policy the anchor set is everything the user ever imported,
     * and a server asking for client authentication would otherwise be handed
     * the list.
     */
    @Test
    fun `the store is not advertised to the server`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.STORE_AUTO,
                customCerts = listOf(TestCertificates.ca, TestCertificates.selfSigned),
            )

        assertEquals(0, manager.acceptedIssuers.size)
    }

    @Test
    fun `an empty store cannot produce a trust manager`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrustManagerFactoryProvider.create(TrustPolicy.STORE_AUTO, customCerts = emptyList())
        }
    }

    @Test
    fun `the pre-flight does not ask for a selection`() {
        val report = preflight(selected = emptyList(), storeSize = 2)

        assertTrue(report.canConnect)
        assertFalse(report.blocking.any { it is PreflightProblem.NoCertificatesSelected })
    }

    @Test
    fun `the pre-flight blocks when the store is empty`() {
        val report = preflight(selected = emptyList(), storeSize = 0)

        assertFalse(report.canConnect)
        assertTrue(report.blocking.contains(PreflightProblem.StoreIsEmpty))
    }

    @Test
    fun `the pre-flight says how many certificates may vouch`() {
        val report = preflight(selected = emptyList(), storeSize = 3)

        val told = report.confirmations.filterIsInstance<PreflightProblem.WholeStoreIsTrusted>().single()
        assertEquals(3, told.certificateCount)
    }

    /**
     * A selection left over from another policy is not reported as ignored.
     *
     * Under this policy it is still consulted -- along with everything else --
     * so the old message would be untrue.
     */
    @Test
    fun `a leftover selection is not reported as ignored`() {
        val summary = CertificateSummary.of(TestCertificates.ca)
        val report =
            TrustPreflight.check(
                policy = TrustPolicy.STORE_AUTO,
                selectedCertificateIds = listOf(summary.id),
                availableCertificates = mapOf(summary.id to summary),
                pinnedFingerprints = emptySet(),
                now = NOW,
                storeSize = 1,
            )

        assertFalse(report.confirmations.any { it is PreflightProblem.CertificatesIgnoredByPolicy })
    }

    @Test
    fun `the new problems carry their own message keys`() {
        val keys =
            listOf(
                PreflightProblem.StoreIsEmpty.messageKey,
                PreflightProblem.WholeStoreIsTrusted(1).messageKey,
                PreflightProblem.NoCertificatesSelected(TrustPolicy.CUSTOM_ONLY).messageKey,
                PreflightProblem.NoPinsConfigured.messageKey,
            )

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all(String::isNotBlank))
    }

    private fun preflight(selected: List<String>, storeSize: Int) = TrustPreflight.check(
        policy = TrustPolicy.STORE_AUTO,
        selectedCertificateIds = selected,
        availableCertificates = emptyMap(),
        pinnedFingerprints = emptySet(),
        now = NOW,
        storeSize = storeSize,
    )

    private companion object {
        const val AUTH_TYPE = "RSA"
        const val NOW = 1_800_000_000_000L
    }
}
