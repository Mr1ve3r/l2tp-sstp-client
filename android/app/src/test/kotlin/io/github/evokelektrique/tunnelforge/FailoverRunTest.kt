package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.profile.FailoverGroup
import io.github.mr1ve3r.combined.engine.EngineError
import io.github.mr1ve3r.combined.engine.EngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a group walks its members (SPEC 10.1.2).
 *
 * The service contributes sockets and threads; this contributes the order and
 * the stopping, and only the second one is worth asserting off a device.
 */
class FailoverRunTest {

    @Test
    fun `a run starts on the first member`() {
        val run = run("l2tp", "sstp")

        assertEquals(1, run.position)
        assertEquals(2, run.size)
        assertEquals("l2tp", run.current.profileName)
    }

    @Test
    fun `an unreachable server moves the run on`() {
        val run = run("l2tp", "sstp")

        val next = run.advanceAfter(EngineError.NetworkUnreachable("no route"))

        assertEquals("sstp", next?.profileName)
        assertEquals(2, run.position)
        assertEquals("sstp", run.current.profileName)
    }

    @Test
    fun `the budget running out moves the run on`() {
        // The case the SPEC's own example is about: UDP/500 is filtered, the
        // L2TP member says nothing at all, and SSTP on 443 is the way through.
        val run = run("l2tp", "sstp")

        assertNotNull(run.advanceAfter(EngineError.TimedOut("failover_budget", null)))
        assertEquals("sstp", run.current.profileName)
    }

    @Test
    fun `wrong credentials stop the run where it is`() {
        val run = run("l2tp", "sstp", "spare")

        assertNull(run.advanceAfter(EngineError.AuthenticationFailed("rejected")))
        // The cursor has not moved: the member that failed is the one reported,
        // and no further login is attempted anywhere (SPEC 10.1.2).
        assertEquals(1, run.position)
        assertEquals("l2tp", run.current.profileName)
    }

    @Test
    fun `a rejected certificate stops the run`() {
        val run = run("l2tp", "sstp")

        assertNull(run.advanceAfter(EngineError.CertificateRejected(null, "untrusted")))
        assertEquals(1, run.position)
    }

    @Test
    fun `the last member's failure is the group's failure`() {
        val run = run("l2tp", "sstp")

        run.advanceAfter(EngineError.NetworkUnreachable(null))

        assertNull(run.advanceAfter(EngineError.NetworkUnreachable(null)))
        assertEquals(2, run.position)
    }

    @Test
    fun `a run of one member advances nowhere`() {
        val run = run("only")

        assertNull(run.advanceAfter(EngineError.TimedOut("stage", null)))
        assertEquals(1, run.position)
    }

    @Test
    fun `every member is tried in the order the group gave`() {
        val run = run("first", "second", "third")
        val tried = mutableListOf(run.current.profileName)

        while (true) {
            val next = run.advanceAfter(EngineError.NetworkUnreachable(null)) ?: break
            tried += next.profileName
        }

        assertEquals(listOf("first", "second", "third"), tried)
    }

    @Test
    fun `the stop reason distinguishes an exhausted list from a fatal error`() {
        val run = run("only")

        assertEquals(
            "the failure stops a group: every member would answer the same way",
            run.stopReason(EngineError.AuthenticationFailed(null)),
        )
        assertEquals("no members left to try", run.stopReason(EngineError.NetworkUnreachable(null)))
    }

    @Test
    fun `the budget is the group's, in milliseconds`() {
        assertEquals(FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC * 1_000L, run("a").connectTimeoutMs)
        assertEquals(30_000L, run("a", connectTimeoutSec = 30).connectTimeoutMs)
    }

    @Test
    fun `every member of a run shares the attempt id`() {
        // The service suppresses events from a stale attempt. Giving members
        // their own ids would make the second one's progress look stale.
        val run = run("l2tp", "sstp")

        assertEquals(listOf(ATTEMPT, ATTEMPT), run.members.map { it.attemptId })
    }

    private fun run(vararg names: String, connectTimeoutSec: Int = FailoverGroup.DEFAULT_CONNECT_TIMEOUT_SEC) =
        FailoverRun(
            groupId = "group-1",
            groupName = "Work",
            members = names.map(::request),
            connectTimeoutSec = connectTimeoutSec,
        )

    private fun request(name: String) = TunnelStartRequest(
        attemptId = ATTEMPT,
        protocol = TunnelProtocol.L2TP,
        profile = EngineProfiles.l2tp("vpn.example.org", "alice", "pw", "psk", 1400) as EngineProfile,
        profileName = name,
        dnsAutomatic = true,
        dnsServers = emptyList(),
        splitTunnelEnabled = false,
        splitTunnelMode = VpnContract.SPLIT_TUNNEL_MODE_INCLUSIVE,
        inclusivePackages = null,
        exclusivePackages = null,
        proxyConfig = ProxyRuntimeConfig(
            httpEnabled = true,
            httpPort = 8080,
            socksEnabled = true,
            socksPort = 1080,
            allowLanConnections = false,
        ),
    )

    private companion object {
        const val ATTEMPT = "attempt-1"
    }
}
