package io.github.mr1ve3r.combined.engine

/**
 * Minimum TLS version an SSTP profile will negotiate, applied through
 * `SSLSocket.setEnabledProtocols`.
 *
 * @property protocolName the name the JSSE provider uses.
 */
enum class TlsVersion(val protocolName: String) {
    /** TLS 1.2. The lowest value this project offers (SPEC appendix А). */
    TLS_1_2("TLSv1.2"),

    /** TLS 1.3. */
    TLS_1_3("TLSv1.3"),
    ;

    companion object {
        /** Default for new SSTP profiles. */
        val DEFAULT: TlsVersion = TLS_1_2
    }
}
