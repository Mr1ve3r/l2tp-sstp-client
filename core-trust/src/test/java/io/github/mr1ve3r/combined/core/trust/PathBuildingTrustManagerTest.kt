package io.github.mr1ve3r.combined.core.trust

import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PathBuildingTrustManager] against the chains a private CA actually produces.
 *
 * The tests are in two halves. The first says what path building buys: a chain
 * missing its intermediate, or carrying its own anchor, or arriving in an order
 * nobody expected, is still resolved. The second says what it must never buy --
 * the pool is not the anchor set, `basicConstraints` still counts, and an
 * expired certificate is still expired.
 */
class PathBuildingTrustManagerTest {
    @Test
    fun `an ordered chain is trusted and reports the anchor it ended at`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca), clock = { NOW })

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)

        assertEquals(TestCertificates.ca, manager.lastAnchor)
        assertEquals(TestCertificates.leafSignedByCa, manager.lastValidatedPath?.first())
    }

    /**
     * The intermediate the server forgot to send, supplied from the store.
     *
     * This is the case `TrustManagerFactory` cannot cover: it validates what
     * arrived, and what arrived is one certificate with no visible issuer.
     */
    @Test
    fun `a missing intermediate is taken from the pool`() {
        val manager =
            PathBuildingTrustManager.anchoredOn(
                certs = listOf(TestCertificates.ca),
                pool = listOf(TestCertificates.ca),
                clock = { NOW },
            )

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)

        assertEquals(TestCertificates.ca, manager.lastAnchor)
    }

    /** A chain carrying certificates the path does not need is still resolved. */
    @Test
    fun `an unrelated extra certificate in the chain is ignored`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca), clock = { NOW })

        manager.checkServerTrusted(
            arrayOf(TestCertificates.leafSignedByCa, TestCertificates.selfSigned, TestCertificates.ca),
            AUTH_TYPE,
        )

        assertEquals(TestCertificates.ca, manager.lastAnchor)
    }

    /**
     * A server presenting exactly the certificate the user stored.
     *
     * There is no issuing step here, so `basicConstraints` governs nothing and
     * the certificate does not need to be a certificate authority. The validity
     * window is still checked, which is the part [TrustPolicy.PIN_LEAF][
     * io.github.mr1ve3r.combined.engine.TrustPolicy.PIN_LEAF] deliberately skips
     * and this deliberately does not.
     */
    @Test
    fun `a server serving the stored certificate itself is trusted`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.selfSigned), clock = { NOW })

        manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)

        assertEquals(TestCertificates.selfSigned, manager.lastAnchor)
    }

    /**
     * The whole store as the anchor set, which is what the automatic mode does.
     *
     * The point of the assertion is not that it succeeds but that
     * [PathBuildingTrustManager.lastAnchor] names the one certificate that
     * actually vouched. Without that the mode would be unauditable.
     */
    @Test
    fun `a store full of unrelated anchors still names the one that vouched`() {
        val store = listOf(TestCertificates.selfSigned, TestCertificates.expired, TestCertificates.ca)
        val manager = PathBuildingTrustManager.anchoredOn(certs = store, pool = store, clock = { NOW })

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)

        assertEquals(TestCertificates.ca, manager.lastAnchor)
    }

    /**
     * The most important negative test in the file.
     *
     * A certificate in the pool is a path candidate and nothing more. If being
     * in the pool were enough to end a path, then adding the store to it --
     * which is the whole design -- would make every stored certificate a trust
     * anchor for every connection, silently.
     */
    @Test
    fun `a certificate in the pool is not an anchor`() {
        val manager =
            PathBuildingTrustManager.anchoredOn(
                certs = listOf(TestCertificates.selfSigned),
                pool = listOf(TestCertificates.ca),
                clock = { NOW },
            )

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)
        }
    }

    /**
     * `basicConstraints` still decides who may vouch for someone else.
     *
     * Accepting this would mean any leaf certificate could mint certificates
     * for any name, which is the one thing the extension exists to stop. The
     * honest answers for such a server are a re-issued CA or leaf pinning.
     */
    @Test
    fun `a CA without basicConstraints still cannot vouch for a leaf`() {
        val manager =
            PathBuildingTrustManager.anchoredOn(
                listOf(TestCertificates.caWithoutBasicConstraints),
                clock = { NOW },
            )

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(
                arrayOf(
                    TestCertificates.leafSignedByCaWithoutBasicConstraints,
                    TestCertificates.caWithoutBasicConstraints,
                ),
                AUTH_TYPE,
            )
        }
    }

    /** A chain nothing in the anchor set issued is refused, pool or no pool. */
    @Test
    fun `a chain with no path to any anchor is refused`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
        }
    }

    /**
     * Expiry has to arrive as an expiry.
     *
     * The builder reports it as a path failure carrying `BasicReason.EXPIRED`,
     * and the engine maps exception types rather than reading messages, so
     * without the translation an expired certificate would be reported as an
     * unbuildable chain -- true, useless, and impossible to act on.
     */
    @Test
    fun `an expired certificate is reported as expired, not as a broken path`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.chainCa), clock = { NOW })

        assertThrows(CertificateExpiredException::class.java) {
            manager.checkServerTrusted(
                arrayOf(TestCertificates.expiredLeafSignedByChainCa, TestCertificates.chainCa),
                AUTH_TYPE,
            )
        }
    }

    /** The same applies when the server serves a stored certificate that has expired. */
    @Test
    fun `a stored certificate serving itself past its expiry is refused`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.expired), clock = { NOW })

        assertThrows(CertificateExpiredException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.expired), AUTH_TYPE)
        }
    }

    @Test
    fun `a server presenting nothing is refused`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), AUTH_TYPE)
        }
    }

    /**
     * An empty anchor set fails at construction rather than at handshake time.
     *
     * Ten seconds into a connection attempt is the wrong moment to discover a
     * configuration problem the application already knew about.
     */
    @Test
    fun `no anchors at all is refused before any connection`() {
        assertThrows(IllegalArgumentException::class.java) {
            PathBuildingTrustManager.anchoredOn(emptyList())
        }
    }

    /** Client authentication is not this manager's business. */
    @Test
    fun `client certificates are not this manager's business`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca))

        assertThrows(CertificateException::class.java) {
            manager.checkClientTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)
        }
    }

    /**
     * The anchor list is withheld when asked to be.
     *
     * Under a whole-store policy this would otherwise hand any server that asks
     * for client authentication the subject of every certificate authority the
     * user has ever imported.
     */
    @Test
    fun `accepted issuers can be withheld`() {
        val certs: List<X509Certificate> = listOf(TestCertificates.ca, TestCertificates.selfSigned)

        assertEquals(2, PathBuildingTrustManager.anchoredOn(certs).acceptedIssuers.size)
        assertEquals(0, PathBuildingTrustManager.anchoredOn(certs, exposeAcceptedIssuers = false).acceptedIssuers.size)
    }

    /**
     * The reversed chain from `ChainOrderDiagnosticTest`, for the record.
     *
     * Path building does not change the outcome and must not: the CA leads the
     * chain, so the CA is what the server authenticated as, and the accepted
     * path is the CA's own. The name check is what refuses it, one step later.
     */
    @Test
    fun `a chain led by the CA is accepted as the CA, not as the server`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca), clock = { NOW })

        manager.checkServerTrusted(TestCertificates.reversedBundle.toTypedArray(), AUTH_TYPE)

        assertEquals(TestCertificates.ca, manager.lastValidatedPath?.first())
        assertTrue(
            HostnameVerification.verify(TestCertificates.ca, "vpn.example.com")
                is HostnameVerificationResult.Mismatch,
        )
    }

    private companion object {
        const val AUTH_TYPE = "RSA"

        /** Well inside the validity window of every fixture but `expired`. */
        const val NOW = 1_800_000_000_000L
    }
}
