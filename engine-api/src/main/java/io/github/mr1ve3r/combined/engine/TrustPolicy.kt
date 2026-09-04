package io.github.mr1ve3r.combined.engine

/**
 * How an SSTP profile decides whether to trust the server's certificate.
 *
 * Declared here so profiles can carry it; the trust managers that implement it
 * live in `core-trust`.
 */
enum class TrustPolicy {
    /** System trust store only. The right choice for a certificate from a public CA. */
    SYSTEM,

    /** System trust store first; on failure, the certificates selected in the profile. */
    SYSTEM_PLUS_CUSTOM,

    /** Only the certificates selected in the profile — a private CA on a MikroTik, say. */
    CUSTOM_ONLY,

    /**
     * Compare the SHA-256 fingerprint of the leaf certificate against the
     * profile's pins and ignore the chain entirely. Intended for self-signed
     * certificates.
     */
    PIN_LEAF,

    /**
     * No verification at all.
     *
     * Debug builds only. A release build must not offer this, and a profile
     * loaded with this value is forced down to [SYSTEM_PLUS_CUSTOM] with a log
     * entry (SPEC phase 5.5).
     */
    INSECURE,
}
