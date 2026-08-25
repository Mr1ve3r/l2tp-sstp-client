package io.github.mr1ve3r.combined.core.tunnel

import android.net.VpnService
import io.github.mr1ve3r.combined.engine.SocketProtector
import java.net.DatagramSocket
import java.net.Socket

/**
 * [SocketProtector] backed by a real `VpnService`.
 *
 * This is the only implementation engines ever get, and the only reason they do
 * not need a `VpnService` reference of their own. Handing them this instead of
 * the service is what stops an engine reaching `VpnService.Builder` and
 * configuring the interface behind the host's back.
 *
 * @property service the service whose `protect()` is used.
 * @property onEvent optional sink for diagnostics. A failed `protect()` is
 *   worth surfacing: it means the socket about to be connected will be routed
 *   into the tunnel it is supposed to be carrying, and the symptom is a
 *   connection that hangs rather than one that fails.
 */
class SocketProtectorImpl(
    private val service: VpnService,
    private val onEvent: (String) -> Unit = {},
) : SocketProtector {
    override fun protect(socket: Socket): Boolean = report("tcp", service.protect(socket))

    override fun protect(socket: DatagramSocket): Boolean = report("udp", service.protect(socket))

    override fun protect(fd: Int): Boolean = report("fd $fd", service.protect(fd))

    private fun report(what: String, protectedOk: Boolean): Boolean {
        if (!protectedOk) {
            onEvent("protect() failed for $what; its traffic would re-enter the tunnel")
        }
        return protectedOk
    }
}
