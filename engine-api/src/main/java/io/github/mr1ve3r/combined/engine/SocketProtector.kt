package io.github.mr1ve3r.combined.engine

import java.net.DatagramSocket
import java.net.Socket

/**
 * Abstraction over `VpnService.protect()`, implemented by the host application.
 *
 * A protected socket bypasses the VPN tunnel. Every socket an engine opens for
 * its own transport must be protected, or the engine's traffic is routed back
 * into the tunnel it is trying to establish and the connection hangs.
 *
 * Protection must be applied **before** `connect()` is called on the socket.
 * That includes every socket created during a reconnect and, for SSTP, the
 * socket to an HTTP proxy — not just the first one. See appendix Б of the SPEC:
 * a reconnect that forgets to protect its new socket produces the classic
 * failure where the first connection works and the second one wedges.
 *
 * Engines receive this through [VpnEngine.connect]; they never reach for
 * `VpnService` themselves.
 */
interface SocketProtector {
    /** Excludes a TCP [socket] from the tunnel. Returns `false` if protection failed. */
    fun protect(socket: Socket): Boolean

    /** Excludes a UDP [socket] from the tunnel. Returns `false` if protection failed. */
    fun protect(socket: DatagramSocket): Boolean

    /** Excludes a raw file descriptor [fd] from the tunnel. Returns `false` if protection failed. */
    fun protect(fd: Int): Boolean
}
