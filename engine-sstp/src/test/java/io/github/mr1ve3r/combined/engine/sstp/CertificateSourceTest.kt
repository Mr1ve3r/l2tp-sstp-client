package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.core.trust.CertificateSummary
import java.security.cert.X509Certificate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the engine and the certificate store.
 *
 * The interesting part is the default on the whole-store methods. A source
 * written before those existed keeps compiling, and answers "nothing" -- which
 * makes the pre-flight refuse for an empty store. The alternative default,
 * answering with the selection, would have made such a source quietly widen
 * what a profile trusts, which is the failure mode worth being unable to have.
 */
class CertificateSourceTest {
    @Test
    fun `a source that predates the whole-store methods reports an empty store`() = runTest {
        val selectionOnly =
            object : CertificateSource {
                override suspend fun certificatesFor(ids: List<String>): List<X509Certificate> = emptyList()

                override suspend fun summariesFor(ids: List<String>): Map<String, CertificateSummary> = emptyMap()
            }

        assertTrue(selectionOnly.allCertificates().isEmpty())
        assertTrue(selectionOnly.allSummaries().isEmpty())
    }

    @Test
    fun `the empty source answers nothing to every question`() = runTest {
        assertEquals(emptyList<X509Certificate>(), CertificateSource.EMPTY.certificatesFor(listOf("a")))
        assertEquals(emptyMap<String, CertificateSummary>(), CertificateSource.EMPTY.summariesFor(listOf("a")))
        assertEquals(emptyList<X509Certificate>(), CertificateSource.EMPTY.allCertificates())
        assertEquals(emptyMap<String, CertificateSummary>(), CertificateSource.EMPTY.allSummaries())
    }
}
