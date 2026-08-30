package io.github.mr1ve3r.combined.core.profile

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.github.mr1ve3r.combined.core.trust.store.StringListConverter
import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy

/** What a profile does with [VpnProfile.appList] (SPEC 8.1, `perAppMode`). */
enum class PerAppMode {
    /** Every application uses the tunnel. */
    OFF,

    /** Only the listed applications use the tunnel. */
    INCLUDE,

    /** Every application except the listed ones uses the tunnel. */
    EXCLUDE,
}

/**
 * One saved connection (SPEC 8.1).
 *
 * The row holds no secret. The password, the pre-shared key, and the proxy
 * password live in [ProfileSecrets] under the reference columns, so a database
 * that leaks — a backup, a debug dump, a `bugreport` — leaks a server name and
 * a username and nothing that opens a session.
 *
 * Both protocols share one table rather than one table each. The fields that
 * only one of them uses stay at their defaults for the other, which is what
 * makes it possible to switch a profile's protocol without losing the rest of
 * it, and what keeps every query in [ProfileDao] protocol-blind.
 *
 * Which certificates an SSTP profile trusts is not here: that is a many-to-many
 * relation and it already has its table
 * ([io.github.mr1ve3r.combined.core.trust.store.ProfileCertificateRef]).
 *
 * @property createdAt milliseconds since the epoch. A table has no order of its
 *   own, and the list the user sees should not reshuffle itself when a profile
 *   is edited, so the order is the order they were made in.
 * @property dns1Host the manual DNS servers, in the two ordered slots the
 *   application has always had, each with its own transport. This is SPEC 8.1's
 *   `customDns`; the shape is wider because DNS-over-TLS and DNS-over-HTTPS
 *   need more than an address.
 */
@Entity(tableName = "profiles")
@TypeConverters(StringListConverter::class, ProfileConverters::class)
data class VpnProfile(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: Protocol,
    val server: String,
    val username: String,
    val passwordRef: String,
    val mtu: Int,
    val createdAt: Long,
    val dnsAutomatic: Boolean = true,
    val dns1Host: String = "",
    val dns1Protocol: String = DEFAULT_DNS_PROTOCOL,
    val dns2Host: String = "",
    val dns2Protocol: String = DEFAULT_DNS_PROTOCOL,
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val appList: List<String> = emptyList(),
    val killSwitch: Boolean = false,
    val autoReconnect: Boolean = true,
    // L2TP
    val ipsecEnabled: Boolean = true,
    val pskRef: String = "",
    val localIdentifier: String? = null,
    val phase1Proposals: List<String> = emptyList(),
    val phase2Proposals: List<String> = emptyList(),
    // SSTP
    val port: Int = EngineProfile.Sstp.DEFAULT_PORT,
    val trustPolicy: TrustPolicy = TrustPolicy.SYSTEM,
    val pinnedFingerprints: List<String> = emptyList(),
    val expectedHostname: String? = null,
    val minTlsVersion: TlsVersion = TlsVersion.DEFAULT,
    val pppAuthMethods: List<String> = DEFAULT_AUTH_METHOD_NAMES,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = DEFAULT_PROXY_PORT,
    val proxyUsername: String = "",
    val proxyPasswordRef: String = "",
) {
    /** The applications [perAppMode] applies to, or empty when it applies to none. */
    val activeAppList: List<String>
        get() = if (perAppMode == PerAppMode.OFF) emptyList() else appList

    companion object {
        /** Matches `DnsProtocol.dnsOverUdp` on the Flutter side. */
        const val DEFAULT_DNS_PROTOCOL: String = "dnsOverUdp"

        /** Where an HTTP proxy listens when a profile names no port. */
        const val DEFAULT_PROXY_PORT: Int = 8080

        /** [EngineProfile.Sstp.DEFAULT_AUTH_METHODS] as stored strings. */
        val DEFAULT_AUTH_METHOD_NAMES: List<String> =
            EngineProfile.Sstp.DEFAULT_AUTH_METHODS.map(PppAuthMethod::name)

        /** The reference under which [id]'s PPP password is kept. */
        fun passwordRefFor(id: String): String = "profile/$id/password"

        /** The reference under which [id]'s IPsec pre-shared key is kept. */
        fun pskRefFor(id: String): String = "profile/$id/psk"

        /** The reference under which [id]'s proxy password is kept. */
        fun proxyPasswordRefFor(id: String): String = "profile/$id/proxyPassword"

        /** All three references for [id], for a delete that must leave nothing behind. */
        fun secretRefsFor(id: String): List<String> = listOf(passwordRefFor(id), pskRefFor(id), proxyPasswordRefFor(id))
    }
}
