package io.github.mr1ve3r.combined.core.trust.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mr1ve3r.combined.core.trust.CertificateFingerprint
import io.github.mr1ve3r.combined.core.trust.CertificateParser
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.security.cert.X509Certificate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store on a real device: Room, the filesystem, and the two staying in step
 * across a restart (SPEC 5.10).
 *
 * The database is closed and reopened between the write and the read, which is
 * what "restart the application" amounts to for storage. Anything that only
 * lived in memory disappears at that point.
 */
@RunWith(AndroidJUnit4::class)
class TrustStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "trust-test")
    private lateinit var database: TrustDatabase
    private lateinit var store: TrustStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        directory.deleteRecursively()
        openStore()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        directory.deleteRecursively()
    }

    @Test
    fun anImportedCertificateSurvivesARestart() = runBlocking {
        val certificate = certificate(SELF_SIGNED_PEM)

        store.import(certificate, alias = "MikroTik", now = NOW)
        restart()

        val stored = store.list().single()
        assertEquals("MikroTik", stored.alias)
        assertEquals(CertificateFingerprint.sha256(certificate), stored.id)
        assertEquals(certificate, store.certificatesFor(listOf(stored.id)).single())
    }

    @Test
    fun theCertificateIsStoredAsPemUnderItsFingerprint() = runBlocking {
        val certificate = certificate(SELF_SIGNED_PEM)

        val stored = store.import(certificate, alias = "", now = NOW)

        val file = File(directory, "${stored.id}.pem")
        assertTrue(file.isFile)
        assertEquals(CertificateParser.toPem(certificate), file.readText())
    }

    @Test
    fun theStoreDirectoryIsReachableOnlyByItsOwner() = runBlocking {
        val stored = store.import(certificate(SELF_SIGNED_PEM), alias = "", now = NOW)

        // 0700 on the directory and 0600 on the file, as SPEC 5.2 asks. Reading
        // the mode bits is the only honest check: `File.canRead` answers for the
        // current process, which is the owner and can always read.
        val group = setOf(GROUP_READ, GROUP_WRITE, GROUP_EXECUTE)
        val others = setOf(OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE)
        val directoryMode = Files.getPosixFilePermissions(directory.toPath())
        val fileMode = Files.getPosixFilePermissions(File(directory, "${stored.id}.pem").toPath())

        assertTrue(directoryMode.containsAll(setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)))
        assertTrue((directoryMode intersect (group + others)).isEmpty())
        assertTrue((fileMode intersect (group + others)).isEmpty())
    }

    @Test
    fun reimportingTheSameCertificateUpdatesTheAliasRatherThanDuplicating() = runBlocking {
        val certificate = certificate(SELF_SIGNED_PEM)
        store.import(certificate, alias = "first", now = NOW)

        store.import(certificate, alias = "second", now = NOW + 1_000)

        val stored = store.list().single()
        assertEquals("second", stored.alias)
        assertEquals(NOW, stored.importedAt)
    }

    @Test
    fun anEmptyAliasFallsBackToTheCommonName() = runBlocking {
        val stored = store.import(certificate(SELF_SIGNED_PEM), alias = "   ", now = NOW)

        assertEquals("mikrotik.local", stored.alias)
    }

    @Test
    fun deletingRemovesTheRowTheFileAndTheProfileReferences() = runBlocking {
        val stored = store.import(certificate(SELF_SIGNED_PEM), alias = "", now = NOW)
        store.setCertificatesFor(PROFILE_ID, listOf(stored.id))

        assertTrue(store.delete(stored.id))
        restart()

        assertNull(store.find(stored.id))
        assertFalse(File(directory, "${stored.id}.pem").exists())
        assertEquals(emptyList<String>(), store.certificateIdsFor(PROFILE_ID))
    }

    @Test
    fun aProfileSelectionSurvivesARestartAndIsCountedOnTheCertificate() = runBlocking {
        val stored = store.import(certificate(SELF_SIGNED_PEM), alias = "", now = NOW)

        store.setCertificatesFor(PROFILE_ID, listOf(stored.id))
        restart()

        assertEquals(listOf(stored.id), store.certificateIdsFor(PROFILE_ID))
        assertEquals(listOf(PROFILE_ID), store.profileIdsUsing(stored.id))
        assertEquals(1, store.list().single().usageCount)
    }

    @Test
    fun subjectAlternativeNamesSurviveTheJsonConverter() = runBlocking {
        val stored = store.import(certificate(SAN_PEM), alias = "", now = NOW)

        restart()

        assertEquals(CertificateFingerprint.sha256(certificate(SAN_PEM)), stored.id)
        assertEquals(
            listOf("DNS:vpn.example.com", "DNS:vpn.internal.lan"),
            store.list().single().summary.subjectAltNames,
        )
    }

    @Test
    fun exportGivesBackThePemThatWasImported() = runBlocking {
        val stored = store.import(certificate(SELF_SIGNED_PEM), alias = "", now = NOW)

        assertNotNull(store.exportPem(stored.id))
        assertEquals(CertificateParser.toPem(certificate(SELF_SIGNED_PEM)), store.exportPem(stored.id))
    }

    /** Closes and reopens everything, which is what a restart leaves behind. */
    private fun restart() {
        database.close()
        openStore()
    }

    private fun openStore() {
        database = androidx.room.Room
            .databaseBuilder(context, TrustDatabase::class.java, DATABASE_NAME)
            .build()
        store = TrustStore(database.serverCertificates(), CertificateFileStore(directory))
    }

    /** Reads a fixture out of the test assets. The same files the JVM tests use. */
    private fun certificate(assetName: String): X509Certificate = context.assets
        .open("certs/$assetName")
        .use { CertificateParser.parse(it).first() }

    private companion object {
        const val DATABASE_NAME = "trust-instrumented-test.db"
        const val PROFILE_ID = "profile-1"
        const val NOW = 1_780_000_000_000L
        const val SELF_SIGNED_PEM = "self-signed.pem"
        const val SAN_PEM = "leaf-signed-by-ca.pem"
    }
}
