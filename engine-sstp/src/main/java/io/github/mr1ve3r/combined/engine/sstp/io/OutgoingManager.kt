/*
 * Derived from Open SSTP Client
 * https://github.com/kittoku/Open-SSTP-Client
 * Copyright (c) 2019 KOBAYASHI Ittoku
 * Licensed under the MIT License.
 * See third_party/open-sstp-client/LICENSE for the full text.
 *
 * Modifications Copyright (C) 2026 Mr1ve3r
 * Licensed under GPL-3.0-or-later as part of this project.
 */
package io.github.mr1ve3r.combined.engine.sstp.io

import io.github.mr1ve3r.combined.engine.sstp.ControlMessage
import io.github.mr1ve3r.combined.engine.sstp.Result
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.terminal.IpTerminal
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_HDLC_HEADER
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_IP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_IPV6
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_PACKET_TYPE_DATA
import java.nio.ByteBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PREFIX_SIZE = 8

private const val IPV4_VERSION_HEADER: Int = (0x4).shl(4 + 3 * Byte.SIZE_BITS)
private const val IPV6_VERSION_HEADER: Int = (0x6).shl(4 + 3 * Byte.SIZE_BITS)
private const val IP_VERSION_MASK: Int = (0xF).shl(4 + 3 * Byte.SIZE_BITS)

internal class OutgoingManager(
    private val bridge: SstpBridge,
    private val ipTerminal: IpTerminal,
) {
    private var jobMain: Job? = null
    private var jobRetrieve: Job? = null

    private val mainBuffer = ByteBuffer.allocate(bridge.requireTransport().applicationBufferSize)
    private val channel = Channel<ByteBuffer>(0)

    internal fun launchJobMain() {
        jobMain = bridge.scope.launch {
            launchJobRetrieve()

            val minCapacity = PREFIX_SIZE + bridge.config.mtu

            while (isActive) {
                mainBuffer.clear()

                if (!load(channel.receive())) continue

                while (isActive) {
                    channel.tryReceive().getOrNull()?.also {
                        load(it)
                    } ?: break

                    if (mainBuffer.remaining() < minCapacity) break
                }

                mainBuffer.flip()
                bridge.send(mainBuffer)
            }
        }
    }

    private fun launchJobRetrieve() {
        jobRetrieve = bridge.scope.launch {
            val bufferAlpha = ByteBuffer.allocate(bridge.config.mtu)
            val bufferBeta = ByteBuffer.allocate(bridge.config.mtu)
            var isBlockingAlpha = true

            while (isActive) {
                isBlockingAlpha = if (isBlockingAlpha) {
                    ipTerminal.readPacket(bufferAlpha)
                    channel.send(bufferAlpha)
                    false
                } else {
                    ipTerminal.readPacket(bufferBeta)
                    channel.send(bufferBeta)
                    true
                }
            }
        }
    }

    private suspend fun load(packet: ByteBuffer): Boolean { // true if data protocol is enabled
        val header = packet.getInt(0)
        val protocol = when (header and IP_VERSION_MASK) {
            IPV4_VERSION_HEADER -> {
                if (!bridge.config.ipv4Enabled) return false

                PPP_PROTOCOL_IP
            }

            IPV6_VERSION_HEADER -> {
                if (!bridge.config.ipv6Enabled) return false

                PPP_PROTOCOL_IPV6
            }

            else -> {
                bridge.mailbox.send(ControlMessage(Where.OUTGOING, Result.ERR_UNKNOWN_TYPE))

                return false
            }
        }

        mainBuffer.putShort(SSTP_PACKET_TYPE_DATA)
        mainBuffer.putShort((packet.remaining() + PREFIX_SIZE).toShort())
        mainBuffer.putShort(PPP_HDLC_HEADER)
        mainBuffer.putShort(protocol)
        mainBuffer.put(packet)

        return true
    }

    internal fun cancel() {
        jobMain?.cancel()
        jobRetrieve?.cancel()
        channel.close()
    }
}
