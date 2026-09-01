package io.github.mr1ve3r.combined.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classification a failover group runs on (SPEC 10.1.2).
 *
 * The four errors the SPEC names are asserted one by one, because they are a
 * requirement and not a judgement call. The rest are asserted as a set, so that
 * a new [EngineError] variant fails this file rather than silently taking a
 * default nobody chose.
 */
class FailoverDecisionTest {
    @Test
    fun `an unreachable server advances to the next member`() {
        assertEquals(FailoverDecision.ADVANCE, EngineError.NetworkUnreachable("no route").failoverDecision)
    }

    @Test
    fun `a timeout advances to the next member`() {
        assertEquals(FailoverDecision.ADVANCE, EngineError.TimedOut("tcp_connect", null).failoverDecision)
    }

    @Test
    fun `an IPsec failure advances to the next member`() {
        assertEquals(FailoverDecision.ADVANCE, EngineError.IpsecFailed("no proposal chosen").failoverDecision)
    }

    @Test
    fun `wrong credentials stop the group`() {
        // SPEC 10.1.2: the credentials are wrong, so every member would reject
        // them, and trying each one spreads failed logins across servers.
        assertEquals(FailoverDecision.STOP, EngineError.AuthenticationFailed("bad password").failoverDecision)
    }

    @Test
    fun `a rejected certificate stops the group`() {
        // Failing over past a trust failure would answer "this server is not
        // who it claims" by quietly connecting somewhere else.
        assertEquals(FailoverDecision.STOP, EngineError.CertificateRejected(null, "untrusted").failoverDecision)
        assertEquals(FailoverDecision.STOP, EngineError.CertificateExpired(0L, "expired").failoverDecision)
        assertEquals(
            FailoverDecision.STOP,
            EngineError.HostnameMismatch("vpn.example", listOf("other.example"), null).failoverDecision,
        )
    }

    @Test
    fun `an internal failure stops the group rather than burying itself`() {
        assertEquals(FailoverDecision.STOP, EngineError.Internal("bug").failoverDecision)
    }

    @Test
    fun `a broken conversation with a reachable server advances`() {
        assertEquals(FailoverDecision.ADVANCE, EngineError.TlsHandshakeFailed("closed").failoverDecision)
        assertEquals(FailoverDecision.ADVANCE, EngineError.PppNegotiationFailed("LCP", null).failoverDecision)
    }

    @Test
    fun `a new error variant cannot slip through unclassified`() {
        // The production `when` is exhaustive with no else branch, so adding an
        // EngineError variant fails compilation until someone decides what it
        // means for a group. This table is the second half of that guard: it is
        // exhaustive too, so the same new variant has to be given an expected
        // answer here, next to the reasoning, rather than only in the code.
        fun expected(error: EngineError): FailoverDecision = when (error) {
            is EngineError.NetworkUnreachable -> FailoverDecision.ADVANCE
            is EngineError.TimedOut -> FailoverDecision.ADVANCE
            is EngineError.IpsecFailed -> FailoverDecision.ADVANCE
            is EngineError.TlsHandshakeFailed -> FailoverDecision.ADVANCE
            is EngineError.PppNegotiationFailed -> FailoverDecision.ADVANCE
            is EngineError.AuthenticationFailed -> FailoverDecision.STOP
            is EngineError.CertificateRejected -> FailoverDecision.STOP
            is EngineError.CertificateExpired -> FailoverDecision.STOP
            is EngineError.HostnameMismatch -> FailoverDecision.STOP
            is EngineError.Internal -> FailoverDecision.STOP
        }

        val everyVariant =
            listOf(
                EngineError.NetworkUnreachable(null),
                EngineError.AuthenticationFailed(null),
                EngineError.TlsHandshakeFailed(null),
                EngineError.CertificateRejected(null, null),
                EngineError.CertificateExpired(0L, null),
                EngineError.HostnameMismatch("a", emptyList(), null),
                EngineError.IpsecFailed(null),
                EngineError.PppNegotiationFailed("LCP", null),
                EngineError.TimedOut("stage", null),
                EngineError.Internal(null),
            )

        everyVariant.forEach { assertEquals(it.toString(), expected(it), it.failoverDecision) }
    }
}
