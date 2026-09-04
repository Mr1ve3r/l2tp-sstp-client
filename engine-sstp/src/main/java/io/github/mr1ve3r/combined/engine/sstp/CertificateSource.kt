/*
 * Derived from Open SSTP Client
 * https://github.com/kittoku/Open-SSTP-Client
 * Copyright (c) 2019 KOBAYASHI Ittoku
 * Licensed under the MIT License.
 * See third_party/open-sstp-client/LICENSE for the full text.
 *
 * Modifications Copyright (C) 2026 Mr1ve3r
 * Licensed under GPL-3.0-or-later as part of this project.
 */
package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.core.trust.CertificateSummary
import io.github.mr1ve3r.combined.core.trust.store.TrustStore
import java.security.cert.X509Certificate

/**
 * Where the engine gets the certificates a profile has selected.
 *
 * An interface rather than `TrustStore` itself for two reasons: the engine has
 * no `Context` to build a Room database with, and the pre-flight and trust
 * paths are worth testing without one.
 */
interface CertificateSource {
    /** The certificates behind [ids], in whatever order the store returns them. */
    suspend fun certificatesFor(ids: List<String>): List<X509Certificate>

    /**
     * What the store knows about [ids], for the pre-flight check.
     *
     * Ids the store no longer holds are simply absent from the result, which is
     * how [TrustPreflight][io.github.mr1ve3r.combined.core.trust.TrustPreflight]
     * notices a certificate that has been deleted out from under a profile.
     */
    suspend fun summariesFor(ids: List<String>): Map<String, CertificateSummary>

    companion object {
        /**
         * A source that holds nothing.
         *
         * The right value for a profile on [TrustPolicy.SYSTEM][
         * io.github.mr1ve3r.combined.engine.TrustPolicy.SYSTEM] or
         * [PIN_LEAF][io.github.mr1ve3r.combined.engine.TrustPolicy.PIN_LEAF],
         * neither of which consults the store.
         */
        val EMPTY: CertificateSource =
            object : CertificateSource {
                override suspend fun certificatesFor(ids: List<String>): List<X509Certificate> = emptyList()

                override suspend fun summariesFor(ids: List<String>): Map<String, CertificateSummary> = emptyMap()
            }
    }
}

/** The real source: the `core-trust` store the certificates screen writes to. */
class TrustStoreCertificateSource(private val store: TrustStore) : CertificateSource {
    override suspend fun certificatesFor(ids: List<String>): List<X509Certificate> = store.certificatesFor(ids)

    override suspend fun summariesFor(ids: List<String>): Map<String, CertificateSummary> = store.summariesFor(ids)
}
