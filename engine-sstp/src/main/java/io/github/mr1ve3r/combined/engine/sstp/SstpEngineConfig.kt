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

import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.ProxyConfig
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy

/**
 * Everything the SSTP clients are allowed to read, fixed for the whole session.
 *
 * This is the first third of what upstream Open SSTP Client kept in
 * `SharedBridge` (SPEC 6.3). Upstream every client reached into
 * `SharedPreferences` through the bridge whenever it wanted a setting, which is
 * why two profiles could never run at once and why no client could be tested on
 * its own. Here the settings are read once, from [EngineProfile.Sstp], and
 * handed down as an immutable value.
 *
 * @property mru largest frame this client will accept. [EngineProfile.Sstp]
 *   carries a single MTU where upstream had separate MRU and MTU knobs; one
 *   number for both is what a user can reason about, and PPP negotiates the
 *   direction that matters anyway.
 * @property verificationHostname the name the certificate is checked against
 *   and the SNI sent in the handshake.
 * @property ipv6Enabled whether to negotiate IPv6CP. Off: [TunnelParams][
 *   io.github.mr1ve3r.combined.engine.TunnelParams] carries one address, so a
 *   negotiated IPv6 identifier would have nowhere to go. Kept as a field
 *   because the negotiation code is imported and ready for the contract to grow
 *   a second address.
 */
internal class SstpEngineConfig(
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
    val mtu: Int,
    val mru: Int,
    val customDns: List<String>,
    val expectedHostname: String?,
    val minTlsVersion: TlsVersion,
    val authMethods: Set<PppAuthMethod>,
    val proxy: ProxyConfig?,
    val trustPolicy: TrustPolicy,
    val trustedCertificateIds: List<String>,
    val pinnedFingerprints: Set<String>,
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
) {
    val verificationHostname: String get() = expectedHostname ?: server

    /** Whether the profile offers [method] during LCP negotiation. */
    fun isEnabled(method: PppAuthMethod): Boolean = method in authMethods

    companion object {
        /** Smallest MRU PPP allows to be negotiated. */
        const val MIN_MRU: Int = 68

        /** Largest MRU this engine will accept, and the size the receive buffer is cut for. */
        const val MAX_MRU: Int = 2000

        /** The MRU a peer is assumed to use when it names none. */
        const val DEFAULT_MRU: Int = 1500

        /**
         * Reads a profile into the shape the clients want.
         *
         * The MTU is clamped rather than rejected: a profile with a nonsensical
         * value is a settings problem, and refusing to connect over it would be
         * a worse answer than connecting with a usable frame size.
         */
        fun of(profile: EngineProfile.Sstp): SstpEngineConfig {
            val mtu = profile.mtu.coerceIn(MIN_MRU, MAX_MRU)

            return SstpEngineConfig(
                server = profile.server,
                port = profile.port,
                username = profile.username,
                password = profile.password,
                mtu = mtu,
                mru = mtu,
                customDns = profile.customDns,
                expectedHostname = profile.expectedHostname?.takeIf { it.isNotBlank() },
                minTlsVersion = profile.minTlsVersion,
                authMethods = profile.pppAuthMethods,
                proxy = profile.proxy,
                trustPolicy = profile.trustPolicy,
                trustedCertificateIds = profile.trustedCertificateIds,
                pinnedFingerprints = profile.pinnedFingerprints,
            )
        }
    }
}
