package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
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

    /**
     * A certificate the trusted authority issued for something other than a TLS
     * server cannot stand in for one.
     *
     * Everything else about this chain is in order -- the authority is trusted,
     * the signature is good, the name is the server's -- so nothing but the
     * extended key usage refuses it. `CertPathBuilder` does not look at it, and
     * the `TrustManagerFactory` this replaced did, which is why the check is
     * written out here rather than assumed.
     */
    @Test
    fun `a certificate issued for client authentication cannot serve as the server`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.chainCa), clock = { NOW })

        val failure =
            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(
                    arrayOf(TestCertificates.clientAuthLeafSignedByChainCa, TestCertificates.chainCa),
                    AUTH_TYPE,
                )
            }
        assertTrue("expected the usage to be named: ${failure.message}", failure.message.orEmpty().contains("key usage"))
    }

    /**
     * The same check must apply when the server presents a stored certificate
     * directly, since that path skips the builder entirely.
     */
    @Test
    fun `a stored certificate not meant for a server cannot serve as one either`() {
        val leaf = TestCertificates.clientAuthLeafSignedByChainCa
        val manager = PathBuildingTrustManager.anchoredOn(listOf(leaf), clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(leaf), AUTH_TYPE)
        }
    }

    /** A certificate with no extended key usage at all is unrestricted, and passes. */
    @Test
    fun `a certificate with no extended key usage is still accepted`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.ca), clock = { NOW })

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
    }

    /**
     * A signature nobody can rely on is not a reason to trust anything.
     *
     * The other check the platform manager applied and `CertPathBuilder` does
     * not. It is refused rather than warned about, unlike at import time: there
     * the user is knowingly adding their own anchor, here the certificate is
     * one the server chose to send.
     */
    @Test
    fun `a leaf signed with SHA-1 is refused`() {
        val manager = PathBuildingTrustManager.anchoredOn(listOf(TestCertificates.chainCa), clock = { NOW })

        val failure =
            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(
                    arrayOf(TestCertificates.sha1LeafSignedByChainCa, TestCertificates.chainCa),
                    AUTH_TYPE,
                )
            }
        assertTrue("expected the algorithm to be named: ${failure.message}", failure.message.orEmpty().contains("SHA1"))
    }

    /**
     * A store that cannot vouch for the server must be refused, not crashed on.
     *
     * Reported from a device: the connection died with a `StackOverflowError`
     * out of `SunCertPathBuilder.depthFirstSearchForward`, taking the whole VPN
     * service with it. Every certificate in a store like this is self-signed,
     * so each one is its own issuer; RFC 5280 does not count a self-issued
     * certificate against the path length, so the depth limit never bites and
     * the search recurses until the stack is gone.
     *
     * The refusal has to be an exception the engine can report. An `Error` is
     * not caught by anything on the way out, and a VPN service that disappears
     * mid-handshake is the worst possible way to say "wrong certificate".
     */
    @Test
    fun `a store with no issuer for the server is refused rather than crashed on`() {
        val store =
            listOf(
                TestCertificates.selfSigned,
                TestCertificates.chainCa,
                TestCertificates.caWithoutBasicConstraints,
                TestCertificates.expired,
            )
        val manager = PathBuildingTrustManager.anchoredOn(certs = store, pool = store, clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
        }
    }

    /**
     * The same shape, driven through the policy the report came from.
     */
    @Test
    fun `STORE_AUTO with an unrelated store refuses instead of crashing`() {
        val store = listOf(TestCertificates.selfSigned, TestCertificates.chainCa, TestCertificates.expired)
        val manager = TrustManagerFactoryProvider.create(TrustPolicy.STORE_AUTO, customCerts = store)

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
        }
    }

    /**
     * The store shape that actually took the service down: many authorities
     * sharing one subject name, none of which issued the server's certificate.
     *
     * Each decoy is a candidate issuer by name, so the builder walks into every
     * one of them. Feeding the anchors back in as path candidates -- which the
     * whole-store policy used to do -- multiplied that again at every level,
     * and self-issued certificates are exempt from the path-length limit, so
     * nothing stopped the recursion before the stack ran out.
     */
    @Test
    fun `many authorities sharing a subject are refused rather than crashed on`() {
        val store = TestCertificates.decoyCasSharingASubject
        val manager = PathBuildingTrustManager.anchoredOn(certs = store, pool = store, clock = { NOW })

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
        }
    }

    /** The real anchor is still found when the decoys are sitting beside it. */
    @Test
    fun `the right authority is found among many sharing its name`() {
        val store = TestCertificates.decoyCasSharingASubject + TestCertificates.ca
        val manager = PathBuildingTrustManager.anchoredOn(certs = store, pool = store, clock = { NOW })

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)

        assertEquals(TestCertificates.ca, manager.lastAnchor)
    }

    /**
     * Path building accepts everything the manager it replaced accepted.
     *
     * The switch of `CUSTOM_ONLY` and `SYSTEM_PLUS_CUSTOM` onto path building is
     * a behaviour change in a security-critical place, and the direction that
     * would matter to a user is a profile that connected yesterday and does not
     * today. Every anchor-and-chain combination the fixtures allow is run
     * through both; where the old one said yes, the new one must too.
     *
     * The converse is deliberately not asserted. Path building accepting more
     * is the point of it, and each of those cases is pinned down by a test of
     * its own above.
     */
    @Test
    fun `nothing the previous trust manager accepted is now refused`() {
        val anchors =
            listOf(TestCertificates.ca, TestCertificates.selfSigned, TestCertificates.caWithoutBasicConstraints)
        val chains =
            listOf(
                arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca),
                arrayOf(TestCertificates.leafSignedByCa),
                arrayOf(TestCertificates.selfSigned),
                arrayOf(TestCertificates.ca),
                TestCertificates.reversedBundle.toTypedArray(),
                arrayOf(
                    TestCertificates.leafSignedByCaWithoutBasicConstraints,
                    TestCertificates.caWithoutBasicConstraints,
                ),
            )

        var compared = 0
        for (anchor in anchors) {
            for (chain in chains) {
                val acceptedBefore = accepts(TrustManagerFactoryProvider.pkixTrustManager(listOf(anchor)), chain)
                if (!acceptedBefore) continue

                compared++
                val label = "anchor=${anchor.subjectX500Principal.name} chain=${chain.map { it.subjectX500Principal.name }}"
                assertTrue(
                    "path building refused something the previous manager accepted: $label",
                    accepts(PathBuildingTrustManager.anchoredOn(listOf(anchor), clock = { NOW }), chain),
                )
            }
        }

        // Otherwise a fixture change could empty the loop and leave this test
        // passing while comparing nothing at all.
        assertTrue("the comparison covered no accepted chain at all", compared >= 4)
    }

    private fun accepts(manager: javax.net.ssl.X509TrustManager, chain: Array<X509Certificate>): Boolean = runCatching {
        manager.checkServerTrusted(chain, AUTH_TYPE)
    }.isSuccess

    private companion object {
        const val AUTH_TYPE = "RSA"

        /** Well inside the validity window of every fixture but `expired`. */
        const val NOW = 1_800_000_000_000L
    }
}
