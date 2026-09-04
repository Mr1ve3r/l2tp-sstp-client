package io.github.mr1ve3r.combined.core.trust.store

import io.github.mr1ve3r.combined.core.trust.CertificateFingerprint
import io.github.mr1ve3r.combined.core.trust.CertificateParser
import io.github.mr1ve3r.combined.core.trust.TestCertificates
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The PEM file half of the store (SPEC 5.2). */
class CertificateFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val store: CertificateFileStore get() = CertificateFileStore(directory)

    private val directory: File get() = File(temporaryFolder.root, "trust")

    @Test
    fun `a certificate is stored under its own fingerprint`() {
        val certificate = TestCertificates.selfSigned

        val file = store.write(certificate)

        assertEquals("${CertificateFingerprint.sha256(certificate)}.pem", file.name)
        assertEquals(directory, file.parentFile)
    }

    @Test
    fun `the directory is created on first write`() {
        assertFalse(directory.exists())

        store.write(TestCertificates.selfSigned)

        assertTrue(directory.isDirectory)
    }

    @Test
    fun `a certificate imported as DER is stored as PEM`() {
        val certificate = CertificateParser.parse(TestCertificates.selfSignedDer).single()

        val file = store.write(certificate)

        assertTrue(file.readText().startsWith("-----BEGIN CERTIFICATE-----"))
    }

    @Test
    fun `a stored certificate reads back byte for byte`() {
        val certificate = TestCertificates.leafSignedByCa
        val fileStore = store

        fileStore.write(certificate)

        assertEquals(certificate, fileStore.read(CertificateFingerprint.sha256(certificate)))
    }

    @Test
    fun `writing the same certificate twice leaves one file`() {
        val fileStore = store

        fileStore.write(TestCertificates.selfSigned)
        fileStore.write(TestCertificates.selfSigned)

        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `reading a certificate that was never stored returns null`() {
        assertNull(store.read("0".repeat(64)))
    }

    @Test
    fun `deleting removes the file and reports whether there was one`() {
        val fileStore = store
        val id = CertificateFingerprint.sha256(TestCertificates.selfSigned)
        fileStore.write(TestCertificates.selfSigned)

        assertTrue(fileStore.delete(id))
        assertFalse(fileStore.delete(id))
        assertNull(fileStore.read(id))
    }

    @Test
    fun `stored ids list what is on disk`() {
        val fileStore = store
        fileStore.write(TestCertificates.ca)
        fileStore.write(TestCertificates.selfSigned)

        assertEquals(
            listOf(TestCertificates.ca, TestCertificates.selfSigned).map(CertificateFingerprint::sha256).sorted(),
            fileStore.storedIds().sorted(),
        )
    }

    @Test
    fun `the exported PEM is what was written`() {
        val fileStore = store
        val id = CertificateFingerprint.sha256(TestCertificates.ca)
        fileStore.write(TestCertificates.ca)

        assertEquals(CertificateParser.toPem(TestCertificates.ca), fileStore.readPem(id))
    }

    // Directory permissions are not asserted here: `File.setReadable(false,
    // false)` is a no-op on the Windows filesystems some contributors build on,
    // and the assertion would fail for reasons that have nothing to do with the
    // code. The instrumentation test checks it on a device, where it matters.
}
