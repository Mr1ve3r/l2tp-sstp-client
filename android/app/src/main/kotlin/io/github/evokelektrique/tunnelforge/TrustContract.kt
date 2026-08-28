package io.github.evokelektrique.tunnelforge

/**
 * Method channel contract for the server certificate store (SPEC phase 5).
 *
 * Certificates never cross this channel as anything but PEM text and metadata:
 * the store lives on the Kotlin side, and Flutter holds identifiers and the
 * fields it displays.
 */
object TrustContract {
    const val METHOD_CHANNEL = "io.github.evokelektrique.tunnelforge/trust"

    /** Flutter -> host: every stored certificate, newest import first. */
    const val LIST_CERTIFICATES = "listCertificates"

    /** Flutter -> host: open the document picker and read what was chosen (SPEC 5.3 A). */
    const val PICK_CERTIFICATE_FILE = "pickCertificateFile"

    /** Flutter -> host: read certificates out of pasted text (SPEC 5.3 B). */
    const val PARSE_PEM_TEXT = "parsePemText"

    /** Flutter -> host: download the chain a server presents (SPEC 5.3 C). */
    const val FETCH_SERVER_CHAIN = "fetchServerChain"

    /** Flutter -> host: store the candidates the user accepted. */
    const val IMPORT_CERTIFICATES = "importCertificates"

    const val DELETE_CERTIFICATE = "deleteCertificate"
    const val RENAME_CERTIFICATE = "renameCertificate"

    /** Flutter -> host: the stored PEM, for sharing or saving elsewhere. */
    const val EXPORT_CERTIFICATE = "exportCertificate"

    /** Flutter -> host: which trust policies this build offers (SPEC 5.5). */
    const val LIST_TRUST_POLICIES = "listTrustPolicies"

    const val ARG_ID = "id"
    const val ARG_IDS = "ids"
    const val ARG_ALIAS = "alias"
    const val ARG_TEXT = "text"
    const val ARG_HOST = "host"
    const val ARG_PORT = "port"
    const val ARG_CERTIFICATES = "certificates"
    const val ARG_PEM = "pem"

    /** Keys of one certificate map, shared by candidates and stored entries. */
    const val FIELD_ID = "id"
    const val FIELD_ALIAS = "alias"
    const val FIELD_SUBJECT_CN = "subjectCn"
    const val FIELD_SUBJECT_DN = "subjectDn"
    const val FIELD_ISSUER_DN = "issuerDn"
    const val FIELD_SERIAL_NUMBER = "serialNumber"
    const val FIELD_NOT_BEFORE = "notBefore"
    const val FIELD_NOT_AFTER = "notAfter"
    const val FIELD_SHA256 = "sha256Fingerprint"
    const val FIELD_SHA1 = "sha1Fingerprint"
    const val FIELD_IS_CA = "isCa"
    const val FIELD_KEY_USAGE = "keyUsage"
    const val FIELD_SUBJECT_ALT_NAMES = "subjectAltNames"
    const val FIELD_PUBLIC_KEY_BITS = "publicKeyBits"
    const val FIELD_SIGNATURE_ALGORITHM = "signatureAlgorithm"
    const val FIELD_IMPORTED_AT = "importedAt"
    const val FIELD_USAGE_COUNT = "usageCount"
    const val FIELD_WARNINGS = "warnings"
    const val FIELD_PEM = "pem"
    const val FIELD_CHAIN_POSITION = "chainPosition"

    /** Keys of one warning map (SPEC 5.4). */
    const val FIELD_WARNING_KEY = "key"
    const val FIELD_WARNING_DETAIL = "detail"

    /** Error codes returned to Flutter. */
    const val ERROR_BAD_ARGS = "bad_args"
    const val ERROR_PARSE_FAILED = "certificate_parse_failed"
    const val ERROR_READ_FAILED = "certificate_read_failed"
    const val ERROR_FETCH_FAILED = "certificate_fetch_failed"
    const val ERROR_STORE_FAILED = "certificate_store_failed"

    /** MIME types the document picker offers (SPEC 5.3 A). */
    val CERTIFICATE_MIME_TYPES =
        arrayOf(
            "application/x-x509-ca-cert",
            "application/x-pem-file",
            "application/pkix-cert",
            "application/octet-stream",
            "text/plain",
            "*/*",
        )
}
