package io.github.mr1ve3r.combined.core.trust

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Path building on a device, where the provider is Conscrypt rather than the
 * JDK's.
 *
 * Not a duplicate of the JVM tests, despite covering the same chains. The two
 * providers disagree in exactly the places this code depends on: Conscrypt runs
 * its own chain cleanup before validating and drops certificates that do not
 * link, and the two differ over a chain whose first element is itself a trust
 * anchor. The JVM result is therefore not evidence about the device, and the
 * device is what ships.
 *
 * A failure here that does not reproduce on the JVM is a real finding, not a
 * flaky test.
 */
@RunWith(AndroidJUnit4::class)
class PathBuildingTrustManagerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ordered_chain_is_trusted_and_names_its_anchor() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(ca), clock = { NOW })

        manager.checkServerTrusted(arrayOf(leafSignedByCa, ca), AUTH_TYPE)

        assertEquals(ca, manager.lastAnchor)
    }

    /**
     * The case the store exists for: the server sends only its own certificate
     * and the issuer comes from the pool.
     */
    @Test
    fun missing_intermediate_is_supplied_from_the_pool() {
        val manager = PathBuildingTrustManager.anchoredOn(certs = listOf(ca), pool = listOf(ca), clock = { NOW })

        manager.checkServerTrusted(arrayOf(leafSignedByCa), AUTH_TYPE)

        assertEquals(ca, manager.lastAnchor)
    }

    /**
     * Conscrypt's cleanup walks forward from the first element and discards
     * what does not chain, so a CA-first chain is where the two providers are
     * most likely to part company. The outcome that matters is the same either
     * way: what got accepted is the CA, and the name check is what refuses it.
     */
    @Test
    fun chain_led_by_the_ca_is_accepted_as_the_ca() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(ca), clock = { NOW })

        manager.checkServerTrusted(reversedBundle.toTypedArray(), AUTH_TYPE)

        assertEquals(ca, manager.lastValidatedPath?.first())
        assertTrue(
            HostnameVerification.verify(ca, "vpn.example.com") is HostnameVerificationResult.Mismatch,
        )
    }

    /** The pool must not become an anchor set on this provider either. */
    @Test
    fun a_certificate_in_the_pool_is_not_an_anchor() {
        val manager =
            PathBuildingTrustManager.anchoredOn(certs = listOf(selfSigned), pool = listOf(ca), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(leafSignedByCa), AUTH_TYPE)
        }
    }

    /**
     * The check `CertPathBuilder` does not perform, on the provider that ships.
     *
     * RFC 5280 excludes the trust anchor from the path, so neither provider
     * applies `basicConstraints` to it; the refusal comes from this class and
     * has to hold here as much as on the JVM.
     */
    @Test
    fun a_ca_without_basic_constraints_still_cannot_vouch() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(caWithoutBasicConstraints), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(
                arrayOf(leafSignedByCaWithoutBasicConstraints, caWithoutBasicConstraints),
                AUTH_TYPE,
            )
        }
    }

    @Test
    fun an_expired_leaf_is_reported_as_expired() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(chainCa), clock = { NOW })

        assertThrows(CertificateExpiredException::class.java) {
            manager.checkServerTrusted(arrayOf(expiredLeafSignedByChainCa, chainCa), AUTH_TYPE)
        }
    }

    /**
     * The checks Conscrypt applied and `CertPathBuilder` does not.
     *
     * These matter more here than on the JVM: Conscrypt is the manager this
     * code replaced, so this is where a claim of parity is actually tested.
     */
    @Test
    fun a_certificate_issued_for_client_authentication_cannot_serve_as_the_server() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(chainCa), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(clientAuthLeafSignedByChainCa, chainCa), AUTH_TYPE)
        }
    }

    @Test
    fun a_leaf_signed_with_sha1_is_refused() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(chainCa), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(sha1LeafSignedByChainCa, chainCa), AUTH_TYPE)
        }
    }

    /** The whole-store policy, end to end, on the provider that ships. */
    @Test
    fun store_auto_anchors_on_everything_and_names_the_one_that_vouched() {
        val store = listOf(selfSigned, expired, ca)

        val manager = TrustManagerFactoryProvider.create(TrustPolicy.STORE_AUTO, customCerts = store)
        manager.checkServerTrusted(arrayOf(leafSignedByCa), AUTH_TYPE)

        assertEquals(ca, (manager as PathBuildingTrustManager).lastAnchor)
        assertEquals("the store must not be advertised", 0, manager.acceptedIssuers.size)
    }

    private fun certificate(assetName: String): X509Certificate = context.assets
        .open("certs/$assetName")
        .use { CertificateParser.parse(it).first() }

    private fun certificates(assetName: String): List<X509Certificate> = context.assets
        .open("certs/$assetName")
        .use { CertificateParser.parse(it) }

    private val ca: X509Certificate get() = certificate("ca.pem")
    private val leafSignedByCa: X509Certificate get() = certificate("leaf-signed-by-ca.pem")
    private val selfSigned: X509Certificate get() = certificate("self-signed.pem")
    private val expired: X509Certificate get() = certificate("expired.pem")
    private val chainCa: X509Certificate get() = certificate("chain-ca.pem")
    private val caWithoutBasicConstraints: X509Certificate get() = certificate("ca-no-basic-constraints.pem")
    private val leafSignedByCaWithoutBasicConstraints: X509Certificate get() = certificate("leaf-signed-by-ca-nbc.pem")
    private val expiredLeafSignedByChainCa: X509Certificate get() = certificate("expired-leaf-signed-by-chain-ca.pem")
    private val clientAuthLeafSignedByChainCa: X509Certificate get() = certificate("client-auth-leaf-signed-by-chain-ca.pem")
    private val sha1LeafSignedByChainCa: X509Certificate get() = certificate("sha1-leaf-signed-by-chain-ca.pem")
    private val reversedBundle: List<X509Certificate> get() = certificates("reversed-bundle.pem")

    private companion object {
        const val AUTH_TYPE = "RSA"

        /** Well inside the validity window of every fixture but the expired ones. */
        const val NOW = 1_800_000_000_000L
    }
}
