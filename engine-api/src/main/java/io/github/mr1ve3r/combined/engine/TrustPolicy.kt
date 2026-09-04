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
     * Every certificate in the application store is offered as a trust anchor,
     * and nothing has to be selected on the profile.
     *
     * For the common case: the user has imported their server's certificate
     * authority and should not also have to work out which entry in the list it
     * is. The cost is real, and is why this is not a default -- a certificate
     * imported for one server can vouch for another. The log names whichever
     * one actually did.
     */
    STORE_AUTO,

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
