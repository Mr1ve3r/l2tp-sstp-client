package io.github.mr1ve3r.combined.core.trust

import io.github.mr1ve3r.combined.core.profile.FailoverGroup
import io.github.mr1ve3r.combined.core.profile.PerAppMode
import io.github.mr1ve3r.combined.core.profile.ProfileConverters
import io.github.mr1ve3r.combined.core.profile.VpnProfile
import io.github.mr1ve3r.combined.core.trust.store.CertificateWithUsage
import io.github.mr1ve3r.combined.core.trust.store.ServerCertificateEntity
import io.github.mr1ve3r.combined.core.trust.store.StoredCertificate
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure mappings between what the user sees and what the database holds.
 *
 * None of this needs a device: these are the parts of the store that are
 * arithmetic and field copying rather than SQLite, and they are where an
 * off-by-one field or a silently dropped enum would hide. The instrumented
 * tests cover the SQL; this covers what is handed to it.
 */
class StoreMappingTest {
    @Test
    fun `a certificate row round-trips through the summary the UI shows`() {
        val summary = CertificateSummary.of(TestCertificates.leafSignedByCa)

        val row = ServerCertificateEntity.of(summary, alias = "Work leaf", importedAt = 1_700_000_000_000)

        assertEquals("Work leaf", row.alias)
        assertEquals(1_700_000_000_000, row.importedAt)
        // Every displayed field survives the trip. A data-class comparison is
        // the assertion because adding a column to the entity without carrying
        // it in `of` is exactly the mistake worth catching, and it fails here.
        assertEquals(summary, row.toSummary())
    }

    @Test
    fun `a stored certificate carries the usage count the query computed`() {
        val summary = CertificateSummary.of(TestCertificates.ca)
        val row = ServerCertificateEntity.of(summary, alias = "Work CA", importedAt = 42)

        val stored = StoredCertificate.of(CertificateWithUsage(certificate = row, usageCount = 3))

        assertEquals(summary, stored.summary)
        assertEquals("Work CA", stored.alias)
        assertEquals(42, stored.importedAt)
        // The count is what the delete confirmation warns with -- "used by 3
        // profiles" is the whole reason the query joins.
        assertEquals(3, stored.usageCount)
    }

    @Test
    fun `a failover budget outside the usable range falls back to the default`() {
        assertEquals(15, FailoverGroup.normalizeTimeout(15))
        assertEquals(5, FailoverGroup.normalizeTimeout(FailoverGroup.MIN_CONNECT_TIMEOUT_SEC))
        assertEquals(120, FailoverGroup.normalizeTimeout(FailoverGroup.MAX_CONNECT_TIMEOUT_SEC))

        // Below the floor a member is abandoned before a slow network answers;
        // above the ceiling a group of three outlasts the user's patience.
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, FailoverGroup.normalizeTimeout(0))
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, FailoverGroup.normalizeTimeout(4))
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, FailoverGroup.normalizeTimeout(121))
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC, FailoverGroup.normalizeTimeout(-1))
    }

    @Test
    fun `every secret a profile owns is named, so deleting one leaves nothing`() {
        val refs = VpnProfile.secretRefsFor("id-1")

        assertEquals(
            listOf("profile/id-1/password", "profile/id-1/psk", "profile/id-1/proxyPassword"),
            refs,
        )
        assertEquals(VpnProfile.passwordRefFor("id-1"), refs[0])
        assertEquals(VpnProfile.pskRefFor("id-1"), refs[1])
        assertEquals(VpnProfile.proxyPasswordRefFor("id-1"), refs[2])
    }

    @Test
    fun `enum columns round-trip through their names`() {
        for (value in Protocol.entries) {
            assertEquals(value, ProfileConverters.decodeProtocol(ProfileConverters.encodeProtocol(value)))
        }
        for (value in PerAppMode.entries) {
            assertEquals(value, ProfileConverters.decodePerAppMode(ProfileConverters.encodePerAppMode(value)))
        }
        for (value in TrustPolicy.entries) {
            assertEquals(value, ProfileConverters.decodeTrustPolicy(ProfileConverters.encodeTrustPolicy(value)))
        }
        for (value in TlsVersion.entries) {
            assertEquals(value, ProfileConverters.decodeTlsVersion(ProfileConverters.encodeTlsVersion(value)))
        }
    }

    @Test
    fun `an unreadable enum column decodes to the default rather than throwing`() {
        // A profile written by a newer build, or a constant that was renamed.
        // Room has nowhere to report a failure from a type converter, so the
        // alternative to a fallback is a profile list that cannot be opened.
        assertEquals(Protocol.L2TP, ProfileConverters.decodeProtocol("WIREGUARD"))
        assertEquals(PerAppMode.OFF, ProfileConverters.decodePerAppMode(""))
        assertEquals(TrustPolicy.SYSTEM, ProfileConverters.decodeTrustPolicy("PIN_EVERYTHING"))
        assertEquals(TlsVersion.DEFAULT, ProfileConverters.decodeTlsVersion("TLS_1_4"))

        // Whitespace and case are the shapes a hand-edited backup arrives in.
        assertEquals(Protocol.SSTP, ProfileConverters.decodeProtocol("  sstp  "))
    }
}
