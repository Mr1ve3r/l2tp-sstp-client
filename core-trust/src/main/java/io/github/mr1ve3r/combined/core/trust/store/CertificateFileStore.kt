package io.github.mr1ve3r.combined.core.trust.store

import io.github.mr1ve3r.combined.core.trust.CertificateFingerprint
import io.github.mr1ve3r.combined.core.trust.CertificateParseException
import io.github.mr1ve3r.combined.core.trust.CertificateParser
import java.io.File
import java.io.IOException
import java.security.cert.X509Certificate

/**
 * Keeps the certificates themselves, as PEM, inside the application's own
 * storage (SPEC 5.2).
 *
 * Two rules shape this class. The first is that nothing outside is referenced:
 * no `Uri` to a document the user picked, only a copy. An always-on VPN starts
 * before the device is unlocked, and at that point a document provider is not
 * there to answer; a certificate the tunnel cannot read is a tunnel that cannot
 * start. The second is that the file name is the fingerprint, so the name of a
 * file and the identity of what is in it cannot drift apart.
 *
 * Storage is always PEM even when the import was DER, so an export gives back
 * one predictable shape.
 *
 * @param directory `filesDir/trust`. Created on first write with owner-only
 *   permissions.
 */
class CertificateFileStore(private val directory: File) {
    /**
     * Writes [certificate] and returns the file it landed in.
     *
     * Writing the same certificate again rewrites the same file with the same
     * bytes, which is what makes a re-import an alias change and nothing more.
     */
    @Throws(IOException::class)
    fun write(certificate: X509Certificate): File {
        prepareDirectory()
        val file = File(directory, fileNameFor(CertificateFingerprint.sha256(certificate)))
        file.writeText(CertificateParser.toPem(certificate))
        restrictToOwner(file)
        return file
    }

    /**
     * Reads back the certificate stored under [id].
     *
     * @return the certificate, or `null` if the file is gone. A missing file is
     *   a normal outcome — the pre-flight check reports it as a profile
     *   referring to a certificate that no longer exists.
     * @throws CertificateParseException if the file is there but unreadable as
     *   PEM, which means something outside this class has written to it.
     */
    fun read(id: String): X509Certificate? {
        val file = File(directory, fileNameFor(id))
        if (!file.isFile) return null
        return CertificateParser.parse(file.readBytes()).firstOrNull()
    }

    /** The stored PEM text for [id], or `null` if it is not there. Used for export. */
    fun readPem(id: String): String? {
        val file = File(directory, fileNameFor(id))
        return if (file.isFile) file.readText() else null
    }

    /** Removes the file for [id]. Returns whether there was one to remove. */
    fun delete(id: String): Boolean = File(directory, fileNameFor(id)).delete()

    /** Ids of every certificate on disk, whether or not the database knows about them. */
    fun storedIds(): List<String> = directory
        .listFiles { file -> file.isFile && file.name.endsWith(PEM_EXTENSION) }
        .orEmpty()
        .map { it.name.removeSuffix(PEM_EXTENSION) }

    private fun prepareDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the certificate directory at $directory")
        }
        restrictToOwner(directory)
    }

    // 0700 on the directory, 0600 on the files. Application-private storage is
    // already unreadable by other applications, so this guards against the
    // remaining case: another process running as the same user, which on a
    // rooted device is not hypothetical.
    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (file.isDirectory) {
            file.setExecutable(true, true)
        }
    }

    companion object {
        private const val PEM_EXTENSION = ".pem"

        /** Directory name under `filesDir`. */
        const val DIRECTORY_NAME: String = "trust"

        /** File name a certificate with fingerprint [id] is stored under. */
        fun fileNameFor(id: String): String = "$id$PEM_EXTENSION"
    }
}
