package io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth

import io.github.mr1ve3r.combined.engine.sstp.ControlMailbox
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.SstpEngineConfig
import io.github.mr1ve3r.combined.engine.sstp.SstpSessionState
import io.github.mr1ve3r.combined.engine.sstp.extension.toHexByteArray
import io.github.mr1ve3r.combined.engine.sstp.extension.toHexString
import io.github.mr1ve3r.combined.engine.sstp.sstpProfile
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapMessageField
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapValueNameField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSCHAPv2 against the RFC 2759 §9.2 test vectors.
 *
 * The arithmetic here — MD4 of the UTF-16LE password, DES with the parity bits
 * put back, the two "magic" constants behind the authenticator response — was
 * ported from upstream Open SSTP Client, which ships no tests at all. A server
 * that rejects a wrong response says only "authentication failed", so a
 * transcription error in the port is indistinguishable from a wrong password
 * until someone checks the numbers against the RFC. This is that check.
 *
 * The peer challenge is fixed rather than random because the RFC fixes it; that
 * is the only reason [MsChapV2Client] takes it as a parameter.
 */
class MsChapV2ClientTest {
    // RFC 2759 §9.2.
    private val userName = "User"
    private val password = "clientPass"
    private val authenticatorChallenge = "5B5D7C7D7B3F2F3E3C2C602132262628"
    private val peerChallenge = "21402324255E262A28295F2B3A337C7E"
    private val ntResponse = "82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF"
    private val authenticatorResponse = "S=407A5589115FD0D6209F510FE9C04566932CDA56"

    private fun clientFor(user: String = userName, pass: String = password, challenge: String = peerChallenge): MsChapV2Client {
        val config = SstpEngineConfig.of(sstpProfile(username = user, password = pass))
        val bridge = SstpBridge(
            config = config,
            state = SstpSessionState(config.mru),
            mailbox = ControlMailbox(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        return MsChapV2Client(bridge) { challenge.toHexByteArray().copyInto(it) }
    }

    private fun serverChallengeField(): ChapValueNameField = ChapValueNameField().also {
        it.value = authenticatorChallenge.toHexByteArray()
    }

    @Test
    fun `computes the NT response from the RFC 2759 vectors`() {
        val response = clientFor().processChallenge(serverChallengeField())

        // 16 octets of peer challenge, then the 24-octet NT response.
        assertEquals(49, response.value.size)
        assertArrayEquals(peerChallenge.toHexByteArray(), response.value.sliceArray(0 until 16))
        assertEquals(ntResponse, response.value.sliceArray(24 until 48).toHexString())
        assertArrayEquals(userName.toByteArray(Charsets.US_ASCII), response.name)
    }

    @Test
    fun `accepts the authenticator response from the RFC 2759 vectors`() {
        val client = clientFor()
        client.processChallenge(serverChallengeField())

        val success = ChapMessageField().also {
            it.message = authenticatorResponse.toByteArray(Charsets.US_ASCII)
        }

        assertTrue(client.verifyAuthenticator(success))
    }

    @Test
    fun `rejects an authenticator response computed from a different password`() {
        val client = clientFor(pass = "wrongPass")
        client.processChallenge(serverChallengeField())

        val success = ChapMessageField().also {
            it.message = authenticatorResponse.toByteArray(Charsets.US_ASCII)
        }

        // A server that cannot prove it knows the password is a server this
        // client must not finish authenticating to -- mutual authentication is
        // the whole point of MSCHAPv2 over MSCHAPv1.
        assertFalse(client.verifyAuthenticator(success))
    }

    @Test
    fun `rejects an authenticator response too short to hold one`() {
        val client = clientFor()
        client.processChallenge(serverChallengeField())

        val truncated = ChapMessageField().also {
            it.message = "S=407A5589".toByteArray(Charsets.US_ASCII)
        }

        assertFalse(client.verifyAuthenticator(truncated))
    }
}
