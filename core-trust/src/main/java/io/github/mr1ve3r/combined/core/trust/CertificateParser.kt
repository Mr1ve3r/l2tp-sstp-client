package io.github.mr1ve3r.combined.core.trust

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Reads X.509 certificates from whatever the user hands over.
 *
 * `CertificateFactory.generateCertificates` already copes with PEM, DER and a
 * bundle holding several certificates, so the work here is turning its failures
 * into something a user can act on, and normalising pasted text before it gets
 * that far.
 */
object CertificateParser {
    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"
    private const val PEM_LINE_LENGTH = 64

    /**
     * Parses every certificate in [bytes].
     *
     * @return the certificates, in file order. A bundle yields more than one,
     *   and the caller is expected to let the user choose (SPEC 5.3 A).
     * @throws CertificateParseException if nothing could be read.
     */
    fun parse(bytes: ByteArray): List<X509Certificate> = parse(ByteArrayInputStream(bytes))

    /**
     * Parses every certificate in [input]. The stream is consumed but not closed.
     *
     * @throws CertificateParseException if nothing could be read.
     */
    fun parse(input: InputStream): List<X509Certificate> {
        val certificates =
            try {
                CertificateFactory.getInstance("X.509").generateCertificates(input)
            } catch (e: CertificateException) {
                throw CertificateParseException("The file is not a readable X.509 certificate", e)
            }
        if (certificates.isEmpty()) {
            throw CertificateParseException("The file contained no certificates")
        }
        return certificates.filterIsInstance<X509Certificate>().also {
            if (it.isEmpty()) {
                throw CertificateParseException("The file contained certificates, but none of them X.509")
            }
        }
    }

    /**
     * Parses certificates from text pasted by the user (SPEC 5.3 B).
     *
     * Line endings are normalised and surrounding whitespace trimmed first,
     * because text arriving through a clipboard rarely survives intact. The
     * absence of a PEM header is reported as such rather than as a parse
     * failure, since it is the mistake people actually make.
     *
     * @throws CertificateParseException if the text holds no PEM block, or the
     *   block does not parse.
     */
    fun parsePem(text: String): List<X509Certificate> {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (!normalised.contains(PEM_BEGIN)) {
            throw CertificateParseException("No \"$PEM_BEGIN\" block found in the pasted text")
        }
        return parse(normalised.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Renders [certificate] as PEM.
     *
     * The store keeps PEM regardless of the format a certificate arrived in, so
     * that an exported file is always the same shape (SPEC 5.2).
     */
    fun toPem(certificate: X509Certificate): String = buildString {
        appendLine(PEM_BEGIN)
        Base64.getEncoder().encodeToString(certificate.encoded).chunked(PEM_LINE_LENGTH).forEach(::appendLine)
        appendLine(PEM_END)
    }
}

/** Thrown when input could not be read as one or more X.509 certificates. */
class CertificateParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
