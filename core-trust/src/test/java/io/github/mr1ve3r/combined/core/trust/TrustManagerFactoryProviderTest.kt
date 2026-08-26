package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every trust policy, against a self-signed certificate, one signed by our own
 * CA, and the chain a public CA would produce (SPEC 5.10).
 */
class TrustManagerFactoryProviderTest {
    @Test
    fun `CUSTOM_ONLY accepts a certificate signed by the CA it was given`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.CUSTOM_ONLY,
                customCerts = listOf(TestCertificates.ca),
            )

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
    }

    @Test
    fun `CUSTOM_ONLY rejects a certificate its CA did not sign`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.CUSTOM_ONLY,
                customCerts = listOf(TestCertificates.ca),
            )

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
        }
    }

    @Test
    fun `CUSTOM_ONLY ignores the system store entirely`() {
        // The self-signed certificate is not in any public store either, but the
        // point here is that a CA-anchored manager does not fall back.
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.CUSTOM_ONLY,
                customCerts = listOf(TestCertificates.selfSigned),
            )

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
        }
    }

    @Test
    fun `SYSTEM rejects a private certificate, since no public CA signed it`() {
        val manager = TrustManagerFactoryProvider.create(TrustPolicy.SYSTEM)

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
        }
    }

    @Test
    fun `SYSTEM trusts the platform's own anchors`() {
        val manager = TrustManagerFactoryProvider.create(TrustPolicy.SYSTEM)

        assertTrue("the platform trust store should not be empty", manager.acceptedIssuers.isNotEmpty())
    }

    @Test
    fun `SYSTEM_PLUS_CUSTOM accepts what the custom CA signed`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.SYSTEM_PLUS_CUSTOM,
                customCerts = listOf(TestCertificates.ca),
            )

        manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa, TestCertificates.ca), AUTH_TYPE)
    }

    @Test
    fun `SYSTEM_PLUS_CUSTOM still rejects what neither store vouches for`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.SYSTEM_PLUS_CUSTOM,
                customCerts = listOf(TestCertificates.ca),
            )

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
        }
    }

    @Test
    fun `SYSTEM_PLUS_CUSTOM offers issuers from both stores`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.SYSTEM_PLUS_CUSTOM,
                customCerts = listOf(TestCertificates.ca),
            )
        val systemOnly = TrustManagerFactoryProvider.systemTrustManager().acceptedIssuers.size

        assertEquals(systemOnly + 1, manager.acceptedIssuers.size)
    }

    @Test
    fun `when both stores reject, the system failure is thrown with the custom one attached`() {
        val manager =
            CompositeTrustManager(
                system = alwaysFails("system says no"),
                custom = alwaysFails("custom says no"),
            )

        val thrown =
            assertThrows(CertificateException::class.java) {
                manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
            }

        assertEquals("system says no", thrown.message)
        assertEquals("custom says no", thrown.suppressed.single().message)
    }

    @Test
    fun `PIN_LEAF accepts exactly the pinned certificate`() {
        val pinned = TestCertificates.selfSigned
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.PIN_LEAF,
                pinnedFingerprints = setOf(CertificateFingerprint.sha256(pinned)),
            )

        manager.checkServerTrusted(arrayOf(pinned), AUTH_TYPE)
    }

    @Test
    fun `PIN_LEAF rejects a different certificate and reports what was presented`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.PIN_LEAF,
                pinnedFingerprints = setOf(CertificateFingerprint.sha256(TestCertificates.selfSigned)),
            )

        val thrown =
            assertThrows(CertificatePinMismatchException::class.java) {
                manager.checkServerTrusted(arrayOf(TestCertificates.leafSignedByCa), AUTH_TYPE)
            }

        assertEquals(CertificateFingerprint.sha256(TestCertificates.leafSignedByCa), thrown.presentedSha256)
    }

    @Test
    fun `PIN_LEAF rejects when one byte of the pin is changed`() {
        val correct = CertificateFingerprint.sha256(TestCertificates.selfSigned)
        val tampered = (if (correct[0] == 'a') "b" else "a") + correct.substring(1)
        val manager = TrustManagerFactoryProvider.create(TrustPolicy.PIN_LEAF, pinnedFingerprints = setOf(tampered))

        assertThrows(CertificatePinMismatchException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
        }
    }

    @Test
    fun `PIN_LEAF ignores the chain, which is what makes a self-signed router usable`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.PIN_LEAF,
                pinnedFingerprints = setOf(CertificateFingerprint.sha256(TestCertificates.selfSigned)),
            )

        // No issuer, no anchor, nothing to build a path from — and still accepted.
        manager.checkServerTrusted(arrayOf(TestCertificates.selfSigned), AUTH_TYPE)
        assertTrue(manager.acceptedIssuers.isEmpty())
    }

    @Test
    fun `PIN_LEAF ignores expiry, which pre-flight reports separately`() {
        val manager =
            TrustManagerFactoryProvider.create(
                TrustPolicy.PIN_LEAF,
                pinnedFingerprints = setOf(CertificateFingerprint.sha256(TestCertificates.expired)),
            )

        manager.checkServerTrusted(arrayOf(TestCertificates.expired), AUTH_TYPE)
    }

    @Test
    fun `PIN_LEAF rejects a server that presents nothing`() {
        val manager = TrustManagerFactoryProvider.create(TrustPolicy.PIN_LEAF, pinnedFingerprints = setOf("ab".repeat(32)))

        val thrown =
            assertThrows(CertificatePinMismatchException::class.java) {
                manager.checkServerTrusted(emptyArray(), AUTH_TYPE)
            }

        assertEquals(null, thrown.presentedSha256)
    }

    @Test
    fun `INSECURE cannot be built unless the caller says it is a debug build`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                TrustManagerFactoryProvider.create(TrustPolicy.INSECURE)
            }

        assertTrue(thrown.message.orEmpty().contains("release"))
    }

    @Test
    fun `INSECURE accepts anything once explicitly allowed`() {
        val manager = TrustManagerFactoryProvider.create(TrustPolicy.INSECURE, allowInsecure = true)

        manager.checkServerTrusted(arrayOf(TestCertificates.expired), AUTH_TYPE)
        manager.checkServerTrusted(emptyArray(), AUTH_TYPE)
    }

    @Test
    fun `a profile asking for INSECURE in a release build is downgraded, not obeyed`() {
        var downgrade: Pair<TrustPolicy, TrustPolicy>? = null

        val effective =
            TrustManagerFactoryProvider.effectivePolicy(TrustPolicy.INSECURE, allowInsecure = false) { from, to ->
                downgrade = from to to
            }

        assertEquals(TrustPolicy.SYSTEM_PLUS_CUSTOM, effective)
        assertEquals(TrustPolicy.INSECURE to TrustPolicy.SYSTEM_PLUS_CUSTOM, downgrade)
    }

    @Test
    fun `a debug build keeps INSECURE, and nothing is logged`() {
        var downgraded = false

        val effective =
            TrustManagerFactoryProvider.effectivePolicy(TrustPolicy.INSECURE, allowInsecure = true) { _, _ ->
                downgraded = true
            }

        assertEquals(TrustPolicy.INSECURE, effective)
        assertFalse(downgraded)
    }

    @Test
    fun `every other policy passes through effectivePolicy untouched`() {
        TrustPolicy.entries.filter { it != TrustPolicy.INSECURE }.forEach { policy ->
            assertEquals(policy, TrustManagerFactoryProvider.effectivePolicy(policy, allowInsecure = false))
        }
    }

    @Test
    fun `a chain-building policy with no certificates is refused rather than silently trusting nothing`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrustManagerFactoryProvider.create(TrustPolicy.CUSTOM_ONLY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustManagerFactoryProvider.create(TrustPolicy.SYSTEM_PLUS_CUSTOM)
        }
    }

    @Test
    fun `pinning with no fingerprints is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrustManagerFactoryProvider.create(TrustPolicy.PIN_LEAF)
        }
    }

    @Test
    fun `every policy produces a usable trust manager when given what it needs`() {
        val built =
            TrustPolicy.entries.map { policy ->
                TrustManagerFactoryProvider.create(
                    policy,
                    customCerts = listOf(TestCertificates.ca),
                    pinnedFingerprints = setOf(CertificateFingerprint.sha256(TestCertificates.selfSigned)),
                    allowInsecure = true,
                )
            }

        assertEquals(TrustPolicy.entries.size, built.size)
        built.forEach(::assertNotNull)
    }

    private fun alwaysFails(message: String) = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException(message)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException(message)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val AUTH_TYPE = "RSA"
    }
}
