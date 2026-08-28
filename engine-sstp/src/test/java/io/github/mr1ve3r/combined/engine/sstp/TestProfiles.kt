package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.engine.EngineProfile
import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.ProxyConfig
import io.github.mr1ve3r.combined.engine.SocketProtector
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.net.DatagramSocket
import java.net.Socket

/** A profile with everything filled in, so a test only states what it cares about. */
internal fun sstpProfile(
    server: String = "vpn.example.test",
    port: Int = EngineProfile.Sstp.DEFAULT_PORT,
    username: String = "alice",
    password: String = "s3cret",
    mtu: Int = EngineProfile.Sstp.DEFAULT_MTU,
    customDns: List<String> = emptyList(),
    trustPolicy: TrustPolicy = TrustPolicy.SYSTEM,
    trustedCertificateIds: List<String> = emptyList(),
    pinnedFingerprints: Set<String> = emptySet(),
    expectedHostname: String? = null,
    minTlsVersion: TlsVersion = TlsVersion.DEFAULT,
    pppAuthMethods: Set<PppAuthMethod> = EngineProfile.Sstp.DEFAULT_AUTH_METHODS,
    proxy: ProxyConfig? = null,
): EngineProfile.Sstp = EngineProfile.Sstp(
    server = server,
    username = username,
    password = password,
    mtu = mtu,
    customDns = customDns,
    port = port,
    trustPolicy = trustPolicy,
    trustedCertificateIds = trustedCertificateIds,
    pinnedFingerprints = pinnedFingerprints,
    expectedHostname = expectedHostname,
    minTlsVersion = minTlsVersion,
    pppAuthMethods = pppAuthMethods,
    proxy = proxy,
)

/**
 * Records what was protected, and whether it was still unconnected at the time.
 *
 * The second half is the point. `protect()` on an already-connected socket
 * compiles, runs, returns true and leaves the connection routed into the tunnel
 * it is supposed to carry — the failure only shows up as a hang on a device.
 */
internal class RecordingSocketProtector(private val result: Boolean = true) : SocketProtector {
    val protectedSockets = mutableListOf<Socket>()
    val connectedWhenProtected = mutableListOf<Boolean>()

    override fun protect(socket: Socket): Boolean {
        protectedSockets += socket
        connectedWhenProtected += socket.isConnected
        return result
    }

    override fun protect(socket: DatagramSocket): Boolean = result

    override fun protect(fd: Int): Boolean = result
}
