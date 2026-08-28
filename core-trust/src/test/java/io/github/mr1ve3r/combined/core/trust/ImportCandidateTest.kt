package io.github.mr1ve3r.combined.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the user is shown before an import is confirmed (SPEC 5.3, 5.4). */
class ImportCandidateTest {
    @Test
    fun `every certificate in a bundle becomes a candidate`() {
        val candidates = ImportCandidate.of(TestCertificates.bundle, now = NOW)

        assertEquals(TestCertificates.bundle.map { CertificateFingerprint.sha256(it) }, candidates.map { it.summary.id })
    }

    @Test
    fun `a candidate carries the PEM that will be stored`() {
        val candidate = ImportCandidate.of(listOf(TestCertificates.selfSigned), now = NOW).single()

        assertEquals(CertificateParser.toPem(TestCertificates.selfSigned), candidate.pem)
    }

    @Test
    fun `warnings are attached so the choice is made with them on screen`() {
        val candidate = ImportCandidate.of(listOf(TestCertificates.expired), now = NOW).single()

        assertTrue(candidate.warnings.any { it is CertificateWarning.Expired })
    }

    @Test
    fun `a certificate already in the store says so`() {
        val id = CertificateFingerprint.sha256(TestCertificates.ca)

        val candidate = ImportCandidate.of(listOf(TestCertificates.ca), now = NOW, alreadyImportedIds = setOf(id)).single()

        assertTrue(CertificateWarning.AlreadyImported in candidate.warnings)
    }

    @Test
    fun `chain positions are numbered from the leaf when the certificates form a chain`() {
        val candidates = ImportCandidate.of(TestCertificates.bundle, now = NOW, withChainPositions = true)

        assertEquals(listOf(0, 1), candidates.map { it.chainPosition })
    }

    @Test
    fun `a picked file has no chain position, because its order means nothing`() {
        val candidate = ImportCandidate.of(listOf(TestCertificates.ca), now = NOW).single()

        assertNull(candidate.chainPosition)
    }

    private companion object {
        /** A moment inside the fixtures' validity window, derived so it cannot go stale. */
        val NOW = TestCertificates.leafSignedByCa.notBefore.time + 86_400_000L
    }
}
