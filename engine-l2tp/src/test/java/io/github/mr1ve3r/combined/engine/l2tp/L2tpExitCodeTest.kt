package io.github.mr1ve3r.combined.engine.l2tp

import io.github.mr1ve3r.combined.engine.EngineError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping table of SPEC phase 4.1.4.
 *
 * These assertions are the reason the table in `docs/ARCHITECTURE.md` can be
 * trusted: if someone changes what a native exit code means, one of them fails.
 */
class L2tpExitCodeTest {
    @Test
    fun cleanExitsCarryNoError() {
        assertNull(L2tpExitCode.toEngineError(L2tpExitCode.OK))
        assertNull(L2tpExitCode.toEngineError(L2tpExitCode.STOPPED))
        assertTrue(L2tpExitCode.isCleanExit(L2tpExitCode.OK))
        assertTrue(L2tpExitCode.isCleanExit(L2tpExitCode.STOPPED))
        assertFalse(L2tpExitCode.isCleanExit(L2tpExitCode.IKE_FAILED))
    }

    @Test
    fun ikeFailureIsAnIpsecFailure() {
        val error = L2tpExitCode.toEngineError(L2tpExitCode.IKE_FAILED)

        assertTrue(error is EngineError.IpsecFailed)
        assertEquals("engine.error.ipsec_failed", error?.messageKey)
    }

    @Test
    fun l2tpAndPppFailuresAreDistinguishedByPhase() {
        val l2tp = L2tpExitCode.toEngineError(L2tpExitCode.L2TP_FAILED)
        val ppp = L2tpExitCode.toEngineError(L2tpExitCode.PPP_FAILED)

        assertEquals("L2TP", (l2tp as EngineError.PppNegotiationFailed).phase)
        assertEquals("PPP", (ppp as EngineError.PppNegotiationFailed).phase)
    }

    @Test
    fun pollErrorAfterTheTunnelWasUpIsReportedAsALostNetwork() {
        assertTrue(L2tpExitCode.toEngineError(L2tpExitCode.POLL_ERROR) is EngineError.NetworkUnreachable)
    }

    @Test
    fun hostSideMistakesAreInternalErrors() {
        assertTrue(L2tpExitCode.toEngineError(L2tpExitCode.BAD_ARGS) is EngineError.Internal)
        assertTrue(L2tpExitCode.toEngineError(L2tpExitCode.PROXY_NOT_IMPLEMENTED) is EngineError.Internal)
    }

    @Test
    fun anUnknownCodeStillProducesAnErrorNamingIt() {
        val error = L2tpExitCode.toEngineError(97)

        assertTrue(error is EngineError.Internal)
        assertEquals("Tunnel engine exited with code 97", error?.detail)
    }

    // Phase 4 changes the shape of this path, not what the user reads. These are
    // the strings the interface has always shown for these codes.
    @Test
    fun detailsKeepTheWordingTheUserAlreadySees() {
        assertEquals(
            "IPsec negotiation failed. Check the PSK and server settings.",
            L2tpExitCode.toEngineError(L2tpExitCode.IKE_FAILED)?.detail,
        )
        assertEquals("L2TP handshake failed.", L2tpExitCode.toEngineError(L2tpExitCode.L2TP_FAILED)?.detail)
        assertEquals("PPP negotiation failed.", L2tpExitCode.toEngineError(L2tpExitCode.PPP_FAILED)?.detail)
        assertEquals("Tunnel poll I/O error.", L2tpExitCode.toEngineError(L2tpExitCode.POLL_ERROR)?.detail)
        assertEquals(
            "Invalid tunnel arguments from the app.",
            L2tpExitCode.toEngineError(L2tpExitCode.BAD_ARGS)?.detail,
        )
        assertEquals(
            "Proxy transport is not implemented yet.",
            L2tpExitCode.toEngineError(L2tpExitCode.PROXY_NOT_IMPLEMENTED)?.detail,
        )
    }
}
