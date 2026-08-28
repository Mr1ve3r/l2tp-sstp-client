package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SstpErrorMappingTest {
    @Test
    fun `a rejected password stops a failover group`() {
        val error = map(Where.MSCHAPV2, Result.ERR_AUTHENTICATION_FAILED)

        // AuthenticationFailed is the one error phase 10 must not retry on: the
        // credentials are wrong, and walking the group only spreads failed
        // logins across servers.
        assertTrue(error is EngineError.AuthenticationFailed)
    }

    @Test
    fun `a server that fails mutual authentication is an authentication failure`() {
        assertTrue(map(Where.MSCHAPV2, Result.ERR_VERIFICATION_FAILED) is EngineError.AuthenticationFailed)
    }

    @Test
    fun `a stalled negotiation names the step that stalled`() {
        val error = map(Where.LCP, Result.ERR_TIMEOUT)

        assertEquals("lcp", (error as EngineError.TimedOut).stage)
    }

    @Test
    fun `a refused option is a PPP negotiation failure naming its sub-protocol`() {
        val error = map(Where.IPCP_IP, Result.ERR_OPTION_REJECTED)

        assertEquals("IPCP_IP", (error as EngineError.PppNegotiationFailed).phase)
    }

    @Test
    fun `a malformed frame from the pipeline is an internal failure, not a config problem`() {
        assertTrue(map(Where.INCOMING, Result.ERR_PARSING_FAILED) is EngineError.Internal)
        // ...while the same result from a protocol client is the protocol failing.
        assertTrue(map(Where.LCP, Result.ERR_PARSING_FAILED) is EngineError.PppNegotiationFailed)
    }

    @Test
    fun `the detail names both the place and the reason`() {
        val error = map(Where.SSTP_REQUEST, Result.ERR_NEGATIVE_ACKNOWLEDGED)

        assertTrue(error.detail.orEmpty().contains("SSTP_REQUEST"))
        assertTrue(error.detail.orEmpty().contains("ERR_NEGATIVE_ACKNOWLEDGED"))
    }

    @Test
    fun `secrets never reach the event stream`() {
        val redactor =
            Redactor.of(
                SstpEngineConfig.of(
                    sstpProfile(
                        password = "s3cret",
                        proxy = ProxyConfig("proxy.example.test", 8080, "bob", "hunter2"),
                    ),
                ),
            )

        val scrubbed = redactor.scrub("auth failed for bob with s3cret through proxy password hunter2")

        assertFalse(scrubbed, scrubbed.contains("s3cret"))
        assertFalse(scrubbed, scrubbed.contains("hunter2"))
        assertFalse(scrubbed, scrubbed.contains("bob"))
    }

    @Test
    fun `a profile without secrets is left alone`() {
        val redactor = Redactor.of(SstpEngineConfig.of(sstpProfile(password = "")))

        assertEquals("nothing to hide", redactor.scrub("nothing to hide"))
    }

    private fun map(from: Where, result: Result): EngineError = SstpErrorMapping.toEngineError(ControlMessage(from, result))
}
