package io.github.mr1ve3r.combined.engine.l2tp

import io.github.mr1ve3r.combined.engine.SocketProtector
import java.net.DatagramSocket
import java.net.Socket

/**
 * A [SocketProtector] that records what it was asked to protect.
 *
 * The point of the tests that use it is that the engine goes through the
 * protector it was handed and never anywhere else — an engine that reached for
 * a `VpnService` of its own would leave this empty.
 */
internal class RecordingSocketProtector(
    private val result: Boolean = true,
) : SocketProtector {
    val protectedFds = mutableListOf<Int>()

    override fun protect(socket: Socket): Boolean = result

    override fun protect(socket: DatagramSocket): Boolean = result

    override fun protect(fd: Int): Boolean {
        protectedFds += fd
        return result
    }
}
